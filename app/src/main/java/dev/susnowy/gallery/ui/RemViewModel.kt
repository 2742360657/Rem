package dev.susnowy.gallery.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.MediaType
import dev.susnowy.gallery.model.Project
import dev.susnowy.gallery.model.splitProjectFolder
import dev.susnowy.gallery.model.toEntry
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

data class UiState(
    val attached: Boolean = false,
    /** The attached tree, needed to build thumbnail requests and to open files. */
    val treeUri: Uri? = null,
    val libraryName: String = "",
    val tab: Tab = Tab.ALBUM,
    val filter: MediaFilter = MediaFilter.ALL,
    val search: String = "",
    val album: List<Entry> = emptyList(),
    val projects: List<Project> = emptyList(),
    /** True while the silent refresh runs, shown as a progress line only. */
    val refreshing: Boolean = false,
    val openProject: String? = null,
    val message: String? = null,
    val violations: List<String> = emptyList(),
    val hideFromSystemGallery: Boolean = false,
    /** True while the `.nomedia` marker is being written or removed. */
    val markerBusy: Boolean = false,
) {
    /** Album entries in newest-first order, filtered by media type. */
    val visibleAlbum: List<Entry>
        get() = album.filter { filter.accepts(it.mediaType) }

    /**
     * Projects after the search box and the media filter are applied.
     *
     * Matching is the minimum the rules ask for: the typed text has to appear somewhere in the
     * author or project name, so `abcde` finds both `abc` and `cde`.
     */
    val visibleProjects: List<Project>
        get() {
            val query = search.trim()
            return projects.mapNotNull { project ->
                if (!query.isEmpty() &&
                    !project.author.contains(query, ignoreCase = true) &&
                    !project.name.contains(query, ignoreCase = true)
                ) {
                    return@mapNotNull null
                }
                val files = project.entries.filter { filter.accepts(it.mediaType) }
                if (files.isEmpty()) null else project.copy(entries = files)
            }
        }

    val currentProject: Project?
        get() = projects.firstOrNull { it.folder == openProject }
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
    private var entries: List<Entry> = emptyList()

    init {
        openAttachedLibrary()
    }

    fun attach(treeUri: Uri) {
        markerJob?.cancel()
        store.attach(treeUri)
        entries = emptyList()
        _state.update { UiState() }
        openAttachedLibrary()
    }

    fun detach() {
        markerJob?.cancel()
        tree = null
        entries = emptyList()
        store.detach()
        _state.update { UiState() }
    }

    /** Surfaces a message that a screen wants to show without owning a snackbar host. */
    fun notify(message: String) = _state.update { it.copy(message = message) }

    fun selectTab(tab: Tab) = _state.update { it.copy(tab = tab) }

    fun selectFilter(filter: MediaFilter) = _state.update { it.copy(filter = filter) }

    fun search(text: String) = _state.update { it.copy(search = text) }

    fun openProject(folder: String) = _state.update { it.copy(openProject = folder) }

    fun closeProject() = _state.update { it.copy(openProject = null) }

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
     * The running `.nomedia` change, if any.
     *
     * Held so a second tap cannot start a parallel change: both would read the root before either
     * wrote, and both would then create the marker.
     */
    private var markerJob: Job? = null

    /**
     * Re-reads the Library, reusing every cached reading whose file is untouched.
     *
     * Runs off the main thread because a provider on a removable volume answers slowly; the
     * already-displayed entries stay on screen the whole time.
     *
     * A scan already in progress makes this a no-op. Two scans walking the same tree would each do
     * the same content reads and then race to write the index, and the user gains nothing: the
     * running scan reads the Library as it is now.
     */
    fun refresh() {
        val current = tree ?: return
        if (scanJob?.isActive == true) return
        val cached = entries.associateBy(Entry::path)
        val cachedViolations = _state.value.violations
        scanJob = viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            runCatching { withContext(Dispatchers.IO) { Scanner(getApplication(), current).scan(cached) } }
                .fold(
                    onSuccess = { scanned ->
                        entries = scanned.entries
                        _state.update {
                            it.copy(
                                refreshing = false,
                                album = scanned.entries
                                    .filter { entry -> entry.projectFolder == null }
                                    .sortedByDescending(Entry::orderTime),
                                projects = groupProjects(scanned.entries),
                                violations = scanned.violations,
                            )
                        }
                        // Reusing every entry means the media on disk is unchanged — but the
                        // violation list is rebuilt from the directory listings on every pass, so
                        // it can move without any entry moving. Skipping the write then left the
                        // cache asserting a violation set the Library no longer had.
                        if (!scanned.reusedCache || scanned.violations != cachedViolations) {
                            withContext(Dispatchers.IO) {
                                store.writeIndex(current, indexOf(entries, scanned.violations))
                            }
                        }
                    },
                    onFailure = { error ->
                        if (error is CancellationException) throw error
                        _state.update {
                            it.copy(
                                refreshing = false,
                                message = "读取 Library 失败：${error.message ?: "未知错误"}",
                            )
                        }
                    },
                )
        }
    }

    /** Shows the cached index immediately, then starts the refresh in the background. */
    private fun openAttachedLibrary() {
        val current = store.tree()
        if (current == null) {
            RemLog.info(SCOPE, "没有已接入的 Library")
            _state.update { UiState() }
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
        entries = cached?.entries?.map { it.toEntry() }.orEmpty()
        _state.update {
            it.copy(
                attached = true,
                treeUri = current.treeUri,
                libraryName = current.name,
                album = entries.filter { entry -> entry.projectFolder == null }
                    .sortedByDescending(Entry::orderTime),
                projects = groupProjects(entries),
                violations = cached?.violations.orEmpty(),
                hideFromSystemGallery = current.isSystemGalleryHidden(),
            )
        }
        // Rewritten on every attach so the Library never carries a stale spec.
        viewModelScope.launch(Dispatchers.IO) {
            store.cleanUpMangledFiles(current)
            store.writeRules(current)
        }
        refresh()
    }

    /**
     * Groups collection files by their project folder.
     *
     * Files are ordered by their `0001` prefix, and an off-rule name is kept but pushed to the
     * end so a malformed file never displaces the numbered run. Projects sort by author then
     * name; a folder that breaks the naming rule sorts last under its raw name.
     */
    private fun groupProjects(all: List<Entry>): List<Project> = all
        .mapNotNull { entry -> entry.projectFolder?.let { it to entry } }
        .groupBy({ it.first }, { it.second })
        .map { (folder, files) ->
            val parts = splitProjectFolder(folder)
            Project(
                folder = folder,
                author = parts?.first ?: folder,
                name = parts?.second ?: folder,
                entries = files.sortedWith(
                    compareBy({ it.sequence == null }, { it.sequence ?: Int.MAX_VALUE }, Entry::fileName),
                ),
            )
        }
        .sortedWith(compareBy({ splitProjectFolder(it.folder) == null }, Project::author, Project::name))

    private companion object {
        const val SCOPE = "Rem"
    }
}
