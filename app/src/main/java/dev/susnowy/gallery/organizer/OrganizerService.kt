package dev.susnowy.gallery.organizer

import dev.susnowy.gallery.metadata.PortableMetadataStore
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

enum class OrganizerTemplate(val label: String) {
    AUTHOR_FIRST("按作者整理"),
    SERIES_FIRST("按系列整理"),
}

data class OrganizationStep(
    val item: MediaItem,
    val source: String,
    val target: String,
    val conflict: String? = null,
)

data class OrganizationPlan(
    val id: String = UUID.randomUUID().toString(),
    val template: OrganizerTemplate,
    val createdAt: Long = System.currentTimeMillis(),
    val steps: List<OrganizationStep>,
) {
    val executableSteps: List<OrganizationStep> get() = steps.filter { it.conflict == null }
    val hasConflicts: Boolean get() = steps.any { it.conflict != null }
    val totalBytes: Long get() = executableSteps.sumOf { it.item.size }
}

@Serializable
private data class TransactionDocument(
    @SerialName("operation_id") val operationId: String,
    @SerialName("created_at") val createdAt: String,
    val status: String,
    val template: String,
    val steps: List<TransactionStep>,
    val error: String? = null,
)

@Serializable
private data class TransactionStep(
    @SerialName("item_id") val itemId: String,
    val source: String,
    val target: String,
    val status: String,
)

class OrganizerService {
    suspend fun preview(
        items: List<MediaItem>,
        storage: DocumentTreeStorage,
        template: OrganizerTemplate,
    ): OrganizationPlan = withContext(Dispatchers.IO) {
        val preliminary = items.asSequence()
            .filterNot { it.trashed || it.needsRepair }
            .mapNotNull { item ->
                val target = targetPath(item, template) ?: return@mapNotNull null
                if (target.equals(item.relativePath, ignoreCase = true)) return@mapNotNull null
                val existing = storage.entry(target)
                OrganizationStep(
                    item = item,
                    source = item.relativePath,
                    target = target,
                    conflict = existing?.let { "目标已经存在" },
                )
            }.toList()
        val duplicatedTargets = preliminary.groupBy { it.target.lowercase(Locale.ROOT) }
            .filterValues { it.size > 1 }
            .keys
        OrganizationPlan(
            template = template,
            steps = preliminary.map { step ->
                if (step.target.lowercase(Locale.ROOT) in duplicatedTargets) {
                    step.copy(conflict = "多个项目生成了相同目标")
                } else step
            },
        )
    }

    suspend fun execute(
        plan: OrganizationPlan,
        storage: DocumentTreeStorage,
        onItemMoved: (MediaItem) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(plan.steps.isNotEmpty()) { "没有需要整理的文件" }
        require(!plan.hasConflicts) { "计划仍有冲突，不能执行" }
        val metadata = PortableMetadataStore(storage)
        metadata.createBackup("organizer-${plan.id}")
        var transaction = TransactionDocument(
            operationId = plan.id,
            createdAt = Instant.ofEpochMilli(plan.createdAt).toString(),
            status = "running",
            template = plan.template.name,
            steps = plan.steps.map {
                TransactionStep(it.item.id, it.source, it.target, "planned")
            },
        )
        writeTransaction(storage, transaction)
        try {
            plan.steps.forEachIndexed { index, step ->
                coroutineContext.ensureActive()
                val source = storage.entry(step.source) ?: error("源文件不存在：${step.source}")
                check(storage.entry(step.target) == null) { "目标在执行前已出现：${step.target}" }
                if (source.isDirectory) storage.copyDirectory(step.source, step.target)
                else storage.copyFile(source, step.target)
                val sourceStats = storage.treeStats(step.source)
                val targetStats = storage.treeStats(step.target)
                check(sourceStats == targetStats) { "复制校验失败：${step.source}" }
                transaction = transaction.updateStep(index, "copied")
                writeTransaction(storage, transaction)

                val sourceDocument = storage.find(step.source) ?: error("复制后源文件丢失")
                check(storage.delete(sourceDocument)) { "无法删除已校验的源文件：${step.source}" }
                transaction = transaction.updateStep(index, "source_deleted")
                writeTransaction(storage, transaction)

                val targetEntry = storage.entry(step.target) ?: error("目标文件校验失败")
                val moved = step.item.copy(
                    relativePath = step.target,
                    uri = targetEntry.uri,
                    coverPath = step.item.coverPath?.replacePathPrefix(step.source, step.target),
                    secondaryPath = step.item.secondaryPath?.replacePathPrefix(step.source, step.target),
                    modifiedAt = targetEntry.lastModified,
                    size = if (targetEntry.isDirectory) targetStats.totalBytes else targetEntry.size,
                )
                val saved = metadata.saveItem(moved, step.item.revision)
                onItemMoved(moved.copy(revision = saved.revision))
                transaction = transaction.updateStep(index, "completed")
                writeTransaction(storage, transaction)
            }
            writeTransaction(storage, transaction.copy(status = "completed"))
        } catch (error: Exception) {
            writeTransaction(
                storage,
                transaction.copy(status = "interrupted", error = error.message ?: error.javaClass.simpleName),
            )
            throw error
        }
    }

