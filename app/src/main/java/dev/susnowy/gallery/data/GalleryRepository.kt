package dev.susnowy.gallery.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import dev.susnowy.gallery.derive.DerivationService
import dev.susnowy.gallery.importer.ImportResult
import dev.susnowy.gallery.importer.SystemMediaAccess
import dev.susnowy.gallery.importer.SystemMediaCatalog
import dev.susnowy.gallery.importer.SystemMediaEntry
import dev.susnowy.gallery.importer.SystemMediaImporter
import dev.susnowy.gallery.importer.WorkImportKind
import dev.susnowy.gallery.library.InitializationInProgressException
import dev.susnowy.gallery.library.PortableLibraryManager
import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.media.ImagePage
import dev.susnowy.gallery.media.ImageSetOrderResult
import dev.susnowy.gallery.media.ImageSetOrderService
import dev.susnowy.gallery.metadata.PortableMetadataStore
import dev.susnowy.gallery.metadata.FieldSource
import dev.susnowy.gallery.metadata.MetadataField
import dev.susnowy.gallery.metadata.withManualEdits
import dev.susnowy.gallery.model.LibraryInspection
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.DiscoveredEntry
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.PermissionState
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.PortableLibrary
import dev.susnowy.gallery.organizer.OrganizationPlan
import dev.susnowy.gallery.organizer.OrganizerService
import dev.susnowy.gallery.organizer.OrganizerTemplate
import dev.susnowy.gallery.scanner.LibraryScanner
import dev.susnowy.gallery.scanner.ScanResult
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class GalleryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = GalleryDatabase(appContext)
    private val scanner = LibraryScanner()
    private val organizer = OrganizerService()
    private val importer = SystemMediaImporter(appContext)
    private val systemMediaCatalog = SystemMediaCatalog(appContext)
    private val derivation = DerivationService()
    private val imageSetOrder = ImageSetOrderService()
    private val progressWriteMutex = Mutex()

    /** Serializes attach: a repeated folder-selection tap must not initialize twice. */
    private val attachMutex = Mutex()

    /** One scan per Library at a time; a second request must not double the queries. */
    private val scanMutex = Mutex()

    private val _libraries = MutableStateFlow<List<LibraryRegistration>>(emptyList())
    val libraries: StateFlow<List<LibraryRegistration>> = _libraries.asStateFlow()

    private val _media = MutableStateFlow<List<MediaItem>>(emptyList())
    val media: StateFlow<List<MediaItem>> = _media.asStateFlow()

    private val _discoveries = MutableStateFlow<List<DiscoveredEntry>>(emptyList())
    val discoveries: StateFlow<List<DiscoveredEntry>> = _discoveries.asStateFlow()

    private val _operation = MutableStateFlow<String?>(null)
    val operation: StateFlow<String?> = _operation.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        refreshFromDatabase()
    }

    suspend fun attach(treeUri: Uri, requestedName: String? = null): LibraryRegistration =
        attachMutex.withLock {
            onIo {
                val storage = DocumentTreeStorage(appContext, treeUri)
                require(storage.isAvailable) { "无法读取所选目录" }
                val manager = PortableLibraryManager(storage)
                val portable = resolveIdentity(storage, manager, treeUri, requestedName)
                val migrated = manager.migrateSchema(portable)
                if (migrated.schemaVersion != portable.schemaVersion) {
                    _events.tryEmit("已把便携元数据升级到 Schema v${migrated.schemaVersion}（原数据已备份）")
                }
                manager.ensureMediaStoreIgnored()
                val registration = LibraryRegistration(
                    libraryId = migrated.libraryId,
                    name = migrated.name,
                    treeUri = treeUri.toString(),
                    permissionState = PermissionState.AVAILABLE,
                    schemaVersion = migrated.schemaVersion,
                    lastScanAt = database.library(migrated.libraryId)?.lastScanAt,
                )
                database.claimLibraryTree(registration)
                refreshFromDatabase()
                registration
            }
        }

    /**
     * Resolves which Library identity the selected directory has, creating one only when
     * the directory genuinely has none.
     *
     * Reopening a directory that is already registered reuses the on-disk identity rather
     * than initializing again. When the registered id and the on-disk id disagree, the
     * registration is replaced by the on-disk identity: the portable `.gallery` documents
     * are the truth, the local row is only an index of them.
     */
    private fun resolveIdentity(
        storage: DocumentTreeStorage,
        manager: PortableLibraryManager,
        treeUri: Uri,
        requestedName: String?,
    ): PortableLibrary {
        val registered = database.libraries().firstOrNull { it.treeUri == treeUri.toString() }
        val existing = when (val inspection = manager.inspect()) {
            is LibraryInspection.Valid -> inspection.library
            is LibraryInspection.Unsupported -> error(
                "Library Schema v${inspection.schemaVersion} 高于本客户端支持的版本，已拒绝写入",
            )
            is LibraryInspection.Invalid -> error(inspection.reason)
            LibraryInspection.Missing -> null
        }
        if (existing != null) {
            if (registered != null && registered.libraryId != existing.libraryId) {
                _events.tryEmit("检测到 Library 身份已变化，已改用磁盘上的身份")
            }
            return existing
        }
        // No identity on disk. If this directory is already registered, another instance
        // is most likely mid-initialization; wait briefly for it rather than racing it.
        if (registered != null) {
            awaitPublishedIdentity(manager)?.let { return it }
        }
        return try {
            manager.initialize(requestedName ?: "Gallery Library")
        } catch (error: InitializationInProgressException) {
            // Lost the race to another instance. Its identity becomes visible when it
            // commits library.json, so re-inspect before reporting a failure.
            awaitPublishedIdentity(manager) ?: throw error
        }
    }

    /** Waits briefly for a concurrent initializer to commit its identity. */
    private fun awaitPublishedIdentity(manager: PortableLibraryManager): PortableLibrary? {
        repeat(INITIALIZATION_WAIT_ATTEMPTS) {
            Thread.sleep(INITIALIZATION_WAIT_MILLIS)
            val published = manager.inspect()
            if (published is LibraryInspection.Valid) return published.library
        }
        return null
    }

    suspend fun scan(libraryId: String): ScanResult = runOperation("正在扫描媒体…") {
        scanMutex.withLock {
            onIo {
            val registration = requireLibrary(libraryId)
            val storage = storageFor(registration)
            if (!storage.isAvailable) {
                database.upsertLibrary(registration.copy(permissionState = PermissionState.OFFLINE))
                refreshFromDatabase()
                throw FileNotFoundException("${registration.name} 当前离线")
            }
            val manager = PortableLibraryManager(storage)
            val identity = when (val inspection = manager.inspect()) {
                is LibraryInspection.Valid -> inspection.library
                is LibraryInspection.Unsupported -> error(
                    "Library Schema v${inspection.schemaVersion} 高于本客户端支持的版本，已拒绝写入",
                )
                is LibraryInspection.Invalid -> error(inspection.reason)
                LibraryInspection.Missing -> error("Library 身份文件缺失，请重新接入并检查目录")
            }
            require(identity.libraryId == libraryId) { "Library 身份与本机登记不一致，已拒绝写入" }
            val migratedIdentity = manager.migrateSchema(identity)
            val effectiveRegistration = registration.copy(
                name = migratedIdentity.name,
                schemaVersion = migratedIdentity.schemaVersion,
            )
            if (migratedIdentity.schemaVersion != identity.schemaVersion) {
                _events.tryEmit("已把便携元数据升级到 Schema v${migratedIdentity.schemaVersion}（原数据已备份）")
            }
            manager.ensureMediaStoreIgnored()
            val recoveredPageOrders = imageSetOrder.recoverInterrupted(storage)
            if (recoveredPageOrders > 0) {
                _events.tryEmit("已恢复 $recoveredPageOrders 个中断的漫画页序事务")
            }
            val portableStore = PortableMetadataStore(storage)
            val catalog = portableStore.loadCatalog(libraryId)
            val state = portableStore.loadState(libraryId)
            val result = scanner.scan(storage, database.scanSnapshot(libraryId)) { progress ->
                _operation.value = "正在扫描：已读取 ${progress.directoriesRead} 个目录、" +
                    "${progress.entriesRead} 个条目，识别 ${progress.candidatesFound} 项"
            }
            val existing = database.media(libraryId)
            val existingByPath = existing.associateBy(MediaItem::relativePath)
            val metadataById = catalog.items.associateBy { it.id }
            val metadataByPath = catalog.items.associateBy { it.relativePath }
            val metadataByHash = catalog.items.filter { it.contentHash != null }
                .groupBy { it.contentHash }
                .mapNotNull { (hash, matches) -> matches.singleOrNull()?.let { hash to it } }
                .toMap()
            val foundPaths = result.candidates.mapTo(mutableSetOf()) { it.relativePath }
            existing.asSequence()
                .filter { result.protectsPreviouslyIndexed(it.relativePath) }
                .forEach { foundPaths += it.relativePath }
            val unmatchedExisting = existing.filter { it.relativePath !in foundPaths }.toMutableList()
            val scanned = ArrayList<MediaItem>(result.candidates.size)

            result.candidates.forEach { candidate ->
                val atPath = existingByPath[candidate.relativePath]
                val relocated = if (atPath == null && candidate.contentHash != null) {
                    unmatchedExisting.filter {
                        it.kind == candidate.kind && it.contentHash == candidate.contentHash && !it.trashed
                    }.singleOrNull()?.also(unmatchedExisting::remove)
                } else null
                val local = atPath ?: relocated
                val metadata = local?.let { metadataById[it.id] }
                    ?: metadataByPath[candidate.relativePath]
                    ?: candidate.contentHash?.let(metadataByHash::get)
                val id = metadata?.id ?: local?.id ?: UUID.randomUUID().toString()
                val trashEntry = state.trash.firstOrNull { it.itemId == id }
                val recognized = candidate.recognizedMetadata
                val storedFieldSources = metadata?.fieldSources ?: local?.fieldSources.orEmpty()
                fun isManual(field: String): Boolean =
                    FieldSource.isManual(storedFieldSources[field])
                val fieldSources = storedFieldSources.toMutableMap().apply {
                    recognized?.fieldSources.orEmpty().forEach { (field, source) ->
                        if (!isManual(field)) put(field, source)
                    }
                    if (metadata == null && local == null) {
                        putIfAbsent(MetadataField.DOMAIN, FieldSource.FILENAME)
                        putIfAbsent(MetadataField.DISPLAY_TITLE, FieldSource.FILENAME)
                        putIfAbsent(MetadataField.ORIGINAL_TITLE, FieldSource.FILENAME)
                        if (candidate.coverPath != null) {
                            putIfAbsent(MetadataField.COVER_PATH, FieldSource.FILENAME)
                        }
                    }
                }
                val recognizedSeries = recognized?.series?.let { title ->
                    dev.susnowy.gallery.model.SeriesRef(
                        id = UUID.nameUUIDFromBytes("$libraryId:$title".encodeToByteArray()).toString(),
                        title = title,
                        sortIndex = recognized.sortIndex ?: 0.0,
                        season = recognized.season,
                        episode = recognized.episode,
                        volume = recognized.volume,
                    )
                }
                val item = MediaItem(
                    id = id,
                    libraryId = libraryId,
                    relativePath = candidate.relativePath,
                    uri = candidate.uri,
                    kind = candidate.kind,
                    // Pre-v3 local rows defaulted to CLASSIFIED. Trust the current scan unless a
                    // portable v3 catalog explicitly records the user's choice.
                    domain = metadata?.domain ?: candidate.domain,
                    sourceKind = candidate.sourceKind,
                    displayTitle = when {
                        metadata != null && isManual(MetadataField.DISPLAY_TITLE) -> metadata.displayTitle
                        recognized?.title != null -> recognized.title
                        metadata != null -> metadata.displayTitle
                        else -> local?.displayTitle ?: candidate.suggestedTitle
                    },
                    originalTitle = metadata?.originalTitle ?: candidate.suggestedTitle,
                    mimeType = candidate.mimeType,
                    size = candidate.size,
                    modifiedAt = candidate.modifiedAt,
                    contentHash = candidate.contentHash,
                    capturedAt = candidate.capturedAt ?: local?.capturedAt,
                    latitude = candidate.latitude ?: local?.latitude,
                    longitude = candidate.longitude ?: local?.longitude,
                    pageCount = candidate.pageCount,
                    authors = when {
                        metadata != null && isManual(MetadataField.AUTHORS) -> metadata.authors
                        !recognized?.authors.isNullOrEmpty() -> recognized?.authors.orEmpty()
                        metadata != null -> metadata.authors
                        else -> local?.authors.orEmpty()
                    },
                    tags = if (metadata != null && isManual(MetadataField.TAGS)) {
                        metadata.tags
                    } else {
                        (
                            (metadata?.tags ?: local?.tags.orEmpty()) + recognized?.tags.orEmpty() +
                                listOfNotNull(recognized?.language?.let { "language:$it" })
                            ).distinct()
                    },
                    collections = metadata?.collections ?: local?.collections.orEmpty(),
                    series = when {
                        metadata != null && isManual(MetadataField.SERIES) -> metadata.series
                        recognizedSeries != null -> recognizedSeries
                        metadata != null -> metadata.series
                        else -> local?.series
                    },
                    coverPath = metadata?.coverPath ?: candidate.coverPath ?: local?.coverPath,
                    secondaryPath = metadata?.secondaryPath ?: candidate.secondaryPath ?: local?.secondaryPath,
                    favorite = metadata?.favorite ?: local?.favorite ?: false,
                    inInbox = metadata == null && (local?.inInbox ?: true),
                    trashed = trashEntry != null,
                    deletedAt = trashEntry?.deletedAt?.let(java.time.Instant::parse)?.toEpochMilli(),
                    needsRepair = false,
                    revision = metadata?.revision ?: local?.revision ?: 0,
                    fieldSources = fieldSources,
                )
                scanned += item
            }
            // One transaction for the whole scan: committing per row would flush the WAL
            // once per candidate, which dominates indexing time on a large Library.
            database.replaceScannedMedia(libraryId, scanned, foundPaths)
            val discovered = result.discoveries.map { candidate ->
                DiscoveredEntry(
                    libraryId = libraryId,
                    relativePath = candidate.relativePath,
                    uri = candidate.uri,
                    isDirectory = candidate.isDirectory,
                    mimeType = candidate.mimeType,
                    size = candidate.size,
                    modifiedAt = candidate.modifiedAt,
                    reason = candidate.reason,
                )
            }
            val protectedDiscoveries = database.discoveries(libraryId)
                .asSequence()
                .filter { result.protectsPreviouslyIndexed(it.relativePath) }
                .mapTo(mutableSetOf(), DiscoveredEntry::relativePath)
            database.replaceDiscoveries(libraryId, discovered, protectedDiscoveries)
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
                effectiveRegistration.copy(
                    permissionState = PermissionState.AVAILABLE,
                    lastScanAt = System.currentTimeMillis(),
                ),
            )
            refreshFromDatabase()
            result
        }
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
            val locked = updated.copy(fieldSources = updated.withManualEdits(database.mediaItem(updated.id)))
            val portable = PortableMetadataStore(storage).saveItem(locked, locked.revision)
            val saved = locked.copy(revision = portable.revision, inInbox = false)
            database.upsertMedia(saved)
            refreshFromDatabase()
            saved
        }
    }

    /**
     * Persists the scanner's current proposal without marking any field as manually edited.
     * This is the explicit boundary between Inbox and the normal library views.
     */
    suspend fun acceptSuggestions(libraryId: String, itemIds: Collection<String>): Int =
        runOperation("正在接受识别建议…") {
            onIo {
                val items = itemIds.distinct().mapNotNull(database::mediaItem)
                    .filter(MediaItem::inInbox)
                require(items.all { it.libraryId == libraryId }) { "不能跨 Library 接受识别建议" }
                if (items.isEmpty()) return@onIo 0
                val storage = storageFor(requireLibrary(libraryId))
                val portable = PortableMetadataStore(storage).saveItems(items).associateBy { it.id }
                items.forEach { item ->
                    database.upsertMedia(
                        item.copy(
                            revision = portable.getValue(item.id).revision,
                            inInbox = false,
                        ),
                    )
                }
                refreshFromDatabase()
                items.size
            }
        }

    suspend fun updateMediaBatch(
        libraryId: String,
        itemIds: Collection<String>,
        addAuthors: List<String> = emptyList(),
        addTags: List<String> = emptyList(),
        addCollections: List<String> = emptyList(),
        favorite: Boolean? = null,
    ): Int = runOperation("正在批量保存元数据…") {
        onIo {
            val items = itemIds.distinct().mapNotNull(database::mediaItem)
            require(items.all { it.libraryId == libraryId }) { "不能跨 Library 批量修改" }
            if (items.isEmpty()) return@onIo 0
            val updated = items.map { item ->
                val changed = item.copy(
                    authors = (item.authors + addAuthors).distinct(),
                    tags = (item.tags + addTags).distinct(),
                    collections = (item.collections + addCollections).distinct(),
                    favorite = favorite ?: item.favorite,
                )
                changed.copy(fieldSources = changed.withManualEdits(item))
            }
            val storage = storageFor(requireLibrary(libraryId))
            val portable = PortableMetadataStore(storage).saveItems(updated).associateBy { it.id }
            updated.forEach { item ->
                database.upsertMedia(
                    item.copy(
                        revision = portable.getValue(item.id).revision,
                        inInbox = false,
                    ),
                )
            }
            refreshFromDatabase()
            updated.size
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

    suspend fun setTrashedBatch(libraryId: String, itemIds: Collection<String>): Int =
        runOperation("正在批量移入回收站…") {
            onIo {
                val items = itemIds.distinct().mapNotNull(database::mediaItem)
                require(items.all { it.libraryId == libraryId }) { "不能跨 Library 批量修改" }
                if (items.isEmpty()) return@onIo 0
                val deletedAt = System.currentTimeMillis()
                val storage = storageFor(requireLibrary(libraryId))
                PortableMetadataStore(storage).setTrashed(items, true, deletedAt)
                items.forEach { item ->
                    database.upsertMedia(item.copy(trashed = true, deletedAt = deletedAt))
                }
                refreshFromDatabase()
                items.size
            }
        }

    suspend fun purge(itemId: String) = runOperation("正在永久删除…") {
        onIo {
            purgeInternal(itemId)
        }
    }

    suspend fun saveProgress(progress: PlaybackProgress) = progressWriteMutex.withLock {
        onIo {
            val item = database.mediaItem(progress.itemId) ?: return@onIo
            val storage = storageFor(requireLibrary(item.libraryId))
            PortableMetadataStore(storage).saveProgress(item.libraryId, progress)
            database.upsertProgress(progress)
        }
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
            onIo {
                val storage = storageFor(requireLibrary(libraryId))
                PortableLibraryManager(storage).ensureMediaStoreIgnored()
                importer.import(uris, storage)
            }
        }

    suspend fun importSystemImageSet(
        libraryId: String,
        uris: List<Uri>,
        title: String,
    ): ImportResult = runOperation("正在复制系统图片并创建 ImageSet…") {
        onIo {
            val storage = storageFor(requireLibrary(libraryId))
            PortableLibraryManager(storage).ensureMediaStoreIgnored()
            importer.importImageSet(uris, title, storage)
        }
    }

    suspend fun importSystemWorks(
        libraryId: String,
        uris: List<Uri>,
        kind: WorkImportKind,
    ): ImportResult = runOperation(
        if (kind == WorkImportKind.IMAGE) "正在按来源分类复制图片…" else "正在按来源分类复制视频…",
    ) {
        onIo {
            val storage = storageFor(requireLibrary(libraryId))
            PortableLibraryManager(storage).ensureMediaStoreIgnored()
            importer.importWorks(uris, kind, storage)
        }
    }

    fun systemMediaAccess(): SystemMediaAccess = systemMediaCatalog.access()

    suspend fun systemMedia(): List<SystemMediaEntry> = onIo { systemMediaCatalog.query() }

    suspend fun recoverInterruptedTransactions(libraryId: String): Int =
        runOperation("正在恢复未完成事务…") {
            onIo { organizer.recoverInterrupted(libraryId, storageFor(requireLibrary(libraryId))) }
        }

    suspend fun cleanupExpired(retentionDays: Int): Int = onIo {
        if (retentionDays <= 0) return@onIo 0
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

    suspend fun deriveImage(itemId: String): String = runOperation("正在复制派生图片…") {
        onIo {
            val item = database.mediaItem(itemId) ?: error("媒体不存在")
            derivation.deriveImage(item, storageFor(requireLibrary(item.libraryId)))
        }
    }

    suspend fun derivePage(itemId: String, pageIndex: Int): String =
        runOperation("正在从 ImageSet 派生页面…") {
            onIo {
                val item = database.mediaItem(itemId) ?: error("媒体不存在")
                derivation.derivePage(item, pageIndex, storageFor(requireLibrary(item.libraryId)))
            }
        }

    suspend fun createImageSet(libraryId: String, itemIds: List<String>, title: String): String =
        runOperation("正在创建 ImageSet…") {
            onIo {
                val items = itemIds.map { id -> database.mediaItem(id) ?: error("图片不存在：$id") }
                require(items.all { it.libraryId == libraryId }) { "不能跨 Library 隐式派生" }
                derivation.createImageSet(items, title, storageFor(requireLibrary(libraryId)))
            }
        }

    suspend fun reorderImageSet(itemId: String, pages: List<ImagePage>): ImageSetOrderResult =
        runOperation("正在写入漫画页序…") {
            onIo {
                val item = database.mediaItem(itemId) ?: error("漫画不存在")
                val storage = storageFor(requireLibrary(item.libraryId))
                val result = imageSetOrder.reorder(item, pages, storage)
                val updated = item.copy(coverPath = result.coverPath)
                val portable = PortableMetadataStore(storage).saveItem(updated, updated.revision)
                database.upsertMedia(updated.copy(revision = portable.revision, inInbox = false))
                refreshFromDatabase()
                result
            }
        }

    suspend fun findDuplicates(libraryId: String): List<List<MediaItem>> =
        runOperation("正在后台计算重复文件指纹…") {
            onIo {
                val storage = storageFor(requireLibrary(libraryId))
                database.media(libraryId)
                    .filter {
                        !it.trashed && !it.needsRepair && it.size > 0 &&
                            it.sourceKind != dev.susnowy.gallery.model.SourceKind.DIRECTORY
                    }
                    .groupBy(MediaItem::size)
                    .values
                    .filter { it.size > 1 }
                    .flatMap { sameSize ->
                        sameSize.groupBy { item ->
                            val digest = MessageDigest.getInstance("SHA-256")
                            val document = LibraryDocument(item.relativePath, item.relativePath.substringAfterLast('/'), false)
                            storage.openInput(document).use { input ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    digest.update(buffer, 0, count)
                                }
                            }
                            digest.digest().joinToString("") { "%02x".format(it) }
                        }.values.filter { it.size > 1 }
                    }
            }
        }

    suspend fun forgetLibrary(libraryId: String) = onIo {
        val registration = database.library(libraryId)
        database.removeLibrary(libraryId)
        registration?.let { library ->
            runCatching {
                val uri = library.treeUri.toUri()
                val permission = appContext.contentResolver.persistedUriPermissions
                    .firstOrNull { it.uri == uri }
                val flags = (if (permission?.isReadPermission == true) {
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                } else 0) or (if (permission?.isWritePermission == true) {
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                } else 0)
                if (flags != 0) appContext.contentResolver.releasePersistableUriPermission(uri, flags)
            }
        }
        refreshFromDatabase()
    }

    fun refreshFromDatabase() {
        _libraries.value = database.libraries()
        _media.value = database.media()
        _discoveries.value = database.discoveries()
    }

    fun library(libraryId: String): LibraryRegistration? = database.library(libraryId)

    fun storage(libraryId: String): DocumentTreeStorage = storageFor(requireLibrary(libraryId))

    private fun requireLibrary(libraryId: String): LibraryRegistration =
        database.library(libraryId) ?: error("Library 未登记：$libraryId")

    private fun storageFor(registration: LibraryRegistration) =
        DocumentTreeStorage(appContext, registration.treeUri.toUri())

    private fun purgeInternal(itemId: String) {
        val item = database.mediaItem(itemId) ?: return
        check(item.trashed) { "只能永久删除回收站中的项目" }
        val storage = storageFor(requireLibrary(item.libraryId))
        val entry = storage.entry(item.relativePath)
            ?: throw FileNotFoundException("文件已不存在，请先重新扫描")
        val secondaryEntry = item.secondaryPath?.let { path ->
            storage.entry(path) ?: throw FileNotFoundException("Live Photo motion 文件已不存在")
        }
        val currentSize = (if (entry.isDirectory) storage.treeStats(item.relativePath).totalBytes else entry.size) +
            (secondaryEntry?.size ?: 0)
        if (item.size > 0 && currentSize != item.size) {
            error("媒体内容大小已变化，为避免误删已停止操作")
        }
        val document = storage.find(item.relativePath)
            ?: throw FileNotFoundException(item.relativePath)
        item.secondaryPath?.let { path ->
            val secondaryDocument = storage.find(path) ?: throw FileNotFoundException(path)
            check(storage.delete(secondaryDocument)) { "Provider 拒绝删除 Live Photo motion 文件" }
        }
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
            if (error is CancellationException) throw error
            _events.tryEmit(error.message ?: "操作失败")
            throw error
        } finally {
            _operation.value = null
        }
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        /** How long a second attach waits for the winner to publish `library.json`. */
        const val INITIALIZATION_WAIT_ATTEMPTS = 8
        const val INITIALIZATION_WAIT_MILLIS = 250L
    }
}
