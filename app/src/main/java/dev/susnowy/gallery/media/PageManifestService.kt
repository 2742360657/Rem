package dev.susnowy.gallery.media

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.scanner.MediaClassifier
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One page of a source, identified by the container it lives in.
 *
 * [containerPath] is the Library-relative path of the file or directory that holds the page
 * and [entryPath] is the path inside that container (an archive entry, or a file name inside
 * a directory), so a page can come from any Asset an Edition references — which is what
 * makes a virtual merged Edition readable. `entryPath` is null only when the container *is*
 * the page (a single image file).
 */
data class PageEntry(
    val containerPath: String,
    val entryPath: String?,
    val name: String,
    val sizeBytes: Long,
    /** Content hash; null when the manifest was built without reading page bytes. */
    val sha256: String? = null,
) {
    val key: String get() = entryPath?.let { "$containerPath/$it" } ?: "$containerPath/$name"

    val hashed: Boolean get() = sha256 != null
}

/** Ordered page list of one source (a Work's preferred Edition, resolved to pages). */
data class SourceManifest(
    val sourceId: String,
    val libraryId: String,
    val label: String,
    val pages: List<PageEntry>,
    val hashed: Boolean,
    val bytesRead: Long,
    val durationMs: Long,
    val fromCache: Boolean = false,
) {
    val pageCount: Int get() = pages.size
}

/**
 * Builds a page manifest for one source.
 *
 * Cost is deliberately bounded and observable, because removable media on a phone can run at
 * roughly 20 MB/s:
 *
 * - a directory costs one listing, plus one read per page only when hashes are requested;
 * - an archive costs exactly **one sequential pass** — the reader cannot seek inside a ZIP,
 *   so enumerating entries already reads the file, and hashing them during that same pass
 *   costs no extra I/O;
 * - a single file is one entry, hashed only on request.
 *
 * Nothing here writes to the Library or changes media.
 */
