package dev.susnowy.gallery.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.compare.EditionComparisonReport
import dev.susnowy.gallery.data.GalleryRepository
import dev.susnowy.gallery.importer.SystemMediaAccess
import dev.susnowy.gallery.importer.SystemMediaEntry
import dev.susnowy.gallery.importer.WorkImportKind
import dev.susnowy.gallery.media.ImagePage
import dev.susnowy.gallery.media.ArchiveCacheStats
import dev.susnowy.gallery.media.OfflinePreviewStats
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.DiscoveredEntry
import dev.susnowy.gallery.model.InboxDisposition
import dev.susnowy.gallery.model.MediaGroup
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaSeries
import dev.susnowy.gallery.model.MembershipRules
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.SeriesAssignment
import dev.susnowy.gallery.model.toSeriesRef
import dev.susnowy.gallery.organizer.OrganizationPlan
import dev.susnowy.gallery.organizer.OrganizerTemplate
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen(val title: String) {
    MEDIA("图片 / 视频"),
    WORKS("漫画 / 动漫"),
    LIBRARIES("媒体库"),
    INBOX("Inbox"),
    PHOTOS("相册"),
    SYSTEM_GALLERY("系统相册"),
    IMAGES("图片"),
    IMAGE_SETS("漫画"),
    VIDEOS("视频"),
    SERIES("系列"),
    COLLECTIONS("Collection"),
    AUTHORS("作者"),
    TAGS("标签"),
    SEARCH("搜索"),
    TRASH("回收站"),
    ORGANIZER("整理文件"),
    SETTINGS("设置"),
}

data class GalleryUiState(
    val libraries: List<LibraryRegistration> = emptyList(),
    val activeLibraryId: String? = null,
    val media: List<MediaItem> = emptyList(),
    val discoveries: List<DiscoveredEntry> = emptyList(),
    val allMedia: List<MediaItem> = emptyList(),
    val groups: List<MediaGroup> = emptyList(),
    val series: List<MediaSeries> = emptyList(),
    val selectedGroupId: String? = null,
    val selectedSeriesId: String? = null,
    val screen: AppScreen = AppScreen.PHOTOS,
    val selectedItemId: String? = null,
    val detailItemIds: List<String> = emptyList(),
    /**
     * Ordered Works the open reader belongs to. Opening a Work from a Series keeps the chapter
     * list here, which is how "next chapter" knows where to go.
     */
    val readerQueueIds: List<String> = emptyList(),
    val searchQuery: String = "",
    val operation: String? = null,
    val message: String? = null,
    val autoScan: Boolean = true,
    val autoAdvanceChapters: Boolean = true,
    val trashRetentionDays: Int = 30,
    val systemMedia: List<SystemMediaEntry> = emptyList(),
    val systemMediaAccess: SystemMediaAccess = SystemMediaAccess.NONE,
    val systemMediaLoading: Boolean = false,
) {
    val activeLibrary: LibraryRegistration?
        get() = libraries.firstOrNull { it.libraryId == activeLibraryId }
    val selectedItem: MediaItem?
        get() = allMedia.firstOrNull { it.id == selectedItemId }
    val selectedGroup: MediaGroup?
        get() = groups.firstOrNull { it.id == selectedGroupId }
    val selectedSeries: MediaSeries?
        get() = series.firstOrNull { it.id == selectedSeriesId }

    /** The reader's chapter context, resolved against the current index and order preserved. */
    val readerQueue: List<MediaItem>
        get() = if (readerQueueIds.isEmpty()) {
            emptyList()
        } else {
            val byId = allMedia.associateBy(MediaItem::id)
            readerQueueIds.mapNotNull(byId::get).filter { !it.trashed }
        }
}

class GalleryViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository: GalleryRepository = (application as GalleryApplication).repository
    private val preferences = application.getSharedPreferences("gallery-settings", 0)
    private val activeLibraryId = MutableStateFlow(preferences.getString(ACTIVE_LIBRARY_KEY, null))
    private val screen = MutableStateFlow(
        savedStateHandle.get<String>(SCREEN_KEY)
            ?.let { value -> runCatching { AppScreen.valueOf(value) }.getOrNull() }
            ?: AppScreen.PHOTOS,
    )
    private val selectedItemId = MutableStateFlow(savedStateHandle.get<String>(SELECTED_ITEM_KEY))
    private val selectedGroupId = MutableStateFlow<String?>(null)
    private val selectedSeriesId = MutableStateFlow<String?>(null)
    private val detailItemIds = MutableStateFlow<List<String>>(emptyList())
    private val readerQueueIds = MutableStateFlow<List<String>>(emptyList())
    private val searchQuery = MutableStateFlow(savedStateHandle.get<String>(SEARCH_QUERY_KEY).orEmpty())
    private val message = MutableStateFlow<String?>(null)
    private val autoScan = MutableStateFlow(preferences.getBoolean("auto_scan", true))
    private val autoAdvanceChapters = MutableStateFlow(
        preferences.getBoolean(AUTO_ADVANCE_CHAPTERS_KEY, true),
    )

    /** Current value of the device-local "read on into the next chapter" preference. */
    val autoAdvanceChaptersEnabled: Boolean get() = autoAdvanceChapters.value
    private val retentionDays = MutableStateFlow(preferences.getInt("trash_retention_days", 30))
    private val systemMedia = MutableStateFlow<List<SystemMediaEntry>>(emptyList())
    private val systemMediaAccess = MutableStateFlow(repository.systemMediaAccess())
    private val systemMediaLoading = MutableStateFlow(false)
    private val _organizationPlan = MutableStateFlow<OrganizationPlan?>(null)
    private var organizationRequest = 0L
    val organizationPlan: StateFlow<OrganizationPlan?> = _organizationPlan
    private val _duplicateGroups = MutableStateFlow<List<List<MediaItem>>>(emptyList())
    val duplicateGroups: StateFlow<List<List<MediaItem>>> = _duplicateGroups
    private val _comparison = MutableStateFlow(ComparisonState())
    val comparison: StateFlow<ComparisonState> = _comparison.asStateFlow()
    private var comparisonJob: Job? = null
    private val _offlinePreviewStats = MutableStateFlow(OfflinePreviewStats(files = 0, bytes = 0))
    val offlinePreviewStats: StateFlow<OfflinePreviewStats> = _offlinePreviewStats
    private val _archiveCacheStats = MutableStateFlow(ArchiveCacheStats(files = 0, bytes = 0))
    val archiveCacheStats: StateFlow<ArchiveCacheStats> = _archiveCacheStats
    val progressRevision: StateFlow<Long> = repository.progressRevision
    private var longOperationJob: Job? = null
    private var attachmentJob: Job? = null
    private val _attachment = MutableStateFlow(LibraryAttachmentState())
    val attachment: StateFlow<LibraryAttachmentState> = _attachment.asStateFlow()
    private val _batchMetadataReport = MutableStateFlow<dev.susnowy.gallery.model.BatchMetadataReport?>(null)
    val batchMetadataReport = _batchMetadataReport.asStateFlow()
    private var systemMediaJob: Job? = null
    private val progressClock = AtomicLong(System.currentTimeMillis())

    val uiState: StateFlow<GalleryUiState> = combine(
        repository.libraries,
        combine(
            repository.media,
            repository.discoveries,
            repository.groups,
            repository.series,
        ) { media, discoveries, groups, series ->
            IndexedContent(media, discoveries, groups, series)
        },
        combine(
            screen,
            selectedItemId,
            searchQuery,
            detailItemIds,
            combine(
                selectedGroupId,
                selectedSeriesId,
                readerQueueIds,
            ) { groupId, seriesId, queueIds ->
                ShelfSelection(groupId, seriesId, queueIds)
            },
        ) { currentScreen, selected, query, detailIds, shelf ->
            NavigationStatus(
                currentScreen,
                selected,
                query,
                detailIds,
                shelf.groupId,
                shelf.seriesId,
                shelf.queueIds,
            )
        },
        combine(
            repository.operation,
            message,
            autoScan,
            retentionDays,
            combine(
                autoAdvanceChapters,
                systemMedia,
                systemMediaAccess,
                systemMediaLoading,
            ) { autoAdvance, media, access, loading ->
                ReadingStatus(autoAdvance, SystemGalleryStatus(media, access, loading))
            },
        ) { operation, currentMessage, scan, days, reading ->
            SettingsStatus(operation, currentMessage, scan, days, reading.autoAdvance, reading.gallery)
        },
        activeLibraryId,
    ) { libraries, indexed, navigation, status, activeId ->
        val resolvedActiveId = activeId?.takeIf { id -> libraries.any { it.libraryId == id } }
            ?: libraries.firstOrNull()?.libraryId
        if (activeLibraryId.value != resolvedActiveId) setActiveLibrary(resolvedActiveId)
        GalleryUiState(
            libraries = libraries,
            activeLibraryId = resolvedActiveId,
            media = indexed.media.filter { resolvedActiveId == null || it.libraryId == resolvedActiveId },
            allMedia = indexed.media,
            discoveries = indexed.discoveries.filter {
                resolvedActiveId == null || it.libraryId == resolvedActiveId
            },
            groups = indexed.groups.filter {
                resolvedActiveId == null || it.libraryId == resolvedActiveId
            },
            series = indexed.series.filter {
                resolvedActiveId == null || it.libraryId == resolvedActiveId
            },
            selectedGroupId = navigation.selectedGroupId,
            selectedSeriesId = navigation.selectedSeriesId,
            screen = navigation.screen,
            selectedItemId = navigation.selectedItemId,
            detailItemIds = navigation.detailItemIds,
            readerQueueIds = navigation.readerQueueIds,
            searchQuery = navigation.searchQuery,
            operation = status.operation,
            message = status.message,
            autoScan = status.autoScan,
            autoAdvanceChapters = status.autoAdvanceChapters,
            trashRetentionDays = status.retentionDays,
            systemMedia = status.systemGallery.media,
            systemMediaAccess = status.systemGallery.access,
            systemMediaLoading = status.systemGallery.loading,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    init {
        viewModelScope.launch {
            repository.events.collect { message.value = it }
        }
        viewModelScope.launch {
            delay(2_000)
            val libraryId = activeLibraryId.value ?: return@launch
            runCatching { repository.resumePendingEnrichment(libraryId) }
                .onSuccess { count ->
                    if (count > 0) message.value = "已从本地检查点继续补全 $count 项媒体信息"
                }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        RemLog.failure("GalleryScanner", "自动续扫暂时无法继续", error)
                    }
                }
        }
        viewModelScope.launch {
            _offlinePreviewStats.value = repository.offlinePreviewStats()
            _archiveCacheStats.value = repository.archiveCacheStats()
        }
    }

    fun attachTree(uri: Uri) {
        if (attachmentJob?.isCompleted == false) return
        _attachment.value = LibraryAttachmentState(progress = "正在取得目录访问权限…")
        attachmentJob = viewModelScope.launch {
            try {
                RemLog.info("LibraryAttach", "开始接入所选目录")
                val suggestedName = withContext(Dispatchers.IO) {
                    try {
                        getApplication<Application>().contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    } catch (error: SecurityException) {
                        throw IllegalStateException("无法保留目录读写授权，请重新选择可读写的目录并允许访问", error)
                    }
                    DocumentFile.fromTreeUri(getApplication(), uri)?.name
                        ?.takeIf(String::isNotBlank) ?: "Rem Library"
                }
                _attachment.value = LibraryAttachmentState(progress = "正在读取或建立媒体库…")
                val library = repository.attach(uri, suggestedName)
                setActiveLibrary(library.libraryId)
                setScreen(AppScreen.INBOX)
                RemLog.info("LibraryAttach", "媒体库已登记，开始检查未完成事务")
                _attachment.value = LibraryAttachmentState(progress = "正在检查未完成事务…")
                val recovered = repository.recoverInterruptedTransactions(library.libraryId)
                _attachment.value = LibraryAttachmentState()
                RemLog.info("LibraryAttach", "接入完成")
                if (recovered > 0) message.value = "已恢复 $recovered 个未完成整理事务"
                if (autoScan.value) scan(library.libraryId)
                else if (recovered == 0) message.value = "已接入 ${library.name}"
            } catch (error: CancellationException) {
                _attachment.value = LibraryAttachmentState()
                throw error
            } catch (error: Exception) {
                RemLog.failure("LibraryAttach", "接入失败：${_attachment.value.progress}", error)
                _attachment.value = LibraryAttachmentState(
                    error = error.message?.takeIf(String::isNotBlank) ?: "无法接入所选目录，请检查目录权限和存储连接",
                )
            }
        }
    }

    fun dismissAttachmentError() {
        if (_attachment.value.progress == null) _attachment.value = LibraryAttachmentState()
    }

    fun scan(libraryId: String? = activeLibraryId.value) {
        if (libraryId == null) return
        launchLongOperation {
            runCatching { repository.scan(libraryId) }
                .onSuccess { result ->
                    message.value = buildString {
                        append("扫描完成：发现 ${result.candidates.size} 项")
                        if (result.discoveries.isNotEmpty()) {
                            append("，${result.discoveries.size} 项其他内容待判断")
                        }
                        if (result.warnings.isNotEmpty()) append("，${result.warnings.size} 条警告")
                    }
                }
                .onFailure(::showError)
        }
    }

    fun rebuildIndex() {
        val libraryId = activeLibraryId.value ?: return
        launchLongOperation {
            runCatching { repository.rebuildIndex(libraryId) }
                .onSuccess { message.value = "本机索引已从 Library 重建" }
                .onFailure(::showError)
        }
    }

    fun selectLibrary(libraryId: String) {
        setActiveLibrary(libraryId)
        setSelectedItem(null)
        detailItemIds.value = emptyList()
    }

    fun forgetLibrary(libraryId: String) {
        viewModelScope.launch {
            runCatching { repository.forgetLibrary(libraryId) }
                .onSuccess { message.value = "已从本机移除 Library 登记，磁盘文件未改变" }
                .onFailure(::showError)
        }
    }

    fun navigate(destination: AppScreen) {
        setScreen(destination)
        setSelectedItem(null)
        detailItemIds.value = emptyList()
    }

    fun open(item: MediaItem, browsingItems: List<MediaItem> = listOf(item)) {
        setActiveLibrary(item.libraryId)
        val browsingIds = browsingItems
            .asSequence()
            .filter { it.libraryId == item.libraryId && !it.trashed }
            .map(MediaItem::id)
            .distinct()
            .toList()
        detailItemIds.value = browsingIds.takeIf { item.id in it } ?: listOf(item.id)
        // A normal grid/search result is only a paging context. It is not a Series and must never
        // make an unrelated following result look like the next chapter.
        readerQueueIds.value = listOf(item.id)
        setSelectedItem(item.id)
    }

    /**
     * Opens one chapter of a Series while keeping the chapter list as the reader's context.
     * Used both by the chapter list and by the end-of-chapter hand-over.
     */
    fun openChapter(item: MediaItem, ordered: List<MediaItem>) {
        setActiveLibrary(item.libraryId)
        val chapterIds = ordered.asSequence()
            .filter { it.libraryId == item.libraryId && !it.trashed }
            .map(MediaItem::id)
            .distinct()
            .toList()
            .takeIf { item.id in it }
            ?: listOf(item.id)
        detailItemIds.value = chapterIds
        readerQueueIds.value = chapterIds
        setSelectedItem(item.id)
        markOpened(item)
    }

    /**
     * Records that a Work was opened, whatever page it is on.
     *
     * Opened from the chapter list, so it belongs to the same user action as opening the Work and
     * must not be left to the page observer: page 0 is a real position and would otherwise look
     * like "never opened".
     */
    private fun markOpened(item: MediaItem) {
        val at = nextProgressTimestamp()
        viewModelScope.launch {
            runCatching { repository.markOpened(item, at) }.onFailure(::showError)
        }
    }

    fun selectDetailItem(item: MediaItem) {
        setSelectedItem(item.id)
    }

    fun closeDetail() {
        setSelectedItem(null)
        detailItemIds.value = emptyList()
        readerQueueIds.value = emptyList()
    }

    fun openGroup(groupId: String) {
        selectedGroupId.value = groupId
    }

    fun closeGroup() {
        selectedGroupId.value = null
    }

    fun openSeries(seriesId: String) {
        selectedSeriesId.value = seriesId
    }

    fun closeSeries() {
        selectedSeriesId.value = null
    }

    /** Renames, reorders or re-numbers a Series in one atomic portable write. */
    fun saveSeries(
        series: MediaSeries,
        title: String = series.title,
        memberIds: List<String> = series.memberIds,
        clearPositions: Boolean = false,
    ) {
        if (memberIds.isEmpty()) {
            message.value = "系列至少需要一个成员；如果不想要这个系列，请直接删除它"
            return
        }
        if (title.isBlank()) {
            message.value = "系列标题不能为空"
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.saveSeries(
                    libraryId = series.libraryId,
                    seriesId = series.id,
                    title = title,
                    memberIds = memberIds,
                    clearPositions = clearPositions,
                    expectedRevision = series.revision,
                )
            }.onSuccess { message.value = "系列已保存" }
                .onFailure(::showError)
        }
    }

    /**
     * Deletes the Series entity only. Its Works and all media stay exactly as they are and
     * simply stop being numbered inside that series.
     */
    fun deleteSeries(series: MediaSeries) {
        viewModelScope.launch {
            runCatching { repository.deleteSeries(series.libraryId, series.id) }
                .onSuccess { removed ->
                    if (removed) {
                        if (selectedSeriesId.value == series.id) closeSeries()
                        message.value = "已删除系列；作品与媒体都没有变化"
                    }
                }
                .onFailure(::showError)
        }
    }

    /** Creates a Group from an explicit member list, used by "新建分组". */
    fun createGroup(title: String, memberIds: List<String>) {
        val libraryId = activeLibraryId.value ?: return
        if (title.isBlank() || memberIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                repository.saveGroup(
                    libraryId = libraryId,
                    groupId = UUID.randomUUID().toString(),
                    title = title.trim(),
                    memberIds = memberIds,
                )
            }.onSuccess { group ->
                openGroup(group.id)
                message.value = "已建立分组 ${group.title}"
            }.onFailure(::showError)
        }
    }

    /** Saves a derived mixed folder as a portable Group the user can then edit. */
    fun saveDerivedGroup(primary: MediaItem, title: String, memberIds: List<String>) {
        if (memberIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                repository.saveDerivedGroup(
                    libraryId = primary.libraryId,
                    primaryItemId = primary.id,
                    title = title,
                    memberIds = memberIds,
                )
            }.onSuccess { group ->
                message.value = "已把 ${group.title} 保存为分组，可以手动增删和排序"
            }.onFailure(::showError)
        }
    }

    /**
     * Saves title, membership, order and cover in one portable write.
     *
     * Member order is the list order; the repository turns it into `sort_index`. Nothing
     * here moves media, and an empty member list is refused instead of silently deleting
     * the group.
     */
    fun updateGroup(
        group: MediaGroup,
        memberIds: List<String> = group.memberIds,
        title: String = group.title,
        coverWorkId: String? = group.coverWorkId,
    ) {
        if (memberIds.isEmpty()) {
            message.value = "分组至少需要一个成员；如果不再需要它，请直接删除分组"
            return
        }
        if (title.isBlank()) {
            message.value = "分组标题不能为空"
            return
        }
        viewModelScope.launch {
            runCatching {
                repository.saveGroup(
                    libraryId = group.libraryId,
                    groupId = group.id,
                    title = title.trim(),
                    memberIds = memberIds,
                    type = group.type,
                    ordered = group.ordered,
                    coverWorkId = coverWorkId?.takeIf { it in memberIds } ?: memberIds.first(),
                    expectedRevision = group.revision,
                )
            }.onSuccess { message.value = "分组已保存" }
                .onFailure(::showError)
        }
    }

    /** Adds the selected Works to an existing Group in one portable write. */
    fun addToGroup(group: MediaGroup, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val members = MembershipRules.appendMembers(group.memberIds, items.map(MediaItem::id))
        val already = MembershipRules.alreadyMembers(group.memberIds, items.map(MediaItem::id))
        if (members.size == group.memberIds.size) {
            message.value = "选中的 ${items.size} 项都已经在这个分组里"
            return
        }
        updateGroup(group, memberIds = members, coverWorkId = group.coverWorkId ?: members.first())
        if (already.isNotEmpty()) {
            message.value = "已加入 ${items.size - already.size} 项；${already.size} 项原本就在分组中"
        }
    }

    /**
     * Adds the selected Works to an existing Series.
     *
     * Works already numbered in another Series are reported instead of being moved, because
     * a Work belongs to one Series and that choice has to stay explicit.
     */
    fun addToSeries(series: MediaSeries, items: List<MediaItem>) {
        if (items.isEmpty()) return
        val (joinable, skipped) = MembershipRules.seriesJoinable(series.id, items)
        if (joinable.isEmpty()) {
            message.value = "选中的作品都已属于其他系列；请先在各自的作品信息里移出系列"
            return
        }
        val members = MembershipRules.appendMembers(series.memberIds, joinable.map(MediaItem::id))
        saveSeries(series, title = series.title, memberIds = members)
        if (skipped.isNotEmpty()) {
            message.value = "已加入 ${joinable.size} 部；${skipped.size} 部属于其他系列，未改动"
        }
    }

    fun deleteGroup(group: MediaGroup) {
        viewModelScope.launch {
            runCatching { repository.deleteGroup(group.libraryId, group.id) }
                .onSuccess { removed ->
                    if (removed) {
                        if (selectedGroupId.value == group.id) closeGroup()
                        message.value = "已删除分组；媒体原文件没有变化"
                    }
                }
                .onFailure(::showError)
        }
    }

    fun removeRelationMembers(request: dev.susnowy.gallery.model.RelationRemoval) {
        launchLongOperation {
            runCatching { repository.removeRelationMembers(request) }
                .onSuccess { message.value = "已从 ${request.title} 移出 $it 项；作品仍保留" }
                .onFailure(::showError)
        }
    }

    fun updateSearch(query: String) {
        searchQuery.value = query
        savedStateHandle[SEARCH_QUERY_KEY] = query
    }

    fun saveMetadata(
        item: MediaItem,
        title: String,
        authors: String,
        tags: String,
        collections: String,
        seriesAssignment: SeriesAssignment?,
        favorite: Boolean,
        domain: MediaDomain = item.domain,
    ) {
        viewModelScope.launch {
            val series = seriesAssignment?.let { assignment ->
                assignment.toSeriesRef(
                    libraryId = item.libraryId,
                    existing = repository.media.value.asSequence()
                        .filter { candidate -> candidate.libraryId == item.libraryId }
                        .mapNotNull(MediaItem::series),
                )
            }
            val updated = item.copy(
                domain = domain,
                displayTitle = title.trim().ifBlank { item.displayTitle },
                authors = authors.splitValues(),
                tags = tags.splitValues(),
                collections = collections.splitValues(),
                series = series,
                favorite = favorite,
            )
            runCatching { repository.updateMedia(item, updated) }
                .onSuccess { message.value = "元数据已写入便携 Library" }
                .onFailure(::showError)
        }
    }

    fun acceptSuggestions(items: Collection<MediaItem>) {
        val grouped = items.filter(MediaItem::inInbox).groupBy(MediaItem::libraryId)
        if (grouped.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                grouped.entries.sumOf { (libraryId, libraryItems) ->
                    repository.acceptSuggestions(libraryId, libraryItems.map(MediaItem::id))
                }
            }.onSuccess { count ->
                message.value = "已接受 $count 项识别建议；自动字段仍可由 Agent 更新"
            }.onFailure(::showError)
        }
    }

    /**
     * Compares two sources page by page.
     *
     * The quick pass reads no page bytes; the deep pass reads each source once and hashes
     * pages during that pass. Both are cancellable and neither changes any media.
     */
    fun startComparison(left: MediaItem, right: MediaItem, deep: Boolean) {
        comparisonJob?.cancel()
        comparisonJob = viewModelScope.launch {
            _comparison.value = ComparisonState(running = true, progress = "准备比较…")
            runCatching {
                repository.compareWorks(
                    libraryId = left.libraryId,
                    leftWorkId = left.id,
                    rightWorkId = right.id,
                    deep = deep,
                    onProgress = { text -> _comparison.value = _comparison.value.copy(progress = text) },
                )
            }.onSuccess { report ->
                _comparison.value = ComparisonState(report = report, progress = "")
            }.onFailure { error ->
                if (error is CancellationException) {
                    _comparison.value = ComparisonState(message = "已取消比较")
                } else {
                    _comparison.value = ComparisonState(message = error.message ?: "比较失败")
                }
            }
        }
    }

    fun cancelComparison() {
        comparisonJob?.cancel()
        comparisonJob = null
        _comparison.value = ComparisonState()
    }

    fun clearComparison() {
        comparisonJob?.cancel()
        comparisonJob = null
        _comparison.value = ComparisonState()
    }

    /** Writes the virtual merged Edition for a finished comparison. */
    fun createMergedEdition(target: MediaItem, report: EditionComparisonReport) {
        viewModelScope.launch {
            runCatching { repository.createMergedEdition(target.libraryId, target.id, report) }
                .onSuccess {
                    _comparison.value = _comparison.value.copy(
                        message = "已生成合并版本（页计划跨来源，媒体未改动）",
                    )
                    message.value = "已生成合并版本；两个来源各自保留原有版本"
                }
                .onFailure(::showError)
        }
    }

    /** Records a portable Inbox decision for the selected media and/or discovered paths. */
    fun decideInbox(
        items: List<MediaItem> = emptyList(),
        discoveries: List<DiscoveredEntry> = emptyList(),
        disposition: InboxDisposition,
        domain: MediaDomain? = null,
    ) {
        val libraryId = activeLibraryId.value ?: return
        if (items.isEmpty() && discoveries.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.decideInbox(libraryId, items, discoveries, disposition, domain) }
                .onSuccess { count ->
                    message.value = when (disposition) {
                        InboxDisposition.ACCEPTED -> "已接受 $count 项，决定已写入 Library"
                        InboxDisposition.CLASSIFIED -> "已归类 $count 项，人工归属已写入 Library"
                        InboxDisposition.IGNORED -> "已忽略 $count 项，媒体保持原样；可在“已忽略”中撤销"
                        InboxDisposition.HANDLED -> "已标记 $count 项为已处理；可在“已处理”中撤销"
                    }
                }
                .onFailure(::showError)
        }
    }

    /** Removes a portable Inbox decision so the target returns to the pending list. */
    fun undoInboxDecision(
        items: List<MediaItem> = emptyList(),
        discoveries: List<DiscoveredEntry> = emptyList(),
    ) {
        val libraryId = activeLibraryId.value ?: return
        if (items.isEmpty() && discoveries.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.undoInboxDecision(libraryId, items, discoveries) }
                .onSuccess { count -> message.value = "已撤销 $count 项 Inbox 决策" }
                .onFailure(::showError)
        }
    }

    fun editBatchMetadata(baselines: List<MediaItem>, edit: dev.susnowy.gallery.model.BatchMetadataEdit) {
        if (baselines.isEmpty() || !edit.active) return
        launchLongOperation {
            runCatching { repository.editMetadataBatch(baselines, edit) }
                .onSuccess { _batchMetadataReport.value = it }
                .onFailure(::showError)
        }
    }

    fun dismissBatchMetadataReport() { _batchMetadataReport.value = null }

    fun setBatchFavorite(itemIds: Collection<String>, favorite: Boolean) {
        val libraryId = activeLibraryId.value ?: return
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                repository.updateMediaBatch(libraryId, itemIds, favorite = favorite)
            }.onSuccess { count ->
                message.value = if (favorite) "已收藏 $count 项媒体" else "已取消收藏 $count 项媒体"
            }.onFailure(::showError)
        }
    }

    fun setFavorite(item: MediaItem, favorite: Boolean) {
        viewModelScope.launch {
            runCatching { repository.updateMedia(item, item.copy(favorite = favorite)) }
                .onSuccess { message.value = if (favorite) "已收藏" else "已取消收藏" }
                .onFailure(::showError)
        }
    }

    fun setTrashedBatch(itemIds: Collection<String>) {
        val libraryId = activeLibraryId.value ?: return
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            runCatching { repository.setTrashedBatch(libraryId, itemIds) }
                .onSuccess { count -> message.value = "已将 $count 项移入逻辑回收站，真实文件未移动" }
                .onFailure(::showError)
        }
    }

    fun setTrashed(item: MediaItem, trashed: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setTrashed(item.id, trashed) }
                .onSuccess {
                    if (trashed) closeDetail()
                    message.value = if (trashed) "已移入逻辑回收站，原文件未移动" else "已恢复"
                }
                .onFailure(::showError)
        }
    }

    fun purge(item: MediaItem) {
        viewModelScope.launch {
            runCatching { repository.purge(item) }
                .onSuccess { message.value = "文件及其 Rem 元数据已永久删除" }
                .onFailure(::showError)
        }
    }

    suspend fun pages(item: MediaItem): List<ImagePage> = repository.pages(item)

    suspend fun resolvePath(item: MediaItem, path: String): String? =
        repository.resolvePath(item, path)

    suspend fun archiveBitmap(
        item: MediaItem,
        entryName: String,
        width: Int,
        height: Int,
        archivePath: String? = null,
        onDimensions: (dev.susnowy.gallery.media.ComicPageDimensions) -> Unit = {},
    ): Bitmap? = repository.archiveBitmap(item, entryName, width, height, archivePath, onDimensions)

    suspend fun oversizedBitmap(item: MediaItem, relativePath: String): Bitmap? =
        repository.oversizedBitmap(item, relativePath)

    suspend fun progress(item: MediaItem): PlaybackProgress? = repository.progress(item.id)

    /** Reading state of a whole series chapter list, read in one query. */
    suspend fun progressFor(items: List<MediaItem>): Map<String, PlaybackProgress> =
        if (items.isEmpty()) emptyMap() else repository.progressFor(items.map(MediaItem::id))

    suspend fun offlinePreview(item: MediaItem): java.io.File? {
        return repository.offlinePreview(item)
    }

    fun refreshOfflinePreviewStats() {
        viewModelScope.launch {
            runCatching { repository.offlinePreviewStats() }
                .onSuccess { _offlinePreviewStats.value = it }
                .onFailure(::showError)
        }
    }

    fun clearOfflinePreviews() {
        viewModelScope.launch {
            runCatching { repository.clearOfflinePreviews() }
                .onSuccess { removed ->
                    _offlinePreviewStats.value = OfflinePreviewStats(files = 0, bytes = 0)
                    message.value = "已清除 ${removed.files} 张离线预览（${removed.bytes.formatBytes()}）"
                }
                .onFailure(::showError)
        }
    }

    fun refreshArchiveCacheStats() {
        viewModelScope.launch {
            runCatching { repository.archiveCacheStats() }
                .onSuccess { _archiveCacheStats.value = it }
                .onFailure(::showError)
        }
    }

    fun clearArchiveCache() {
        viewModelScope.launch {
            runCatching { repository.clearArchiveCache() }
                .onSuccess { removed ->
                    _archiveCacheStats.value = repository.archiveCacheStats()
                    message.value = "已清除 ${removed.files} 个压缩包缓存（${removed.bytes.formatBytes()}）"
                }
                .onFailure(::showError)
        }
    }

    fun saveProgress(item: MediaItem, page: Int = 0, positionMs: Long = 0, finished: Boolean = false) {
        // Stamp the UI event before launching its write. Coroutine scheduling is not an ordering
        // guarantee: if an older page event starts later, the repository can now identify it as
        // stale instead of moving portable progress backwards.
        val requestedAt = nextProgressTimestamp()
        viewModelScope.launch {
            runCatching {
                repository.saveProgress(
                    PlaybackProgress(
                        itemId = item.id,
                        page = page,
                        positionMs = positionMs,
                        finished = finished,
                        lastOpenedAt = requestedAt,
                    ),
                )
            }.onFailure(::showError)
        }
    }

    fun setAutoScan(enabled: Boolean) {
        autoScan.value = enabled
        preferences.edit { putBoolean("auto_scan", enabled) }
    }

    /**
     * Whether reaching the end of a chapter continues into the next one.
     *
     * A display/reading preference, deliberately device-local: it says how this reader likes to
     * read, it is not a Library decision, and it never changes which chapter was read.
     */
    fun setAutoAdvanceChapters(enabled: Boolean) {
        autoAdvanceChapters.value = enabled
        preferences.edit { putBoolean(AUTO_ADVANCE_CHAPTERS_KEY, enabled) }
    }

    fun setRetentionDays(days: Int) {
        retentionDays.value = if (days <= 0) 0 else days.coerceIn(1, 3650)
        preferences.edit { putInt("trash_retention_days", retentionDays.value) }
    }

    fun previewOrganization(template: OrganizerTemplate) {
        val libraryId = activeLibraryId.value ?: return
        val request = ++organizationRequest
        _organizationPlan.value = null
        launchLongOperation {
            runCatching { repository.previewOrganization(libraryId, template) }
                .onSuccess {
                    if (request == organizationRequest && activeLibraryId.value == libraryId) {
                        _organizationPlan.value = it
                    }
                }
                .onFailure(::showError)
        }
    }

    fun executeOrganization(plan: OrganizationPlan) {
        if (plan != _organizationPlan.value || plan.steps.isEmpty() ||
            plan.steps.any { it.item.libraryId != activeLibraryId.value } ||
            longOperationJob?.isActive == true
        ) return
        launchLongOperation {
            runCatching {
                check(plan == _organizationPlan.value &&
                    plan.steps.all { it.item.libraryId == activeLibraryId.value }) {
                    "整理计划已失效，请重新生成预览"
                }
                repository.executeOrganization(plan)
                repository.scan(plan.steps.first().item.libraryId)
            }.onSuccess {
                _organizationPlan.value = null
                message.value = "整理事务已完成并校验"
            }.onFailure(::showError)
        }
    }

    fun clearOrganizationPlan() {
        organizationRequest++
        _organizationPlan.value = null
    }

    fun importSystemMedia(uris: List<Uri>) {
        val libraryId = activeLibraryId.value ?: return
        if (uris.isEmpty()) return
        launchLongOperation {
            runCatching {
                val result = repository.importSystemMedia(libraryId, uris)
                repository.scan(libraryId)
                result
            }.onSuccess { result ->
                message.value = "已导入 ${result.imported} 项，跳过 ${result.skipped} 项" +
                    if (result.warnings.isEmpty()) "" else "，${result.warnings.size} 条警告"
            }.onFailure(::showError)
        }
    }

    fun importSystemImageSet(uris: List<Uri>, title: String) {
        val libraryId = activeLibraryId.value ?: return
        if (uris.size < 2 || title.isBlank()) return
        launchLongOperation {
            runCatching {
                val result = repository.importSystemImageSet(libraryId, uris, title.trim())
                repository.scan(libraryId)
                result
            }.onSuccess { result ->
                message.value = "已创建漫画/图集：导入 ${result.imported} 页" +
                    if (result.warnings.isEmpty()) "" else "，${result.warnings.size} 条警告"
                setScreen(AppScreen.WORKS)
            }.onFailure(::showError)
        }
    }

    fun importSystemWorks(uris: List<Uri>, kind: WorkImportKind) {
        val libraryId = activeLibraryId.value ?: return
        if (uris.isEmpty()) return
        launchLongOperation {
            runCatching {
                val result = repository.importSystemWorks(libraryId, uris, kind)
                repository.scan(libraryId)
                result
            }.onSuccess { result ->
                val label = if (kind == WorkImportKind.IMAGE) "图片" else "视频"
                message.value = "已按来源分类导入 ${result.imported} 项$label，跳过 ${result.skipped} 项" +
                    if (result.warnings.isEmpty()) "" else "，${result.warnings.size} 条警告"
                setScreen(AppScreen.MEDIA)
            }.onFailure(::showError)
        }
    }

    fun refreshSystemMedia() {
        systemMediaJob?.cancel()
        val access = repository.systemMediaAccess()
        systemMediaAccess.value = access
        if (access == SystemMediaAccess.NONE) {
            systemMedia.value = emptyList()
            systemMediaLoading.value = false
            return
        }
        systemMediaJob = viewModelScope.launch {
            systemMediaLoading.value = true
            try {
                runCatching { repository.systemMedia() }
                    .onSuccess {
                        systemMedia.value = it
                        systemMediaAccess.value = repository.systemMediaAccess()
                    }
                    .onFailure(::showError)
            } finally {
                systemMediaLoading.value = false
            }
        }
    }

    fun onSystemMediaPermissionResult() {
        refreshSystemMedia()
    }

    fun deriveImage(item: MediaItem) {
        launchLongOperation {
            runCatching {
                val target = repository.deriveImage(item.id)
                repository.scan(item.libraryId)
                target
            }.onSuccess { message.value = "已复制派生为 $it" }
                .onFailure(::showError)
        }
    }

    fun derivePage(item: MediaItem, pageNumber: Int) {
        launchLongOperation {
            runCatching {
                val target = repository.derivePage(item.id, pageNumber - 1)
                repository.scan(item.libraryId)
                target
            }.onSuccess { message.value = "已复制页面为 $it" }
                .onFailure(::showError)
        }
    }

    fun createImageSet(itemIds: List<String>, title: String) {
        val libraryId = activeLibraryId.value ?: return
        launchLongOperation {
            runCatching {
                val target = repository.createImageSet(libraryId, itemIds, title)
                repository.scan(libraryId)
                target
            }.onSuccess {
                message.value = "已创建漫画/图集：$it"
                setScreen(AppScreen.WORKS)
            }
                .onFailure(::showError)
        }
    }

    fun reorderImageSet(item: MediaItem, pages: List<ImagePage>) {
        if (pages.size < 2) return
        launchLongOperation {
            runCatching {
                repository.reorderImageSet(item.id, pages)
                repository.scan(item.libraryId)
            }.onSuccess {
                message.value = "已按当前顺序重新编号 ${pages.size} 页"
            }.onFailure(::showError)
        }
    }

    fun findDuplicates() {
        val libraryId = activeLibraryId.value ?: return
        launchLongOperation {
            runCatching { repository.findDuplicates(libraryId) }
                .onSuccess {
                    _duplicateGroups.value = it
                    message.value = if (it.isEmpty()) "未发现内容完全相同的文件" else "发现 ${it.size} 组重复内容；不会自动删除"
                }
                .onFailure(::showError)
        }
    }

    fun clearMessage() {
        message.value = null
    }

    fun cancelLongOperation() {
        if (longOperationJob?.isActive == true) {
            longOperationJob?.cancel()
            message.value = "操作已取消；已提交的事务步骤保留在恢复日志中"
        } else {
            message.value = "当前操作已进入不可取消的短提交阶段"
        }
    }

    private fun launchLongOperation(block: suspend () -> Unit) {
        if (longOperationJob?.isCompleted == false) {
            message.value = "已有任务正在运行或结束中，请稍后再试"
            return
        }
        longOperationJob = viewModelScope.launch { block() }
    }

    private fun showError(error: Throwable) {
        if (error is CancellationException) return
        RemLog.failure("操作", "用户操作失败", error)
        val detail = generateSequence(error) { it.cause }
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
        message.value = detail ?: "操作失败（${error.javaClass.simpleName}）"
    }

    private fun setActiveLibrary(libraryId: String?) {
        if (activeLibraryId.value != libraryId) {
            // A plan describes real paths inside one Library. Keeping it across a Library switch
            // would let the Organizer offer to move files of a Library the user is no longer
            // looking at, under a template chip that no longer describes that plan.
            clearOrganizationPlan()
            selectedGroupId.value = null
            selectedSeriesId.value = null
            readerQueueIds.value = emptyList()
        }
        activeLibraryId.value = libraryId
        preferences.edit {
            if (libraryId == null) remove(ACTIVE_LIBRARY_KEY) else putString(ACTIVE_LIBRARY_KEY, libraryId)
        }
    }

    private fun setScreen(destination: AppScreen) {
        screen.value = destination
        savedStateHandle[SCREEN_KEY] = destination.name
    }

    private fun setSelectedItem(itemId: String?) {
        selectedItemId.value = itemId
        savedStateHandle[SELECTED_ITEM_KEY] = itemId
    }

    private fun String.splitValues(): List<String> =
        split(',', '，', ';', '；').map(String::trim).filter(String::isNotEmpty).distinct()

    private fun Long.formatBytes(): String = when {
        this >= 1_073_741_824 -> "%.1f GB".format(this / 1_073_741_824.0)
        this >= 1_048_576 -> "%.1f MB".format(this / 1_048_576.0)
        this >= 1_024 -> "%.1f KB".format(this / 1_024.0)
        else -> "$this B"
    }

    private fun nextProgressTimestamp(): Long {
        while (true) {
            val previous = progressClock.get()
            val next = maxOf(System.currentTimeMillis(), previous + 1)
            if (progressClock.compareAndSet(previous, next)) return next
        }
    }

    private data class SettingsStatus(
        val operation: String?,
        val message: String?,
        val autoScan: Boolean,
        val retentionDays: Int,
        val autoAdvanceChapters: Boolean,
        val systemGallery: SystemGalleryStatus,
    )

    private data class ReadingStatus(
        val autoAdvance: Boolean,
        val gallery: SystemGalleryStatus,
    )

    private data class NavigationStatus(
        val screen: AppScreen,
        val selectedItemId: String?,
        val searchQuery: String,
        val detailItemIds: List<String>,
        val selectedGroupId: String?,
        val selectedSeriesId: String?,
        val readerQueueIds: List<String>,
    )

    private data class ShelfSelection(
        val groupId: String?,
        val seriesId: String?,
        val queueIds: List<String>,
    )

    data class ComparisonState(
        val running: Boolean = false,
        val progress: String = "",
        val report: EditionComparisonReport? = null,
        val message: String? = null,
    )

    private data class IndexedContent(
        val media: List<MediaItem>,
        val discoveries: List<DiscoveredEntry>,
        val groups: List<MediaGroup>,
        val series: List<MediaSeries>,
    )

    private data class SystemGalleryStatus(
        val media: List<SystemMediaEntry>,
        val access: SystemMediaAccess,
        val loading: Boolean,
    )

    private companion object {
        const val ACTIVE_LIBRARY_KEY = "active_library_id"
        const val SCREEN_KEY = "screen"
        const val SELECTED_ITEM_KEY = "selected_item_id"
        const val SEARCH_QUERY_KEY = "search_query"
        const val AUTO_ADVANCE_CHAPTERS_KEY = "auto_advance_chapters"
    }
}
