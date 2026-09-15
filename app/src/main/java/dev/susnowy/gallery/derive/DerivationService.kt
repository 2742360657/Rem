package dev.susnowy.gallery.derive

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.scanner.MediaClassifier
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

@Serializable
private data class DerivationManifest(
    @SerialName("operation_id") val operationId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("derived_from") val derivedFrom: List<String>,
    val targets: List<String>,
)

class DerivationService {
    suspend fun deriveImage(item: MediaItem, storage: DocumentTreeStorage): String =
        withContext(Dispatchers.IO) {
            require(item.sourceKind == SourceKind.FILE || item.sourceKind == SourceKind.SYSTEM_IMPORT) {
                "当前项目不是可直接复制的单文件"
            }
            val source = storage.entry(item.relativePath) ?: error("源文件不存在")
            val extension = item.relativePath.substringAfterLast('.', "jpg")
            val target = uniqueTarget(storage, "Images/Derived", "${item.displayTitle.safeName()}.$extension")
            storage.ensureDirectory("Images/Derived")
            storage.copyFile(source, target)
            writeManifest(storage, listOf(item.id), listOf(target))
            target
        }

    suspend fun derivePage(
        item: MediaItem,
        pageIndex: Int,
        storage: DocumentTreeStorage,
    ): String = withContext(Dispatchers.IO) {
        require(item.sourceKind == SourceKind.DIRECTORY || item.sourceKind == SourceKind.ARCHIVE) {
            "当前 ImageSet 不支持页面派生"
        }
        val targetDirectory = "Images/Derived"
        storage.ensureDirectory(targetDirectory)
        val target = when (item.sourceKind) {
            SourceKind.DIRECTORY -> {
                val pages = storage.list(item.relativePath)
                    .filter { !it.isDirectory && MediaClassifier.isImage(it.name, it.mimeType) }
                    .sortedWith { left, right -> MediaClassifier.naturalCompare(left.name, right.name) }
                val page = pages.getOrNull(pageIndex) ?: error("页码超出范围")
                val extension = page.name.substringAfterLast('.', "jpg")
                val path = uniqueTarget(
                    storage,
                    targetDirectory,
                    "${item.displayTitle.safeName()} - ${pageIndex + 1}.$extension",
                )
                storage.copyFile(page, path)
                path
            }
            SourceKind.ARCHIVE -> extractArchivePage(item, pageIndex, storage, targetDirectory)
            else -> error("不支持的 ImageSet 来源")
        }
        writeManifest(storage, listOf("${item.id}#page=${pageIndex + 1}"), listOf(target))
        target
    }

    suspend fun createImageSet(
        items: List<MediaItem>,
        title: String,
        storage: DocumentTreeStorage,
    ): String = withContext(Dispatchers.IO) {
        require(items.size >= 2) { "至少选择两张图片" }
        require(items.all { it.sourceKind == SourceKind.FILE || it.sourceKind == SourceKind.SYSTEM_IMPORT }) {
            "只能从独立图片创建 ImageSet"
        }
        val orderedItems = items.sortedWith { left, right ->
            MediaClassifier.naturalCompare(
                left.relativePath.substringAfterLast('/'),
                right.relativePath.substringAfterLast('/'),
            )
        }
        val directory = uniqueDirectory(storage, "ImageSets/Derived", title.safeName())
        storage.ensureDirectory(directory)
        orderedItems.forEachIndexed { index, item ->
            coroutineContext.ensureActive()
            val source = storage.entry(item.relativePath) ?: error("源图片不存在：${item.relativePath}")
            val extension = item.relativePath.substringAfterLast('.', "jpg").lowercase(Locale.ROOT)
            storage.copyFile(source, "$directory/${(index + 1).toString().padStart(4, '0')}.$extension")
        }
        writeManifest(storage, orderedItems.map(MediaItem::id), listOf(directory))
        directory
    }

    private fun extractArchivePage(
        item: MediaItem,
        pageIndex: Int,
        storage: DocumentTreeStorage,
        targetDirectory: String,
    ): String {
        val document = LibraryDocument(item.relativePath, item.relativePath.substringAfterLast('/'), false)
        val names = storage.openInput(document).buffered().use { input ->
            ZipInputStream(input).use { zip ->
                buildList {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.isDirectory && MediaClassifier.isImage(entry.name, null)) add(entry.name)
                        zip.closeEntry()
                    }
                }.sortedWith(MediaClassifier::naturalCompare)
            }
        }
        val entryName = names.getOrNull(pageIndex) ?: error("页码超出范围")
        val extension = entryName.substringAfterLast('.', "jpg")
        val target = uniqueTarget(
            storage,
            targetDirectory,
            "${item.displayTitle.safeName()} - ${pageIndex + 1}.$extension",
        )
        val targetDocument = storage.createFile(target, mimeForExtension(extension))
        storage.openInput(document).buffered().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: error("压缩包页面已变化")
                    if (!entry.isDirectory && entry.name == entryName) {
                        storage.openOutput(targetDocument).use { output -> zip.copyTo(output, DEFAULT_BUFFER_SIZE) }
                        break
                    }
                    zip.closeEntry()
                }
            }
        }
        return target
    }

    private fun uniqueTarget(
        storage: DocumentTreeStorage,
        parent: String,
        fileName: String,
    ): String {
        val direct = "$parent/$fileName"
        if (storage.entry(direct) == null) return direct
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "").takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
        for (index in 1..9999) {
            val path = "$parent/$base ($index)$extension"
            if (storage.entry(path) == null) return path
        }
        error("无法为派生文件生成唯一名称")
    }

    private fun uniqueDirectory(storage: DocumentTreeStorage, parent: String, name: String): String {
        storage.ensureDirectory(parent)
        val direct = "$parent/$name"
        if (storage.entry(direct) == null) return direct
        for (index in 1..9999) {
            val path = "$parent/$name ($index)"
            if (storage.entry(path) == null) return path
        }
        error("无法为 ImageSet 生成唯一名称")
    }

    private fun writeManifest(
        storage: DocumentTreeStorage,
        sources: List<String>,
        targets: List<String>,
    ) {
        val operationId = UUID.randomUUID().toString()
        val manifest = DerivationManifest(operationId, Instant.now().toString(), sources, targets)
        val document = storage.createFile(".gallery/imports/derive-$operationId.json", "application/json")
        storage.openOutput(document).bufferedWriter(Charsets.UTF_8).use {
            it.write(JSON.encodeToString(manifest))
        }
    }

    private fun String.safeName(): String = trim()
        .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
        .trim(' ', '.')
        .take(120)
        .ifBlank { "Untitled" }

    private fun mimeForExtension(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
        else -> "application/octet-stream"
    }

    companion object {
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }
    }
}
