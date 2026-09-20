package dev.susnowy.gallery.scan

import android.content.Context
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.media.MediaProbe
import dev.susnowy.gallery.media.Metadata
import dev.susnowy.gallery.model.ALBUM
import dev.susnowy.gallery.model.COLLECTION
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.MediaType
import dev.susnowy.gallery.model.splitProjectFolder
import dev.susnowy.gallery.storage.Child
import dev.susnowy.gallery.storage.LibraryTree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** What one pass over a Library produced. */
data class ScanResult(
    val entries: List<Entry>,
    /** Every directory under the browsable roots, relative to the Library root. */
    val folders: List<String>,
    /** Relative paths that do not follow the directory rules. Reported, never corrected. */
    val violations: List<String>,
    /** True when every entry came from the cache, so nothing had to be re-read. */
    val reusedCache: Boolean,
)

/**
 * Receives a pass while it is still running.
 *
 * The index is written from these calls instead of once at the end, because a scan of a real
 * Library takes long enough that the process can be killed first — measured on device: nine
 * minutes of scanning lost because the user swiped the app away. Whatever was reported here is
 * already durable, and the next pass reuses it.
 */
interface ScanSink {
    /**
     * Entries read since the previous report. `scanned` counts media files whose metadata was read,
     * `total` is the media file count discovered so far (which grows as folders are listed).
     */
    fun onProgress(
        entries: List<Entry>,
        folders: List<String>,
        violations: List<String>,
        scanned: Int,
        total: Int,
    )

}

/**
 * Walks the two browsable directories and produces the entry and folder lists.
 *
 * `相册/` contributes its direct files and nothing below them: a subfolder there is a violation, not
 * a place to look. `画集/` is a tree — the first level must be `作者名称-项目名称` folders, and
 * everything under them is browsable to any depth with any names. `待分类/` is never touched at all.
 *
 * Cost is dominated by reading EXIF and video headers, so a cached entry whose size and
 * modification time still match is reused without opening the file. Reads inside one folder run
 * concurrently: each one is a blocking round trip to the provider, and on a removable volume the
 * wait is the whole cost, so overlapping them is what turns minutes into seconds.
 */
class Scanner(private val context: Context, private val tree: LibraryTree) {

    /**
     * Reads the Library's structure.
     *
     * With `readMetadata = false` this opens no media file at all: it lists directories and records
     * size and modification time, which is what makes a Library of tens of thousands of files
     * browsable in seconds instead of after a per-file metadata pass. The capture times and places
     * arrive later, from [readMetadata], which is resumable because each entry it finishes is
     * written back.
     */
    suspend fun scan(
        cached: Map<String, Entry>,
        sink: ScanSink? = null,
        readMetadata: Boolean = true,
    ): ScanResult {
        val startedAt = System.currentTimeMillis()
        // Directory listings are never reused between scans; only the per-file readings are. The
        // listing is the only way a new or removed file becomes visible at all.
        tree.invalidateListings()
        RemLog.info(SCOPE, "开始扫描 root='${tree.rootDocumentId()}' 缓存条目=${cached.size} 并发=$WORKERS")
        val pass = Pass(cached, sink, readMetadata)
        return pass.run().also { result ->
            RemLog.info(
                SCOPE,
                "扫描结束 条目=${result.entries.size} 文件夹=${result.folders.size} " +
                    "复用=${if (result.reusedCache) "全部" else "部分"} " +
                    "违规=${result.violations.size} 用时=${System.currentTimeMillis() - startedAt}ms",
            )
            logViolations(result.violations)
        }
    }

