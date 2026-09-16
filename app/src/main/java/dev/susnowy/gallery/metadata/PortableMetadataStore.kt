package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.library.LibraryDocumentAccess
import dev.susnowy.gallery.library.PortableDocumentWriter
import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.PortableCatalog
import dev.susnowy.gallery.model.PortableItemMetadata
import dev.susnowy.gallery.model.PortableProgress
import dev.susnowy.gallery.model.PortableState
import dev.susnowy.gallery.model.PortableTrashEntry
import dev.susnowy.gallery.model.UnsupportedSchemaException
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
                .also {
                    require(it.libraryId == libraryId) { "Catalog 不属于当前 Library" }
                    requireSupportedSchema(it.schemaVersion)
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
        val now = Instant.now().toString()
        val metadata = updates.map { (item, expectedRevision) ->
            val existing = catalog.items.firstOrNull { it.id == item.id }
                ?: catalog.items.firstOrNull { it.relativePath == item.relativePath }
            if (existing != null && existing.revision != expectedRevision) {
                throw RevisionConflictException(
                    "${item.displayTitle} 已被其他设备修改（磁盘 ${existing.revision}，本机 $expectedRevision）",
                )
            }
            item.toPortableMetadata(
                revision = (existing?.revision ?: 0) + 1,
                updatedAt = now,
                // Provenance is sticky: a caller that does not mention a field keeps
                // whatever the disk already recorded, so a relocation or cover
                // refresh cannot silently drop a human's manual lock.
                fieldSources = existing?.fieldSources.orEmpty() + item.fieldSources,
            )
        }
        val updatedIds = metadata.mapTo(mutableSetOf(), PortableItemMetadata::id)
        val updatedPaths = metadata.mapTo(mutableSetOf(), PortableItemMetadata::relativePath)
        val remaining = catalog.items.filterNot { it.id in updatedIds || it.relativePath in updatedPaths }
        val updated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            items = (remaining + metadata).sortedBy(PortableItemMetadata::relativePath),
        )
        writeSafely(CATALOG_PATH, json.encodeToString(updated), "application/json")
        return metadata
    }

    fun loadState(libraryId: String): PortableState = read(STATE_PATH)?.let { text ->
        runCatching { json.decodeFromString<PortableState>(text) }
            .getOrElse { throw SerializationException("state.json 无法解析", it) }
            .also {
                require(it.libraryId == libraryId) { "State 不属于当前 Library" }
                requireSupportedSchema(it.schemaVersion)
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
        val catalogUpdated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            items = catalog.items.filterNot { it.id == item.id },
        )
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
        val existing = catalog.items.firstOrNull { it.id == itemId } ?: return false
        val now = Instant.now().toString()
        val relocated = existing.copy(
            relativePath = target,
            coverPath = existing.coverPath?.replacePathPrefix(source, target),
            secondaryPath = secondaryTarget ?: existing.secondaryPath?.replacePathPrefix(source, target),
            revision = existing.revision + 1,
            updatedAt = now,
        )
        val updated = catalog.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            revision = catalog.revision + 1,
            updatedAt = now,
            items = catalog.items.map { if (it.id == itemId) relocated else it },
        )
        writeSafely(CATALOG_PATH, json.encodeToString(updated), "application/json")
        return true
    }

    private fun read(path: String): String? = writer.read(path)

    private fun writeSafely(path: String, text: String, mimeType: String) =
        writer.write(path, text, mimeType)

    private fun requireSupportedSchema(schemaVersion: Int) {
        if (schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaException(
                "Library 元数据 Schema v$schemaVersion 高于本客户端支持的版本，已拒绝写入",
            )
        }
    }

    private fun MediaItem.toPortableMetadata(
        revision: Long,
        updatedAt: String,
        fieldSources: Map<String, String>,
    ) =
        PortableItemMetadata(
            id = id,
            relativePath = relativePath,
            type = kind,
            displayTitle = displayTitle,
            originalTitle = originalTitle,
            source = sourceKind,
            authors = authors,
            tags = tags,
            collections = collections,
            series = series,
            coverPath = coverPath,
            secondaryPath = secondaryPath,
            contentHash = contentHash,
            favorite = favorite,
            fieldSources = fieldSources,
            revision = revision,
            updatedAt = updatedAt,
        )

    private fun String.replacePathPrefix(source: String, target: String): String = when {
        this == source -> target
        startsWith("$source/") -> target + removePrefix(source)
        else -> this
    }

    companion object {
        const val CATALOG_PATH = ".gallery/items/catalog.json"
        const val STATE_PATH = ".gallery/state/state.json"
    }
}
