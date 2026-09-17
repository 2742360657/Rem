package dev.susnowy.gallery.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.core.graphics.scale
import androidx.core.net.toUri
import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.scanner.MediaClassifier
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OfflinePreviewStats(val files: Int, val bytes: Long)

internal data class PreviewRetentionRecord(
    val key: String,
    val bytes: Long,
    val lastAccess: Long,
)

internal fun previewRetentionVictims(
    records: List<PreviewRetentionRecord>,
    maxBytes: Long,
    maxFiles: Int,
    protectedKey: String? = null,
): List<String> {
    var bytes = records.sumOf(PreviewRetentionRecord::bytes)
    var files = records.size
    if (bytes <= maxBytes && files <= maxFiles) return emptyList()
    return buildList {
        records.asSequence()
            .filterNot { it.key == protectedKey }
            .sortedWith(compareBy(PreviewRetentionRecord::lastAccess, PreviewRetentionRecord::key))
            .forEach { record ->
                if (bytes <= maxBytes && files <= maxFiles) return@forEach
                add(record.key)
                bytes -= record.bytes
                files--
            }
    }
}

/**
 * Small device-private covers used when a removable Library is offline.
 *
 * These files are a bounded, clearable cache. They are never copied into `.gallery`, never
 * replace originals, and are generated only when a thumbnail is actually requested.
 */