    /**
     * Reads the metadata of one batch of entries.
     *
     * Used by the catch-up pass: the listing scan records what exists, and this fills in capture
     * times and places afterwards, in batches the caller can checkpoint. Entries whose file no
     * longer resolves come back unchanged, so a Library that changed underneath the pass loses
     * nothing.
     */
    suspend fun readMetadata(entries: List<Entry>): List<Entry> = coroutineScope {
        entries.map { entry: Entry ->
            val type = entry.mediaType
            if (type == null) {
                CompletableDeferred(entry)
            } else {
                async(READERS) {
                    val child = withContext(Dispatchers.IO) { tree.find(entry.path) }
                    if (child == null) {
                        entry
                    } else {
                        val metadata = MediaProbe.read(context, child.uri, type)
                        entry.copy(captured = metadata.captured, place = metadata.place)
                    }
                }
            }
        }.awaitAll()
    }

    private fun logViolations(violations: List<String>) {
        violations.take(MAX_LOGGED_VIOLATIONS).forEach { RemLog.warn(SCOPE, "违规：$it") }
        if (violations.size > MAX_LOGGED_VIOLATIONS) {
            RemLog.warn(SCOPE, "另有 ${violations.size - MAX_LOGGED_VIOLATIONS} 条违规未逐条记录")
        }
    }

