package dev.susnowy.gallery.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.scanner.MediaClassifier
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImagePage(
    val name: String,
    val uri: String? = null,
    val archiveEntry: String? = null,
    val relativePath: String? = null,
)

class MediaContentService {
    suspend fun imageSetPages(item: MediaItem, storage: DocumentTreeStorage): List<ImagePage> =
        withContext(Dispatchers.IO) {
            when (item.sourceKind) {
                SourceKind.DIRECTORY -> storage.list(item.relativePath)
                    .filter { !it.isDirectory && MediaClassifier.isImage(it.name, it.mimeType) }
                    .sortedWith { left, right -> MediaClassifier.naturalCompare(left.name, right.name) }
                    .map { ImagePage(name = it.name, uri = it.uri, relativePath = it.relativePath) }
                SourceKind.ARCHIVE -> archiveEntryNames(item, storage).map {
                    ImagePage(name = it.substringAfterLast('/'), archiveEntry = it)
                }
                else -> emptyList()
            }
        }

    suspend fun resolveUri(path: String, storage: DocumentTreeStorage): String? =
        withContext(Dispatchers.IO) { storage.contentUri(path)?.toString() }

    suspend fun decodeArchivePage(
        item: MediaItem,
        entryName: String,
        storage: DocumentTreeStorage,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decodeArchiveEntry(item, entryName, storage, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        decodeArchiveEntry(item, entryName, storage, options)
    }

    private fun archiveEntryNames(item: MediaItem, storage: DocumentTreeStorage): List<String> {
        val document = LibraryDocument(item.relativePath, item.relativePath.substringAfterLast('/'), false)
        return storage.openInput(document).buffered().use { input ->
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
    }

    private fun decodeArchiveEntry(
        item: MediaItem,
        entryName: String,
        storage: DocumentTreeStorage,
        options: BitmapFactory.Options,
    ): Bitmap? {
        val document = LibraryDocument(item.relativePath, item.relativePath.substringAfterLast('/'), false)
        return storage.openInput(document).buffered().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return@use null
                    if (!entry.isDirectory && entry.name == entryName) {
                        return@use BitmapFactory.decodeStream(zip, null, options)
                    }
                    zip.closeEntry()
                }
                null
            }
        }
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sample = 1
        val safeWidth = targetWidth.coerceAtLeast(1)
        val safeHeight = targetHeight.coerceAtLeast(1)
        while (width / (sample * 2) >= safeWidth && height / (sample * 2) >= safeHeight) {
            sample *= 2
        }
        return sample
    }
}
