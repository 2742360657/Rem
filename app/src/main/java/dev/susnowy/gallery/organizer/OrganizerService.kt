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
    val secondarySource: String? = null,
    val secondaryTarget: String? = null,
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
data class TransactionDocument(
    @SerialName("operation_id") val operationId: String,
    @SerialName("created_at") val createdAt: String,
    val status: String,
    val template: String,
    val steps: List<TransactionStep>,
    val error: String? = null,
)

@Serializable
data class TransactionStep(
    @SerialName("item_id") val itemId: String,
    val source: String,
    val target: String,
    @SerialName("secondary_source") val secondarySource: String? = null,
    @SerialName("secondary_target") val secondaryTarget: String? = null,
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
                val secondaryTarget = item.secondaryPath?.let { source ->
                    "${target.substringBeforeLast('/')}/${source.substringAfterLast('/')}"
                }
                OrganizationStep(
                    item = item,
                    source = item.relativePath,
                    target = target,
                    secondarySource = item.secondaryPath,
                    secondaryTarget = secondaryTarget,
                    conflict = when {
                        existing != null -> "目标已经存在"
                        secondaryTarget != null && storage.entry(secondaryTarget) != null -> "Motion Photo 目标已经存在"
                        else -> null
                    },
                )
            }.toList()
        val duplicatedTargets = preliminary.flatMap { step ->
            listOfNotNull(step.target, step.secondaryTarget)
        }.groupingBy { it.lowercase(Locale.ROOT) }.eachCount().filterValues { it > 1 }.keys
        OrganizationPlan(
            template = template,
            steps = preliminary.map { step ->
                if (step.target.lowercase(Locale.ROOT) in duplicatedTargets ||
                    step.secondaryTarget?.lowercase(Locale.ROOT) in duplicatedTargets
                ) {
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
                TransactionStep(
                    itemId = it.item.id,
                    source = it.source,
                    target = it.target,
                    secondarySource = it.secondarySource,
                    secondaryTarget = it.secondaryTarget,
                    status = "planned",
                )
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
                if (step.secondarySource != null && step.secondaryTarget != null) {
                    val secondary = storage.entry(step.secondarySource)
                        ?: error("Live Photo motion 文件不存在：${step.secondarySource}")
                    storage.copyFile(secondary, step.secondaryTarget)
                }
                val sourceStats = storage.treeStats(step.source)
                val targetStats = storage.treeStats(step.target)
                check(sourceStats == targetStats) { "复制校验失败：${step.source}" }
                if (step.secondarySource != null && step.secondaryTarget != null) {
                    check(storage.treeStats(step.secondarySource) == storage.treeStats(step.secondaryTarget)) {
                        "Live Photo motion 复制校验失败"
                    }
                }
                transaction = transaction.updateStep(index, "copied")
                writeTransaction(storage, transaction)

                val sourceDocument = storage.find(step.source) ?: error("复制后源文件丢失")
                check(storage.delete(sourceDocument)) { "无法删除已校验的源文件：${step.source}" }
                step.secondarySource?.let { secondarySource ->
                    val secondaryDocument = storage.find(secondarySource)
                        ?: error("复制后 motion 源文件丢失")
                    check(storage.delete(secondaryDocument)) { "无法删除已校验的 motion 源文件" }
                }
                transaction = transaction.updateStep(index, "source_deleted")
                writeTransaction(storage, transaction)

                val targetEntry = storage.entry(step.target) ?: error("目标文件校验失败")
                val moved = step.item.copy(
                    relativePath = step.target,
                    uri = targetEntry.uri,
                    coverPath = step.item.coverPath?.replacePathPrefix(step.source, step.target),
                    secondaryPath = step.secondaryTarget
                        ?: step.item.secondaryPath?.replacePathPrefix(step.source, step.target),
                    modifiedAt = targetEntry.lastModified,
                    contentHash = step.item.contentHash,
                    size = (if (targetEntry.isDirectory) targetStats.totalBytes else targetEntry.size) +
                        (step.secondaryTarget?.let { storage.entry(it)?.size } ?: 0),
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

    suspend fun recoverInterrupted(
        libraryId: String,
        storage: DocumentTreeStorage,
    ): Int = withContext(Dispatchers.IO) {
        val metadata = PortableMetadataStore(storage)
        val transactions = storage.list(".gallery/transactions")
            .filter {
                !it.isDirectory && (it.name.endsWith(".json", ignoreCase = true) ||
                    it.name.endsWith(".tmp", ignoreCase = true) ||
                    it.name.endsWith(".bak", ignoreCase = true))
            }
            .mapNotNull { entry ->
                runCatching {
                    val document = storage.find(entry.relativePath) ?: return@runCatching null
                    storage.openInput(document).bufferedReader(Charsets.UTF_8).use {
                        JSON.decodeFromString<TransactionDocument>(it.readText())
                    }
                }.getOrNull()
            }
            .groupBy(TransactionDocument::operationId)
            .mapNotNull { (_, versions) ->
                versions.maxWithOrNull(
                    compareBy<TransactionDocument> { document ->
                        document.steps.count { it.status == "completed" }
                    }.thenBy { document ->
                        document.steps.fold(0) { total, step ->
                            total + when (step.status) {
                                "source_deleted" -> 2
                                "copied" -> 1
                                else -> 0
                            }
                        }
                    },
                )
            }
            .filter { it.status == "running" || it.status == "interrupted" }
        var recovered = 0
        transactions.forEach { original ->
            var transaction = original.copy(status = "running", error = null)
            writeTransaction(storage, transaction)
            try {
                transaction.steps.forEachIndexed { index, step ->
                    if (step.status == "completed") return@forEachIndexed
                    coroutineContext.ensureActive()
                    val pairs = buildList {
                        add(step.source to step.target)
                        if (step.secondarySource != null && step.secondaryTarget != null) {
                            add(step.secondarySource to step.secondaryTarget)
                        }
                    }
                    pairs.forEach { (sourcePath, targetPath) ->
                        val source = storage.entry(sourcePath)
                        var target = storage.entry(targetPath)
                        check(source != null || target != null) {
                            "事务 ${transaction.operationId} 的源和目标都不存在"
                        }
                        if (source != null && target == null) {
                            if (source.isDirectory) storage.copyDirectory(sourcePath, targetPath)
                            else storage.copyFile(source, targetPath)
                            target = storage.entry(targetPath) ?: error("恢复复制后目标不存在")
                        }
                        if (source != null && target != null) {
                            check(storage.treeStats(sourcePath) == storage.treeStats(targetPath)) {
                                "事务 ${transaction.operationId} 的源与目标不一致，已停止恢复"
                            }
                        }
                    }
                    transaction = transaction.updateStep(index, "copied")
                    writeTransaction(storage, transaction)
                    pairs.forEach { (sourcePath, _) ->
                        storage.find(sourcePath)?.let { sourceDocument ->
                            check(storage.delete(sourceDocument)) { "恢复时无法删除已校验源文件" }
                        }
                    }
                    transaction = transaction.updateStep(index, "source_deleted")
                    writeTransaction(storage, transaction)
                    check(
                        metadata.relocateItem(
                            libraryId,
                            step.itemId,
                            step.source,
                            step.target,
                            step.secondaryTarget,
                        ),
                    ) { "事务项目 ${step.itemId} 缺少便携元数据" }
                    transaction = transaction.updateStep(index, "completed")
                    writeTransaction(storage, transaction)
                }
                writeTransaction(storage, transaction.copy(status = "completed", error = null))
                recovered++
            } catch (error: Exception) {
                writeTransaction(
                    storage,
                    transaction.copy(status = "interrupted", error = error.message ?: error.javaClass.simpleName),
                )
            }
        }
        recovered
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
        val temporaryPath = ".gallery/transactions/.${transaction.operationId}.tmp"
        val backupPath = ".gallery/transactions/.${transaction.operationId}.bak"
        storage.find(temporaryPath)?.let(storage::delete)
        storage.find(backupPath)?.let(storage::delete)
        val temporary = storage.createFile(temporaryPath, "application/json")
        storage.openOutput(temporary).bufferedWriter(Charsets.UTF_8).use {
            it.write(JSON.encodeToString(transaction))
        }
        val current = storage.find(path)
        if (current != null) check(storage.rename(current, ".${transaction.operationId}.bak")) {
            "无法备份 Organizer 事务日志"
        }
        if (!storage.rename(temporary, "${transaction.operationId}.json")) {
            storage.find(backupPath)?.let { storage.rename(it, "${transaction.operationId}.json") }
            error("无法提交 Organizer 事务日志")
        }
        storage.find(backupPath)?.let(storage::delete)
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
