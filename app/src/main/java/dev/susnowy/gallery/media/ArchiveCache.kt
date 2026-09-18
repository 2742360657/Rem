package dev.susnowy.gallery.media

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.library.LibraryDocumentAccess
import dev.susnowy.gallery.model.MediaItem
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
    private val copyMutex = Mutex()

    /** Opens a cached copy of [item], copying it once when necessary. Null when unavailable. */
    suspend fun open(item: MediaItem, storage: LibraryDocumentAccess): ZipFile? =
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
        storage: LibraryDocumentAccess,
    ): ZipFile? = withContext(Dispatchers.IO) {
        val target = fileFor(libraryId, relativePath, size, modifiedAt) ?: return@withContext null
        mutex.withLock { openCached(target) }?.let { return@withContext it }
        copyMutex.withLock {
            mutex.withLock { openCached(target) }?.let { return@withLock it }
            copyInto(relativePath, storage, target)
        }
    }

    /** Called under the short cache lock, never while waiting on a Provider stream. */
    private fun openCached(target: File): ZipFile? {
        if (!target.isFile || target.length() == 0L) return null
        target.setLastModified(System.currentTimeMillis())
        return runCatching { ZipFile(target) }.getOrElse { target.delete(); null }
    }

    /** Removes every cached copy and reports what was actually removed. */
    suspend fun clear(): ArchiveCacheStats = withContext(Dispatchers.IO) {
        copyMutex.withLock {
            mutex.withLock {
                val files = directory.listFiles().orEmpty().filter(File::isFile)
                var removedFiles = 0
                var removedBytes = 0L
                files.forEach { file ->
                    val bytes = file.length()
                    if (file.delete()) {
                        removedFiles++
                        removedBytes += bytes
                    }
                }
                ArchiveCacheStats(removedFiles, removedBytes)
            }
        }
    }

    suspend fun stats(): ArchiveCacheStats = withContext(Dispatchers.IO) {
        mutex.withLock {
            val files = directory.listFiles().orEmpty().filter(File::isFile)
            ArchiveCacheStats(files.size, files.sumOf(File::length))
        }
    }

    private suspend fun copyInto(relativePath: String, storage: LibraryDocumentAccess, target: File): ZipFile? {
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
            mutex.withLock {
                if (!staging.renameTo(target)) {
                    staging.copyTo(target, overwrite = true)
                    staging.delete()
                }
                val opened = openCached(target)
                // A newly opened archive survives its own trim even when over budget.
                trim(keep = target.path)
                opened
            }
        }.getOrElse {
            staging.delete()
            if (it is CancellationException) throw it
            null
        }
    }

    /** Keeps the cache inside its byte budget, oldest use first. */
    private fun trim(keep: String? = null) {
        val entries = directory.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") }
            ?.map { ArchiveCacheEntry(it.path, it.length(), it.lastModified()) }
            .orEmpty()
        val doomed = archiveEvictions(entries, maxBytes, keep)
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

data class ArchiveCacheStats(val files: Int, val bytes: Long)

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