class OfflinePreviewStore(
    context: Context,
    private val maxBytes: Long = MAX_BYTES,
    private val maxFiles: Int = MAX_FILES,
    private val edgePixels: Int = EDGE_PIXELS,
    rootDirectory: File? = null,
    private val archives: ArchiveCache,
) {
    private val appContext = context.applicationContext
    private val root = rootDirectory ?: File(appContext.noBackupFilesDir, DIRECTORY)
    private val mutex = Mutex()
    private val content = MediaContentService(archives, 8 * 1024 * 1024)
    private var cachedFiles = -1
    private var cachedBytes = -1L

    suspend fun getOrCreate(item: MediaItem, storage: DocumentTreeStorage): File? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val target = target(item)
                ensureStats()
                if (target.isFile && target.length() > 0) {
                    target.setLastModified(System.currentTimeMillis())
                    return@withLock target
                }
                val bitmap = runCatching { createBitmap(item, storage) }.getOrNull()
                    ?: return@withLock null
                try {
                    target.parentFile?.mkdirs()
                    val temporary = File(target.parentFile, ".${target.name}.${System.nanoTime()}.tmp")
                    val previousLength = target.takeIf(File::isFile)?.length()
                    val written = runCatching {
                        temporary.outputStream().buffered().use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                                "无法编码离线预览"
                            }
                        }
                        if (target.exists()) target.delete()
                        check(temporary.renameTo(target)) { "无法提交离线预览" }
                    }.isSuccess
                    if (!written) {
                        temporary.delete()
                        return@withLock null
                    }
                    target.setLastModified(System.currentTimeMillis())
                    if (previousLength == null) cachedFiles++
                    cachedBytes += target.length() - (previousLength ?: 0L)
                    pruneIfNeeded(target.absolutePath)
                    target
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }

    suspend fun stats(): OfflinePreviewStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureStats()
            OfflinePreviewStats(cachedFiles, cachedBytes)
        }
    }

    suspend fun clear(): OfflinePreviewStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            val files = previewFiles()
            val before = OfflinePreviewStats(files.size, files.sumOf(File::length))
            files.forEach(File::delete)
            root.walkBottomUp().filter { it.isDirectory && it != root }.forEach(File::delete)
            cachedFiles = 0
            cachedBytes = 0
            before
        }
    }

    private suspend fun createBitmap(item: MediaItem, storage: DocumentTreeStorage): Bitmap? {
        val bitmap = when {
            item.kind == MediaKind.VIDEO || item.kind == MediaKind.PHOTO_VIDEO -> videoFrame(item)
            item.kind == MediaKind.IMAGE_SET && item.sourceKind == SourceKind.DIRECTORY -> {
                val preferred = item.coverPath?.let(storage::entry)
                val page = preferred?.takeIf { !it.isDirectory && MediaClassifier.isImage(it.name, it.mimeType) }
                    ?: storage.list(item.relativePath)
                        .asSequence()
                        .filter { !it.isDirectory && MediaClassifier.isImage(it.name, it.mimeType) }
                        .sortedWith { left, right -> MediaClassifier.naturalCompare(left.name, right.name) }
                        .firstOrNull()
                page?.let { decodeImage(storage, it.relativePath) }
            }
            item.kind == MediaKind.IMAGE_SET && item.sourceKind == SourceKind.ARCHIVE -> {
                val entry = content.imageSetPages(item, storage).firstOrNull()?.archiveEntry
                entry?.let {
                    content.decodeArchivePage(item, it, storage, edgePixels, edgePixels)
                        ?.copy(Bitmap.Config.RGB_565, false)
                }
            }
            item.sourceKind != SourceKind.DIRECTORY -> decodeImage(storage, item.relativePath)
            else -> null
        }
        return bitmap?.scaledToFit(edgePixels)
    }

    private fun decodeImage(storage: DocumentTreeStorage, relativePath: String): Bitmap? {
        val document = LibraryDocument(relativePath, relativePath.substringAfterLast('/'), false)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        storage.openInput(document).buffered().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edgePixels) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return storage.openInput(document).buffered().use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun videoFrame(item: MediaItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, item.uri.toUri())
            retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun Bitmap.scaledToFit(edge: Int): Bitmap {
        val largest = maxOf(width, height)
        if (largest <= edge) return this
        val ratio = edge.toFloat() / largest
        val scaled = scale(
            (width * ratio).toInt().coerceAtLeast(1),
            (height * ratio).toInt().coerceAtLeast(1),
            filter = true,
        )
        if (scaled !== this) recycle()
        return scaled
    }

    private fun target(item: MediaItem): File {
        val library = digest(item.libraryId).take(24)
        val identity = digest(
            listOf(item.id, item.modifiedAt, item.coverPath.orEmpty(), item.secondaryPath.orEmpty())
                .joinToString(":"),
        )
        return File(File(root, library), "$identity.jpg")
    }

    private fun pruneIfNeeded(protectedPath: String) {
        if (cachedBytes <= maxBytes && cachedFiles <= maxFiles) return
        val files = previewFiles()
        val records = files.map { file ->
            PreviewRetentionRecord(file.absolutePath, file.length(), file.lastModified())
        }
        val victims = previewRetentionVictims(
            records = records,
            maxBytes = (maxBytes * RETENTION_LOW_WATER_PERCENT) / 100,
            maxFiles = (maxFiles * RETENTION_LOW_WATER_PERCENT) / 100,
            protectedKey = protectedPath,
        ).toSet()
        files.filter { it.absolutePath in victims }.forEach { file ->
            val length = file.length()
            if (file.delete()) {
                cachedFiles--
                cachedBytes -= length
            }
        }
        root.walkBottomUp().filter { it.isDirectory && it != root }.forEach { directory ->
            if (directory.list().isNullOrEmpty()) directory.delete()
        }
    }

    private fun previewFiles(): List<File> =
        if (!root.isDirectory) emptyList()
        else root.walkTopDown().filter { it.isFile && it.extension == "jpg" }.toList()

    private fun ensureStats() {
        if (cachedFiles >= 0 && cachedBytes >= 0) return
        val files = previewFiles()
        cachedFiles = files.size
        cachedBytes = files.sumOf(File::length)
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        private const val DIRECTORY = "offline-previews"
        private const val EDGE_PIXELS = 512
        private const val JPEG_QUALITY = 80
        private const val MAX_FILES = 20_000
        private const val MAX_BYTES = 256L * 1024L * 1024L
        private const val RETENTION_LOW_WATER_PERCENT = 90
    }
}
