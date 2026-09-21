package dev.susnowy.gallery.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.susnowy.gallery.RemApplication
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.COLLECTION
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.Folder
import dev.susnowy.gallery.model.Index
import dev.susnowy.gallery.model.MediaType
import dev.susnowy.gallery.model.NATURAL_ORDER
import dev.susnowy.gallery.model.SortMode
import dev.susnowy.gallery.model.ViewMode
import dev.susnowy.gallery.model.splitProjectFolder
import dev.susnowy.gallery.model.toEntry
import dev.susnowy.gallery.scan.ScanResult
import dev.susnowy.gallery.scan.ScanSink
import dev.susnowy.gallery.scan.Scanner
import dev.susnowy.gallery.storage.LibraryStore
import dev.susnowy.gallery.storage.LibraryTree
import dev.susnowy.gallery.storage.indexOf
import dev.susnowy.gallery.ui.OpenWith.Outcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the provider answered when the Library was opened, gathered off the main thread.
 *
 * A carrier for the whole set so the calls happen in one place and one hop, rather than each being
 * made where it is used — which is how they ended up on the composition thread.
 */
private data class OpenProbe(
    val readable: Boolean,
    val rootId: String,
    val name: String,
    val rootNames: List<String>,
    val cached: Index?,
    val hidden: Boolean,
)

/** The three destinations in the bottom bar. */
enum class Tab(val title: String, val icon: ImageVector) {
    ALBUM("相册", Icons.Rounded.PhotoLibrary),
    COLLECTION("画集", Icons.Rounded.Collections),
    SETTINGS("设置", Icons.Rounded.Settings),
}

/** Whether to show images, videos, or both. Applies to both sections. */
enum class MediaFilter(val title: String) {
    ALL("全部"),
    IMAGE("图片"),
    VIDEO("视频"),
    ;

    fun accepts(type: MediaType?): Boolean = when (this) {
        ALL -> true
        IMAGE -> type == MediaType.IMAGE
        VIDEO -> type == MediaType.VIDEO
    }
}

/** How far the running scan has got, so a pass that takes minutes is visibly alive. */
data class ScanProgress(val scanned: Int, val total: Int)

/** One row of the collection's folder list: the folder, and what it holds. */
data class FolderRow(
    val folder: Folder,
    val mediaCount: Int,
    val folderCount: Int,
    /** True when the folder has both, which is the case the rules show one kind at a time. */
    val mixed: Boolean,
)

