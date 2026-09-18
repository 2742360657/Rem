package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.library.LibraryDocumentAccess
import dev.susnowy.gallery.library.PortableDocumentWriter
import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.EditionAssetRole
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.PortableAsset
import dev.susnowy.gallery.model.PortableCatalog
import dev.susnowy.gallery.model.PortableEdition
import dev.susnowy.gallery.model.PortableEditionAsset
import dev.susnowy.gallery.model.PortableGroup
import dev.susnowy.gallery.model.PortableGroupMember
import dev.susnowy.gallery.model.PortableItemMetadata
import dev.susnowy.gallery.model.PortableProgress
import dev.susnowy.gallery.model.PortableSeries
import dev.susnowy.gallery.model.PortableSeriesMember
import dev.susnowy.gallery.model.PortableState
import dev.susnowy.gallery.model.PortableTrashEntry
import dev.susnowy.gallery.model.PortableWork
import dev.susnowy.gallery.model.SeriesRef
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.model.UnsupportedSchemaException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RevisionConflictException(message: String) : IllegalStateException(message)

class PortableMetadataStore(
    private val access: LibraryDocumentAccess,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    private val writer = PortableDocumentWriter(access)

    fun loadCatalog(libraryId: String): PortableCatalog =
        read(CATALOG_PATH)?.let { text ->
            runCatching { json.decodeFromString<PortableCatalog>(text) }
                .getOrElse { throw SerializationException("catalog.json 无法解析", it) }
                .also { catalog ->
                    require(catalog.libraryId == libraryId) { "Catalog 不属于当前 Library" }
                    requireCurrentSchema(catalog.schemaVersion)
                    validate(catalog)
                }
        } ?: PortableCatalog(
            libraryId = libraryId,
            updatedAt = Instant.EPOCH.toString(),
        )

    fun saveItem(item: MediaItem, expectedRevision: Long): PortableItemMetadata {
        return saveItemUpdates(listOf(item to expectedRevision)).single()
    }

    fun saveItems(items: List<MediaItem>): List<PortableItemMetadata> =
        saveItemUpdates(items.map { it to it.revision })

    private fun saveItemUpdates(updates: List<Pair<MediaItem, Long>>): List<PortableItemMetadata> {
        if (updates.isEmpty()) return emptyList()
        val libraryId = updates.first().first.libraryId
        require(updates.all { it.first.libraryId == libraryId }) { "不能跨 Library 批量修改元数据" }
        require(updates.map { it.first.id }.distinct().size == updates.size) { "批量修改包含重复媒体" }
        require(updates.map { it.first.relativePath }.distinct().size == updates.size) {
            "批量修改包含重复媒体路径"
        }

        val catalog = loadCatalog(libraryId)
        val previousItems = catalog.items
        val now = Instant.now().toString()
        val assets = catalog.assets.toMutableList()
        val works = catalog.works.toMutableList()
        val editions = catalog.editions.toMutableList()

        updates.forEach { (item, expectedRevision) ->
            val previous = previousItems.firstOrNull { it.id == item.id }
                ?: previousItems.firstOrNull { it.relativePath == item.relativePath }
            if (previous != null && previous.revision != expectedRevision) {
                throw RevisionConflictException(
                    "${item.displayTitle} 已被其他设备修改（磁盘 ${previous.revision}，本机 $expectedRevision）",
                )
            }
            val existingWork = previous?.let { old -> works.firstOrNull { it.id == old.id } }
            val existingEdition = existingWork?.let { work ->
                editions.firstOrNull { it.id == work.preferredEditionId }
                    ?: editions.filter { it.workId == work.id }.minByOrNull(PortableEdition::id)
            }
            val existingMember = existingEdition?.assets?.firstOrNull { it.role == EditionAssetRole.PRIMARY }
                ?: existingEdition?.assets?.firstOrNull()
            val existingAsset = existingMember?.let { member -> assets.firstOrNull { it.id == member.assetId } }
            val assetId = existingAsset?.id ?: stableId("asset", libraryId, item.id)
            val editionId = existingEdition?.id ?: stableId("edition", libraryId, item.id)

            assets.replaceById(
                PortableAsset(
                    id = assetId,
                    relativePath = item.relativePath,
                    mediaType = item.kind,
                    source = item.sourceKind,
                    secondaryPath = item.secondaryPath,
                    contentHash = item.contentHash,
                    revision = (existingAsset?.revision ?: 0) + 1,
                    updatedAt = now,
                ),
            )
            editions.replaceById(
                PortableEdition(
                    id = editionId,
                    workId = item.id,
                    label = existingEdition?.label,
                    assets = if (existingEdition == null) {
                        listOf(PortableEditionAsset(assetId = assetId))
                    } else {
                        existingEdition.assets.map { member ->
                            if (member.assetId == existingMember?.assetId) member.copy(assetId = assetId) else member
                        }.ifEmpty { listOf(PortableEditionAsset(assetId = assetId)) }
                    },
                    revision = (existingEdition?.revision ?: 0) + 1,
                    updatedAt = now,
                ),
            )
            works.replaceById(
                PortableWork(
                    id = item.id,
                    type = item.kind,
                    domain = item.domain,
                    displayTitle = item.displayTitle,
                    originalTitle = item.originalTitle,
                    authors = item.authors,
                    tags = item.tags,
                    collections = item.collections,
                    preferredEditionId = editionId,
                    coverPath = item.coverPath,
                    favorite = item.favorite,
                    fieldSources = existingWork?.fieldSources.orEmpty() + item.fieldSources,
                    revision = (existingWork?.revision ?: 0) + 1,
                    updatedAt = now,
                ),
            )
        }

        val updatedIds = updates.mapTo(mutableSetOf()) { it.first.id }
        val modifiedSeriesIds = mutableSetOf<String>()
        val series = catalog.series.map { sequence ->
            val members = sequence.members.filterNot { it.workId in updatedIds }
            if (members.size == sequence.members.size) {
                sequence
            } else {
                modifiedSeriesIds += sequence.id
                sequence.copy(
                    members = members,
                    revision = sequence.revision + 1,
                    updatedAt = now,
                )
            }
        }.toMutableList()
        updates.forEach { (item, _) ->
            val assignment = item.series ?: return@forEach
            // Identity is the id, never the title: two Series may legitimately share a name, and
            // matching by title would silently merge their members, numbering and progress.
            val index = series.indexOfFirst { sequence -> sequence.id == assignment.id }
            val current = series.getOrNull(index)
            val seriesId = current?.id ?: assignment.id
            val replacement = PortableSeries(
                id = seriesId,
                title = assignment.title.trim(),
                aliases = current?.aliases.orEmpty(),
                members = current?.members.orEmpty() + PortableSeriesMember(
                    workId = item.id,
                    sortIndex = assignment.sortIndex,
                    season = assignment.season,
                    episode = assignment.episode,
                    volume = assignment.volume,
                    chapter = assignment.chapter,
                ),
                fieldSources = current?.fieldSources.orEmpty(),
                revision = when {
                    current == null -> 1
                    seriesId in modifiedSeriesIds -> current.revision
                    else -> current.revision + 1
                },
                updatedAt = now,
            )
            modifiedSeriesIds += seriesId
            if (index >= 0) series[index] = replacement else series += replacement
        }

        val updated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            assets = assets.sortedBy(PortableAsset::relativePath),
            works = works.sortedBy(PortableWork::id),
            editions = editions.sortedBy(PortableEdition::id),
            series = series.filter { it.members.isNotEmpty() }
                .map { it.copy(members = it.members.sortedWith(seriesMemberOrder())) }
                .sortedBy(PortableSeries::id),
        )
        validate(updated)
        writeSafely(CATALOG_PATH, json.encodeToString(updated), "application/json")
        return updated.items.filter { it.id in updatedIds }.sortedBy { item ->
            updates.indexOfFirst { it.first.id == item.id }
        }
    }

    fun loadState(libraryId: String): PortableState = read(STATE_PATH)?.let { text ->
        runCatching { json.decodeFromString<PortableState>(text) }
            .getOrElse { throw SerializationException("state.json 无法解析", it) }
            .also {
                require(it.libraryId == libraryId) { "State 不属于当前 Library" }
                requireCurrentSchema(it.schemaVersion)
                validate(it)
            }
    } ?: PortableState(libraryId = libraryId, updatedAt = Instant.EPOCH.toString())

    fun saveProgress(libraryId: String, progress: PlaybackProgress) {
        val state = loadState(libraryId)
        val now = Instant.now()
        val portable = PortableProgress(
            itemId = progress.itemId,
            page = progress.page,
            positionMs = progress.positionMs,
            finished = progress.finished,
            lastOpenedAt = Instant.ofEpochMilli(progress.lastOpenedAt).toString(),
            openedAt = progress.openedAt?.let { Instant.ofEpochMilli(it).toString() },
        )
        val updated = state.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = state.revision + 1,
            updatedAt = now.toString(),
            progress = (state.progress.filterNot { it.itemId == progress.itemId } + portable)
                .sortedBy(PortableProgress::itemId),
        )
        writeSafely(STATE_PATH, json.encodeToString(updated), "application/json")
    }

    fun setTrashed(item: MediaItem, trashed: Boolean, deletedAt: Long = System.currentTimeMillis()) {
        setTrashed(listOf(item), trashed, deletedAt)
    }

    fun setTrashed(
        items: List<MediaItem>,
        trashed: Boolean,
        deletedAt: Long = System.currentTimeMillis(),
    ) {
        if (items.isEmpty()) return
        val libraryId = items.first().libraryId
        require(items.all { it.libraryId == libraryId }) { "不能跨 Library 批量修改回收站状态" }
        val itemIds = items.mapTo(mutableSetOf(), MediaItem::id)
        val state = loadState(libraryId)
        val remaining = state.trash.filterNot { it.itemId in itemIds }
        val trash = if (trashed) {
            remaining + items.distinctBy(MediaItem::id).map { item ->
                PortableTrashEntry(
                    itemId = item.id,
                    relativePath = item.relativePath,
                    deletedAt = Instant.ofEpochMilli(deletedAt).toString(),
                )
            }
        } else remaining
        val updated = state.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = state.revision + 1,
            updatedAt = Instant.now().toString(),
            trash = trash.sortedBy(PortableTrashEntry::deletedAt),
        )
        writeSafely(STATE_PATH, json.encodeToString(updated), "application/json")
    }

    fun removeItem(item: MediaItem) {
        val now = Instant.now().toString()
        val catalog = loadCatalog(item.libraryId)
        val removedEditionIds = catalog.editions.filter { it.workId == item.id }
            .mapTo(mutableSetOf(), PortableEdition::id)
        val removedAssetIds = catalog.editions.filter { it.id in removedEditionIds }
            .flatMapTo(mutableSetOf()) { edition -> edition.assets.map(PortableEditionAsset::assetId) }
        val remainingEditions = catalog.editions.filterNot { it.id in removedEditionIds }
        val stillReferencedAssets = remainingEditions.flatMapTo(mutableSetOf()) { edition ->
            edition.assets.map(PortableEditionAsset::assetId)
        }
        val catalogUpdated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            assets = catalog.assets.filterNot { it.id in removedAssetIds && it.id !in stillReferencedAssets },
            works = catalog.works.filterNot { it.id == item.id },
            editions = remainingEditions,
            groups = catalog.groups.mapNotNull { group ->
                val members = group.members.filterNot { it.workId == item.id }
                when {
                    members.size == group.members.size -> group
                    members.isEmpty() -> null
                    else -> group.copy(
                        members = members,
                        coverWorkId = group.coverWorkId?.takeUnless { it == item.id },
                        revision = group.revision + 1,
                        updatedAt = now,
                    )
                }
            },
            series = catalog.series.mapNotNull { sequence ->
                val members = sequence.members.filterNot { it.workId == item.id }
                when {
                    members.size == sequence.members.size -> sequence
                    members.isEmpty() -> null
                    else -> sequence.copy(
                        members = members,
                        revision = sequence.revision + 1,
                        updatedAt = now,
                    )
                }
            },
        )
        validate(catalogUpdated)
        writeSafely(CATALOG_PATH, json.encodeToString(catalogUpdated), "application/json")

        val state = loadState(item.libraryId)
        val stateUpdated = state.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = state.revision + 1,
            updatedAt = now,
            progress = state.progress.filterNot { it.itemId == item.id },
            trash = state.trash.filterNot { it.itemId == item.id },
        )
        writeSafely(STATE_PATH, json.encodeToString(stateUpdated), "application/json")
    }

    fun createBackup(label: String): List<String> {
        val safeLabel = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return buildList {
            read(CATALOG_PATH)?.let { catalog ->
                val path = ".gallery/backups/$safeLabel-catalog.json"
                writeSafely(path, catalog, "application/json")
                add(path)
            }
            read(STATE_PATH)?.let { state ->
                val path = ".gallery/backups/$safeLabel-state.json"
                writeSafely(path, state, "application/json")
                add(path)
            }
            // Inbox decisions are user truth too; a high-risk batch must be able to put
            // them back exactly as they were.
            read(PortableInboxStore.PATH)?.let { inbox ->
                val path = ".gallery/backups/$safeLabel-inbox.json"
                writeSafely(path, inbox, "application/json")
                add(path)
            }
        }
    }

    fun relocateItem(
        libraryId: String,
        itemId: String,
        source: String,
        target: String,
        secondaryTarget: String? = null,
    ): Boolean {
        val catalog = loadCatalog(libraryId)
        val work = catalog.works.firstOrNull { it.id == itemId } ?: return false
        val edition = catalog.editions.firstOrNull { it.id == work.preferredEditionId }
            ?: catalog.editions.filter { it.workId == itemId }.minByOrNull(PortableEdition::id)
            ?: return false
        val primary = edition.assets.firstOrNull { it.role == EditionAssetRole.PRIMARY }
            ?: edition.assets.firstOrNull()
            ?: return false
        val asset = catalog.assets.firstOrNull { it.id == primary.assetId } ?: return false
        val now = Instant.now().toString()
        val updated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            assets = catalog.assets.map {
                if (it.id == asset.id) it.copy(
                    relativePath = target,
                    secondaryPath = secondaryTarget ?: it.secondaryPath?.replacePathPrefix(source, target),
                    revision = it.revision + 1,
                    updatedAt = now,
                ) else it
            },
            works = catalog.works.map {
                if (it.id == itemId) it.copy(
                    coverPath = it.coverPath?.replacePathPrefix(source, target),
                    revision = it.revision + 1,
                    updatedAt = now,
                ) else it
            },
        )
        validate(updated)
        writeSafely(CATALOG_PATH, json.encodeToString(updated), "application/json")
        return true
    }

    /**
     * Creates or replaces a Group.
     *
     * A Group is the portable way to say "these Works are browsed together". It lives in
     * `catalog.json` next to Works so the decision travels with the Library, and the whole
     * catalog is rewritten for that one relation; that is why group editing stays an
     * explicit user action rather than something a scan does.
     *
     * Replacing a Group never touches media, and removing a member only removes the
     * relationship.
     */
    fun upsertGroup(
        libraryId: String,
        group: PortableGroup,
        expectedRevision: Long? = null,
    ): PortableGroup {
        val catalog = loadCatalog(libraryId)
        val existing = catalog.groups.firstOrNull { it.id == group.id }
        if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
            throw RevisionConflictException(
                "${existing.title} 已被其他设备修改（磁盘 ${existing.revision}，本机 $expectedRevision）",
            )
        }
        require(group.title.isNotBlank()) { "Group 标题不能为空" }
        val now = Instant.now().toString()
        val title = group.title.trim()
        val replaced = group.copy(
            title = title,
            members = group.members.distinctBy(PortableGroupMember::workId),
            // Removing the old cover member clears that selection in the same action.
            // An unrelated non-member cover is still rejected by validation.
            coverWorkId = group.coverWorkId?.takeUnless { cover ->
                existing?.coverWorkId == cover &&
                    existing.members.any { it.workId == cover } &&
                    group.members.none { it.workId == cover }
            },
            // A group's own editable fields keep their provenance: an existing value an agent
            // set stays automatic, while a title the user changed becomes manual.
            fieldSources = existing?.fieldSources.orEmpty() + group.fieldSources +
                if (existing != null && existing.title != title) {
                    mapOf(MANUAL_TITLE_FIELD to FieldSource.MANUAL)
                } else {
                    emptyMap()
                },
            revision = (existing?.revision ?: 0) + 1,
            updatedAt = now,
        )
        val updated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            groups = (catalog.groups.filterNot { it.id == group.id } + replaced)
                .sortedBy(PortableGroup::id),
        )
        validate(updated)
        writeSafely(CATALOG_PATH, json.encodeToString(updated), "application/json")
        return replaced
    }

    /** Removes the browsing relationship only; Works, Editions and media stay untouched. */
    fun deleteGroup(libraryId: String, groupId: String): Boolean {
        val catalog = loadCatalog(libraryId)
        if (catalog.groups.none { it.id == groupId }) return false
        val now = Instant.now().toString()
        val updated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            groups = catalog.groups.filterNot { it.id == groupId },
        )
        validate(updated)
        writeSafely(CATALOG_PATH, json.encodeToString(updated), "application/json")
        return true
    }

    /**
     * Creates or replaces one Edition.
     *
     * Used for virtual merged Editions: the members are page-level references
     * (`entry_path` inside a directory or archive) across several Assets, which is exactly
     * what the model allows. Nothing here moves or rewrites media, and [prefer] can make the
     * new Edition the Work's default in the same write instead of a second catalog rewrite.
     */
    fun upsertEdition(
        libraryId: String,
        edition: PortableEdition,
        expectedRevision: Long? = null,
        prefer: Boolean = false,
    ): PortableEdition {
        val catalog = loadCatalog(libraryId)
        val existing = catalog.editions.firstOrNull { it.id == edition.id }
        if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
            throw RevisionConflictException(
                "版本 ${existing.label ?: existing.id} 已被其他设备修改（磁盘 ${existing.revision}，本机 $expectedRevision）",
            )
        }
        require(edition.assets.isNotEmpty()) { "Edition 至少需要一个来源" }
        require(catalog.works.any { it.id == edition.workId }) { "Edition 必须属于一个已存在的 Work" }
        val now = Instant.now().toString()
        val replaced = edition.copy(
            assets = edition.assets.mapIndexed { index, member ->
                member.copy(sortIndex = member.sortIndex ?: index.toDouble())
            },
            revision = (existing?.revision ?: 0) + 1,
            updatedAt = now,
        )
        val updated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            works = if (prefer) {
                catalog.works.map { work ->
                    if (work.id == edition.workId) {
                        work.copy(
                            preferredEditionId = replaced.id,
                            revision = work.revision + 1,
                            updatedAt = now,
                        )
                    } else {
                        work
                    }
                }
            } else {
                catalog.works
            },
            editions = (catalog.editions.filterNot { it.id == edition.id } + replaced)
                .sortedBy(PortableEdition::id),
        )
        validate(updated)
        writeSafely(CATALOG_PATH, json.encodeToString(updated), "application/json")
        return replaced
    }

    /**
     * Creates or replaces one Series in a single catalog write.
     *
     * Series membership is a user decision: when the app reorders or renames a series it also
     * stamps `field_sources.series = manual` on every touched Work, so a later scan cannot
     * pull that Work back out through filename or folder recognition.
     */
    fun upsertSeries(
        libraryId: String,
        series: PortableSeries,
        expectedRevision: Long? = null,
        markSeriesManualFor: Set<String> = emptySet(),
    ): PortableSeries {
        val catalog = loadCatalog(libraryId)
        val existing = catalog.series.firstOrNull { it.id == series.id }
        if (expectedRevision != null && existing != null && existing.revision != expectedRevision) {
            throw RevisionConflictException(
                "${existing.title} 已被其他设备修改（磁盘 ${existing.revision}，本机 $expectedRevision）",
            )
        }
        require(series.title.isNotBlank()) { "Series 标题不能为空" }
        require(series.members.isNotEmpty()) { "Series 至少需要一个成员" }
        val now = Instant.now().toString()
        val replaced = series.copy(
            title = series.title.trim(),
            members = series.members.distinctBy(PortableSeriesMember::workId)
                .sortedWith(seriesMemberOrder()),
            revision = (existing?.revision ?: 0) + 1,
            updatedAt = now,
        )
        val updated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            works = if (markSeriesManualFor.isEmpty()) {
                catalog.works
            } else {
                catalog.works.map { work ->
                    if (work.id in markSeriesManualFor) {
                        work.copy(
                            fieldSources = work.fieldSources + (MetadataField.SERIES to FieldSource.MANUAL),
                            revision = work.revision + 1,
                            updatedAt = now,
                        )
                    } else {
                        work
                    }
                }
            },
            series = (catalog.series.filterNot { it.id == series.id } + replaced)
                .sortedBy(PortableSeries::id),
        )
        validate(updated)
        writeSafely(CATALOG_PATH, json.encodeToString(updated), "application/json")
        return replaced
    }

    /**
     * Removes a Series entity. Its Works stay exactly as they are and simply stop having a
     * series assignment; media, Editions and Assets are untouched.
     */
    fun deleteSeries(libraryId: String, seriesId: String): Boolean {
        val catalog = loadCatalog(libraryId)
        if (catalog.series.none { it.id == seriesId }) return false
        val now = Instant.now().toString()
        val updated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            series = catalog.series.filterNot { it.id == seriesId },
        )
        validate(updated)
        writeSafely(CATALOG_PATH, json.encodeToString(updated), "application/json")
        return true
    }

    /**
     * One-time pre-release conversion. Normal reads accept only v4; this converter is
     * invoked after all v3 documents have been snapshotted. It is idempotent so a stop
     * between the catalog and state commits can resume without a compatibility branch.
     */
    fun migrateV3ToV4(libraryId: String) {
        val now = Instant.now().toString()
        read(CATALOG_PATH)?.let { text ->
            when (declaredSchema(text)) {
                CURRENT_SCHEMA_VERSION -> json.decodeFromString<PortableCatalog>(text).also { catalog ->
                    require(catalog.libraryId == libraryId) { "Catalog 不属于当前 Library" }
                    validate(catalog)
                }
                3 -> {
                    val legacy = json.decodeFromString<LegacyCatalog>(text)
                    require(legacy.libraryId == libraryId) { "Catalog 不属于当前 Library" }
                    val assets = legacy.items.map { item ->
                        PortableAsset(
                            id = stableId("asset", libraryId, item.id),
                            relativePath = item.relativePath,
                            mediaType = item.type,
                            source = item.source,
                            secondaryPath = item.secondaryPath,
                            contentHash = item.contentHash,
                            revision = item.revision,
                            updatedAt = item.updatedAt,
                        )
                    }
                    val editions = legacy.items.map { item ->
                        PortableEdition(
                            id = stableId("edition", libraryId, item.id),
                            workId = item.id,
                            assets = listOf(
                                PortableEditionAsset(stableId("asset", libraryId, item.id)),
                            ),
                            revision = item.revision,
                            updatedAt = item.updatedAt,
                        )
                    }
                    val works = legacy.items.map { item ->
                        PortableWork(
                            id = item.id,
                            type = item.type,
                            domain = item.domain ?: MediaDomain.CLASSIFIED,
                            displayTitle = item.displayTitle,
                            originalTitle = item.originalTitle,
                            authors = item.authors,
                            tags = item.tags,
                            collections = item.collections,
                            preferredEditionId = stableId("edition", libraryId, item.id),
                            coverPath = item.coverPath,
                            favorite = item.favorite,
                            fieldSources = item.fieldSources,
                            revision = item.revision,
                            updatedAt = item.updatedAt,
                        )
                    }
                    // One Series entity per reference id. Grouping by title here would merge two
                    // legitimately same-named series during the one-time v3 conversion, and the
                    // converted catalog is portable truth from then on.
                    val series = legacy.items.mapNotNull { item -> item.series?.let { it to item.id } }
                        .groupBy { (reference, _) -> reference.id }
                        .map { (id, assignments) ->
                            PortableSeries(
                                id = id,
                                title = assignments.first().first.title,
                                members = assignments.map { (reference, workId) ->
                                    PortableSeriesMember(
                                        workId = workId,
                                        sortIndex = reference.sortIndex,
                                        season = reference.season,
                                        episode = reference.episode,
                                        volume = reference.volume,
                                        chapter = reference.chapter,
                                    )
                                }.sortedWith(seriesMemberOrder()),
                                updatedAt = now,
                            )
                        }
                    val migrated = PortableCatalog(
                        libraryId = libraryId,
                        revision = legacy.revision + 1,
                        updatedAt = now,
                        assets = assets.sortedBy(PortableAsset::relativePath),
                        works = works.sortedBy(PortableWork::id),
                        editions = editions.sortedBy(PortableEdition::id),
                        series = series.sortedBy(PortableSeries::id),
                    )
                    validate(migrated)
                    writeSafely(CATALOG_PATH, json.encodeToString(migrated), "application/json")
                }
                else -> throw UnsupportedSchemaException("只支持把测试期 Schema v3 转换为 v4")
            }
        }
        read(STATE_PATH)?.let { text ->
            when (declaredSchema(text)) {
                CURRENT_SCHEMA_VERSION -> json.decodeFromString<PortableState>(text).also { state ->
                    require(state.libraryId == libraryId) { "State 不属于当前 Library" }
                    validate(state)
                }
                3 -> {
                    val legacy = json.decodeFromString<LegacyState>(text)
                    require(legacy.libraryId == libraryId) { "State 不属于当前 Library" }
                    val migrated = PortableState(
                        libraryId = libraryId,
                        revision = legacy.revision + 1,
                        updatedAt = now,
                        progress = legacy.progress.map { progress ->
                            PortableProgress(
                                itemId = progress.itemId,
                                page = progress.page,
                                positionMs = progress.positionMs,
                                finished = progress.finished,
                                lastOpenedAt = progress.lastOpenedAt,
                            )
                        },
                        trash = legacy.trash.map { trash ->
                            PortableTrashEntry(
                                itemId = trash.itemId,
                                relativePath = trash.relativePath,
                                deletedAt = trash.deletedAt,
                            )
                        },
                    )
                    validate(migrated)
                    writeSafely(STATE_PATH, json.encodeToString(migrated), "application/json")
                }
                else -> throw UnsupportedSchemaException("只支持把测试期 Schema v3 转换为 v4")
            }
        }
    }

    private fun validate(catalog: PortableCatalog) =
        dev.susnowy.gallery.portable.PortableValidation.validate(catalog)

    private fun validate(state: PortableState) =
        dev.susnowy.gallery.portable.PortableValidation.validate(state)

    private fun declaredSchema(text: String): Int? = runCatching {
        json.parseToJsonElement(text).jsonObject["schema_version"]?.jsonPrimitive?.intOrNull
    }.getOrNull()

    private fun requireCurrentSchema(schemaVersion: Int) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaException(
                if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                    "Library 元数据 Schema v$schemaVersion 高于本客户端支持的版本，已拒绝写入"
                } else {
                    "Library 元数据仍是测试期 Schema v$schemaVersion，请先完成 Library 升级"
                },
            )
        }
    }

    private fun read(path: String): String? = writer.read(path)

    private fun writeSafely(path: String, text: String, mimeType: String) =
        writer.write(path, text, mimeType)

    private fun String.replacePathPrefix(source: String, target: String): String = when {
        this == source -> target
        startsWith("$source/") -> target + removePrefix(source)
        else -> this
    }

    private fun <T> MutableList<T>.replaceById(value: T) {
        val id = when (value) {
            is PortableAsset -> value.id
            is PortableWork -> value.id
            is PortableEdition -> value.id
            else -> error("不支持的便携实体")
        }
        val index = indexOfFirst { current ->
            when (current) {
                is PortableAsset -> current.id == id
                is PortableWork -> current.id == id
                is PortableEdition -> current.id == id
                else -> false
            }
        }
        if (index >= 0) this[index] = value else add(value)
    }

    companion object {
        const val CATALOG_PATH = ".gallery/items/catalog.json"
        const val STATE_PATH = ".gallery/state/state.json"

        /** Field name used when the user renames a Group from the shelf editor. */
        const val MANUAL_TITLE_FIELD = "title"

        private fun stableId(kind: String, libraryId: String, workId: String): String =
            UUID.nameUUIDFromBytes("$kind:$libraryId:$workId".toByteArray(StandardCharsets.UTF_8)).toString()

        private fun seriesMemberOrder(): Comparator<PortableSeriesMember> =
            compareBy<PortableSeriesMember> { it.sortIndex ?: Double.MAX_VALUE }
                .thenBy { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Double.MAX_VALUE }
                .thenBy { it.volume ?: Double.MAX_VALUE }
                .thenBy { it.chapter ?: Double.MAX_VALUE }
                .thenBy(PortableSeriesMember::workId)
    }
}