    private inner class Pass(
        private val cached: Map<String, Entry>,
        private val sink: ScanSink?,
        private val readMetadata: Boolean,
    ) {
        /** Written from concurrent readers, so this list is synchronized. */
        private val violations = Collections.synchronizedList(mutableListOf<String>())
        private val reused = AtomicInteger()
        private val total = AtomicInteger()
        private val scanned = AtomicInteger()
        private val lastReport = AtomicLong(0)

        /** Entries not yet handed to the sink. Drained under the lock in [report]. */
        private val pending = mutableListOf<Entry>()
        private val pendingLock = Any()

        /** Folders found since the last report. Grows as the tree is walked. */
        private val folders = mutableListOf<String>()

        suspend fun run(): ScanResult {
            val entries = mutableListOf<Entry>()

            // `相册/` stays flat: direct files only, and a subfolder there is reported.
            entries += readAlbum()

            // `画集/` is a tree. Its first level must be project folders; below that anything goes.
            val firstLevel = tree.list(COLLECTION)
            RemLog.info(SCOPE, "'$COLLECTION' 直属项 ${firstLevel.size}")
            for (child in firstLevel.sortedBy { it.name }) {
                if (child.name.startsWith('.')) continue
                if (!child.isDirectory) {
                    violations += "${child.path}：画集第一层只能是项目文件夹"
                    report(force = false)
                    continue
                }
                if (splitProjectFolder(child.name) == null) {
                    violations += "${child.path}：项目文件夹必须命名为「作者名称-项目名称」"
                }
                entries += walk(child)
            }

            report(force = true)
            return ScanResult(
                entries = entries,
                folders = synchronized(pendingLock) { folders.toList() },
                violations = violations.toList(),
                reusedCache = reused.get() == total.get(),
            )
        }

        private suspend fun readAlbum(): List<Entry> {
            val children = tree.list(ALBUM)
            RemLog.info(SCOPE, "'$ALBUM' 直属项 ${children.size}")
            children.forEach { child ->
                if (child.isDirectory) violations += "${child.path}：相册下不允许建立子文件夹"
            }
            // Every file is handed over, not only the media: an unsupported extension is reported
            // inside readFiles, and filtering those out first would drop the report silently.
            return readFiles(children.filter { !it.isDirectory })
        }

        /** Depth-first walk of one browsable directory; returns every media file below it. */
        private suspend fun walk(directory: Child): List<Entry> {
            val result = mutableListOf<Entry>()
            val stack = ArrayDeque<Child>()
            stack.addLast(directory)

            while (stack.isNotEmpty()) {
                val current = stack.removeFirst()
                // The folder itself is recorded, not just the ones found below it. Leaving out the
                // starting directory dropped every first-level project, and an empty folder has no
                // media whose path could imply it — both are browsable and must be in the index.
                synchronized(pendingLock) { folders += current.path }

                val children = tree.list(current.path)
                RemLog.debug(SCOPE, "目录 '${current.path}' 内含 ${children.size} 项")

                result += readFiles(children.filter { !it.isDirectory })
                report(force = false)

                // Sorted so a resume reads the same folder first and the log stays comparable.
                children.filter { it.isDirectory }
                    .sortedByDescending { it.name }
                    .forEach { stack.addFirst(it) }
            }
            return result
        }

        /**
         * Reads one folder's files, reporting everything that cannot be an entry.
         *
         * Only unsupported extensions are reported: names are free now, and a nested folder is a
         * legitimate part of the collection tree.
         */
        private suspend fun readFiles(children: List<Child>): List<Entry> = coroutineScope {
            val files = children.filterNot { it.name.startsWith('.') }
            total.addAndGet(files.count { it.mediaType != null })
            files.mapNotNull { child ->
                val type = child.mediaType
                if (type == null) {
                    violations += "${child.path}：不支持的媒体格式"
                    return@mapNotNull null
                }
                async(READERS) { entryFor(child, type) }
            }.awaitAll()
        }

        private suspend fun entryFor(child: Child, type: MediaType): Entry {
            cachedEntry(child)?.let {
                reused.incrementAndGet()
                countAndMaybeReport(it)
                return it
            }
            val metadata = if (readMetadata) probe(child.uri, type) else Metadata.NONE
            val entry = Entry(
                path = child.path,
                size = child.size,
                modified = child.modified,
                captured = metadata.captured,
                place = metadata.place,
            )
            countAndMaybeReport(entry)
            return entry
        }

        private suspend fun probe(uri: android.net.Uri, type: MediaType): Metadata =
            withContext(READERS) {
                coroutineContext.ensureActive()
                MediaProbe.read(context, uri, type)
            }

        private fun countAndMaybeReport(entry: Entry) {
            scanned.incrementAndGet()
            synchronized(pendingLock) { pending += entry }
            report(force = false)
        }

        /**
         * Hands the sink everything collected since the last call.
         *
         * Throttled by time rather than by count: the expensive part of a report is the index write
         * that follows it, and a Library with a hundred thousand files would otherwise write the
         * whole index thousands of times.
         */
        private fun report(force: Boolean) {
            val now = System.currentTimeMillis()
            if (!force && now - lastReport.get() < REPORT_INTERVAL_MS) return
            lastReport.set(now)
            val batch: List<Entry>
            val folderSnapshot: List<String>
            synchronized(pendingLock) {
                if (pending.isEmpty() && !force) return
                batch = pending.toList()
                pending.clear()
                folderSnapshot = folders.toList()
            }
            sink?.onProgress(batch, folderSnapshot, violations.toList(), scanned.get(), total.get())
        }

        /**
         * A cached reading is trusted only when both size and modification time match. Size alone
         * would miss an edit that kept the length, and a timestamp alone would miss a replacement
         * that restored one.
         */
        private fun cachedEntry(child: Child): Entry? =
            cached[child.path]?.takeIf { it.size == child.size && it.modified == child.modified }
    }

    private companion object {
        const val SCOPE = "Scan"
        const val MAX_LOGGED_VIOLATIONS = 50

        /**
         * Metadata reads in flight inside one folder.
         *
         * Two, not six. Measured on a real device against a USB SSD: six concurrent readers each
         * forcing a metadata read saturated the volume (`/proc/pressure/io` full avg10 16.6), and
         * every worker ended up queued behind the content-provider lock the reads take. The app
         * froze for five seconds at a time, ANR'd, was killed, and the next launch re-read the same
         * files — a loop that never finished. Overlapping a few waits helps; stampeding the volume
         * and the provider makes it strictly worse.
         */
        private const val WORKERS = 2

        /** Progress is written at most this often, plus once at the end of every folder. */
        private const val REPORT_INTERVAL_MS = 2000L

        private val READERS = Dispatchers.IO.limitedParallelism(WORKERS)
    }
}
