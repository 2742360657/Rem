package dev.susnowy.gallery.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.media.ArchiveCache
import dev.susnowy.gallery.media.ArchiveCacheStats
import dev.susnowy.gallery.media.ImagePage
import dev.susnowy.gallery.media.ImageSetOrderResult
import dev.susnowy.gallery.media.ImageSetOrderService
import dev.susnowy.gallery.media.OfflinePreviewStats
import dev.susnowy.gallery.media.OfflinePreviewStore
import dev.susnowy.gallery.metadata.PortableInboxStore
import dev.susnowy.gallery.metadata.PortableMetadataStore
import dev.susnowy.gallery.metadata.FieldSource
import dev.susnowy.gallery.metadata.MetadataField
import dev.susnowy.gallery.metadata.withManualEdits
import dev.susnowy.gallery.model.InboxDisposition
import dev.susnowy.gallery.model.InboxTarget
import dev.susnowy.gallery.model.LibraryInspection
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.DiscoveredEntry
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaSeries
import dev.susnowy.gallery.model.PortableSeries
import dev.susnowy.gallery.model.PortableSeriesMember
import dev.susnowy.gallery.model.SeriesRef
import dev.susnowy.gallery.model.toMediaSeries
import dev.susnowy.gallery.model.PermissionState
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.GroupMemberRole
import dev.susnowy.gallery.model.GroupType
import dev.susnowy.gallery.model.MediaGroup
import dev.susnowy.gallery.model.derivedGroupId
import dev.susnowy.gallery.model.roleForKind
import dev.susnowy.gallery.model.toMediaGroup
import dev.susnowy.gallery.model.PortableGroup
import dev.susnowy.gallery.compare.EditionComparisonReport
import dev.susnowy.gallery.compare.MergeManifest
import dev.susnowy.gallery.compare.MergePlan
import dev.susnowy.gallery.compare.MergeResult
import dev.susnowy.gallery.compare.MergeSource
import dev.susnowy.gallery.compare.PageComparison
import dev.susnowy.gallery.library.PortableDocumentWriter
import dev.susnowy.gallery.media.MediaContentService
import dev.susnowy.gallery.media.PageManifestService
import dev.susnowy.gallery.media.editionPlanPages
import dev.susnowy.gallery.model.EditionAssetRole
import dev.susnowy.gallery.model.PortableAsset
import dev.susnowy.gallery.model.PortableCatalog
import dev.susnowy.gallery.model.PortableEdition
import dev.susnowy.gallery.model.PortableEditionAsset
import dev.susnowy.gallery.model.PortableGroupMember
import dev.susnowy.gallery.model.PortableInboxDecision
import dev.susnowy.gallery.model.PortableItemMetadata
import dev.susnowy.gallery.model.PortableLibrary
import dev.susnowy.gallery.model.PortableWork
import dev.susnowy.gallery.model.mutedByInboxDecision
import dev.susnowy.gallery.organizer.OrganizationPlan
import dev.susnowy.gallery.organizer.OrganizerService
import dev.susnowy.gallery.organizer.OrganizerTemplate
import dev.susnowy.gallery.scanner.LibraryScanner
import dev.susnowy.gallery.scanner.ScanDepth
import dev.susnowy.gallery.scanner.ScanEnrichment
import dev.susnowy.gallery.scanner.ScanResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.susnowy.gallery.storage.DocumentTreeStorage
import dev.susnowy.gallery.storage.StorageMetrics
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class GalleryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val mergeManifestJson = Json {
        prettyPrint = true
        encodeDefaults = true
    }
    private val database = GalleryDatabase(appContext)
    private val scanner = LibraryScanner()
    private val organizer = OrganizerService()
    private val importer = SystemMediaImporter(appContext)
    private val systemMediaCatalog = SystemMediaCatalog(appContext)
    private val derivation = DerivationService()
    private val imageSetOrder = ImageSetOrderService()
    private val archives = ArchiveCache(File(appContext.cacheDir, "archives"))
    private val pageManifests = PageManifestService(archives = archives)
    private val content = MediaContentService(archives = archives)
    private val offlinePreviews = OfflinePreviewStore(appContext, archives = archives)
    /**
     * Every portable store performs a read-modify-write of one of the shared `.gallery`
     * documents. Serializing only playback progress is insufficient: a simultaneous trash,
     * metadata, Group or Series edit can otherwise commit an older snapshot over it.
     *
     * This is deliberately repository-wide rather than per document. Several user actions
     * update catalog/state and Inbox together, and their ordering is part of the commit.
     */
    private val portableWriteMutex = Mutex()

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

    private val _groups = MutableStateFlow<List<MediaGroup>>(emptyList())
    val groups: StateFlow<List<MediaGroup>> = _groups.asStateFlow()

    private val _series = MutableStateFlow<List<MediaSeries>>(emptyList())
    val series: StateFlow<List<MediaSeries>> = _series.asStateFlow()

    /**
     * Reading order of Works whose preferred Edition is a page plan (a virtual merge).
     * Rebuilt whenever the catalog is loaded, so the reader never has to read it again.
     */
    private val _editionPlans = MutableStateFlow<Map<String, List<ImagePage>>>(emptyMap())

    private val _operation = MutableStateFlow<String?>(null)
    val operation: StateFlow<String?> = _operation.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        refreshFromDatabase()
    }

    suspend fun attach(treeUri: Uri, requestedName: String? = null): LibraryRegistration =
        attachMutex.withLock {
            withPortableWrite {
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
                // Portable Inbox decisions are the truth; mirror them into the fresh index
                // immediately so a re-attached Library does not look undecided.
                runCatching {
                    val inbox = PortableInboxStore(storage).load(migrated.libraryId)
                    database.applyInboxDecisions(migrated.libraryId, inbox.decisions)
                }.onFailure { error ->
                    RemLog.failure("GalleryRepository", "Inbox 决策回填失败", error)
                }
                runCatching {
                    val catalog = PortableMetadataStore(storage).loadCatalog(migrated.libraryId)
                    syncGroups(migrated.libraryId, catalog.groups)
                    syncSeries(migrated.libraryId, catalog.series)
                    refreshEditionPlans(migrated.libraryId, catalog)
                }.onFailure { error ->
                    RemLog.failure("GalleryRepository", "分组/系列索引回填失败", error)
                }
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
            val effectiveRegistration = portableWriteMutex.withLock {
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
                if (migratedIdentity.schemaVersion != identity.schemaVersion) {
                    _events.tryEmit(
                        "已把便携元数据升级到 Schema v${migratedIdentity.schemaVersion}（原数据已备份）",
                    )
                }
                manager.ensureMediaStoreIgnored()
                val recoveredPageOrders = imageSetOrder.recoverInterrupted(storage)
                if (recoveredPageOrders > 0) {
                    _events.tryEmit("已恢复 $recoveredPageOrders 个中断的漫画页序事务")
                }
                registration.copy(
                    name = migratedIdentity.name,
                    schemaVersion = migratedIdentity.schemaVersion,
                )
            }
            val inventoryStartedAt = System.currentTimeMillis()
            val result = scanner.scan(
                storage,
                database.scanSnapshot(libraryId),
                ScanDepth.INVENTORY,
            ) { progress ->
                _operation.value = "正在扫描：已读取 ${progress.directoriesRead} 个目录、" +
                    "${progress.entriesRead} 个条目，识别 ${progress.candidatesFound} 项"
                if (progress.directoriesRead == 1 ||
                    progress.directoriesRead % SCAN_LOG_DIRECTORY_INTERVAL == 0
                ) {
                    RemLog.info(
                        SCAN_TAG,
                        "清点进度：目录=${progress.directoriesRead}，条目=${progress.entriesRead}，" +
                            "候选=${progress.candidatesFound}，${StorageMetrics.summary()}",
                    )
                }
            }
            val inventoryMillis = System.currentTimeMillis() - inventoryStartedAt
            // Inventory can take a long time on a removable tree. Load portable truth only
            // after that traversal, then keep it stable until the disposable projection is
            // committed. Edits made while inventory was running are therefore included,
            // while a later edit waits instead of being overwritten in the local index.
            portableWriteMutex.withLock {
            val portableStore = PortableMetadataStore(storage)
            val catalog = portableStore.loadCatalog(libraryId)
            val state = portableStore.loadState(libraryId)
            val inbox = PortableInboxStore(storage).load(libraryId)
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
                val decision = inbox.forWork(id, candidate.relativePath)
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
                        sortIndex = recognized.sortIndex,
                        season = recognized.season,
                        episode = recognized.episode,
                        volume = recognized.volume,
                        chapter = recognized.chapter,
                    )
                }
                val item = MediaItem(
                    id = id,
                    libraryId = libraryId,
                    relativePath = candidate.relativePath,
                    uri = candidate.uri,
                    kind = candidate.kind,
                    // Automatic classifications may evolve as recognizers improve. Only a
                    // portable manual decision is authoritative over the current scan.
                    domain = if (metadata != null && isManual(MetadataField.DOMAIN)) {
                        metadata.domain ?: candidate.domain
                    } else {
                        candidate.domain
                    },
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
                    // A portable decision is the only thing that keeps an item out of the
                    // pending list without also writing Work metadata: ignored paths must
                    // stay ignored after this device index is rebuilt.
                    inInbox = decision == null && metadata == null && (local?.inInbox ?: true),
                    inboxDisposition = decision?.disposition,
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
            database.replaceScannedMedia(
                libraryId = libraryId,
                items = scanned,
                foundPaths = foundPaths,
                pendingEnrichment = result.candidates.asSequence()
                    .filter { it.needsEnrichment }
                    .mapTo(mutableSetOf()) { it.relativePath },
            )
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
                    disposition = inbox.forDiscovery(candidate.relativePath)?.disposition,
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
            syncGroups(libraryId, catalog.groups)
            syncSeries(libraryId, catalog.series)
            refreshEditionPlans(libraryId, catalog)
            refreshFromDatabase()
            }
            val pending = database.pendingEnrichmentCount(libraryId)
            RemLog.info(
                SCAN_TAG,
                "快速索引已提交：媒体=${result.candidates.size}，待补全=$pending，" +
                    "其他=${result.discoveries.size}，清点耗时=${inventoryMillis} ms，${StorageMetrics.summary()}",
            )
            if (pending > 0) enrichPending(libraryId, storage)
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

    /** Continues only the local byte-inspection queue; portable metadata is untouched. */
    suspend fun resumePendingEnrichment(libraryId: String): Int = runOperation("正在继续补全媒体信息…") {
        scanMutex.withLock {
            onIo {
                val pending = database.pendingEnrichmentCount(libraryId)
                if (pending == 0) return@onIo 0
                val registration = requireLibrary(libraryId)
                val storage = storageFor(registration)
                if (!storage.isAvailable) return@onIo 0
                val identity = (PortableLibraryManager(storage).inspect() as? LibraryInspection.Valid)?.library
                require(identity?.libraryId == libraryId) { "Library 身份与本机登记不一致，已拒绝续扫" }
                enrichPending(libraryId, storage)
            }
        }
    }

    private suspend fun enrichPending(libraryId: String, storage: DocumentTreeStorage): Int {
        val initial = database.pendingEnrichmentCount(libraryId)
        if (initial == 0) return 0
        var completed = 0
        var failed = 0
        while (true) {
            val batch = database.pendingEnrichmentItems(libraryId, ENRICHMENT_BATCH_SIZE)
            if (batch.isEmpty()) break
            val enriched = mutableListOf<MediaItem>()
            batch.forEach { item ->
                coroutineContext.ensureActive()
                runCatching { scanner.enrich(storage, item, archives) }
                    .onSuccess { result -> enriched += item.withEnrichment(result) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        failed++
                        database.markEnrichmentFailed(libraryId, item.relativePath)
                        RemLog.warn(SCAN_TAG, "媒体信息补全失败：${item.relativePath}", error)
                    }
            }
            if (enriched.isNotEmpty()) {
                database.applyEnrichmentBatch(libraryId, enriched)
                completed += enriched.size
            }
            _operation.value = "快速索引已可用；正在补全媒体信息 $completed/$initial" +
                if (failed == 0) "" else "（失败 $failed）"
        }
        refreshFromDatabase()
        RemLog.info(
            SCAN_TAG,
            "媒体信息补全结束：完成=$completed，失败=$failed，总计=$initial，${StorageMetrics.summary()}",
        )
        if (failed > 0) _events.tryEmit("有 $failed 项媒体信息暂时无法补全；下次扫描会重试")
        return completed
    }

    private fun MediaItem.withEnrichment(result: ScanEnrichment): MediaItem {
        require(relativePath == result.relativePath)
        val recognized = result.recognizedMetadata
        val sources = fieldSources.toMutableMap()
        fun manual(field: String): Boolean = FieldSource.isManual(sources[field])
        recognized?.fieldSources.orEmpty().forEach { (field, source) ->
            if (!manual(field)) sources[field] = source
        }
        val recognizedSeries = recognized?.series?.let { title ->
            dev.susnowy.gallery.model.SeriesRef(
                id = UUID.nameUUIDFromBytes("$libraryId:$title".encodeToByteArray()).toString(),
                title = title,
                sortIndex = recognized.sortIndex,
                season = recognized.season,
                episode = recognized.episode,
                volume = recognized.volume,
                chapter = recognized.chapter,
            )
        }
        return copy(
            contentHash = result.contentHash,
            capturedAt = result.capturedAt ?: capturedAt,
            latitude = result.latitude ?: latitude,
            longitude = result.longitude ?: longitude,
            pageCount = result.pageCount ?: pageCount,
            displayTitle = if (!manual(MetadataField.DISPLAY_TITLE)) {
                recognized?.title ?: displayTitle
            } else {
                displayTitle
            },
            authors = if (!manual(MetadataField.AUTHORS) && !recognized?.authors.isNullOrEmpty()) {
                recognized?.authors.orEmpty()
            } else {
                authors
            },
            tags = if (!manual(MetadataField.TAGS)) {
                (tags + recognized?.tags.orEmpty() +
                    listOfNotNull(recognized?.language?.let { "language:$it" })).distinct()
            } else {
                tags
            },
            series = if (!manual(MetadataField.SERIES)) recognizedSeries ?: series else series,
            fieldSources = sources,
        )
    }

    suspend fun updateMedia(updated: MediaItem): MediaItem = runOperation("正在保存元数据…") {
        withPortableWrite {
            val storage = storageFor(requireLibrary(updated.libraryId))
            val stored = database.mediaItem(updated.id)
            val locked = updated.copy(fieldSources = updated.withManualEdits(stored))
            val portable = PortableMetadataStore(storage).saveItem(locked, locked.revision)
            // Editing a Work is also an Inbox decision: the suggestion is no longer pending,
            // and the decision has to survive this device's index.
            val disposition = if (stored != null && stored.domain != locked.domain) {
                InboxDisposition.CLASSIFIED
            } else {
                InboxDisposition.ACCEPTED
            }
            PortableInboxStore(storage).upsert(
                updated.libraryId,
                listOf(
                    mediaDecision(
                        item = locked,
                        disposition = disposition,
                        domain = locked.domain.takeIf { disposition == InboxDisposition.CLASSIFIED },
                        reason = "manual_edit",
                    ),
                ),
            )
            val saved = locked.withPortableMetadata(portable).copy(
                inInbox = false,
                inboxDisposition = disposition,
            )
            database.upsertMedia(saved)
            synchronizeSeriesProjection(saved.libraryId, listOf(portable))
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
            withPortableWrite {
                val items = itemIds.distinct().mapNotNull(database::mediaItem)
                    .filter(MediaItem::inInbox)
                require(items.all { it.libraryId == libraryId }) { "不能跨 Library 接受识别建议" }
                if (items.isEmpty()) return@withPortableWrite 0
                val storage = storageFor(requireLibrary(libraryId))
                val portable = PortableMetadataStore(storage).saveItems(items).associateBy { it.id }
                PortableInboxStore(storage).upsert(
                    libraryId,
                    items.map { item ->
                        mediaDecision(
                            item = item,
                            disposition = InboxDisposition.ACCEPTED,
                            reason = "suggestion_accepted",
                        )
                    },
                )
                items.forEach { item ->
                    database.upsertMedia(
                        item.withPortableMetadata(portable.getValue(item.id)).copy(
                            inInbox = false,
                            inboxDisposition = InboxDisposition.ACCEPTED,
                        ),
                    )
                }
                synchronizeSeriesProjection(libraryId, portable.values)
                refreshFromDatabase()
                items.size
            }
        }

    /**
     * Records a portable Inbox decision for media and/or discovered paths.
     *
     * `accepted` and `classified` also write Work metadata so the Work keeps existing on a
     * device that has never seen this index; `ignored` deliberately writes only the
     * decision, because the user asked Rem to stop surfacing the path, not to edit it.
     * Media is never moved, renamed or deleted by any of these dispositions.
     */
    suspend fun decideInbox(
        libraryId: String,
        items: List<MediaItem> = emptyList(),
        discoveries: List<DiscoveredEntry> = emptyList(),
        disposition: InboxDisposition,
        domain: MediaDomain? = null,
    ): Int = runOperation("正在保存 Inbox 决策…") {
        withPortableWrite {
            val media = items.distinctBy(MediaItem::id)
            val discovered = discoveries.distinctBy(DiscoveredEntry::relativePath)
            require(media.all { it.libraryId == libraryId } && discovered.all { it.libraryId == libraryId }) {
                "不能跨 Library 保存 Inbox 决策"
            }
            if (media.isEmpty() && discovered.isEmpty()) return@withPortableWrite 0
            val now = java.time.Instant.now().toString()
            val storage = storageFor(requireLibrary(libraryId))
            val prepared = if (disposition != InboxDisposition.IGNORED) {
                media.map { item ->
                    if (disposition == InboxDisposition.CLASSIFIED && domain != null && item.domain != domain) {
                        item.copy(domain = domain)
                            .let { changed -> changed.copy(fieldSources = changed.withManualEdits(item)) }
                    } else {
                        item
                    }
                }
            } else {
                emptyList()
            }
            val portableById = if (prepared.isNotEmpty()) {
                // Commit the Work before recording an accepted/classified decision. If the
                // catalog write fails, Inbox remains pending instead of hiding an item whose
                // manual classification never reached portable truth.
                PortableMetadataStore(storage).saveItems(prepared).associateBy { it.id }
            } else {
                emptyMap()
            }
            val updated = PortableInboxStore(storage).upsert(
                libraryId,
                media.map { item ->
                    mediaDecision(
                        item = item,
                        disposition = disposition,
                        domain = domain.takeIf { disposition == InboxDisposition.CLASSIFIED },
                        reason = if (item.inInbox) "media_suggestion" else null,
                    )
                } + discovered.map { entry ->
                    PortableInboxDecision(
                        relativePath = entry.relativePath,
                        target = InboxTarget.DISCOVERY,
                        disposition = disposition,
                        reason = entry.reason.name,
                        decidedAt = now,
                    )
                },
            )
            if (prepared.isNotEmpty()) {
                prepared.forEach { item ->
                    database.upsertMedia(
                        item.withPortableMetadata(portableById.getValue(item.id)).copy(
                            inInbox = false,
                            inboxDisposition = disposition,
                        ),
                    )
                }
                synchronizeSeriesProjection(libraryId, portableById.values)
            }
            // The portable document is the truth: mirror it, including clearing the
            // decisions that were removed and leaving pending rows untouched.
            database.applyInboxDecisions(libraryId, updated.decisions)
            refreshFromDatabase()
            media.size + discovered.size
        }
    }

    /**
     * Removes Inbox decisions, returning the targets to the pending list.
     *
     * A Work that already has portable catalog metadata stays out of Inbox; only a Work
     * whose sole record was the decision becomes pending again.
     */
    suspend fun undoInboxDecision(
        libraryId: String,
        items: List<MediaItem> = emptyList(),
        discoveries: List<DiscoveredEntry> = emptyList(),
    ): Int = runOperation("正在撤销 Inbox 决策…") {
        withPortableWrite {
            val media = items.distinctBy(MediaItem::id)
            val discovered = discoveries.distinctBy(DiscoveredEntry::relativePath)
            require(media.all { it.libraryId == libraryId } && discovered.all { it.libraryId == libraryId }) {
                "不能跨 Library 撤销 Inbox 决策"
            }
            if (media.isEmpty() && discovered.isEmpty()) return@withPortableWrite 0
            val storage = storageFor(requireLibrary(libraryId))
            val keys = media.flatMap { listOf(it.id, it.relativePath) }.toSet() +
                discovered.map(DiscoveredEntry::relativePath)
            val updated = PortableInboxStore(storage).remove(libraryId, keys)
            database.applyInboxDecisions(libraryId, updated.decisions)
            if (media.isNotEmpty()) {
                val catalogWorks = PortableMetadataStore(storage).loadCatalog(libraryId)
                    .works.mapTo(mutableSetOf(), PortableWork::id)
                media.forEach { item ->
                    database.setMediaInboxDecision(
                        libraryId = libraryId,
                        workId = item.id,
                        relativePath = item.relativePath,
                        disposition = null,
                        inInbox = item.id !in catalogWorks,
                    )
                }
            }
            refreshFromDatabase()
            media.size + discovered.size
        }
    }

    /**
     * Creates or updates a browsing Group.
     *
     * Members are Works, so a group never owns media: adding, reordering or removing a
     * member only edits the relationship, and deleting the group removes just that.
     */
    suspend fun saveGroup(
        libraryId: String,
        groupId: String,
        title: String,
        memberIds: List<String>,
        type: GroupType = GroupType.MEDIA_SET,
        ordered: Boolean = true,
        coverWorkId: String? = null,
        expectedRevision: Long? = null,
    ): MediaGroup = runOperation("正在保存分组…") {
        withPortableWrite {
            val storage = storageFor(requireLibrary(libraryId))
            val existing = database.groups(libraryId).firstOrNull { it.id == groupId }
            val members = memberIds.distinct().mapNotNull { memberId ->
                database.mediaItem(memberId)?.takeIf { it.libraryId == libraryId }
            }
            require(members.size == memberIds.distinct().size) { "分组包含不属于当前 Library 的成员" }
            val portable = PortableGroup(
                id = groupId,
                title = title,
                type = type,
                ordered = ordered,
                members = members.mapIndexed { index, item ->
                    PortableGroupMember(
                        workId = item.id,
                        role = existing?.roleOf(item.id)?.takeIf { it != GroupMemberRole.ITEM }
                            ?: roleForKind(item.kind),
                        sortIndex = index.toDouble(),
                    )
                },
                coverWorkId = coverWorkId ?: existing?.coverWorkId ?: members.firstOrNull()?.id,
                revision = existing?.revision ?: 1,
                updatedAt = java.time.Instant.now().toString(),
            )
            val saved = PortableMetadataStore(storage).upsertGroup(
                libraryId = libraryId,
                group = portable,
                expectedRevision = expectedRevision ?: existing?.revision,
            )
            val projected = saved.toMediaGroup(libraryId)
            database.upsertGroup(projected)
            refreshFromDatabase()
            projected
        }
    }

    /**
     * Saves a derived mixed folder as a real Group.
     *
     * The id is derived from the primary Work, so saving the same folder twice updates the
     * existing group instead of creating a second one.
     */
    suspend fun saveDerivedGroup(
        libraryId: String,
        primaryItemId: String,
        title: String,
        memberIds: List<String>,
    ): MediaGroup = saveGroup(
        libraryId = libraryId,
        groupId = derivedGroupId(libraryId, primaryItemId),
        title = title,
        memberIds = memberIds,
    )

    suspend fun deleteGroup(libraryId: String, groupId: String): Boolean = runOperation("正在删除分组…") {
        withPortableWrite {
            val storage = storageFor(requireLibrary(libraryId))
            val removed = PortableMetadataStore(storage).deleteGroup(libraryId, groupId)
            if (removed) {
                database.deleteGroupRow(groupId)
                refreshFromDatabase()
            }
            removed
        }
    }

    /**
     * Renames, reorders and re-numbers one Series in a single portable write.
     *
     * Membership and order are the relationship itself, so nothing here moves media. Every
     * touched Work (including the ones that left the series) is stamped `series = manual`,
     * which is what stops the scanner from re-assigning it from a folder name later.
     */
    suspend fun saveSeries(
        libraryId: String,
        seriesId: String,
        title: String,
        memberIds: List<String>,
        clearPositions: Boolean = false,
        expectedRevision: Long? = null,
    ): MediaSeries = runOperation("正在保存系列…") {
        withPortableWrite {
            val ordered = memberIds.distinct()
            require(ordered.isNotEmpty()) { "系列至少需要一个成员" }
            require(title.isNotBlank()) { "系列标题不能为空" }
            val storage = storageFor(requireLibrary(libraryId))
            val store = PortableMetadataStore(storage)
            val existing = store.loadCatalog(libraryId).series.firstOrNull { it.id == seriesId }
            val previousIds = existing?.members.orEmpty().mapTo(mutableSetOf(), PortableSeriesMember::workId)
            val portable = PortableSeries(
                id = seriesId,
                title = title.trim(),
                aliases = existing?.aliases.orEmpty(),
                members = ordered.mapIndexed { index, workId ->
                    val current = existing?.members?.firstOrNull { it.workId == workId }
                    PortableSeriesMember(
                        workId = workId,
                        sortIndex = index.toDouble(),
                        season = if (clearPositions) null else current?.season,
                        episode = if (clearPositions) null else current?.episode,
                        volume = if (clearPositions) null else current?.volume,
                        chapter = if (clearPositions) null else current?.chapter,
                    )
                },
                fieldSources = existing?.fieldSources.orEmpty(),
                revision = existing?.revision ?: 1,
                updatedAt = java.time.Instant.now().toString(),
            )
            val touched = previousIds + ordered
            val saved = store.upsertSeries(
                libraryId = libraryId,
                series = portable,
                expectedRevision = expectedRevision ?: existing?.revision,
                markSeriesManualFor = touched,
            )
            applySeriesProjection(libraryId, saved, touched)
            database.upsertSeriesRow(saved.toMediaSeries(libraryId))
            refreshFromDatabase()
            saved.toMediaSeries(libraryId)
        }
    }

    /**
     * Deletes a Series entity. Its Works keep their metadata and simply lose the assignment.
     */
    suspend fun deleteSeries(libraryId: String, seriesId: String): Boolean =
        runOperation("正在删除系列…") {
            withPortableWrite {
                val storage = storageFor(requireLibrary(libraryId))
                val store = PortableMetadataStore(storage)
                val existing = store.loadCatalog(libraryId).series.firstOrNull { it.id == seriesId }
                val removed = store.deleteSeries(libraryId, seriesId)
                if (removed) {
                    val touched = existing?.members.orEmpty().mapTo(mutableSetOf(), PortableSeriesMember::workId)
                    database.applySeriesAssignment(libraryId, touched.associateWith { null }, touched)
                    database.deleteSeriesRow(seriesId)
                    refreshFromDatabase()
                }
                removed
            }
        }

    /** Rewrites the media rows of every Work touched by a series edit. */
    private fun applySeriesProjection(
        libraryId: String,
        series: PortableSeries,
        touched: Set<String>,
    ) {
        val ordered = series.members.sortedWith(
            compareBy<PortableSeriesMember> { it.sortIndex ?: Double.MAX_VALUE }
                .thenBy(PortableSeriesMember::workId),
        )
        val assignments = touched.associateWith { workId ->
            val index = ordered.indexOfFirst { it.workId == workId }
            if (index < 0) {
                null
            } else {
                val member = ordered[index]
                SeriesRef(
                    id = series.id,
                    title = series.title,
                    sortIndex = member.sortIndex,
                    season = member.season,
                    episode = member.episode,
                    volume = member.volume,
                    chapter = member.chapter,
                )
            }
        }
        database.applySeriesAssignment(libraryId, assignments, touched)
    }

    /**
     * Compares the preferred Editions of two Works page by page.
     *
     * A quick comparison reads no page bytes at all: it lists directory pages and, for
     * archives, performs the single sequential pass the streaming reader requires. A deep
     * comparison adds a SHA-256 per page during that same pass (directories then cost one
     * read per page). Either way the result is evidence — nothing is moved, rewritten or
     * deleted, and the sources stay exactly as they are.
     */
    suspend fun compareWorks(
        libraryId: String,
        leftWorkId: String,
        rightWorkId: String,
        deep: Boolean,
        onProgress: (String) -> Unit = {},
    ): EditionComparisonReport = runOperation(if (deep) "正在按页比较（需要读取内容）…" else "正在快速比较…") {
        onIo {
            require(leftWorkId != rightWorkId) { "请选择两个不同的来源进行比较" }
            val left = database.mediaItem(leftWorkId) ?: error("左侧作品不在本机索引中")
            val right = database.mediaItem(rightWorkId) ?: error("右侧作品不在本机索引中")
            require(left.libraryId == libraryId && right.libraryId == libraryId) {
                "不能跨 Library 比较"
            }
            val storage = storageFor(requireLibrary(libraryId))
            val leftManifest = pageManifests.manifest(
                item = left,
                storage = storage,
                label = left.displayTitle,
                hashPages = deep,
            ) { pages, bytes ->
                onProgress("${left.displayTitle}：$pages 页 / ${bytes / 1024 / 1024} MB")
            }
            val rightManifest = pageManifests.manifest(
                item = right,
                storage = storage,
                label = right.displayTitle,
                hashPages = deep,
            ) { pages, bytes ->
                onProgress("${right.displayTitle}：$pages 页 / ${bytes / 1024 / 1024} MB")
            }
            PageComparison.compare(leftManifest, rightManifest)
        }
    }

    /**
     * Writes a virtual merged Edition on [targetWorkId].
     *
     * The result is a page plan across the source Assets — no file is copied, rewritten or
     * removed, and both original sources keep their own Edition. The same pair of sources
     * always resolves to the same Edition id, so merging twice updates that Edition instead
     * of creating a duplicate, and the evidence lands in `.gallery/imports/`.
     */
    suspend fun createMergedEdition(
        libraryId: String,
        targetWorkId: String,
        report: EditionComparisonReport,
    ): MediaItem = runOperation("正在生成虚拟合并版本…") {
        withPortableWrite {
            val target = database.mediaItem(targetWorkId) ?: error("目标作品不在本机索引中")
            val storage = storageFor(requireLibrary(libraryId))
            val store = PortableMetadataStore(storage)
            val catalog = store.loadCatalog(libraryId)
            val assetsByPath = catalog.assets.associateBy(PortableAsset::relativePath)
            val plan = MergePlan.build(report)
            val members = plan.mapIndexed { index, page ->
                val asset = assetsByPath[page.containerPath]
                    ?: error("合并计划引用了未知来源：${page.containerPath}")
                PortableEditionAsset(
                    assetId = asset.id,
                    role = EditionAssetRole.PAGE,
                    sortIndex = index.toDouble(),
                    // Inside an archive this is the entry, inside a directory the file name,
                    // and for a single-image container it stays null (the container is the page).
                    entryPath = page.entryPath
                        ?: page.name.takeIf { asset.source != dev.susnowy.gallery.model.SourceKind.FILE },
                )
            }
            require(members.isNotEmpty()) { "合并结果为空" }
            val editionId = UUID.nameUUIDFromBytes(
                "merge:$libraryId:$targetWorkId:${report.left.sourceId}:${report.right.sourceId}"
                    .encodeToByteArray(),
            ).toString()
            val label = "合并版（${report.left.label} + ${report.right.label}）"
            val saved = store.upsertEdition(
                libraryId = libraryId,
                edition = PortableEdition(
                    id = editionId,
                    workId = targetWorkId,
                    label = label,
                    assets = members,
                    revision = 1,
                    updatedAt = java.time.Instant.now().toString(),
                ),
                prefer = true,
            )
            writeMergeManifest(libraryId, storage, saved, targetWorkId, label, report)
            editionPlanPages(saved.assets, assetsByPath)?.let { pages ->
                _editionPlans.value = _editionPlans.value + (planKey(libraryId, targetWorkId) to pages)
            }
            val updated = target.copy(
                revision = saved.revision,
                coverPath = target.coverPath,
            )
            database.upsertMedia(updated)
            refreshFromDatabase()
            updated
        }
    }

    private fun writeMergeManifest(
        libraryId: String,
        storage: DocumentTreeStorage,
        edition: PortableEdition,
        targetWorkId: String,
        label: String,
        report: EditionComparisonReport,
    ) {
        val manifest = MergeManifest(
            schemaVersion = dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION,
            libraryId = libraryId,
            createdAt = java.time.Instant.now().toString(),
            editionId = edition.id,
            targetWorkId = targetWorkId,
            label = label,
            sources = listOf(report.left, report.right).map { source ->
                MergeSource(
                    workId = source.sourceId,
                    assetPath = source.pages.firstOrNull()?.containerPath.orEmpty(),
                    label = source.label,
                    pages = source.pageCount,
                    hashed = source.hashed,
                    bytesRead = source.bytesRead,
                    durationMs = source.durationMs,
                )
            },
            result = MergeResult(
                pages = edition.assets.size,
                identical = report.identicalCount,
                leftOnly = report.leftOnly.size,
                rightOnly = report.rightOnly.size,
                conflicting = report.conflictingCount,
                unverified = report.unverifiedCount,
                deep = report.deep,
            ),
        )
        runCatching {
            PortableDocumentWriter(storage).write(
                ".gallery/imports/merge-${edition.id}.json",
                mergeManifestJson.encodeToString(manifest),
                "application/json",
            )
        }.onFailure { error ->
            // The Edition is already committed; a missing evidence file must not look like a
            // failed merge, but it is worth a log line.
            RemLog.failure("GalleryRepository", "合并来源清单写入失败", error)
        }
    }

    private fun mediaDecision(
        item: MediaItem,
        disposition: InboxDisposition,
        domain: MediaDomain? = null,
        reason: String? = null,
    ) = PortableInboxDecision(
        relativePath = item.relativePath,
        target = InboxTarget.MEDIA,
        workId = item.id,
        disposition = disposition,
        domain = domain,
        reason = reason,
        decidedAt = java.time.Instant.now().toString(),
    )

    suspend fun updateMediaBatch(
        libraryId: String,
        itemIds: Collection<String>,
        addAuthors: List<String> = emptyList(),
        addTags: List<String> = emptyList(),
        addCollections: List<String> = emptyList(),
        favorite: Boolean? = null,
    ): Int = runOperation("正在批量保存元数据…") {
        withPortableWrite {
            val items = itemIds.distinct().mapNotNull(database::mediaItem)
            require(items.all { it.libraryId == libraryId }) { "不能跨 Library 批量修改" }
            if (items.isEmpty()) return@withPortableWrite 0
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
            val pending = items.filter(MediaItem::inInbox)
            if (pending.isNotEmpty()) {
                PortableInboxStore(storage).upsert(
                    libraryId,
                    pending.map { item ->
                        mediaDecision(
                            item = item,
                            disposition = InboxDisposition.ACCEPTED,
                            reason = "batch_edit",
                        )
                    },
                )
            }
            updated.forEach { item ->
                database.upsertMedia(
                    item.withPortableMetadata(portable.getValue(item.id)).copy(
                        inInbox = false,
                        inboxDisposition = item.inboxDisposition ?: InboxDisposition.ACCEPTED,
                    ),
                )
            }
            synchronizeSeriesProjection(libraryId, portable.values)
            refreshFromDatabase()
            updated.size
        }
    }

    suspend fun setTrashed(itemId: String, trashed: Boolean) = runOperation(
        if (trashed) "正在移入回收站…" else "正在恢复…",
    ) {
        withPortableWrite {
            val item = database.mediaItem(itemId) ?: return@withPortableWrite
            val deletedAt = if (trashed) System.currentTimeMillis() else null
            val storage = storageFor(requireLibrary(item.libraryId))
            PortableMetadataStore(storage).setTrashed(item, trashed, deletedAt ?: 0)
            database.upsertMedia(item.copy(trashed = trashed, deletedAt = deletedAt))
            refreshFromDatabase()
        }
    }

    suspend fun setTrashedBatch(libraryId: String, itemIds: Collection<String>): Int =
        runOperation("正在批量移入回收站…") {
            withPortableWrite {
                val items = itemIds.distinct().mapNotNull(database::mediaItem)
                require(items.all { it.libraryId == libraryId }) { "不能跨 Library 批量修改" }
                if (items.isEmpty()) return@withPortableWrite 0
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
        withPortableWrite {
            purgeInternal(itemId)
        }
    }

    suspend fun saveProgress(progress: PlaybackProgress) = withPortableWrite {
        val item = database.mediaItem(progress.itemId) ?: return@withPortableWrite
        val storage = storageFor(requireLibrary(item.libraryId))
        PortableMetadataStore(storage).saveProgress(item.libraryId, progress)
        database.upsertProgress(progress)
    }

    suspend fun progress(itemId: String): PlaybackProgress? = onIo { database.progress(itemId) }

    suspend fun offlinePreview(item: MediaItem): java.io.File? =
        offlinePreviews.getOrCreate(item, storageFor(requireLibrary(item.libraryId)))

    suspend fun offlinePreviewStats(): OfflinePreviewStats = offlinePreviews.stats()

    suspend fun clearOfflinePreviews(): OfflinePreviewStats = offlinePreviews.clear()

    suspend fun archiveCacheStats(): ArchiveCacheStats = archives.stats()

    suspend fun clearArchiveCache(): ArchiveCacheStats = archives.clear()

    suspend fun previewOrganization(
        libraryId: String,
        template: OrganizerTemplate,
    ): OrganizationPlan = runOperation("正在生成整理计划…") {
        onIo {
            val items = database.media(libraryId).filterNot {
                it.trashed || it.inInbox || it.mutedByInboxDecision
            }
            organizer.preview(items, storageFor(requireLibrary(libraryId)), template)
        }
    }

    suspend fun executeOrganization(plan: OrganizationPlan) = runOperation("正在执行整理事务…") {
        withPortableWrite {
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
            withPortableWrite {
                organizer.recoverInterrupted(libraryId, storageFor(requireLibrary(libraryId)))
            }
        }

    suspend fun cleanupExpired(retentionDays: Int): Int = withPortableWrite {
        if (retentionDays <= 0) return@withPortableWrite 0
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
            withPortableWrite {
                val item = database.mediaItem(itemId) ?: error("漫画不存在")
                val storage = storageFor(requireLibrary(item.libraryId))
                val result = imageSetOrder.reorder(item, pages, storage)
                val updated = item.copy(coverPath = result.coverPath)
                val portable = PortableMetadataStore(storage).saveItem(updated, updated.revision)
                database.upsertMedia(updated.withPortableMetadata(portable).copy(inInbox = false))
                synchronizeSeriesProjection(item.libraryId, listOf(portable))
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
        _groups.value = database.groups()
        _series.value = database.series()
    }

    /**
     * Mirrors the portable Groups of one Library into the disposable index.
     *
     * Called after a scan, after an attach, and after every group edit; the portable
     * catalog is the truth and this projection only exists so the UI can render a group
     * without loading the whole catalog.
     */
    private fun syncGroups(libraryId: String, groups: List<PortableGroup>) {
        database.replaceGroups(libraryId, groups.map { it.toMediaGroup(libraryId) })
    }

    /** Mirrors the portable Series of one Library into the disposable index. */
    private fun syncSeries(libraryId: String, series: List<PortableSeries>) {
        database.replaceSeries(libraryId, series.map { it.toMediaSeries(libraryId) })
    }

    /**
     * Keeps the reading order of page-plan Editions ready for the reader.
     *
     * A plan is only rebuilt from the catalog that was just loaded; if a plan cannot be read
     * as a flat page list the Work simply falls back to its own single source.
     */
    private fun refreshEditionPlans(libraryId: String, catalog: PortableCatalog) {
        val assetsById = catalog.assets.associateBy(PortableAsset::id)
        val editionsByWork = catalog.editions.groupBy(PortableEdition::workId)
        // Plans are keyed per Library so a refresh can replace one Library without touching
        // the others.
        val plans = _editionPlans.value.filterKeys { !it.startsWith("$libraryId:") }.toMutableMap()
        catalog.works.forEach { work ->
            val editions = editionsByWork[work.id].orEmpty()
            val edition = editions.firstOrNull { it.id == work.preferredEditionId }
                ?: editions.minByOrNull(PortableEdition::id)
                ?: return@forEach
            val isPagePlan = edition.assets.size > 1 || edition.assets.any { it.entryPath != null }
            if (!isPagePlan) return@forEach
            editionPlanPages(edition.assets, assetsById)?.let { pages ->
                plans[planKey(libraryId, work.id)] = pages
            }
        }
        _editionPlans.value = plans
    }

    private fun planKey(libraryId: String, workId: String) = "$libraryId:$workId"

    /**
     * Pages of a Work: its Edition page plan when it has one, otherwise its own source.
     *
     * Directory and file pages resolve their URIs with one listing per parent directory
     * instead of one provider lookup per page, so a merged plan with hundreds of pages does
     * not turn into hundreds of round trips.
     */
    /**
     * Reader-side media access.
     *
     * These live on the repository because it owns the single [MediaContentService], the one
     * wired to the archive cache. A second instance somewhere else silently loses that cache
     * and falls back to the streaming reader, which cannot open every archive layout.
     */
    suspend fun resolvePath(item: MediaItem, path: String): String? =
        content.resolveUri(path, storageFor(requireLibrary(item.libraryId)))

    suspend fun archiveBitmap(
        item: MediaItem,
        entryName: String,
        width: Int,
        height: Int,
        archivePath: String? = null,
    ): Bitmap? = content.decodeArchivePage(
        item = item,
        entryName = entryName,
        storage = storageFor(requireLibrary(item.libraryId)),
        targetWidth = width,
        targetHeight = height,
        archivePath = archivePath ?: item.relativePath,
    )

    suspend fun oversizedBitmap(item: MediaItem, relativePath: String): Bitmap? =
        content.decodeOversizedImage(relativePath, storageFor(requireLibrary(item.libraryId)))

    suspend fun pages(item: MediaItem): List<ImagePage> {
        val storage = storageFor(requireLibrary(item.libraryId))
        val plan = _editionPlans.value[planKey(item.libraryId, item.id)]
            ?: return content.imageSetPages(item, storage)
        val parents = plan.mapNotNull { page ->
            page.relativePath?.takeIf { page.archiveEntry == null }?.substringBeforeLast('/')
        }.distinct()
        val listings = parents.associateWith { parent ->
            runCatching { storage.list(parent) }.getOrDefault(emptyList())
                .associate { it.relativePath to it.uri }
        }
        return plan.map { page ->
            val path = page.relativePath
            if (page.archiveEntry != null || path == null) {
                page
            } else {
                page.copy(uri = listings[path.substringBeforeLast('/')]?.get(path))
            }
        }
    }

    private fun MediaItem.withPortableMetadata(portable: PortableItemMetadata): MediaItem = copy(
        domain = portable.domain ?: domain,
        displayTitle = portable.displayTitle,
        originalTitle = portable.originalTitle,
        authors = portable.authors,
        tags = portable.tags,
        collections = portable.collections,
        series = portable.series,
        coverPath = portable.coverPath,
        secondaryPath = portable.secondaryPath,
        contentHash = portable.contentHash,
        favorite = portable.favorite,
        fieldSources = portable.fieldSources,
        revision = portable.revision,
    )

    private fun synchronizeSeriesProjection(
        libraryId: String,
        portable: Collection<PortableItemMetadata>,
    ) {
        val canonical = portable.mapNotNull(PortableItemMetadata::series)
        if (canonical.isEmpty()) return
        val byId = canonical.associateBy { it.id }
        val byTitle = canonical.associateBy { it.title.trim().lowercase(java.util.Locale.ROOT) }
        database.media(libraryId).forEach { item ->
            val current = item.series ?: return@forEach
            val resolved = byId[current.id]
                ?: byTitle[current.title.trim().lowercase(java.util.Locale.ROOT)]
                ?: return@forEach
            if (current.id != resolved.id || current.title != resolved.title) {
                database.upsertMedia(item.copy(series = current.copy(id = resolved.id, title = resolved.title)))
            }
        }
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
        PortableInboxStore(storage).removeWorks(item.libraryId, setOf(item.id))
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

    private suspend fun <T> withPortableWrite(block: suspend () -> T): T =
        portableWriteMutex.withLock { onIo(block) }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        const val SCAN_TAG = "GalleryScanner"
        const val SCAN_LOG_DIRECTORY_INTERVAL = 100
        const val ENRICHMENT_BATCH_SIZE = 24
        /** How long a second attach waits for the winner to publish `library.json`. */
        const val INITIALIZATION_WAIT_ATTEMPTS = 8
        const val INITIALIZATION_WAIT_MILLIS = 250L
    }
}
