package dev.susnowy.gallery.media

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local copies of archive documents, opened through [ZipFile].
 *
 * Two problems are solved by the same cache:
 *
 * 1. `ZipInputStream` refuses archives whose entries are stored uncompressed but carry an
 *    extended data descriptor (`only DEFLATED entries can have EXT descriptor`), which is a
 *    layout real downloader tools produce. `ZipFile` reads the central directory and handles
 *    every layout, so pages and ComicInfo stay readable.
 * 2. A stream cannot seek, so reaching page N of an archive used to read the whole file, and
 *    every page read did it again. With a local copy, page access is a seek.
 *
 * The cache is disposable: it lives in the app cache directory, is keyed by path plus size and
 * modified time (so changed content is never reused), and is trimmed to a byte budget. Nothing
 * here writes to the Library.
 */
class ArchiveCache(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val mutex = Mutex()

    /** Opens a cached copy of [item], copying it once when necessary. Null when unavailable. */
    suspend fun open(item: MediaItem, storage: DocumentTreeStorage): ZipFile? =
        open(item.libraryId, item.relativePath, item.size, item.modifiedAt, storage)

    /**
     * Opens a cached copy of one archive document.
     *
     * The key carries size and modified time, so a changed archive is copied again instead of
     * reusing a stale copy; a caller that references another Asset (a merged page plan) passes
     * that document's own identity.
     */
    suspend fun open(
        libraryId: String,
        relativePath: String,
        size: Long,
        modifiedAt: Long,
        storage: DocumentTreeStorage,
    ): ZipFile? = withContext(Dispatchers.IO) {
        val target = fileFor(libraryId, relativePath, size, modifiedAt) ?: return@withContext null
        mutex.withLock {
            if (!target.isFile || target.length() == 0L) {
                if (!copyInto(relativePath, storage, target)) {
                    target.delete()
                    return@withLock null
                }
            }
            target.setLastModified(System.currentTimeMillis())
            runCatching { ZipFile(target) }.getOrNull()
                ?: run {
                    // A truncated or corrupt copy must not be reused forever.
                    target.delete()
                    null
                }
        }
    }

    /** Removes every cached copy; used by the settings screen and tests. */
    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { directory.listFiles()?.forEach { it.delete() } }
        Unit
    }

    suspend fun stats(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val files = directory.listFiles().orEmpty()
        files.size to files.sumOf(File::length)
    }

    private suspend fun copyInto(relativePath: String, storage: DocumentTreeStorage, target: File): Boolean {
        directory.mkdirs()
        val staging = File(directory, "${target.name}.part")
        val document = LibraryDocument(
            key = relativePath,
            name = relativePath.substringAfterLast('/'),
            isDirectory = false,
        )
        return runCatching {
            storage.openInput(document).use { input ->
                staging.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            trim()
            true
        }.getOrElse {
            staging.delete()
            false
        }
    }

    /** Keeps the cache inside its byte budget, oldest use first. */
    private fun trim() {
        val entries = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.map { ArchiveCacheEntry(it.path, it.length(), it.lastModified()) }
            .orEmpty()
        val doomed = archiveEvictions(entries, maxBytes)
        doomed.forEach { path -> runCatching { File(path).delete() } }
    }

    private fun fileFor(libraryId: String, relativePath: String, size: Long, modifiedAt: Long): File? {
        if (relativePath.isBlank()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$libraryId:$relativePath".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(directory, "$digest-$size-$modifiedAt.zip")
    }

    companion object {
        /** 512 MiB of archive copies; the system may reclaim the cache directory anyway. */
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024 * 1024
    }
}

data class ArchiveCacheEntry(val path: String, val bytes: Long, val lastUsed: Long)

/**
 * Chooses which cached archives to delete so the cache stays inside [maxBytes].
 *
 * Oldest use is evicted first, and eviction stops at 90% of the budget so a single trim does
 * not have to run again on the very next copy. Pure, so the policy is testable without a
 * device or a file system.
 */
fun archiveEvictions(
    entries: List<ArchiveCacheEntry>,
    maxBytes: Long,
    keep: String? = null,
): List<String> {
    val total = entries.sumOf(ArchiveCacheEntry::bytes)
    if (total <= maxBytes) return emptyList()
    val lowWater = (maxBytes * 9) / 10
    val doomed = mutableListOf<String>()
    var remaining = total
    entries.sortedBy(ArchiveCacheEntry::lastUsed).forEach { entry ->
        if (remaining <= lowWater) return@forEach
        if (entry.path == keep) return@forEach
        doomed += entry.path
        remaining -= entry.bytes
    }
    return doomed
}