data class UiState(
    val attached: Boolean = false,
    /** The attached tree, needed to build thumbnail requests and to open files. */
    val treeUri: Uri? = null,
    /**
     * The open tree itself, published as state so the UI notices when it arrives.
     *
     * The screen needs it to turn a path into a URI, and opening a Library is asynchronous now, so
     * on the first composition there is no tree yet. A plain property would leave the UI holding
     * "no tree" for good: Compose has nothing to observe, never recomposes, and every thumbnail and
     * viewer page stays empty.
     */
    val tree: LibraryTree? = null,
    val libraryName: String = "",
    val tab: Tab = Tab.ALBUM,
    val filter: MediaFilter = MediaFilter.ALL,
    val search: String = "",
    /** Everything the index holds. Every visible list is derived from this one field. */
    val entries: List<Entry> = emptyList(),
    /** Every folder the index recorded, as paths, so an empty folder stays browsable. */
    val folders: List<String> = emptyList(),
    /** Null while the collection shows its top level. */
    val openFolder: String? = null,
    val sortMode: SortMode = SortMode.SEQUENCE,
    val viewMode: ViewMode = ViewMode.GRID,
    /**
     * Order direction. The album reads newest first by default because that is what a camera roll
     * looks like, and every order in the collection can be flipped.
     */
    val sortAscending: Boolean = false,
    /** Thumbnail cache size on disk, read off the main thread. Null until it is known. */
    val thumbnailBytes: Long? = null,
    val refreshing: Boolean = false,
    /**
     * Whether the volume was readable when it was last touched.
     *
     * False is a normal state right after a removable volume is mounted: the provider still lists
     * nothing, so the Library looks empty and `.gallery/` cannot be written. It is tracked so the
     * app can refresh by itself once the volume really answers, instead of asking the user to
     * detach and re-attach the Library.
     */
    val libraryReadable: Boolean = false,
    /** Non-null only while a scan is running. */
    val scanProgress: ScanProgress? = null,
    /**
     * How many files are still waiting for their capture time to be read, or null when nothing is
     * pending. Shown as a quiet hint: the Library is already browsable while this drains.
     */
    val metadataPending: Int? = null,
    val message: String? = null,
    val violations: List<String> = emptyList(),
    val hideFromSystemGallery: Boolean = false,
    /** True while the `.nomedia` marker is being written or removed. */
    val markerBusy: Boolean = false,
) {
    /**
     * Album entries in capture order, filtered by media type.
     *
     * Memoised on the inputs it reads. It used to be a plain getter, and every recomposition — and
     * there are many while a scan runs — filtered and sorted the whole Library again: on a real
     * Library of 48 000 files that is tens of milliseconds per call, on the main thread, several
     * times a second. That is what froze the UI and produced the ANRs.
     */
    private val albumMemo = Memo<List<Entry>>()

    val visibleAlbum: List<Entry>
        get() = albumMemo(entries, filter, sortAscending) {
            val files = entries.filter { it.projectFolder == null && filter.accepts(it.mediaType) }
            val byTime = files.sortedBy(Entry::orderTime)
            if (sortAscending) byTime else byTime.asReversed()
        }

    /**
     * Every known folder, once each: the folders the index recorded, plus the ones an entry path
     * implies while a pass is still running and has not reported its folder list yet.
     *
     * The section itself is deliberately not in here. It is not a folder the user can be inside of
     * as a folder, and giving it an entry made it both a parent key and its own child, which put it
     * in the tree and sent the subtree counts into infinite recursion.
     *
     * Built once per state. The alternative is rebuilding a list of every folder on every
     * recomposition of the grid, which on a large collection is work the user can feel.
     */
    private val allFolders: List<Folder> by lazy {
        (folders + entries.mapNotNull { it.parentFolder }).distinct().map(::Folder)
    }

    /**
     * Direct children of every folder, keyed by parent path.
     *
     * A level is derived from this rather than from [allFolders], because a child has to stay
     * visible no matter how deep it sits: a folder holding only a deeper folder, or one holding
     * nothing at all, would otherwise vanish from the tree.
     */
    private val childFolders: Map<String, List<Folder>> by lazy {
        allFolders.groupBy { it.parent.orEmpty() }
    }

    /**
     * Entries grouped by the folder that directly contains them.
     *
     * Built once per state. Every folder row needs its own contents, and filtering the whole entry
     * list per row made one recomposition cost folders × entries — on a real library that is tens
     * of millions of path comparisons for a single frame.
     */
    private val entriesByParent: Map<String, List<Entry>> by lazy {
        // Keyed by the containing folder. A file at a browsable root has none, and the bare
        // substring before its last separator is the section name — which must never become a
        // folder that the tree then shows as if it were a project.
        entries.mapNotNull { entry -> entry.parentFolder?.let { it to entry } }
            .groupBy({ it.first }, { it.second })
    }

    /**
     * How much is inside each folder, counting everything below it.
     *
     * Counted once per state instead of per row: a folder row has to say what it holds, and a
     * folder whose media all sits two levels down would otherwise claim to be empty. Only direct
     * children are walked at each step, so the whole map costs one pass over the folder tree.
     */
    private val subtreeCounts: Map<String, IntArray> by lazy {
        val counted = HashMap<String, IntArray>()
        fun count(path: String): IntArray = counted.getOrPut(path) {
            var media = entriesIn(path).size
            var folders = 0
            childFolders[path]?.forEach { child ->
                val below = count(child.path)
                media += below[0]
                folders += 1 + below[1]
            }
            intArrayOf(media, folders)
        }
        allFolders.forEach { count(it.path) }
        counted
    }

    val currentFolder: Folder?
        get() = openFolder?.let(::Folder)

    /**
     * The folders shown at the current level.
     *
     * A level shows folders when it has any, and its own media when it has none. That is the agreed
     * rule: a folder and a picture never share a level, so a level with subfolders hides its media
     * rather than mixing the two.
     */
    private val folderRowsMemo = Memo<List<FolderRow>>()

    val folderRows: List<FolderRow>
        get() = folderRowsMemo(entries, folders, openFolder, search, sortAscending) {
            val query = search.trim()
            // The top level has no path of its own; its children are the folders with no parent.
            val parent = openFolder.orEmpty()
            val rows = childFolders[parent].orEmpty().asSequence()
                .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                .map { folder ->
                    val counts = subtreeCounts[folder.path] ?: IntArray(2)
                    FolderRow(
                        folder = folder,
                        mediaCount = counts[0],
                        folderCount = counts[1],
                        mixed = counts[0] > 0 && counts[1] > 0,
                    )
                }
                .sortedWith(compareBy(NATURAL_ORDER) { it.folder.name })
                .toList()
            if (sortAscending) rows else rows.asReversed()
        }

    /** True when the current level shows folders instead of media. */
    val showsFolders: Boolean get() = folderRows.isNotEmpty()

    private val folderMediaMemo = Memo<List<Entry>>()

    /** The media of the current level, shown only when that level has no subfolders. */
    val folderMedia: List<Entry>
        get() {
            val folder = openFolder ?: return emptyList()
            if (showsFolders) return emptyList()
            return folderMediaMemo(entries, openFolder, search, filter, sortMode, sortAscending) {
                val query = search.trim()
                val media = entriesIn(folder)
                    .filter { filter.accepts(it.mediaType) }
                    .filter { query.isEmpty() || it.fileName.contains(query, ignoreCase = true) }
                media.sortedWith(mediaOrder())
            }
        }

    /** Breadcrumbs from the first level down to the open folder, excluding the `画集` root. */
    val breadcrumb: List<Folder>
        get() {
            val folder = currentFolder ?: return emptyList()
            return (folder.ancestors() + folder.path).map(::Folder)
        }

    /** The files directly inside one folder. Grouped once per state, not scanned per call. */
    private fun entriesIn(folderPath: String): List<Entry> = entriesByParent[folderPath].orEmpty()

    /**
     * What「序号/名称/拍摄时间/修改时间/文件大小」mean for a list of files, in the chosen direction.
     *
     * A file with no value for the chosen order (no number in its name, no capture time) always
     * sorts last whichever direction is asked for: it is missing data, not the smallest value.
     */
    private fun mediaOrder(): Comparator<Entry> {
        val ascending = when (sortMode) {
            SortMode.SEQUENCE -> compareBy<Entry> { it.sequence == null }
                .thenBy { it.sequence ?: Long.MAX_VALUE }
                .thenBy(NATURAL_ORDER) { it.fileName }
            SortMode.NAME -> compareBy(NATURAL_ORDER) { it.fileName }
            SortMode.CAPTURED -> compareBy<Entry> { it.captured == null }
                .thenBy { it.captured ?: Long.MAX_VALUE }
                .thenBy(NATURAL_ORDER) { it.fileName }
            SortMode.MODIFIED -> compareBy(Entry::modified).thenBy(NATURAL_ORDER) { it.fileName }
            SortMode.SIZE -> compareBy(Entry::size).thenBy(NATURAL_ORDER) { it.fileName }
        }
        return if (sortAscending) ascending else ascending.reversed()
    }
}

