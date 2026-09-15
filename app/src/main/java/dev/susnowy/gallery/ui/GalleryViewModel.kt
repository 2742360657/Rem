package dev.susnowy.gallery.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.data.GalleryRepository
import dev.susnowy.gallery.media.ImagePage
import dev.susnowy.gallery.media.MediaContentService
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.SeriesRef
import dev.susnowy.gallery.organizer.OrganizationPlan
import dev.susnowy.gallery.organizer.OrganizerTemplate
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppScreen(val title: String) {
    HOME("首页"),
    LIBRARIES("媒体库"),
    INBOX("Inbox"),
    PHOTOS("相册"),
    IMAGES("图片"),
    IMAGE_SETS("漫画与图集"),
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
    val screen: AppScreen = AppScreen.HOME,
    val selectedItemId: String? = null,
    val searchQuery: String = "",
    val operation: String? = null,
    val message: String? = null,
    val autoScan: Boolean = true,
    val trashRetentionDays: Int = 30,
) {
    val activeLibrary: LibraryRegistration?
        get() = libraries.firstOrNull { it.libraryId == activeLibraryId }
    val selectedItem: MediaItem?
        get() = media.firstOrNull { it.id == selectedItemId }
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: GalleryRepository = (application as GalleryApplication).repository
    private val content = MediaContentService()
    private val preferences = application.getSharedPreferences("gallery-settings", 0)
    private val activeLibraryId = MutableStateFlow<String?>(null)
    private val screen = MutableStateFlow(AppScreen.HOME)
    private val selectedItemId = MutableStateFlow<String?>(null)
    private val searchQuery = MutableStateFlow("")
    private val message = MutableStateFlow<String?>(null)
    private val autoScan = MutableStateFlow(preferences.getBoolean("auto_scan", true))
    private val retentionDays = MutableStateFlow(preferences.getInt("trash_retention_days", 30))
    private val _organizationPlan = MutableStateFlow<OrganizationPlan?>(null)
    val organizationPlan: StateFlow<OrganizationPlan?> = _organizationPlan
    private val _duplicateGroups = MutableStateFlow<List<List<MediaItem>>>(emptyList())
    val duplicateGroups: StateFlow<List<List<MediaItem>>> = _duplicateGroups

    val uiState: StateFlow<GalleryUiState> = combine(
        repository.libraries,
        repository.media,
        combine(screen, selectedItemId, searchQuery) { currentScreen, selected, query ->
            Triple(currentScreen, selected, query)
        },
        combine(repository.operation, message, autoScan, retentionDays) { operation, currentMessage, scan, days ->
            SettingsStatus(operation, currentMessage, scan, days)
        },
        activeLibraryId,
    ) { libraries, allMedia, navigation, status, activeId ->
        val resolvedActiveId = activeId?.takeIf { id -> libraries.any { it.libraryId == id } }
            ?: libraries.firstOrNull()?.libraryId
        if (activeLibraryId.value != resolvedActiveId) activeLibraryId.value = resolvedActiveId
        GalleryUiState(
            libraries = libraries,
            activeLibraryId = resolvedActiveId,
            media = allMedia.filter { resolvedActiveId == null || it.libraryId == resolvedActiveId },
            screen = navigation.first,
            selectedItemId = navigation.second,
            searchQuery = navigation.third,
            operation = status.operation,
            message = status.message,
            autoScan = status.autoScan,
            trashRetentionDays = status.retentionDays,
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
                    ?: "Gallery Library"
                val library = repository.attach(uri, suggestedName)
                activeLibraryId.value = library.libraryId
                screen.value = AppScreen.INBOX
                val recovered = repository.recoverInterruptedTransactions(library.libraryId)
                if (recovered > 0) message.value = "已恢复 $recovered 个未完成整理事务"
                if (autoScan.value) scan(library.libraryId)
                else if (recovered == 0) message.value = "已接入 ${library.name}"
            }.onFailure(::showError)
        }
    }

    fun scan(libraryId: String? = activeLibraryId.value) {
        if (libraryId == null) return
        viewModelScope.launch {
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
        viewModelScope.launch {
            runCatching { repository.rebuildIndex(libraryId) }
                .onSuccess { message.value = "本机索引已从 Library 重建" }
                .onFailure(::showError)
        }
    }

    fun selectLibrary(libraryId: String) {
        activeLibraryId.value = libraryId
        selectedItemId.value = null
    }

    fun forgetLibrary(libraryId: String) {
        viewModelScope.launch {
            runCatching { repository.forgetLibrary(libraryId) }
                .onSuccess { message.value = "已从本机移除 Library 登记，磁盘文件未改变" }
                .onFailure(::showError)
        }
    }

    fun navigate(destination: AppScreen) {
        screen.value = destination
        selectedItemId.value = null
    }

    fun open(item: MediaItem) {
        selectedItemId.value = item.id
    }

    fun closeDetail() {
        selectedItemId.value = null
    }

    fun updateSearch(query: String) {
        searchQuery.value = query
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
                .onSuccess { message.value = "文件及其 Gallery 元数据已永久删除" }
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
        preferences.edit().putBoolean("auto_scan", enabled).apply()
    }

    fun setRetentionDays(days: Int) {
        retentionDays.value = days.coerceIn(1, 3650)
        preferences.edit().putInt("trash_retention_days", retentionDays.value).apply()
    }

    fun previewOrganization(template: OrganizerTemplate) {
        val libraryId = activeLibraryId.value ?: return
        viewModelScope.launch {
            runCatching { repository.previewOrganization(libraryId, template) }
                .onSuccess { _organizationPlan.value = it }
                .onFailure(::showError)
        }
    }

    fun executeOrganization(plan: OrganizationPlan) {
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    fun deriveImage(item: MediaItem) {
        viewModelScope.launch {
            runCatching {
                val target = repository.deriveImage(item.id)
                repository.scan(item.libraryId)
                target
            }.onSuccess { message.value = "已复制派生为 $it" }
                .onFailure(::showError)
        }
    }

    fun derivePage(item: MediaItem, pageNumber: Int) {
        viewModelScope.launch {
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
        viewModelScope.launch {
            runCatching {
                val target = repository.createImageSet(libraryId, itemIds, title)
                repository.scan(libraryId)
                target
            }.onSuccess { message.value = "已创建 ImageSet：$it" }
                .onFailure(::showError)
        }
    }

    fun findDuplicates() {
        val libraryId = activeLibraryId.value ?: return
        viewModelScope.launch {
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

    private fun showError(error: Throwable) {
        message.value = error.message ?: "操作失败"
    }

    private fun String.splitValues(): List<String> =
        split(',', '，', ';', '；').map(String::trim).filter(String::isNotEmpty).distinct()

    private data class SettingsStatus(
        val operation: String?,
        val message: String?,
        val autoScan: Boolean,
        val retentionDays: Int,
    )
}
