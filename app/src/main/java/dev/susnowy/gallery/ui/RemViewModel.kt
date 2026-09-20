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
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.COLLECTION
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.Folder
import dev.susnowy.gallery.model.MediaType
import dev.susnowy.gallery.model.NATURAL_ORDER
import dev.susnowy.gallery.model.SortMode
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val refreshing: Boolean = false,
    /** Non-null only while a scan is running. */
    val scanProgress: ScanProgress? = null,
    val message: String? = null,
    val violations: List<String> = emptyList(),
    val hideFromSystemGallery: Boolean = false,
    /** True while the `.nomedia` marker is being written or removed. */
    val markerBusy: Boolean = false,
) {
    /** Album entries in newest-first order, filtered by media type. */
    val visibleAlbum: List<Entry>
        get() = entries.asSequence()
            .filter { it.projectFolder == null }
            .sortedByDescending(Entry::orderTime)
            .filter { filter.accepts(it.mediaType) }
            .toList()

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
    val folderRows: List<FolderRow>
        get() {
            val query = search.trim()
            // The top level has no path of its own; its children are the folders with no parent.
            val parent = openFolder.orEmpty()
            return childFolders[parent].orEmpty().asSequence()
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
        }

    /** True when the current level shows folders instead of media. */
    val showsFolders: Boolean get() = folderRows.isNotEmpty()

    /** The media of the current level, shown only when that level has no subfolders. */
    val folderMedia: List<Entry>
        get() {
            val folder = openFolder ?: return emptyList()
            if (showsFolders) return emptyList()
            val query = search.trim()
            val media = entriesIn(folder)
                .filter { filter.accepts(it.mediaType) }
                .filter { query.isEmpty() || it.fileName.contains(query, ignoreCase = true) }
            return media.sortedWith(mediaOrder())
        }

    /** Breadcrumbs from the first level down to the open folder, excluding the `画集` root. */
    val breadcrumb: List<Folder>
        get() {
            val folder = currentFolder ?: return emptyList()
            return (folder.ancestors() + folder.path).map(::Folder)
        }

    /** The files directly inside one folder. Grouped once per state, not scanned per call. */
    private fun entriesIn(folderPath: String): List<Entry> = entriesByParent[folderPath].orEmpty()

    /** What「序号/名称/拍摄时间/修改时间/文件大小」mean for a list of files. */
    private fun mediaOrder(): Comparator<Entry> = when (sortMode) {
        // A name with no number at all sorts after every numbered one.
        SortMode.SEQUENCE -> compareBy<Entry> { it.sequence == null }
            .thenBy { it.sequence ?: Long.MAX_VALUE }
            .thenBy(NATURAL_ORDER) { it.fileName }
        SortMode.NAME -> compareBy(NATURAL_ORDER) { it.fileName }
        SortMode.CAPTURED -> compareByDescending<Entry> { it.captured ?: Long.MIN_VALUE }
            .thenBy(NATURAL_ORDER) { it.fileName }
        SortMode.MODIFIED -> compareByDescending(Entry::modified).thenBy(NATURAL_ORDER) { it.fileName }
        SortMode.SIZE -> compareByDescending(Entry::size).thenBy(NATURAL_ORDER) { it.fileName }
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

    init {
        openAttachedLibrary()
    }

    fun attach(treeUri: Uri) {
        markerJob?.cancel()
        scanJob?.cancel()
        store.attach(treeUri)
        resetLibraryState()
        _state.update { UiState(sortMode = it.sortMode) }
        openAttachedLibrary()
    }

    fun detach() {
        markerJob?.cancel()
        scanJob?.cancel()
        tree = null
        store.detach()
        resetLibraryState()
        _state.update { UiState(sortMode = it.sortMode) }
    }

    private fun resetLibraryState() {
        known.clear()
        folders = emptyList()
        violations = emptyList()
    }

    /** Surfaces a message that a screen wants to show without owning a snackbar host. */
    fun notify(message: String) = _state.update { it.copy(message = message) }

    fun selectTab(tab: Tab) = _state.update { it.copy(tab = tab) }

    fun selectFilter(filter: MediaFilter) = _state.update { it.copy(filter = filter) }

    fun search(text: String) = _state.update { it.copy(search = text) }

    /** Opens one folder of the collection. */
    fun openFolder(path: String) = _state.update { it.copy(openFolder = path, search = "") }

    /** Goes back one level, or to the collection's top level from a first-level folder. */
    fun closeFolder() = _state.update {
        it.copy(openFolder = it.currentFolder?.parent, search = "")
    }

    /** Remembers the collection order; it survives restarts, unlike the browsing position. */
    fun selectSortMode(mode: SortMode) {
        store.sortMode = mode
        _state.update { it.copy(sortMode = mode) }
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

    /** The URI of one Library file, for the viewer and for handing a file to another app. */
    fun documentUri(entry: Entry): Uri? = tree?.find(entry.path)?.uri

    /** Opens one file with whatever system app claims its type. */
    fun open(entry: Entry) {
        val type = entry.mediaType ?: return
        val uri = documentUri(entry)
        if (uri == null) {
            _state.update { it.copy(message = "找不到 ${entry.fileName}，可能已被移动或删除") }
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

    /**
     * The index write in flight, if any.
     *
     * Held so a report arriving while the previous write is still running cancels it instead of
     * queueing a second write of the same, newer, state. Writes are reconciled by the last one
     * winning, and the last one always carries everything the earlier ones did.
     */
    private var writeJob: Job? = null

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
        lastWritten = known.size
        scanJob = viewModelScope.launch {
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
                    entries.forEach { known[it.path] = it }
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
                    // Durable now, not at the end of the pass: this callback is the whole reason a
                    // scan killed halfway still leaves its work behind for the next one to reuse.
                    //
                    // Written only when the progress is worth a whole-index rewrite. Each write is
                    // the entire index — 1.6 MB for nine thousand files — and on a removable volume
                    // that write competes with the reads the scan itself is waiting for.
                    if (known.size - lastWritten < PERSIST_EVERY_ENTRIES) return
                    lastWritten = known.size
                    // A write still running is dropped in favour of this newer one.
                    writeJob?.cancel()
                    writeJob = viewModelScope.launch(Dispatchers.IO) { persist() }
                }
            }
            runCatching {
                // No seed write. It used to rewrite the cache with "whatever is known so far" before
                // the pass started, which on a resumed Library replaced a large cached index with a
                // handful of entries — measured on device: 5562 entries became one. A pass that is
                // interrupted now leaves the previous cache untouched; only real progress overwrites.
                val result = Scanner(getApplication(), current).scan(known.toMap(), sink)
                withContext(Dispatchers.IO) { persist(result) }
                result
            }.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            refreshing = false,
                            scanProgress = null,
                            entries = known.values.toList(),
                            folders = folders,
                            violations = violations,
                        )
                    }
                    RemLog.info(SCOPE, "刷新完成 条目=${known.size} 文件夹=${folders.size}")
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

    /** Writes what is known so far. Called before, during and after a pass. */
    private suspend fun persist(result: ScanResult? = null) {
        val current = tree ?: return
        store.writeIndex(
            current,
            indexOf(
                entries = result?.entries ?: known.values.toList(),
                folders = result?.folders ?: folders,
                violations = result?.violations ?: violations,
            ),
        )
    }

    /** Shows the cached index immediately, then starts the refresh in the background. */
    private fun openAttachedLibrary() {
        val current = store.tree()
        if (current == null) {
            RemLog.info(SCOPE, "没有已接入的 Library")
            _state.update { UiState(sortMode = store.sortMode) }
            return
        }
        tree = current
        val readable = current.isAvailable
        val rootId = current.rootDocumentId()
        RemLog.info(SCOPE, "打开已接入的 Library root='$rootId' 可读=$readable 名称='${current.name}'")

        // The root listing is what the whole scan rests on, so record exactly what the provider
        // returns for it. An empty list here explains an empty screen everywhere else.
        val rootNames = runCatching { current.list("").map { it.name } }
            .onFailure { RemLog.error(SCOPE, "根目录列举失败", it) }
            .getOrDefault(emptyList())
        RemLog.info(SCOPE, "根目录直属项 ${rootNames.size}：${rootNames.joinToString("、")}")

        val cached = store.readIndex(current)
        known.clear()
        cached?.entries?.forEach { known[it.path] = it.toEntry() }
        folders = cached?.folders.orEmpty()
        violations = cached?.violations.orEmpty()
        _state.update {
            it.copy(
                attached = true,
                treeUri = current.treeUri,
                libraryName = current.name,
                entries = known.values.toList(),
                folders = folders,
                violations = violations,
                sortMode = store.sortMode,
                hideFromSystemGallery = current.isSystemGalleryHidden(),
            )
        }
        // Rewritten on every attach so the Library never carries a stale spec.
        viewModelScope.launch(Dispatchers.IO) {
            current.cleanUpDuplicateInternalDirectories()
            store.cleanUpMangledFiles(current)
            store.writeRules(current)
        }
        refresh()
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