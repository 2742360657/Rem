package dev.susnowy.gallery.media

import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.scanner.MediaClassifier
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ImageSetOrderResult(
    val coverPath: String,
    val orderedPaths: List<String>,
)

/**
 * Applies visual page order to a directory ImageSet by physically numbering its
 * files. A small portable journal makes an interrupted two-phase rename
 * recoverable before the next library scan.
 */
class ImageSetOrderService {
    fun reorder(
        item: MediaItem,
        orderedPages: List<ImagePage>,
        storage: DocumentTreeStorage,
    ): ImageSetOrderResult {
        require(item.sourceKind == SourceKind.DIRECTORY) { "ZIP/CBZ 暂不支持改写页序" }
        require(orderedPages.size >= 2) { "漫画至少需要两页" }
        val directory = item.relativePath.trimEnd('/')
        val currentPages = storage.list(directory)
            .filter { !it.isDirectory && MediaClassifier.isImage(it.name, it.mimeType) }
        val requestedPaths = orderedPages.map { page ->
            page.relativePath ?: error("页面缺少 Library 相对路径：${page.name}")
        }
        require(requestedPaths.distinct().size == requestedPaths.size) { "页序中存在重复页面" }
        require(requestedPaths.toSet() == currentPages.mapTo(mutableSetOf()) { it.relativePath }) {
            "页序与当前目录内容不一致，请重新扫描后再试"
        }

        val operationId = UUID.randomUUID().toString()
        val targetNames = numberedPageNames(orderedPages.map(ImagePage::name))
        val padding = maxOf(4, orderedPages.size.toString().length)
        val entries = orderedPages.mapIndexed { index, page ->
            val originalPath = requireNotNull(page.relativePath)
            val originalName = originalPath.substringAfterLast('/')
            val extension = originalName.substringAfterLast('.', "")
                .takeIf(String::isNotEmpty)
                ?.let { ".$it" }
                .orEmpty()
            PageOrderEntry(
                originalName = originalName,
                temporaryName = ".__gallery_order_${operationId}_${index.toString().padStart(padding, '0')}$extension",
                targetName = targetNames[index],
            )
        }
        val pageNames = entries.mapTo(mutableSetOf()) { it.originalName }
        val nonPageNames = storage.list(directory).mapTo(mutableSetOf()) { it.name }.apply { removeAll(pageNames) }
        val collision = entries.firstOrNull { it.targetName in nonPageNames }
        require(collision == null) { "目标页码与非图片文件重名：${collision?.targetName}" }

        val journal = PageOrderJournal(operationId, directory, JournalStage.PLANNED, entries)
        writeJournal(storage, journal)
        try {
            entries.forEach { entry ->
                renameExact(storage, directory, entry.originalName, entry.temporaryName)
            }
            writeJournal(storage, journal.copy(stage = JournalStage.TEMPORARY_NAMES))
            entries.forEach { entry ->
                renameExact(storage, directory, entry.temporaryName, entry.targetName)
            }
            writeJournal(storage, journal.copy(stage = JournalStage.FINAL_NAMES))
            deleteJournal(storage, journal)
        } catch (error: Throwable) {
            runCatching { recover(storage, journal) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        val paths = entries.map { "$directory/${it.targetName}" }
        return ImageSetOrderResult(paths.first(), paths)
    }

    fun recoverInterrupted(storage: DocumentTreeStorage): Int {
        var recovered = 0
        storage.list(TRANSACTION_DIRECTORY)
            .filter { !it.isDirectory && it.name.startsWith(JOURNAL_PREFIX) && it.name.endsWith(".json") }
            .forEach { entry ->
                val document = storage.find(entry.relativePath) ?: return@forEach
                val journal = storage.openInput(document).bufferedReader(Charsets.UTF_8).use { reader ->
                    JSON.decodeFromString<PageOrderJournal>(reader.readText())
                }
                if (journal.stage == JournalStage.FINAL_NAMES) deleteJournal(storage, journal)
                else recover(storage, journal)
                recovered++
            }
        return recovered
    }

    private fun recover(storage: DocumentTreeStorage, journal: PageOrderJournal) {
        val recoveryNames = mutableMapOf<PageOrderEntry, String>()
        if (journal.stage == JournalStage.TEMPORARY_NAMES) {
            journal.entries.forEachIndexed { index, entry ->
                val targetPath = "${journal.directory}/${entry.targetName}"
                if (storage.entry(targetPath) != null) {
                    val extension = entry.originalName.substringAfterLast('.', "")
                        .takeIf(String::isNotEmpty)
                        ?.let { ".$it" }
                        .orEmpty()
                    val recoveryName = ".__gallery_recover_${journal.operationId}_$index$extension"
                    renameExact(storage, journal.directory, entry.targetName, recoveryName)
                    recoveryNames[entry] = recoveryName
                }
            }
        }
        journal.entries.forEach { entry ->
            val currentName = recoveryNames[entry] ?: entry.temporaryName
            if (storage.entry("${journal.directory}/$currentName") != null) {
                renameExact(storage, journal.directory, currentName, entry.originalName)
            }
        }
        deleteJournal(storage, journal)
    }

    private fun renameExact(
        storage: DocumentTreeStorage,
        directory: String,
        oldName: String,
        newName: String,
    ) {
        val oldPath = "$directory/$oldName"
        val document = storage.find(oldPath) ?: error("找不到待重命名页面：$oldPath")
        check(storage.rename(document, newName)) { "无法重命名页面：$oldName" }
        check(storage.entry("$directory/$newName") != null) { "文件提供方未按请求生成名称：$newName" }
    }

    private fun writeJournal(storage: DocumentTreeStorage, journal: PageOrderJournal) {
        storage.ensureDirectory(TRANSACTION_DIRECTORY)
        val document = storage.createFile(journal.path(), "application/json")
        storage.openOutput(document).bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(JSON.encodeToString(journal))
        }
    }

    private fun deleteJournal(storage: DocumentTreeStorage, journal: PageOrderJournal) {
        storage.find(journal.path())?.let(storage::delete)
    }

    private fun PageOrderJournal.path(): String = "$TRANSACTION_DIRECTORY/$JOURNAL_PREFIX$operationId.json"

    @Serializable
    private data class PageOrderJournal(
        @SerialName("operation_id") val operationId: String,
        val directory: String,
        val stage: JournalStage,
        val entries: List<PageOrderEntry>,
    )

    @Serializable
    private data class PageOrderEntry(
        @SerialName("original_name") val originalName: String,
        @SerialName("temporary_name") val temporaryName: String,
        @SerialName("target_name") val targetName: String,
    )

    @Serializable
    private enum class JournalStage { PLANNED, TEMPORARY_NAMES, FINAL_NAMES }

    companion object {
        internal fun numberedPageNames(originalNames: List<String>): List<String> {
            val padding = maxOf(4, originalNames.size.toString().length)
            return originalNames.mapIndexed { index, name ->
                val extension = name.substringAfterLast('.', "")
                    .takeIf(String::isNotEmpty)
                    ?.let { ".$it" }
                    .orEmpty()
                "${(index + 1).toString().padStart(padding, '0')}$extension"
            }
        }

        private const val TRANSACTION_DIRECTORY = ".gallery/transactions"
        private const val JOURNAL_PREFIX = "page-order-"
        private val JSON = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    }
}