class PageManifestService(
    private val cache: PageManifestCache = PageManifestCache(),
) {
    suspend fun manifest(
        item: MediaItem,
        storage: DocumentTreeStorage,
        label: String = item.displayTitle,
        hashPages: Boolean = false,
        onProgress: (pages: Int, bytesRead: Long) -> Unit = { _, _ -> },
    ): SourceManifest = withContext(Dispatchers.IO) {
        cache.get(item, hashPages)?.let { cached ->
            return@withContext cached.copy(fromCache = true, label = label)
        }
        val startedAt = System.currentTimeMillis()
        val counter = ByteCounter()
        val pages = when (item.sourceKind) {
            SourceKind.DIRECTORY -> directoryPages(item, storage, hashPages, counter, onProgress)
            SourceKind.ARCHIVE -> archivePages(item, storage, hashPages, counter, onProgress)
            else -> listOf(singlePage(item, storage, hashPages, counter, onProgress))
        }
        val manifest = SourceManifest(
            sourceId = item.id,
            libraryId = item.libraryId,
            label = label,
            pages = pages,
            hashed = pages.isNotEmpty() && pages.all(PageEntry::hashed),
            bytesRead = counter.bytes,
            durationMs = System.currentTimeMillis() - startedAt,
        )
        cache.put(item, hashPages, manifest)
        manifest
    }

    private suspend fun directoryPages(
        item: MediaItem,
        storage: DocumentTreeStorage,
        hashPages: Boolean,
        counter: ByteCounter,
        onProgress: (Int, Long) -> Unit,
    ): List<PageEntry> {
        val directory = item.relativePath.trimEnd('/')
        val entries = storage.list(directory)
            .filter { !it.isDirectory && MediaClassifier.isImage(it.name, it.mimeType) }
            .sortedWith { left, right -> MediaClassifier.naturalCompare(left.name, right.name) }
        val pages = ArrayList<PageEntry>(entries.size)
        entries.forEach { entry ->
            coroutineContext.ensureActive()
            val digest = if (hashPages) {
                sha256Of(storage, entry.relativePath, counter)
            } else {
                null
            }
            pages += PageEntry(
                containerPath = directory,
                // The path inside the container, so a page can be referenced from an Edition
                // page plan without re-listing the directory later.
                entryPath = entry.name,
                name = entry.name,
                sizeBytes = entry.size,
                sha256 = digest,
            )
            onProgress(pages.size, counter.bytes)
        }
        return pages
    }

    private suspend fun archivePages(
        item: MediaItem,
        storage: DocumentTreeStorage,
        hashPages: Boolean,
        counter: ByteCounter,
        onProgress: (Int, Long) -> Unit,
    ): List<PageEntry> {
        val document = LibraryDocument(
            key = item.relativePath,
            name = item.relativePath.substringAfterLast('/'),
            isDirectory = false,
        )
        return storage.openInput(document).buffered().use { input ->
            readArchivePages(item.relativePath, input, hashPages, counter, onProgress)
        }
    }

    private suspend fun singlePage(
        item: MediaItem,
        storage: DocumentTreeStorage,
        hashPages: Boolean,
        counter: ByteCounter,
        onProgress: (Int, Long) -> Unit,
    ): PageEntry {
        coroutineContext.ensureActive()
        val entry = storage.entry(item.relativePath)
        val digest = if (hashPages) sha256Of(storage, item.relativePath, counter) else null
        return PageEntry(
            containerPath = item.relativePath,
            entryPath = null,
            name = item.relativePath.substringAfterLast('/'),
            sizeBytes = entry?.size ?: item.size,
            sha256 = digest,
        ).also { onProgress(1, counter.bytes) }
    }

    private fun sha256Of(
        storage: DocumentTreeStorage,
        relativePath: String,
        counter: ByteCounter,
    ): String {
        val document = LibraryDocument(relativePath, relativePath.substringAfterLast('/'), false)
        val digest = MessageDigest.getInstance("SHA-256")
        storage.openInput(document).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                counter.bytes += count
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    internal class ByteCounter(var bytes: Long = 0L)
}

/**
 * Reads every image entry of one archive in a single sequential pass.
 *
 * Kept as a standalone function over an [InputStream] so it can be tested without a device:
 * the streaming reader cannot seek, so this pass is the only cheap way to enumerate and hash
 * pages, and it must never be repeated per page.
 */
internal suspend fun readArchivePages(
    containerPath: String,
    input: InputStream,
    hashPages: Boolean,
    counter: PageManifestService.ByteCounter = PageManifestService.ByteCounter(),
    onProgress: (pages: Int, bytesRead: Long) -> Unit = { _, _ -> },
): List<PageEntry> {
    val pages = mutableListOf<PageEntry>()
    ZipInputStream(input).use { zip ->
        while (true) {
            coroutineContext.ensureActive()
            val entry = zip.nextEntry ?: break
            if (!entry.isDirectory && MediaClassifier.isImage(entry.name, null)) {
                val digest = if (hashPages) MessageDigest.getInstance("SHA-256") else null
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var size = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = zip.read(buffer)
                    if (count < 0) break
                    size += count
                    counter.bytes += count
                    digest?.update(buffer, 0, count)
                }
                pages += PageEntry(
                    containerPath = containerPath,
                    entryPath = entry.name,
                    name = entry.name.substringAfterLast('/'),
                    sizeBytes = size,
                    sha256 = digest?.digest()?.joinToString("") { "%02x".format(it) },
                )
                onProgress(pages.size, counter.bytes)
            }
            zip.closeEntry()
        }
    }
    return pages.sortedWith { left, right -> MediaClassifier.naturalCompare(left.name, right.name) }
}

/**
 * Session cache keyed by source identity, size and modified time.
 *
 * A comparison is usually run several times while the user narrows the scope, and a deep pass
 * must never read the same bytes twice. The cache is disposable and never enters the Library.
 */
class PageManifestCache(private val maxEntries: Int = 24) {
    private val entries = object : LinkedHashMap<String, SourceManifest>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, SourceManifest>?,
        ): Boolean = size > maxEntries
    }

    fun get(item: MediaItem, hashPages: Boolean): SourceManifest? = synchronized(entries) {
        entries[key(item, hashPages)]
    }

    fun put(item: MediaItem, hashPages: Boolean, manifest: SourceManifest) = synchronized(entries) {
        entries[key(item, hashPages)] = manifest
    }

    fun clear() = synchronized(entries) { entries.clear() }

    private fun key(item: MediaItem, hashPages: Boolean) = listOf(
        item.libraryId,
        item.relativePath,
        item.size,
        item.modifiedAt,
        hashPages,
    ).joinToString(":")
}
