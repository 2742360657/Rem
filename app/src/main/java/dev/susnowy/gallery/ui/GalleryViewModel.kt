package dev.susnowy.gallery.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.data.GalleryRepository
import dev.susnowy.gallery.importer.SystemMediaAccess
import dev.susnowy.gallery.importer.SystemMediaEntry
import dev.susnowy.gallery.importer.WorkImportKind
import dev.susnowy.gallery.media.ImagePage
import dev.susnowy.gallery.media.MediaContentService
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.SeriesRef
import dev.susnowy.gallery.organizer.OrganizationPlan
import dev.susnowy.gallery.organizer.OrganizerTemplate
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val allMedia: List<MediaItem> = emptyList(),
    val screen: AppScreen = AppScreen.PHOTOS,
    val selectedItemId: String? = null,
    val detailItemIds: List<String> = emptyList(),
    val searchQuery: String = "",
    val operation: String? = null,
    val message: String? = null,
    val autoScan: Boolean = true,
    val trashRetentionDays: Int = 30,
    val systemMedia: List<SystemMediaEntry> = emptyList(),
    val systemMediaAccess: SystemMediaAccess = SystemMediaAccess.NONE,
    val systemMediaLoading: Boolean = false,
) {
    val activeLibrary: LibraryRegistration?
        get() = libraries.firstOrNull { it.libraryId == activeLibraryId }
    val selectedItem: MediaItem?
        get() = allMedia.firstOrNull { it.id == selectedItemId }
}

class GalleryViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val repository: GalleryRepository = (application as GalleryApplication).repository
    private val content = MediaContentService()
    private val preferences = application.getSharedPreferences("gallery-settings", 0)
    private val activeLibraryId = MutableStateFlow(preferences.getString(ACTIVE_LIBRARY_KEY, null))
    private val screen = MutableStateFlow(
        savedStateHandle.get<String>(SCREEN_KEY)
            ?.let { value -> runCatching { AppScreen.valueOf(value) }.getOrNull() }
            ?: AppScreen.PHOTOS,
    )
    private val selectedItemId = MutableStateFlow(savedStateHandle.get<String>(SELECTED_ITEM_KEY))
    private val detailItemIds = MutableStateFlow<List<String>>(emptyList())
    private val searchQuery = MutableStateFlow(savedStateHandle.get<String>(SEARCH_QUERY_KEY).orEmpty())
    private val message = MutableStateFlow<String?>(null)
    private val autoScan = MutableStateFlow(preferences.getBoolean("auto_scan", true))
    private val retentionDays = MutableStateFlow(preferences.getInt("trash_retention_days", 30))
    private val systemMedia = MutableStateFlow<List<SystemMediaEntry>>(emptyList())
    private val systemMediaAccess = MutableStateFlow(repository.systemMediaAccess())
    private val systemMediaLoading = MutableStateFlow(false)
    private val _organizationPlan = MutableStateFlow<OrganizationPlan?>(null)
    val organizationPlan: StateFlow<OrganizationPlan?> = _organizationPlan
    private val _duplicateGroups = MutableStateFlow<List<List<MediaItem>>>(emptyList())
    val duplicateGroups: StateFlow<List<List<MediaItem>>> = _duplicateGroups
    private var longOperationJob: Job? = null
    private var systemMediaJob: Job? = null

    val uiState: StateFlow<GalleryUiState> = combine(
        repository.libraries,
        repository.media,
        combine(screen, selectedItemId, searchQuery, detailItemIds) { currentScreen, selected, query, detailIds ->
            NavigationStatus(currentScreen, selected, query, detailIds)
        },
        combine(
            repository.operation,
            message,
            autoScan,
            retentionDays,
            combine(systemMedia, systemMediaAccess, systemMediaLoading) { media, access, loading ->
                SystemGalleryStatus(media, access, loading)
            },
        ) { operation, currentMessage, scan, days, gallery ->
            SettingsStatus(operation, currentMessage, scan, days, gallery)
        },
        activeLibraryId,
    ) { libraries, allMedia, navigation, status, activeId ->
        val resolvedActiveId = activeId?.takeIf { id -> libraries.any { it.libraryId == id } }
            ?: libraries.firstOrNull()?.libraryId
        if (activeLibraryId.value != resolvedActiveId) setActiveLibrary(resolvedActiveId)
        GalleryUiState(
            libraries = libraries,
            activeLibraryId = resolvedActiveId,
            media = allMedia.filter { resolvedActiveId == null || it.libraryId == resolvedActiveId },
            allMedia = allMedia,
            screen = navigation.screen,
            selectedItemId = navigation.selectedItemId,
            detailItemIds = navigation.detailItemIds,
            searchQuery = navigation.searchQuery,
            operation = status.operation,
            message = status.message,
            autoScan = status.autoScan,
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
            delay(1_500)
            runCatching { repository.cleanupExpired(retentionDays.value) }
                .onSuccess { if (it > 0) message.value = "已安全清理 $it 个到期回收站项目" }
        }
    }

    fun attachTree(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val suggestedName = DocumentFile.fromTreeUri(getApplication(), uri)?.name
                    ?.takeIf(String::isNotBlank)
                    ?: "Rem Library"
                val library = repository.attach(uri, suggestedName)
                setActiveLibrary(library.libraryId)
                setScreen(AppScreen.INBOX)
                val recovered = repository.recoverInterruptedTransactions(library.libraryId)
                if (recovered > 0) message.value = "已恢复 $recovered 个未完成整理事务"
                if (autoScan.value) scan(library.libraryId)
                else if (recovered == 0) message.value = "已接入 ${library.name}"
            }.onFailure(::showError)
        }
    }

    fun scan(libraryId: String? = activeLibraryId.value) {
        if (libraryId == null) return
        longOperationJob = viewModelScope.launch {
            runCatching { repository.scan(libraryId) }
                .onSuccess { result ->
                    message.value = buildString {
                        append("扫描完成：发现 ${result.candidates.size} 项")
                        if (result.ambiguousDirectories.isNotEmpty()) {
                            append("，${result.ambiguousDirectories.size} 个目录待确认")
                        }
                        if (result.warnings.isNotEmpty()) append("，${result.warnings.size} 条警告")
                    }
                }
                .onFailure(::showError)
        }
    }

    fun rebuildIndex() {
        val libraryId = activeLibraryId.value ?: return
        longOperationJob = viewModelScope.launch {
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
        detailItemIds.value = browsingItems
            .asSequence()
            .filter { it.libraryId == item.libraryId && !it.trashed }
            .map(MediaItem::id)
            .distinct()
            .toList()
            .takeIf { item.id in it }
            ?: listOf(item.id)
        setSelectedItem(item.id)
    }

    fun selectDetailItem(item: MediaItem) {
        setSelectedItem(item.id)
    }

    fun closeDetail() {
        setSelectedItem(null)
        detailItemIds.value = emptyList()
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
        seriesTitle: String,
        sortIndex: String,
        favorite: Boolean,
        domain: MediaDomain = item.domain,
    ) {
        viewModelScope.launch {
            val series = seriesTitle.trim().takeIf(String::isNotEmpty)?.let { value ->
                SeriesRef(
                    id = item.series?.takeIf { it.title == value }?.id ?: UUID.randomUUID().toString(),
                    title = value,
                    sortIndex = sortIndex.toDoubleOrNull() ?: 0.0,
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
            runCatching { repository.updateMedia(updated) }
                .onSuccess { message.value = "元数据已写入便携 Library" }
                .onFailure(::showError)
        }
    }

    fun addBatchMetadata(
        itemIds: Collection<String>,
        authors: String,
        tags: String,
        collections: String,
    ) {
        val libraryId = activeLibraryId.value ?: return
        if (itemIds.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                repository.updateMediaBatch(
                    libraryId = libraryId,
                    itemIds = itemIds,
                    addAuthors = authors.splitValues(),
                    addTags = tags.splitValues(),
                    addCollections = collections.splitValues(),
                )
            }.onSuccess { count -> message.value = "已更新 $count 项媒体的作者 / Tag / Collection" }
                .onFailure(::showError)
        }
    }

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
            runCatching { repository.purge(item.id) }
                .onSuccess { message.value = "文件及其 Rem 元数据已永久删除" }
                .onFailure(::showError)
        }
    }

    suspend fun pages(item: MediaItem): List<ImagePage> =
        content.imageSetPages(item, repository.storage(item.libraryId))

    suspend fun resolvePath(item: MediaItem, path: String): String? =
        content.resolveUri(path, repository.storage(item.libraryId))

    suspend fun archiveBitmap(
        item: MediaItem,
        entryName: String,
        width: Int,
        height: Int,
    ): Bitmap? = content.decodeArchivePage(
        item,
        entryName,
        repository.storage(item.libraryId),
        width,
        height,
    )

    suspend fun oversizedBitmap(item: MediaItem, relativePath: String): Bitmap? =
        content.decodeOversizedImage(relativePath, repository.storage(item.libraryId))

    suspend fun progress(item: MediaItem): PlaybackProgress? = repository.progress(item.id)

    fun saveProgress(item: MediaItem, page: Int = 0, positionMs: Long = 0, finished: Boolean = false) {
        viewModelScope.launch {
            runCatching {
                repository.saveProgress(
                    PlaybackProgress(
                        itemId = item.id,
                        page = page,
                        positionMs = positionMs,
                        finished = finished,
                        lastOpenedAt = System.currentTimeMillis(),
                    ),
                )
            }.onFailure(::showError)
        }
    }

    fun setAutoScan(enabled: Boolean) {
        autoScan.value = enabled
        preferences.edit { putBoolean("auto_scan", enabled) }
    }

    fun setRetentionDays(days: Int) {
        retentionDays.value = if (days <= 0) 0 else days.coerceIn(1, 3650)
        preferences.edit { putInt("trash_retention_days", retentionDays.value) }
    }

    fun previewOrganization(template: OrganizerTemplate) {
        val libraryId = activeLibraryId.value ?: return
        longOperationJob = viewModelScope.launch {
            runCatching { repository.previewOrganization(libraryId, template) }
                .onSuccess { _organizationPlan.value = it }
                .onFailure(::showError)
        }
    }

    fun executeOrganization(plan: OrganizationPlan) {
        longOperationJob = viewModelScope.launch {
            runCatching {
                repository.executeOrganization(plan)
                repository.scan(plan.steps.first().item.libraryId)
            }.onSuccess {
                _organizationPlan.value = null
                message.value = "整理事务已完成并校验"
            }.onFailure(::showError)
        }
    }

    fun clearOrganizationPlan() {
        _organizationPlan.value = null
    }

    fun importSystemMedia(uris: List<Uri>) {
        val libraryId = activeLibraryId.value ?: return
        if (uris.isEmpty()) return
        longOperationJob = viewModelScope.launch {
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
        longOperationJob = viewModelScope.launch {
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
        longOperationJob = viewModelScope.launch {
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
        longOperationJob = viewModelScope.launch {
            runCatching {
                val target = repository.deriveImage(item.id)
                repository.scan(item.libraryId)
                target
            }.onSuccess { message.value = "已复制派生为 $it" }
                .onFailure(::showError)
        }
    }

    fun derivePage(item: MediaItem, pageNumber: Int) {
        longOperationJob = viewModelScope.launch {
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
        longOperationJob = viewModelScope.launch {
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
        longOperationJob = viewModelScope.launch {
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
        longOperationJob = viewModelScope.launch {
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
            longOperationJob = null
            message.value = "操作已取消；已提交的事务步骤保留在恢复日志中"
        } else {
            message.value = "当前操作已进入不可取消的短提交阶段"
        }
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

    private data class SettingsStatus(
        val operation: String?,
        val message: String?,
        val autoScan: Boolean,
        val retentionDays: Int,
        val systemGallery: SystemGalleryStatus,
    )

    private data class NavigationStatus(
        val screen: AppScreen,
        val selectedItemId: String?,
        val searchQuery: String,
        val detailItemIds: List<String>,
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
    }
}