@Serializable
private data class LegacyCatalog(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("library_id") val libraryId: String,
    val revision: Long = 0,
    @SerialName("updated_at") val updatedAt: String,
    val items: List<LegacyItem> = emptyList(),
)

@Serializable
private data class LegacyItem(
    val id: String,
    @SerialName("relative_path") val relativePath: String,
    val type: MediaKind,
    val domain: MediaDomain? = null,
    @SerialName("display_title") val displayTitle: String,
    @SerialName("original_title") val originalTitle: String? = null,
    val source: SourceKind,
    val authors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val series: SeriesRef? = null,
    @SerialName("cover_path") val coverPath: String? = null,
    @SerialName("secondary_path") val secondaryPath: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    val favorite: Boolean = false,
    @SerialName("field_sources") val fieldSources: Map<String, String> = emptyMap(),
    val revision: Long = 1,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class LegacyState(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("library_id") val libraryId: String,
    val revision: Long = 0,
    @SerialName("updated_at") val updatedAt: String,
    val progress: List<LegacyProgress> = emptyList(),
    val trash: List<LegacyTrash> = emptyList(),
)

@Serializable
private data class LegacyProgress(
    @SerialName("item_id") val itemId: String,
    val page: Int = 0,
    @SerialName("position_ms") val positionMs: Long = 0,
    val finished: Boolean = false,
    @SerialName("last_opened_at") val lastOpenedAt: String,
)

@Serializable
private data class LegacyTrash(
    @SerialName("item_id") val itemId: String,
    @SerialName("relative_path") val relativePath: String,
    @SerialName("deleted_at") val deletedAt: String,
)
