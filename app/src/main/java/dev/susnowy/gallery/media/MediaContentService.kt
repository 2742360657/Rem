package dev.susnowy.gallery.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.scanner.MediaClassifier
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.util.zip.ZipInputStream
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ImagePage(
    val name: String,
    val uri: String? = null,
    val archiveEntry: String? = null,
    val relativePath: String? = null,
)

class MediaContentService(
    archiveBitmapCacheBytes: Int = DEFAULT_ARCHIVE_BITMAP_CACHE_BYTES,
) {
    private val archiveBitmapCache = object : LruCache<String, Bitmap>(archiveBitmapCacheBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val archiveDecodeMutex = Mutex()

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
        val cacheKey = listOf(
            item.id,
            item.modifiedAt,
            entryName,
            targetWidth,
            targetHeight,
        ).joinToString(":")
        archiveBitmapCache.get(cacheKey)?.let { return@withContext it }

        archiveDecodeMutex.withLock {
            archiveBitmapCache.get(cacheKey)?.let { return@withLock it }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decodeArchiveEntry(item, entryName, storage, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withLock null
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            decodeArchiveEntry(item, entryName, storage, options)?.also { bitmap ->
                archiveBitmapCache.put(cacheKey, bitmap)
            }
        }
    }

    /**
     * Returns a safely sampled bitmap only when the source is too large for a
     * normal full-frame decode. Animated formats stay on Coil's drawable path.
     */
    suspend fun decodeOversizedImage(
        relativePath: String,
        storage: DocumentTreeStorage,
    ): Bitmap? = withContext(Dispatchers.IO) {
        val document = LibraryDocument(relativePath, relativePath.substringAfterLast('/'), false)
        if (shouldKeepAnimated(document, storage)) return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        storage.openInput(document).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (pixels <= OVERSIZED_IMAGE_PIXELS &&
            maxOf(bounds.outWidth, bounds.outHeight) <= OVERSIZED_IMAGE_DIMENSION
        ) {
            return@withContext null
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateMemorySafeSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        storage.openInput(document).use { BitmapFactory.decodeStream(it, null, options) }
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
        while (
            (width / (sample * 2) >= safeWidth && height / (sample * 2) >= safeHeight) ||
            decodedPixels(width, height, sample) > MAX_DECODED_PIXELS
        ) {
            sample *= 2
        }
        return sample
    }

    private fun calculateMemorySafeSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (
            decodedPixels(width, height, sample) > MAX_DECODED_PIXELS ||
            ceil(maxOf(width, height).toDouble() / sample).toInt() > MAX_DECODED_DIMENSION
        ) {
            sample *= 2
        }
        return sample
    }

    private fun decodedPixels(width: Int, height: Int, sample: Int): Long =
        ceil(width.toDouble() / sample).toLong() * ceil(height.toDouble() / sample).toLong()

    private fun shouldKeepAnimated(
        document: LibraryDocument,
        storage: DocumentTreeStorage,
    ): Boolean = when (document.name.substringAfterLast('.', "").lowercase()) {
        "gif", "apng" -> true
        "webp" -> storage.openInput(document).use { input ->
            val header = ByteArray(WEBP_ANIMATION_HEADER_SIZE)
            val count = input.read(header)
            count >= WEBP_ANIMATION_HEADER_SIZE &&
                header.copyOfRange(0, 4).decodeToString() == "RIFF" &&
                header.copyOfRange(8, 12).decodeToString() == "WEBP" &&
                header.copyOfRange(12, 16).decodeToString() == "VP8X" &&
                header[20].toInt() and WEBP_ANIMATION_FLAG != 0
        }
        else -> false
    }

    companion object {
        private const val DEFAULT_ARCHIVE_BITMAP_CACHE_BYTES = 64 * 1024 * 1024
        private const val MAX_DECODED_PIXELS = 8_000_000L
        private const val MAX_DECODED_DIMENSION = 12_000
        private const val OVERSIZED_IMAGE_PIXELS = 40_000_000L
        private const val OVERSIZED_IMAGE_DIMENSION = 16_000
        private const val WEBP_ANIMATION_HEADER_SIZE = 21
        private const val WEBP_ANIMATION_FLAG = 0x02
    }
}