/**
 * Owns the attached Library, the cached index, and the silent refresh.
 *
 * The cached index is shown the moment it is read and the scan runs afterwards, so a Library with
 * tens of thousands of files opens as fast as one with ten. Scanning never happens on its own:
 * [refresh] is the only entry point, and it runs on attach and on the user's button.
 */
class RemViewModel(application: Application) : AndroidViewModel(application) {

    private val store = LibraryStore(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var tree: LibraryTree? = null

    /** Everything read or reused so far, keyed by path. Seeded from the cache, then extended. */
    private val known = linkedMapOf<String, Entry>()

    private var folders: List<String> = emptyList()
    private var violations: List<String> = emptyList()

    /** How many entries the cache file held at the last incremental write. */
    private var lastWritten = 0

    /** When the screen was last refreshed from a progress report. */
    private var lastPublished = 0L

    /** Paths the running pass has actually seen. Anything else is gone from the volume. */
    private val readThisPass = mutableSetOf<String>()

    /**
     * 画集 projects the listing walk has finished, across passes.
     *
     * The walk cannot be shortened — no provider reports a folder's modification time — so on a
     * real Library it runs for minutes, and it is routinely stopped before the end. This is the
     * resume point: an interrupted pass leaves the projects it finished behind, and the next one
     * starts at the first project that was never reached instead of listing everything again.
     */
    private val doneProjects = mutableSetOf<String>()

    /**
     * Paths whose capture time and place have been read.
     *
     * Kept across passes and written into the index, which is what makes the metadata pass resumable:
     * a Library is listed once, and the expensive per-file reading continues from wherever it stopped
     * instead of starting over.
     */
    private val metadataDone = mutableSetOf<String>()

    /**
     * Set when an index write has been asked for, drained by [writer].
     *
     * Sending never suspends — the buffer holds one — so a scan's progress callback can ask for a
     * write from inside the walk without waiting for a volume round-trip. Requests collapse: a
     * second one while the first is still pending is the same request, because what gets written
     * is read when the write starts, not when it was asked for.
     */
    private val writeRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * The one writer. Every index write goes through it, one at a time.
     *
     * Writes used to be launched independently and the previous one cancelled. That reads as
     * harmless — the newest state wins — and it is not: `persist` reads `known` and then encodes
     * it, so a write started before the walk added its last projects and encoding after them
     * overwrote the walk's own, complete, write with an older picture of the Library. Measured on
     * a test Library: the last four projects walked were missing from the cache afterwards, 5562
     * entries down to 5518, with nothing to show for it but a file that looked fine.
     */
    private val writer: Job by lazy {
        viewModelScope.launch(Dispatchers.IO) {
            for (ignored in writeRequests) persist()
        }
    }

    /** Asks for the index to be written without waiting for it. */
    private fun requestWrite() {
        writeRequests.trySend(Unit)
    }

    init {
        // Started here rather than left to first use: `requestWrite` only posts to the channel, so
        // nothing else would ever touch the lazy value and the drain loop would never run.
        writer
        // Launched, never called directly. The work is provider calls, and running them here — as
        // the constructor used to — means the first frame waits for the volume.
        viewModelScope.launch { openAttachedLibrary() }
        refreshThumbnailSize()
    }

    fun attach(treeUri: Uri) {
        markerJob?.cancel()
        scanJob?.cancel()
        metadataJob?.cancel()
        store.attach(treeUri)
        resetLibraryState()
        _state.update { UiState(sortMode = it.sortMode) }
        viewModelScope.launch { openAttachedLibrary() }
    }

    fun detach() {
        markerJob?.cancel()
        scanJob?.cancel()
        metadataJob?.cancel()
        tree = null
        store.detach()
        resetLibraryState()
        _state.update { UiState(sortMode = it.sortMode) }
    }

    private fun resetLibraryState() {
        known.clear()
        metadataDone.clear()
        folders = emptyList()
        violations = emptyList()
    }

    /** Surfaces a message that a screen wants to show without owning a snackbar host. */
    fun notify(message: String) = _state.update { it.copy(message = message) }

    fun selectTab(tab: Tab) = _state.update { it.copy(tab = tab) }

    fun selectFilter(filter: MediaFilter) = _state.update { it.copy(filter = filter) }

    fun search(text: String) = _state.update { it.copy(search = text) }

    /**
     * Opens one folder of the collection.
     *
     * The path is resolved before it becomes the current level. A folder deleted on the volume is
     * otherwise a level that renders empty with no way to tell it apart from a folder that really is
     * empty, and the way back is not obvious either — so the viewer of the tree stays where it is,
     * says what happened, and refreshes.
     */
    fun openFolder(path: String) {
        val current = tree
        if (current == null) {
            openFolderLocally(path)
            return
        }
        if (hasFolder(path)) {
            openFolderLocally(path)
            return
        }
        viewModelScope.launch {
            val stillThere = withContext(Dispatchers.IO) {
                runCatching { current.isDirectory(path) }.getOrDefault(false)
            }
            if (stillThere) {
                openFolderLocally(path)
            } else {
                RemLog.warn(SCOPE, "文件夹 '$path' 已不在库中，回到上级")
                val parent = Folder(path).parent
                _state.update {
                    it.copy(
                        openFolder = parent,
                        search = "",
                        message = "「${Folder(path).name}」已不在库中，已返回上一级",
                    )
                }
                refresh()
            }
        }
    }

    /** True when the index already lists this folder. */
    private fun hasFolder(path: String): Boolean = folders.any { it == path } ||
        known.keys.any { it.startsWith("$path/") }

    private fun openFolderLocally(path: String) =
        _state.update { it.copy(openFolder = path, search = "") }

    /** Goes back one level, or to the collection's top level from a first-level folder. */
    fun closeFolder() = _state.update {
        it.copy(openFolder = it.currentFolder?.parent, search = "")
    }

    /** Remembers the collection order; it survives restarts, unlike the browsing position. */
    fun selectSortMode(mode: SortMode) {
        store.sortMode = mode
        _state.update { it.copy(sortMode = mode) }
    }

    /** Flips the order direction, for both the album and the collection. Remembered. */
    fun toggleSortDirection() {
        val ascending = !_state.value.sortAscending
        store.sortAscending = ascending
        _state.update { it.copy(sortAscending = ascending) }
    }

    /** Remembers the layout. A reading preference, so it survives restarts. */
    fun selectViewMode(mode: ViewMode) {
        store.viewMode = mode
        _state.update { it.copy(viewMode = mode) }
    }

    /**
     * Refreshes if the volume has become readable since the last look.
     *
     * Called when the app returns to the foreground. A removable volume that was just mounted
     * answers with an empty listing for a while; without this the app would sit on an empty Library
     * until the user detached and re-attached it. The check is cheap and only runs while the last
     * observation said "not readable", so a normal return to the foreground does no provider work.
     */
    fun refreshIfVolumeAppeared() {
        val current = tree ?: return
        if (scanJob?.isActive == true) return
        if (_state.value.libraryReadable) return
        viewModelScope.launch {
            val readable = withContext(Dispatchers.IO) {
                runCatching { current.isAvailable }.getOrDefault(false)
            }
            if (!readable) return@launch
            RemLog.info(SCOPE, "卷已可读，自动刷新 Library")
            openAttachedLibrary()
        }
    }

    /** Reads the thumbnail cache size off the main thread; a directory walk is not free. */
    fun refreshThumbnailSize() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { app.thumbnailCacheBytes(getApplication()) }
            _state.update { it.copy(thumbnailBytes = bytes) }
        }
    }

    fun clearThumbnails() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.clearThumbnailCache(getApplication()) }
            refreshThumbnailSize()
            _state.update { it.copy(message = "缩略图缓存已清空，浏览时会重新生成") }
        }
    }

    private val app: RemApplication get() = getApplication()

    /** Drops every cached reading and re-reads the Library from scratch. */
    fun rebuildIndex() {
        val current = tree
        if (current == null) {
            _state.update { it.copy(message = "尚未接入 Library，无法重建索引") }
            return
        }
        if (scanJob?.isActive == true) return
        known.clear()
        metadataDone.clear()
        // The resume record is part of the cache, so rebuilding has to forget it too. Leaving it
        // was worse than not rebuilding at all: the deleted cache made the walk start from nothing,
        // while the record still claimed every project was finished, so the pass skipped all of them
        // and the Library came back as album-only — measured on a test Library, 5562 entries down
        // to 2200. This is also the supported way to see a file added inside an existing project,
        // because a resumed walk deliberately does not re-list finished projects.
        doneProjects.clear()
        folders = emptyList()
        violations = emptyList()
        lastWritten = 0
        _state.update {
            it.copy(entries = emptyList(), folders = emptyList(), violations = emptyList())
        }
        RemLog.info(SCOPE, "重建索引：已丢弃缓存，重新扫描")
        // The file is dropped before the walk starts, not alongside it. Launched separately, the
        // walk could write a fresh index first and the drop then deleted it, leaving a Library that
        // had just been walked with no cache at all.
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.dropIndex(current) }
            refresh()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun dismissViolations() = _state.update { it.copy(violations = emptyList()) }

    /**
     * Creates or removes the Library root `.nomedia`.
     *
     * Only that one file is touched: original media is never modified and existing `MediaStore`
     * rows are never deleted, so the message describes the resulting marker state instead of
     * promising the system gallery is now empty.
     *
     * A tap while the previous one is still running is ignored. Two overlapping calls would both
     * see "no marker" and both create one, which is exactly the duplicate the rules forbid.
     */
    fun setHideFromSystemGallery(hidden: Boolean) {
        val current = tree
        if (current == null) {
            _state.update { it.copy(message = "尚未接入 Library，无法创建或移除 .nomedia") }
            return
        }
        if (markerJob?.isActive == true) return
        markerJob = viewModelScope.launch {
            _state.update { it.copy(markerBusy = true) }
            val resulting = runCatching {
                withContext(Dispatchers.IO) { current.setSystemGalleryHidden(hidden) }
            }.getOrNull()
            _state.update {
                it.copy(
                    markerBusy = false,
                    // The tree is the only source of truth, so a failed call keeps the old value.
                    hideFromSystemGallery = resulting ?: it.hideFromSystemGallery,
                    message = when (resulting) {
                        null -> "无法更新 .nomedia，请确认 Library 仍可写入"
                        true -> "已开启：Library 根目录存在 .nomedia，后续新媒体不再进入系统媒体索引；已有记录可能仍显示"
                        false -> "已关闭：Library 根目录没有 .nomedia，后续新媒体可以重新进入系统媒体索引"
                    },
                )
            }
        }
    }

    /**
     * The URI of one Library file, for the viewer and for thumbnails.
     *
     * Safe to call while composing: it reads the recorded URI or derives it from the path, and
     * never asks the provider. Resolving a path here instead walked it with a round-trip per level,
     * which is what froze the app on a real device.
     */
    fun documentUri(entry: Entry): Uri? = tree?.uriFor(entry.path)

    /** Opens one file with whatever system app claims its type. */
    fun open(entry: Entry) {
        val type = entry.mediaType ?: return
        val uri = documentUri(entry)
        if (uri == null) {
            // The index still listed a file the volume no longer has. Say so, then re-read so the
            // grid stops offering it.
            _state.update { it.copy(message = "找不到 ${entry.fileName}，可能已被移动或删除，正在重新扫描") }
            refresh()
            return
        }
        val outcome = OpenWith.launch(getApplication(), uri, type)
        RemLog.info(SCOPE, "打开 '${entry.path}' type=$type -> $outcome")
        if (outcome is Outcome.NoHandler) {
            _state.update { it.copy(message = "系统没有可以打开 ${entry.fileName} 的应用") }
        }
    }

    /** The running scan, if any. Held so a second request cannot start a scan beside it. */
    private var scanJob: Job? = null

    /** The running metadata catch-up pass, if any. One at a time, like the scan itself. */
    private var metadataJob: Job? = null

    /**
     * The running `.nomedia` change, if any.
     *
     * Held so a second tap cannot start a parallel change: both would read the root before either
     * wrote, and both would then create the marker.
     */
    private var markerJob: Job? = null

    /**
     * Re-reads the Library, reusing every cached reading whose file is untouched.
     *
     * The index is written as the pass advances rather than only at the end. On a removable volume
     * a full scan of a real Library runs for many minutes, and the process can be killed long
     * before it finishes — measured on device: nine minutes of scanning lost because the app was
     * swiped away. Everything reported so far is therefore already on disk, and the next pass
     * reuses it instead of starting over.
     *
     * A scan already in progress makes this a no-op: two scans walking the same tree would each do
     * the same content reads and then race to write the index.
     */
    fun refresh() {
        val current = tree ?: return
        if (scanJob?.isActive == true) return
        // The catch-up pass is stopped first, for two reasons that both matter.
        //
        // Only one of the two should be reading the volume: the listing walk is the one the user is
        // waiting for, and the metadata pass is resumable, so interrupting it costs at most one
        // batch. And both write the whole index, so overlapping them means the index is written
        // from whichever pass happens to run last — measured on a test Library, a metadata
        // checkpoint landing mid-walk left the cache claiming a project was finished while holding
        // none of its files. It restarts below, after the walk's own write.
        //
        // Joined, not just cancelled: a cancelled pass can still be inside its write, and that
        // write is the stale one this is here to keep out of the way.
        val interruptedMetadata = metadataJob?.takeIf { it.isActive }
        if (interruptedMetadata != null) {
            RemLog.info(SCOPE, "重新扫描打断了元数据补齐，等遍历结束后继续")
            interruptedMetadata.cancel()
        }
        // Reuse decides by path, so the cached readings stay in `known` while the pass runs — the
        // grid must not go blank for the tens of seconds a large Library takes. What the pass reads
        // is recorded separately, and anything the pass never saw is dropped when it finishes.
        val cachedSnapshot = known.toMap()
        readThisPass.clear()
        // A resumed pass does not walk the projects an earlier pass finished, so by the usual
        // bookkeeping nothing under them was "seen" — and the pass, being complete, would then
        // treat every one of their files as deleted and drop it from the cache. Measured on a test
        // Library: a resumed pass took 5123 entries down to 2470.
        //
        // They are accounted for, just not re-listed: the resume record is the pass's own statement
        // that this subtree was listed and has not been touched since. Counting them as present is
        // what separates "the whole Library is accounted for" from "the whole Library was re-listed
        // this time" — and only the second is a reason to forget anything.
        val resumed = doneProjects.toList()
        readThisPass += known.keys.filter { path ->
            if (!path.startsWith(COLLECTION_PREFIX)) {
                false
            } else {
                // Exactly the project segment, so a project is matched without building a string
                // for it. A Library of tens of thousands of entries is compared once each.
                var separator = path.indexOf('/', COLLECTION_PREFIX.length)
                while (separator > 0) {
                    if (path.substring(0, separator) in resumed) return@filter true
                    separator = path.indexOf('/', separator + 1)
                }
                false
            }
        }
        lastWritten = known.size
        RemLog.info(
            SCOPE,
            "刷新开始 缓存条目=${known.size} 已保留=${readThisPass.size} 续扫记录=${resumed.size}",
        )
        scanJob = viewModelScope.launch {
            // Waited for here rather than in `refresh` itself, which is not a suspend function:
            // the walk must not start while the pass it just interrupted is still writing.
            interruptedMetadata?.join()
            _state.update {
                it.copy(refreshing = true, scanProgress = ScanProgress(known.size, known.size))
            }
            val sink = object : ScanSink {
                override fun onProgress(
                    entries: List<Entry>,
                    folders: List<String>,
                    violations: List<String>,
                    scanned: Int,
                    total: Int,
                ) {
                    // Entries are added for what the pass reads; what it never reports again is
                    // pruned when the pass ends. Adding without removing left entries for files that
                    // had been moved or deleted on the volume — the grid kept showing them and
                    // opening one produced a black screen.
                    entries.forEach {
                        known[it.path] = it
                        readThisPass += it.path
                    }
                    this@RemViewModel.folders = folders
                    this@RemViewModel.violations = violations
                    _state.update {
                        it.copy(
                            entries = known.values.toList(),
                            folders = folders,
                            scanProgress = ScanProgress(scanned, total),
                        )
                    }
                    RemLog.debug(SCOPE, "进度上报 已读=$scanned 发现=$total 新增=${entries.size}")
                    // The screen is redrawn at most every few seconds while listing. Piecing it
                    // together from every report rebuilt the whole grid — and re-derived every list
                    // — while the reader is already busy with the volume, which is what made the
                    // progress counter feel like it froze the app.
                    val now = System.currentTimeMillis()
                    if (now - lastPublished < UI_PUBLISH_INTERVAL_MS) return
                    lastPublished = now
                    // Durable now, not at the end of the pass: this callback is the whole reason a
                    // scan killed halfway still leaves its work behind for the next one to reuse.
                    //
                    // Written only when the progress is worth a whole-index rewrite. Each write is
                    // the entire index — 1.6 MB for nine thousand files — and on a removable volume
                    // that write competes with the reads the scan itself is waiting for.
                    if (known.size - lastWritten < PERSIST_EVERY_ENTRIES) return
                    lastWritten = known.size
                    // A write still running is dropped in favour of this newer one.
                    requestWrite()
                }

                /**
                 * Checkpointed the moment a project is finished, not at the end of the pass.
                 *
                 * The end of the pass is what an interrupted walk never reaches, and the whole
                 * point of recording this is to survive exactly that. It is a small write — the
                 * index is already being written every couple of seconds — and it means a kill
                 * costs at most the one project in flight rather than the whole walk.
                 */
                override fun onProjectDone(path: String) {
                    // Only projects walked this pass arrive here now; the ones that were skipped
                    // are already in the set and reporting them again would rewrite the whole
                    // index once per project for no new information.
                    doneProjects += path
                    RemLog.info(SCOPE, "项目已遍历完成：$path（累计 ${doneProjects.size} 个，已记录可续扫）")
                    requestWrite()
                }
            }
            runCatching {
                // No seed write. It used to rewrite the cache with "whatever is known so far" before
                // the pass started, which on a resumed Library replaced a large cached index with a
                // handful of entries — measured on device: 5562 entries became one. A pass that is
                // interrupted now leaves the previous cache untouched; only real progress overwrites.
                // Listing only: no media file is opened, so a Library of tens of thousands of files
                // becomes browsable in seconds. Capture times follow in the metadata pass below.
                //
                // Moved off the main dispatcher, which is where this whole walk used to run. It is
                // hundreds of provider round-trips over a USB volume, and doing it on the thread
                // that answers input is what produced the ANR the system attributed to
                // `Pass.readAlbum -> LibraryTree.list -> childrenOf -> ContentResolver.query` —
                // with the UI frozen for the minutes the walk needed before it was killed, leaving
                // an index holding only the part it had reached.
                val result = withContext(Dispatchers.IO) {
                    Scanner(getApplication(), current)
                        .scan(
                            cached = cachedSnapshot,
                            sink = sink,
                            readMetadata = false,
                            // Where the previous pass stopped. Projects listed here are already in
                            // `known`, so the pass walks only what was never reached.
                            doneProjects = doneProjects.toList(),
                        )
                }
                // The pass is the only thing that has actually looked at 画集, so its list of what
                // is there replaces the resume set. Building that set any other way cannot work:
                // the old record may name a project that has been deleted (which would keep being
                // skipped), and a project added since the last pass is in no record at all.
                doneProjects.clear()
                doneProjects += result.allProjects
                // Deliberately not written here.
                //
                // `result.entries` holds only the projects this pass walked, and after a resume
                // that is not the Library — the skipped ones are absent from it. Writing that view
                // raced the progress callbacks' own writes, which carry the full picture, and the
                // loser of the race decided what the cache said: measured on a test Library, a
                // resumed pass left 284 of 303 projects behind. The write belongs after the walk,
                // once, from the merged set.
                result
            }.fold(
                onSuccess = { result ->
                    // Only a pass that covered the whole tree can say what is gone. A partial one
                    // never saw most of the Library, and pruning on its evidence would delete the
                    // cache of every project it had not reached yet.
                    if (result.complete) {
                        val vanished = known.keys.filterNot { it in readThisPass }
                        RemLog.info(
                            SCOPE,
                            "剪枝前 条目=${known.size} 本轮已见=${readThisPass.size} " +
                                "待移除=${vanished.size} 库方返回=${result.entries.size}",
                        )
                        vanished.forEach { known.remove(it) }
                        metadataDone.retainAll { it in readThisPass }
                        if (vanished.isNotEmpty()) {
                            RemLog.info(SCOPE, "本次扫描发现 ${vanished.size} 个文件已不在库中，已从列表移除")
                        }
                    } else {
                        RemLog.info(
                            SCOPE,
                            "本次只走了一部分项目，保留未遍历项目的缓存；下次刷新会接着走",
                        )
                    }
                    _state.update {
                        it.copy(
                            refreshing = false,
                            scanProgress = null,
                            entries = known.values.toList(),
                            folders = folders,
                            violations = violations,
                            metadataPending = pendingCount(),
                        )
                    }
                    RemLog.info(
                        SCOPE,
                        "刷新完成 条目=${known.size} 文件夹=${folders.size} " +
                            "已完成项目=${doneProjects.size}",
                    )
                    withContext(Dispatchers.IO) { persist() }
                    startMetadataPass(current)
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    // Whatever was read before the failure is still cached; say so rather than
                    // pretending the Library is empty.
                    _state.update {
                        it.copy(
                            refreshing = false,
                            scanProgress = null,
                            entries = known.values.toList(),
                            folders = folders,
                            message = "读取 Library 失败：${error.message ?: "未知错误"}",
                        )
                    }
                },
            )
        }
    }

    /** How many entries the index holds whose capture time has not been read yet. */
    private fun pendingCount(): Int = known.keys.count { it !in metadataDone }

    /**
     * Reads capture times and places in the background, batch by batch.
     *
     * The Library is already usable when this starts — it was listed, not opened. Each batch is
     * checkpointed to the index, so stopping halfway (app killed, volume removed) costs only the
     * batch in flight. The folder being viewed is done first, because that is the list the user is
     * looking at.
     */
    private fun startMetadataPass(current: LibraryTree) {
        if (metadataJob?.isActive == true) return
        if (!_state.value.libraryReadable) {
            RemLog.info(SCOPE, "卷当前不可读，跳过补齐")
            return
        }
        val pending = known.values.filter { it.path !in metadataDone }
        if (pending.isEmpty()) {
            _state.update { it.copy(metadataPending = 0) }
            return
        }
        RemLog.info(SCOPE, "开始补齐元数据 待读=${pending.size}")
        metadataJob = viewModelScope.launch {
            val visible = _state.value.openFolder
            val ordered = if (visible == null) {
                pending
            } else {
                pending.sortedByDescending { it.parentFolder == visible }
            }
            val scanner = Scanner(getApplication(), current)
            var read = 0
            ordered.chunked(METADATA_BATCH).forEach { batch ->
                // Off the main dispatcher for the same reason as the listing walk: each batch opens
                // files on the volume, and the coroutine this runs in is main-dispatched.
                val readBatch = withContext(Dispatchers.IO) {
                    runCatching { scanner.readMetadata(batch) }.getOrNull()
                } ?: return@launch
                readBatch.forEach { entry ->
                    known[entry.path] = entry
                    metadataDone += entry.path
                }
                read += readBatch.size
                RemLog.info(
                    SCOPE,
                    "补齐批次完成 已读=$read/${ordered.size} 待补剩余=${pendingCount()}",
                )
                _state.update {
                    it.copy(
                        entries = known.values.toList(),
                        metadataPending = pendingCount(),
                    )
                }
                // Checkpointed per batch: the index is written with what has been read so far, but
                // without the resume record — only the listing walk may say which projects it has
                // finished, because only it knows.
                withContext(Dispatchers.IO) { persist(fromWalk = false) }
            }
            RemLog.info(SCOPE, "元数据补齐完成 本次读=$read 剩余=${pendingCount()}")
        }
    }

    /**
     * Writes what is known so far. Called before, during and after a pass.
     *
     * Always the whole picture, never a pass's own slice of it. A resumed pass walks only the
     * projects an earlier one had not reached, so its own result describes a fraction of the
     * Library — writing that was how a resume ended up replacing a full cache with a partial one.
     */
    private suspend fun persist(fromWalk: Boolean = true) {
        val current = tree ?: return
        // A volume that is not readable cannot be written either, and asking anyway produced a
        // stream of "无法创建或打开文件" errors for every report while the drive was still mounting.
        if (!runCatching { current.isAvailable }.getOrDefault(false)) return
        val snapshot = known.values.toList()
        // A project may only be recorded as finished once the files under it are in the same
        // snapshot. A batch still waiting to be handed over — `report` is throttled — would
        // otherwise leave the cache claiming a project is fully listed while holding none of it,
        // and the next pass would skip it and show an empty folder.
        //
        // Walked as a set of directories rather than a search per project: this runs on every
        // write, and comparing every project against every entry would be millions of comparisons
        // per write on a real Library.
        val covered = mutableSetOf<String>()
        snapshot.forEach { entry ->
            var slash = entry.path.indexOf('/')
            while (slash > 0) {
                covered += entry.path.substring(0, slash)
                slash = entry.path.indexOf('/', slash + 1)
            }
        }
        // The metadata pass reads one file at a time and knows nothing about the listing walk, so
        // letting it write the resume record meant writing the record it happened to see. Measured
        // on a test Library: one project left marked finished with its files absent, because a
        // metadata checkpoint landed between the walk finishing that project and the walk's own
        // write. It is a better checkpoint while a walk is running — the walk writes on its own,
        // and the metadata it just read is not lost by leaving the record alone.
        val writeable = if (fromWalk) doneProjects else emptySet()
        val claimable = writeable.intersect(covered)
        if (claimable.size != writeable.size) {
            RemLog.warn(
                SCOPE,
                "续扫记录里有 ${writeable.size - claimable.size} 个项目尚无条目，本次不写入，留待重走",
            )
        }
        store.writeIndex(
            current,
            indexOf(
                entries = snapshot,
                folders = folders,
                violations = violations,
                metadataDone = metadataDone,
                doneProjects = claimable,
            ),
        )
    }

    /**
     * Shows the cached index, then continues whatever reading is outstanding.
     *
     * Everything in here talks to the provider, and every one of those calls can block for as long
     * as the volume takes — on a USB drive that is often seconds, and when the drive is being
     * inserted or removed it can be longer than any frame will wait. It used to be called straight
     * from the constructor, which put all of it on the composition thread: measured on a real
     * device, the system caught the main thread inside `DocumentsContractApi19.exists` for five
     * seconds while the first frame was being composed, so nothing was ever drawn and the app was
     * simply a white screen. Suspending, so no caller can accidentally do that again.
     */
    private suspend fun openAttachedLibrary() {
        val current = store.tree()
        if (current == null) {
            RemLog.info(SCOPE, "没有已接入的 Library")
            _state.update {
                UiState(
                    sortMode = store.sortMode,
                    viewMode = store.viewMode,
                    sortAscending = store.sortAscending,
                )
            }
            return
        }
        tree = current
        // One hop off the composition thread for every provider call this needs. The name is in
        // here too: it looks like a plain property and is another document query, which is exactly
        // how it ended up on the main thread inside the log line below.
        val probe = withContext(Dispatchers.IO) {
            val names = runCatching { current.list("").map { it.name } }
                .onFailure { RemLog.error(SCOPE, "根目录列举失败", it) }
                .getOrDefault(emptyList())
            OpenProbe(
                readable = runCatching { current.isAvailable }.getOrDefault(false),
                rootId = current.rootDocumentId(),
                name = current.name,
                rootNames = names,
                cached = store.readIndex(current),
                hidden = current.isSystemGalleryHidden(),
            )
        }
        val readable = probe.readable
        val rootNames = probe.rootNames
        val cached = probe.cached
        RemLog.info(
            SCOPE,
            "打开已接入的 Library root='${probe.rootId}' 可读=$readable 名称='${probe.name}'",
        )
        RemLog.info(SCOPE, "根目录直属项 ${rootNames.size}：${rootNames.joinToString("、")}")

        known.clear()
        metadataDone.clear()
        doneProjects.clear()
        cached?.entries?.forEach { stored ->
            known[stored.path] = stored.toEntry()
            if (stored.metadataRead) metadataDone += stored.path
        }
        // Restored so a refresh continues the listing walk instead of starting it over. Only
        // projects still present can match, and the walk drops the rest on its own.
        doneProjects += cached?.doneProjects.orEmpty()
        folders = cached?.folders.orEmpty()
        violations = cached?.violations.orEmpty()
        _state.update {
            it.copy(
                attached = true,
                treeUri = current.treeUri,
                tree = current,
                libraryName = probe.name,
                entries = known.values.toList(),
                folders = folders,
                violations = violations,
                sortMode = store.sortMode,
                viewMode = store.viewMode,
                sortAscending = store.sortAscending,
                hideFromSystemGallery = probe.hidden,
                libraryReadable = readable,
            )
        }
        // Rewritten on every attach so the Library never carries a stale spec.
        viewModelScope.launch(Dispatchers.IO) {
            current.cleanUpDuplicateInternalDirectories()
            store.cleanUpMangledFiles(current)
            store.writeRules(current)
        }

        // Deliberately no scan here.
        //
        // Walking the tree used to start on every launch, and on a real Library of 48 000 files
        // across hundreds of folders on a USB volume that walk takes minutes even when every entry
        // is reused. The app was therefore busy re-listing a Library that had not changed, and a
        // walk that did not finish in time was killed and started again from the top — which is
        // exactly what "it scans from scratch every time" was.
        //
        // The cached index is what makes the Library usable immediately, and the metadata pass
        // below continues whatever reading is still outstanding. Re-listing happens when the user
        // asks for it (the refresh button or 重建索引), or when the volume appears after being
        // unreadable.
        if (known.isEmpty()) {
            refresh()
        } else {
            RemLog.info(SCOPE, "使用缓存直接打开，共 ${known.size} 条；不自动遍历")
            startMetadataPass(current)
        }
    }

    private companion object {
        const val SCOPE = "Rem"

        /**
         * How much new progress justifies rewriting the whole index.
         *
         * The cache file is rewritten in full, so the cost is proportional to the Library, not to
         * what changed. Two hundred entries is a few seconds of scanning on a slow volume and keeps
         * the exposure to a lost pass small without writing constantly.
         */
        const val PERSIST_EVERY_ENTRIES = 200

        /**
         * Files per metadata batch.
         *
         * Each batch is checkpointed and pushed to the screen. Two hundred is a few seconds of
         * reading on a slow volume: often enough that stopping loses almost nothing, rare enough
         * that the index is not rewritten constantly.
         */
        const val METADATA_BATCH = 200

        /** How often a running listing refreshes the screen. */
        const val UI_PUBLISH_INTERVAL_MS = 5000L
    }
}

