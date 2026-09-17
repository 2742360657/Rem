package dev.susnowy.gallery.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import dev.susnowy.gallery.model.DiscoveredEntry
import dev.susnowy.gallery.model.DiscoveryReason
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PermissionState
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.SeriesRef
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.scanner.ScannedFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GalleryDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    private val json = Json { ignoreUnknownKeys = true }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE libraries (
                library_id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                tree_uri TEXT NOT NULL,
                permission_state TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                last_scan_at INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE media (
                id TEXT PRIMARY KEY NOT NULL,
                library_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                uri TEXT NOT NULL,
                kind TEXT NOT NULL,
                domain TEXT NOT NULL,
                source_kind TEXT NOT NULL,
                display_title TEXT NOT NULL,
                original_title TEXT,
                mime_type TEXT,
                size INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                content_hash TEXT,
                captured_at INTEGER,
                latitude REAL,
                longitude REAL,
                page_count INTEGER,
                authors_json TEXT NOT NULL,
                tags_json TEXT NOT NULL,
                collections_json TEXT NOT NULL,
                series_json TEXT,
                cover_path TEXT,
                secondary_path TEXT,
                favorite INTEGER NOT NULL,
                in_inbox INTEGER NOT NULL,
                trashed INTEGER NOT NULL,
                deleted_at INTEGER,
                needs_repair INTEGER NOT NULL,
                revision INTEGER NOT NULL,
                field_sources_json TEXT NOT NULL,
                UNIQUE(library_id, relative_path),
                FOREIGN KEY(library_id) REFERENCES libraries(library_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE progress (
                item_id TEXT PRIMARY KEY NOT NULL,
                page INTEGER NOT NULL,
                position_ms INTEGER NOT NULL,
                finished INTEGER NOT NULL,
                last_opened_at INTEGER NOT NULL,
                FOREIGN KEY(item_id) REFERENCES media(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX media_library_kind ON media(library_id, kind)")
        db.execSQL("CREATE INDEX media_search_title ON media(display_title)")
        db.execSQL("CREATE INDEX media_trash ON media(trashed, deleted_at)")
        createDiscoveriesTable(db)
    }

    private fun createDiscoveriesTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS discoveries (
                library_id TEXT NOT NULL,
                relative_path TEXT NOT NULL,
                uri TEXT,
                is_directory INTEGER NOT NULL,
                mime_type TEXT,
                size INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                reason TEXT NOT NULL,
                PRIMARY KEY(library_id, relative_path),
                FOREIGN KEY(library_id) REFERENCES libraries(library_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE media ADD COLUMN content_hash TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE media ADD COLUMN domain TEXT NOT NULL DEFAULT 'CLASSIFIED'")
            db.execSQL("ALTER TABLE media ADD COLUMN field_sources_json TEXT NOT NULL DEFAULT '{}'")
            db.execSQL("UPDATE media SET domain = 'ALBUM' WHERE kind IN ('PHOTO', 'PHOTO_VIDEO', 'LIVE_PHOTO')")
            db.execSQL("UPDATE media SET domain = 'WORKS' WHERE kind = 'IMAGE_SET'")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE media ADD COLUMN latitude REAL")
            db.execSQL("ALTER TABLE media ADD COLUMN longitude REAL")
        }
        if (oldVersion < 5) {
            createDiscoveriesTable(db)
        }
        if (oldVersion > newVersion) {
            db.execSQL("DROP TABLE IF EXISTS progress")
            db.execSQL("DROP TABLE IF EXISTS discoveries")
            db.execSQL("DROP TABLE IF EXISTS media")
            db.execSQL("DROP TABLE IF EXISTS libraries")
            onCreate(db)
        }
    }

    @Synchronized
    fun upsertLibrary(library: LibraryRegistration) {
        val database = writableDatabase
        val values = library.toValues()
        val updated = database.update(
            "libraries",
            values,
            "library_id = ?",
            arrayOf(library.libraryId),
        )
        if (updated == 0) database.insertOrThrow("libraries", null, values)
    }

    @Synchronized
    fun libraries(): List<LibraryRegistration> = readableDatabase.query(
        "libraries",
        null,
        null,
        null,
        null,
        null,
        "name COLLATE NOCASE",
    ).use { cursor -> cursor.mapRows(::libraryFromCursor) }

    @Synchronized
    fun library(libraryId: String): LibraryRegistration? = readableDatabase.query(
        "libraries",
        null,
        "library_id = ?",
        arrayOf(libraryId),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) libraryFromCursor(cursor) else null }

    @Synchronized
    fun removeLibrary(libraryId: String) {
        writableDatabase.delete("libraries", "library_id = ?", arrayOf(libraryId))
    }

    /**
     * Makes [library] the only registration for its directory.
     *
     * Any row pointing at the same tree under a different library id is dropped first —
     * it is either a stale registration for a Library whose identity file has since been
     * replaced, or a sibling of a duplicate initialization, and either way its media rows
     * are keyed to an id that no longer exists on disk. The delete cascades to `media`,
     * whose rows the following scan rebuilds against the surviving identity.
     */
    @Synchronized
    fun claimLibraryTree(library: LibraryRegistration) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.delete(
                "libraries",
                "tree_uri = ? AND library_id != ?",
                arrayOf(library.treeUri, library.libraryId),
            )
            database.delete("libraries", "library_id = ?", arrayOf(library.libraryId))
            database.insertOrThrow("libraries", null, library.toValues())
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun upsertMedia(item: MediaItem) {
        upsertMediaRow(writableDatabase, item)
    }

    private fun upsertMediaRow(database: SQLiteDatabase, item: MediaItem) {
        val values = item.toValues()
        val updated = database.update("media", values, "id = ?", arrayOf(item.id))
        if (updated == 0) {
            // A portable metadata record can intentionally replace a local placeholder at the
            // same path. REPLACE is only used for that identity hand-off; routine updates stay
            // in-place so their progress rows are not deleted by ON DELETE CASCADE.
            database.insertWithOnConflict(
                "media",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    @Synchronized
    fun media(libraryId: String? = null): List<MediaItem> {
        val selection = libraryId?.let { "library_id = ?" }
        val args = if (libraryId == null) null else arrayOf(libraryId)
        return readableDatabase.query(
            "media",
            null,
            selection,
            args,
            null,
            null,
            "display_title COLLATE NOCASE",
        ).use { cursor -> cursor.mapRows(::mediaFromCursor) }
    }

    @Synchronized
    fun mediaItem(itemId: String): MediaItem? = readableDatabase.query(
        "media",
        null,
        "id = ?",
        arrayOf(itemId),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) mediaFromCursor(cursor) else null }

    @Synchronized
    fun discoveries(libraryId: String? = null): List<DiscoveredEntry> {
        val selection = libraryId?.let { "library_id = ?" }
        val args = if (libraryId == null) null else arrayOf(libraryId)
        return readableDatabase.query(
            "discoveries",
            null,
            selection,
            args,
            null,
            null,
            "relative_path COLLATE NOCASE",
        ).use { cursor -> cursor.mapRows(::discoveryFromCursor) }
    }

    /**
     * Only the columns a rescan needs to decide whether a file still has to be opened,
     * so the whole media table does not have to be materialized into [MediaItem] objects
     * before scanning. Mirrors [media] column for column.
     */
    @Synchronized
    fun scanSnapshot(libraryId: String): Map<String, ScannedFile> {
        val columns = arrayOf(
            "relative_path",
            "size",
            "modified_at",
            "content_hash",
            "captured_at",
            "latitude",
            "longitude",
            "page_count",
        )
        return readableDatabase.query(
            "media",
            columns,
            "library_id = ?",
            arrayOf(libraryId),
            null,
            null,
            null,
        ).use { cursor ->
            buildMap(cursor.count) {
                while (cursor.moveToNext()) {
                    val path = cursor.string("relative_path")
                    put(
                        path,
                        ScannedFile(
                            relativePath = path,
                            size = cursor.long("size"),
                            modifiedAt = cursor.long("modified_at"),
                            contentHash = cursor.nullableString("content_hash"),
                            capturedAt = cursor.nullableLong("captured_at"),
                            latitude = cursor.nullableDouble("latitude"),
                            longitude = cursor.nullableDouble("longitude"),
                            pageCount = cursor.nullableInt("page_count"),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Writes a whole scan result in one transaction.
     *
     * A scan of a large Library produces tens of thousands of rows; committing each one
     * separately costs a journal flush per row, which on a WAL database is the dominant
     * cost of indexing. Rows the scan did not find are flagged in the same transaction,
     * so a scan that fails half way cannot leave a partially updated index. Missing-file
     * detection reads only identity and path columns; it must not decode the full media
     * table (including portable metadata JSON) a second time after the repository merge.
     */
    @Synchronized
    fun replaceScannedMedia(libraryId: String, items: List<MediaItem>, foundPaths: Set<String>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            items.forEach { item -> upsertMediaRow(database, item) }
            val missing = ContentValues().apply { put("needs_repair", 1) }
            val missingIds = database.query(
                "media",
                arrayOf("id", "relative_path"),
                "library_id = ? AND trashed = 0",
                arrayOf(libraryId),
                null,
                null,
                null,
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        if (cursor.string("relative_path") !in foundPaths) {
                            add(cursor.string("id"))
                        }
                    }
                }
            }
            missingIds.forEach { itemId ->
                database.update("media", missing, "id = ?", arrayOf(itemId))
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    /** Replaces rebuildable unknown/ambiguous scan results while protecting unreadable subtrees. */
    @Synchronized
    fun replaceDiscoveries(
        libraryId: String,
        entries: List<DiscoveredEntry>,
        protectedPaths: Set<String> = emptySet(),
    ) {
        require(entries.all { it.libraryId == libraryId }) { "不能跨 Library 写入待判断索引" }
        writableDatabase.transaction {
            entries.forEach { entry ->
                insertWithOnConflict(
                    "discoveries",
                    null,
                    entry.toValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            val foundPaths = entries.mapTo(mutableSetOf(), DiscoveredEntry::relativePath) + protectedPaths
            val stalePaths = query(
                "discoveries",
                arrayOf("relative_path"),
                "library_id = ?",
                arrayOf(libraryId),
                null,
                null,
                null,
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        cursor.string("relative_path").takeIf { it !in foundPaths }?.let(::add)
                    }
                }
            }
            stalePaths.forEach { path ->
                delete(
                    "discoveries",
                    "library_id = ? AND relative_path = ?",
                    arrayOf(libraryId, path),
                )
            }
        }
    }

    @Synchronized
    fun removeMedia(itemId: String) {
        writableDatabase.delete("media", "id = ?", arrayOf(itemId))
    }

    @Synchronized
    fun clearMediaIndex(libraryId: String) {
        writableDatabase.delete("media", "library_id = ?", arrayOf(libraryId))
        writableDatabase.delete("discoveries", "library_id = ?", arrayOf(libraryId))
    }

    @Synchronized
    fun upsertProgress(progress: PlaybackProgress) {
        val values = ContentValues().apply {
            put("item_id", progress.itemId)
            put("page", progress.page)
            put("position_ms", progress.positionMs)
            put("finished", progress.finished.asInt())
            put("last_opened_at", progress.lastOpenedAt)
        }
        writableDatabase.insertWithOnConflict(
            "progress",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun progress(itemId: String): PlaybackProgress? = readableDatabase.query(
        "progress",
        null,
        "item_id = ?",
        arrayOf(itemId),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        PlaybackProgress(
            itemId = cursor.string("item_id"),
            page = cursor.int("page"),
            positionMs = cursor.long("position_ms"),
            finished = cursor.int("finished") != 0,
            lastOpenedAt = cursor.long("last_opened_at"),
        )
    }

    private fun LibraryRegistration.toValues() = ContentValues().apply {
        put("library_id", libraryId)
        put("name", name)
        put("tree_uri", treeUri)
        put("permission_state", permissionState.name)
        put("schema_version", schemaVersion)
        lastScanAt?.let { put("last_scan_at", it) } ?: putNull("last_scan_at")
    }

    private fun MediaItem.toValues() = ContentValues().apply {
        put("id", id)
        put("library_id", libraryId)
        put("relative_path", relativePath)
        put("uri", uri)
        put("kind", kind.name)
        put("domain", domain.name)
        put("source_kind", sourceKind.name)
        put("display_title", displayTitle)
        originalTitle?.let { put("original_title", it) } ?: putNull("original_title")
        mimeType?.let { put("mime_type", it) } ?: putNull("mime_type")
        put("size", size)
        put("modified_at", modifiedAt)
        contentHash?.let { put("content_hash", it) } ?: putNull("content_hash")
        capturedAt?.let { put("captured_at", it) } ?: putNull("captured_at")
        latitude?.let { put("latitude", it) } ?: putNull("latitude")
        longitude?.let { put("longitude", it) } ?: putNull("longitude")
        pageCount?.let { put("page_count", it) } ?: putNull("page_count")
        put("authors_json", json.encodeToString(authors))
        put("tags_json", json.encodeToString(tags))
        put("collections_json", json.encodeToString(collections))
        series?.let { put("series_json", json.encodeToString(it)) } ?: putNull("series_json")
        coverPath?.let { put("cover_path", it) } ?: putNull("cover_path")
        secondaryPath?.let { put("secondary_path", it) } ?: putNull("secondary_path")
        put("favorite", favorite.asInt())
        put("in_inbox", inInbox.asInt())
        put("trashed", trashed.asInt())
        deletedAt?.let { put("deleted_at", it) } ?: putNull("deleted_at")
        put("needs_repair", needsRepair.asInt())
        put("revision", revision)
        put("field_sources_json", json.encodeToString(fieldSources))
    }

    private fun DiscoveredEntry.toValues() = ContentValues().apply {
        put("library_id", libraryId)
        put("relative_path", relativePath)
        uri?.let { put("uri", it) } ?: putNull("uri")
        put("is_directory", isDirectory.asInt())
        mimeType?.let { put("mime_type", it) } ?: putNull("mime_type")
        put("size", size)
        put("modified_at", modifiedAt)
        put("reason", reason.name)
    }

    private fun libraryFromCursor(cursor: Cursor) = LibraryRegistration(
        libraryId = cursor.string("library_id"),
        name = cursor.string("name"),
        treeUri = cursor.string("tree_uri"),
        permissionState = PermissionState.valueOf(cursor.string("permission_state")),
        schemaVersion = cursor.int("schema_version"),
        lastScanAt = cursor.nullableLong("last_scan_at"),
    )

    private fun mediaFromCursor(cursor: Cursor) = MediaItem(
        id = cursor.string("id"),
        libraryId = cursor.string("library_id"),
        relativePath = cursor.string("relative_path"),
        uri = cursor.string("uri"),
        kind = MediaKind.valueOf(cursor.string("kind")),
        domain = MediaDomain.valueOf(cursor.string("domain")),
        sourceKind = SourceKind.valueOf(cursor.string("source_kind")),
        displayTitle = cursor.string("display_title"),
        originalTitle = cursor.nullableString("original_title"),
        mimeType = cursor.nullableString("mime_type"),
        size = cursor.long("size"),
        modifiedAt = cursor.long("modified_at"),
        contentHash = cursor.nullableString("content_hash"),
        capturedAt = cursor.nullableLong("captured_at"),
        latitude = cursor.nullableDouble("latitude"),
        longitude = cursor.nullableDouble("longitude"),
        pageCount = cursor.nullableInt("page_count"),
        authors = json.decodeFromString(cursor.string("authors_json")),
        tags = json.decodeFromString(cursor.string("tags_json")),
        collections = json.decodeFromString(cursor.string("collections_json")),
        series = cursor.nullableString("series_json")?.let { json.decodeFromString<SeriesRef>(it) },
        coverPath = cursor.nullableString("cover_path"),
        secondaryPath = cursor.nullableString("secondary_path"),
        favorite = cursor.int("favorite") != 0,
        inInbox = cursor.int("in_inbox") != 0,
        trashed = cursor.int("trashed") != 0,
        deletedAt = cursor.nullableLong("deleted_at"),
        needsRepair = cursor.int("needs_repair") != 0,
        revision = cursor.long("revision"),
        fieldSources = json.decodeFromString(cursor.string("field_sources_json")),
    )

    private fun discoveryFromCursor(cursor: Cursor) = DiscoveredEntry(
        libraryId = cursor.string("library_id"),
        relativePath = cursor.string("relative_path"),
        uri = cursor.nullableString("uri"),
        isDirectory = cursor.int("is_directory") != 0,
        mimeType = cursor.nullableString("mime_type"),
        size = cursor.long("size"),
        modifiedAt = cursor.long("modified_at"),
        reason = DiscoveryReason.valueOf(cursor.string("reason")),
    )

    private fun Boolean.asInt() = if (this) 1 else 0

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private fun Cursor.nullableString(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.nullableLong(column: String): Long? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }
    private fun Cursor.nullableInt(column: String): Int? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.nullableDouble(column: String): Double? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getDouble(it) }

    private inline fun <T> Cursor.mapRows(transform: (Cursor) -> T): List<T> {
        val output = mutableListOf<T>()
        while (moveToNext()) output += transform(this)
        return output
    }

    companion object {
        private const val DATABASE_NAME = "gallery-index.db"
        private const val DATABASE_VERSION = 5
    }
}