    fun targetPath(item: MediaItem, template: OrganizerTemplate): String? {
        val author = item.authors.firstOrNull()?.safeSegment()
        val series = item.series?.title?.safeSegment()
        val title = item.displayTitle.safeSegment()
        val extension = item.relativePath.substringAfterLast('.', "")
            .takeIf { item.sourceKind != SourceKind.DIRECTORY && it.length in 1..10 }
            ?.lowercase(Locale.ROOT)
        val fileName = if (extension == null) title else "$title.$extension"
        val grouping = when (template) {
            OrganizerTemplate.AUTHOR_FIRST -> listOfNotNull(author, series)
            OrganizerTemplate.SERIES_FIRST -> listOfNotNull(series, author)
        }
        return when (item.kind) {
            MediaKind.IMAGE_SET -> (listOf("ImageSets") + grouping + fileName).joinToString("/")
            MediaKind.IMAGE -> (listOf("Images") + grouping + fileName).joinToString("/")
            MediaKind.VIDEO -> {
                val base = if (series == null) listOf("Videos", "Movies") else listOf("Videos", "Series")
                (base + grouping + fileName).joinToString("/")
            }
            MediaKind.PHOTO, MediaKind.PHOTO_VIDEO, MediaKind.LIVE_PHOTO -> {
                val captured = Instant.ofEpochMilli(item.capturedAt ?: item.modifiedAt)
                    .atZone(ZoneOffset.UTC)
                val originalName = item.relativePath.substringAfterLast('/').safeSegment(preserveExtension = true)
                "Photos/${captured.year}/${captured.monthValue.toString().padStart(2, '0')}/$originalName"
            }
        }
    }

    private fun writeTransaction(storage: DocumentTreeStorage, transaction: TransactionDocument) {
        val path = ".gallery/transactions/${transaction.operationId}.json"
        val document = storage.find(path) ?: storage.createFile(path, "application/json")
        storage.openOutput(document).bufferedWriter(Charsets.UTF_8).use {
            it.write(JSON.encodeToString(transaction))
        }
    }

    private fun TransactionDocument.updateStep(index: Int, status: String) = copy(
        steps = steps.mapIndexed { current, step -> if (current == index) step.copy(status = status) else step },
    )

    private fun String.replacePathPrefix(source: String, target: String): String = when {
        this == source -> target
        startsWith("$source/") -> target + removePrefix(source)
        else -> this
    }

    private fun String.safeSegment(preserveExtension: Boolean = false): String {
        val value = trim()
            .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
            .trim(' ', '.')
            .take(120)
            .ifBlank { "Untitled" }
        val base = if (preserveExtension) value.substringBeforeLast('.', value) else value
        return if (base.uppercase(Locale.ROOT) in WINDOWS_RESERVED_NAMES) "_$value" else value
    }

    companion object {
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }
        private val WINDOWS_RESERVED_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            (1..9).forEach {
                add("COM$it")
                add("LPT$it")
            }
        }
    }
}