/**
 * Start of every path under `画集/`, used to pull a project out of an entry path.
 *
 * Built from the one place the folder is named, so renaming the collection cannot leave this
 * matching nothing and silently switching the resume logic off.
 */
private val COLLECTION_PREFIX = "$COLLECTION/"
/**
 * A one-slot memo for a derived list.
 *
 * Compose reads these getters several times per composition and recomposes often; without this,
 * every read re-sorted the whole Library. The key is the identity of the inputs the computation
 * reads, so a new state object recomputes and an unchanged one does not.
 */
private class Memo<T> {
    private var key: List<Any?>? = null
    private var value: T? = null

    operator fun invoke(vararg inputs: Any?, compute: () -> T): T {
        val current = inputs.toList()
        if (key != current) {
            value = compute()
            key = current
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}

/** `作者名称-项目名称` split for a row label, or null when the folder does not follow the rule. */
fun authorOf(folder: Folder): String? = splitProjectFolder(folder.name)?.first

/** The second line of a folder row: what is inside, without promising an order. */
fun FolderRow.detail(): String = buildString {
    if (folderCount > 0) append("$folderCount 个文件夹")
    if (mediaCount > 0) {
        if (isNotEmpty()) append(" · ")
        append("$mediaCount 个媒体文件")
    }
    if (mixed) append(" · 两者都有，先显示文件夹")
    if (isEmpty()) append("空文件夹")
}