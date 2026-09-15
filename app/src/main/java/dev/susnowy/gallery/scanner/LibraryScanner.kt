package dev.susnowy.gallery.scanner

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.storage.DocumentTreeStorage
import dev.susnowy.gallery.storage.StorageEntry
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ScanCandidate(
    val relativePath: String,
    val uri: String,
    val kind: MediaKind,
    val sourceKind: SourceKind,
    val suggestedTitle: String,
    val mimeType: String?,
    val size: Long,
    val modifiedAt: Long,
    val pageCount: Int? = null,
    val coverPath: String? = null,
    val secondaryPath: String? = null,
)

data class ScanResult(
    val candidates: List<ScanCandidate>,
    val ambiguousDirectories: List<String>,
    val warnings: List<String>,
)

class LibraryScanner {
    suspend fun scan(storage: DocumentTreeStorage): ScanResult = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ScanCandidate>()
        val ambiguous = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        scanDirectory(storage, "", candidates, ambiguous, warnings)
        ScanResult(
            candidates = candidates.sortedWith(compareBy<ScanCandidate> { it.kind.ordinal }
                .thenComparator { left, right ->
                    MediaClassifier.naturalCompare(left.relativePath, right.relativePath)
                }),
            ambiguousDirectories = ambiguous,
            warnings = warnings,
        )
    }

    private suspend fun scanDirectory(
        storage: DocumentTreeStorage,
        path: String,
        output: MutableList<ScanCandidate>,
        ambiguous: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        coroutineContext.ensureActive()
        val entries = runCatching { storage.list(path) }.getOrElse { error ->
            warnings += "无法读取 ${path.ifEmpty { "Library 根目录" }}：${error.message.orEmpty()}"
            return
        }.filterNot { path.isEmpty() && it.name == ".gallery" }

        val directories = entries.filter(StorageEntry::isDirectory)
        val files = entries.filterNot(StorageEntry::isDirectory)
        val inPhotos = path.pathSegments().firstOrNull()?.equals("Photos", ignoreCase = true) == true
        val images = files.filter { MediaClassifier.isImage(it.name, it.mimeType) }
        val videos = files.filter { MediaClassifier.isVideo(it.name, it.mimeType) }
        val archives = files.filter { MediaClassifier.isImageArchive(it.name) }

        if (!inPhotos && path.isNotEmpty() && images.size >= MIN_IMAGE_SET_PAGES && directories.isEmpty()) {
            val sortedPages = images.sortedWith { left, right ->
                MediaClassifier.naturalCompare(left.name, right.name)
            }
            val directory = storage.entry(path)
            if (directory != null) {
                output += ScanCandidate(
                    relativePath = path,
                    uri = directory.uri,
                    kind = MediaKind.IMAGE_SET,
                    sourceKind = SourceKind.DIRECTORY,
                    suggestedTitle = path.substringAfterLast('/'),
                    mimeType = null,
                    size = images.sumOf(StorageEntry::size),
                    modifiedAt = maxOf(directory.lastModified, images.maxOfOrNull(StorageEntry::lastModified) ?: 0),
                    pageCount = images.size,
                    coverPath = sortedPages.firstOrNull()?.relativePath,
                )
            }
        } else {
            if (!inPhotos && images.size >= MIN_IMAGE_SET_PAGES && directories.isNotEmpty()) {
                ambiguous += path
            }
            val pairedVideoPaths = mutableSetOf<String>()
            images.forEach { image ->
                val motion = if (inPhotos) videos.firstOrNull {
                    it.name.substringBeforeLast('.').equals(
                        image.name.substringBeforeLast('.'),
                        ignoreCase = true,
                    )
                } else null
                if (motion != null) pairedVideoPaths += motion.relativePath
                output += image.toCandidate(
                    kind = if (motion == null) {
                        if (inPhotos) MediaKind.PHOTO else MediaKind.IMAGE
                    } else MediaKind.LIVE_PHOTO,
                    sourceKind = if (inPhotos) SourceKind.SYSTEM_IMPORT else SourceKind.FILE,
                    secondaryPath = motion?.relativePath,
                )
            }
            videos.filterNot { it.relativePath in pairedVideoPaths }.forEach { video ->
                output += video.toCandidate(
                    kind = if (inPhotos) MediaKind.PHOTO_VIDEO else MediaKind.VIDEO,
                    sourceKind = if (inPhotos) SourceKind.SYSTEM_IMPORT else SourceKind.FILE,
                )
            }
        }

        archives.forEach { archive ->
            val count = runCatching { countArchivePages(storage, archive) }.getOrElse { error ->
                warnings += "压缩包 ${archive.relativePath} 无法读取：${error.message.orEmpty()}"
                0
            }
            output += archive.toCandidate(
                kind = MediaKind.IMAGE_SET,
                sourceKind = SourceKind.ARCHIVE,
                pageCount = count,
            )
        }

        directories.forEach { directory ->
            scanDirectory(storage, directory.relativePath, output, ambiguous, warnings)
        }
    }

    private fun countArchivePages(storage: DocumentTreeStorage, archive: StorageEntry): Int {
        val document = LibraryDocument(archive.relativePath, archive.name, false)
        return storage.openInput(document).buffered().use { stream ->
            ZipInputStream(stream).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && MediaClassifier.isImage(entry.name, null)) count++
                    zip.closeEntry()
                }
                count
            }
        }
    }

    private fun StorageEntry.toCandidate(
        kind: MediaKind,
        sourceKind: SourceKind,
        pageCount: Int? = null,
        secondaryPath: String? = null,
    ) = ScanCandidate(
        relativePath = relativePath,
        uri = uri,
        kind = kind,
        sourceKind = sourceKind,
        suggestedTitle = name.substringBeforeLast('.', name),
        mimeType = mimeType,
        size = size,
        modifiedAt = lastModified,
        pageCount = pageCount,
        secondaryPath = secondaryPath,
    )

    private fun String.pathSegments(): List<String> = split('/').filter(String::isNotBlank)

    companion object {
        const val MIN_IMAGE_SET_PAGES = 2
    }
}
