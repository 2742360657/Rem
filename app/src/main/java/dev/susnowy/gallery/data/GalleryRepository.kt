package dev.susnowy.gallery.data

import android.content.Context
import android.net.Uri
import dev.susnowy.gallery.importer.ImportResult
import dev.susnowy.gallery.importer.SystemMediaImporter
import dev.susnowy.gallery.library.PortableLibraryManager
import dev.susnowy.gallery.metadata.PortableMetadataStore
import dev.susnowy.gallery.model.LibraryInspection
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.PermissionState
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.organizer.OrganizationPlan
import dev.susnowy.gallery.organizer.OrganizerService
import dev.susnowy.gallery.organizer.OrganizerTemplate
import dev.susnowy.gallery.scanner.LibraryScanner
import dev.susnowy.gallery.scanner.ScanResult
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class GalleryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = GalleryDatabase(appContext)
    private val scanner = LibraryScanner()
    private val organizer = OrganizerService()
    private val importer = SystemMediaImporter(appContext)

    private val _libraries = MutableStateFlow<List<LibraryRegistration>>(emptyList())
    val libraries: StateFlow<List<LibraryRegistration>> = _libraries.asStateFlow()

    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())
    val media: StateFlow<List<MediaItem>> = _media.asStateFlow()

    private val _operation = MutableStateFlow<String?>(null)
    val operation: StateFlow<String?> = _operation.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        refreshFromDatabase()
    }

    suspend fun attach(treeUri: Uri, requestedName: String? = null): LibraryRegistration = onIo {
        val storage = DocumentTreeStorage(appContext, treeUri)
        require(storage.isAvailable) { "无法读取所选目录" }
        val manager = PortableLibraryManager(storage)
        val portable = when (val inspection = manager.inspect()) {
            LibraryInspection.Missing -> manager.initialize(requestedName ?: "Gallery Library")
            is LibraryInspection.Valid -> inspection.library
            is LibraryInspection.Unsupported -> error(
                "Library Schema v${inspection.schemaVersion} 高于本客户端支持的版本，已拒绝写入",
            )
            is LibraryInspection.Invalid -> error(inspection.reason)
        }
        val registration = LibraryRegistration(
            libraryId = portable.libraryId,
            name = portable.name,
            treeUri = treeUri.toString(),
            permissionState = PermissionState.AVAILABLE,
            schemaVersion = portable.schemaVersion,
            lastScanAt = database.library(portable.libraryId)?.lastScanAt,
        )
        database.upsertLibrary(registration)
        refreshFromDatabase()
        registration
    }

    suspend fun scan(libraryId: String): ScanResult = runOperation("正在扫描媒体…") {
        onIo {
            val registration = requireLibrary(libraryId)
            val storage = storageFor(registration)
            if (!storage.isAvailable) {
                database.upsertLibrary(registration.copy(permissionState = PermissionState.OFFLINE))
                refreshFromDatabase()
                throw FileNotFoundException("${registration.name} 当前离线")
            }
            val portableStore = PortableMetadataStore(storage)
            val catalog = portableStore.loadCatalog(libraryId)
            val state = portableStore.loadState(libraryId)
            val result = scanner.scan(storage)
            val existing = database.media(libraryId)
            val existingByPath = existing.associateBy(MediaItem::relativePath)
            val metadataById = catalog.items.associateBy { it.id }
            val metadataByPath = catalog.items.associateBy { it.relativePath }
            val foundPaths = result.candidates.mapTo(mutableSetOf()) { it.relativePath }
            val unmatchedExisting = existing.filter { it.relativePath !in foundPaths }.toMutableList()

            result.candidates.forEach { candidate ->
                val atPath = existingByPath[candidate.relativePath]
                val relocated = if (atPath == null && candidate.sourceKind.name != "DIRECTORY") {
                    unmatchedExisting.filter {
                        it.kind == candidate.kind && it.size == candidate.size &&
                            it.modifiedAt == candidate.modifiedAt && !it.trashed
                    }.singleOrNull()?.also(unmatchedExisting::remove)
                } else null
                val local = atPath ?: relocated
                val metadata = local?.let { metadataById[it.id] }
                    ?: metadataByPath[candidate.relativePath]
                val id = metadata?.id ?: local?.id ?: UUID.randomUUID().toString()
                val trashEntry = state.trash.firstOrNull { it.itemId == id }
                val item = MediaItem(
                    id = id,
                    libraryId = libraryId,
                    relativePath = candidate.relativePath,
                    uri = candidate.uri,
                    kind = candidate.kind,
                    sourceKind = candidate.sourceKind,
                    displayTitle = metadata?.displayTitle ?: local?.displayTitle ?: candidate.suggestedTitle,
                    originalTitle = metadata?.originalTitle ?: local?.originalTitle,
                    mimeType = candidate.mimeType,
                    size = candidate.size,
                    modifiedAt = candidate.modifiedAt,
                    capturedAt = candidate.capturedAt ?: local?.capturedAt,
                    pageCount = candidate.pageCount,
                    authors = metadata?.authors ?: local?.authors.orEmpty(),
                    tags = metadata?.tags ?: local?.tags.orEmpty(),
                    collections = metadata?.collections ?: local?.collections.orEmpty(),
                    series = metadata?.series ?: local?.series,
                    coverPath = metadata?.coverPath ?: candidate.coverPath ?: local?.coverPath,
                    secondaryPath = metadata?.secondaryPath ?: candidate.secondaryPath ?: local?.secondaryPath,
                    favorite = metadata?.favorite ?: local?.favorite ?: false,
                    inInbox = metadata == null && (local?.inInbox ?: true),
                    trashed = trashEntry != null,
                    deletedAt = trashEntry?.deletedAt?.let(java.time.Instant::parse)?.toEpochMilli(),
                    needsRepair = false,
                    revision = metadata?.revision ?: local?.revision ?: 0,
                )
                database.upsertMedia(item)
            }
            database.markMissing(libraryId, foundPaths)
            state.progress.forEach { progress ->
                if (database.mediaItem(progress.itemId) != null) {
                    database.upsertProgress(
                        PlaybackProgress(
                            itemId = progress.itemId,
                            page = progress.page,
                            positionMs = progress.positionMs,
                            finished = progress.finished,
                            lastOpenedAt = java.time.Instant.parse(progress.lastOpenedAt).toEpochMilli(),
                        ),
                    )
                }
            }
            database.upsertLibrary(
                registration.copy(
                    permissionState = PermissionState.AVAILABLE,
                    lastScanAt = System.currentTimeMillis(),
                ),
            )
            refreshFromDatabase()
            result
        }
    }

    suspend fun rebuildIndex(libraryId: String): ScanResult = runOperation("正在重建本机索引…") {
        onIo {
            database.clearMediaIndex(libraryId)
            refreshFromDatabase()
        }
        scan(libraryId)
    }

    suspend fun updateMedia(updated: MediaItem): MediaItem = runOperation("正在保存元数据…") {
        onIo {
            val storage = storageFor(requireLibrary(updated.libraryId))
            val portable = PortableMetadataStore(storage).saveItem(updated, updated.revision)
            val saved = updated.copy(revision = portable.revision, inInbox = false)
            database.upsertMedia(saved)
            refreshFromDatabase()
            saved
        }
    }

    suspend fun setTrashed(itemId: String, trashed: Boolean) = runOperation(
        if (trashed) "正在移入回收站…" else "正在恢复…",
    ) {
        onIo {
            val item = database.mediaItem(itemId) ?: return@onIo
            val deletedAt = if (trashed) System.currentTimeMillis() else null
            val storage = storageFor(requireLibrary(item.libraryId))
            PortableMetadataStore(storage).setTrashed(item, trashed, deletedAt ?: 0)
            database.upsertMedia(item.copy(trashed = trashed, deletedAt = deletedAt))
            refreshFromDatabase()
        }
    }

    suspend fun purge(itemId: String) = runOperation("正在永久删除…") {
        onIo {
            purgeInternal(itemId)
        }
    }

    suspend fun saveProgress(progress: PlaybackProgress) = onIo {
        val item = database.mediaItem(progress.itemId) ?: return@onIo
        val storage = storageFor(requireLibrary(item.libraryId))
        PortableMetadataStore(storage).saveProgress(item.libraryId, progress)
        database.upsertProgress(progress)
    }

    suspend fun progress(itemId: String): PlaybackProgress? = onIo { database.progress(itemId) }

    suspend fun previewOrganization(
        libraryId: String,
        template: OrganizerTemplate,
    ): OrganizationPlan = runOperation("正在生成整理计划…") {
        onIo {
            val items = database.media(libraryId).filterNot { it.trashed || it.inInbox }
            organizer.preview(items, storageFor(requireLibrary(libraryId)), template)
        }
    }

    suspend fun executeOrganization(plan: OrganizationPlan) = runOperation("正在执行整理事务…") {
        onIo {
            val libraryId = plan.steps.firstOrNull()?.item?.libraryId ?: error("整理计划为空")
            organizer.execute(plan, storageFor(requireLibrary(libraryId))) { moved ->
                database.upsertMedia(moved)
            }
            refreshFromDatabase()
        }
    }

    suspend fun importSystemMedia(libraryId: String, uris: List<Uri>): ImportResult =
        runOperation("正在复制系统相册媒体…") {
            onIo { importer.import(uris, storageFor(requireLibrary(libraryId))) }
        }

    suspend fun cleanupExpired(retentionDays: Int): Int = onIo {
        val threshold = System.currentTimeMillis() - retentionDays.coerceAtLeast(1) * 86_400_000L
        val expired = database.media().filter {
            it.trashed && (it.deletedAt ?: Long.MAX_VALUE) <= threshold
        }
        var deleted = 0
        expired.forEach { item ->
            runCatching { purgeInternal(item.id) }.onSuccess { deleted++ }
        }
        if (deleted > 0) refreshFromDatabase()
        deleted
    }

    suspend fun forgetLibrary(libraryId: String) = onIo {
        database.removeLibrary(libraryId)
        refreshFromDatabase()
    }

    fun refreshFromDatabase() {
        _libraries.value = database.libraries()
        _media.value = database.media()
    }

    fun library(libraryId: String): LibraryRegistration? = database.library(libraryId)

    fun storage(libraryId: String): DocumentTreeStorage = storageFor(requireLibrary(libraryId))

    private fun requireLibrary(libraryId: String): LibraryRegistration =
        database.library(libraryId) ?: error("Library 未登记：$libraryId")

    private fun storageFor(registration: LibraryRegistration) =
        DocumentTreeStorage(appContext, Uri.parse(registration.treeUri))

    private fun purgeInternal(itemId: String) {
        val item = database.mediaItem(itemId) ?: return
        check(item.trashed) { "只能永久删除回收站中的项目" }
        val storage = storageFor(requireLibrary(item.libraryId))
        val entry = storage.entry(item.relativePath)
            ?: throw FileNotFoundException("文件已不存在，请先重新扫描")
        val currentSize = if (entry.isDirectory) storage.treeStats(item.relativePath).totalBytes else entry.size
        if (item.size > 0 && currentSize != item.size) {
            error("媒体内容大小已变化，为避免误删已停止操作")
        }
        val document = storage.find(item.relativePath)
            ?: throw FileNotFoundException(item.relativePath)
        check(storage.delete(document)) { "Provider 拒绝删除 ${item.relativePath}" }
        PortableMetadataStore(storage).removeItem(item)
        database.removeMedia(item.id)
        refreshFromDatabase()
    }

    private suspend fun <T> runOperation(label: String, block: suspend () -> T): T {
        _operation.value = label
        return try {
            block()
        } catch (error: Exception) {
            _events.tryEmit(error.message ?: "操作失败")
            throw error
        } finally {
            _operation.value = null
        }
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
