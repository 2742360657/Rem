package dev.susnowy.gallery.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PermissionState
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.SeriesRef
import dev.susnowy.gallery.model.SourceKind
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
                source_kind TEXT NOT NULL,
                display_title TEXT NOT NULL,
                original_title TEXT,
                mime_type TEXT,
                size INTEGER NOT NULL,
                modified_at INTEGER NOT NULL,
                captured_at INTEGER,
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) {
            db.execSQL("DROP TABLE IF EXISTS progress")
            db.execSQL("DROP TABLE IF EXISTS media")
            db.execSQL("DROP TABLE IF EXISTS libraries")
            onCreate(db)
        }
    }

    @Synchronized
    fun upsertLibrary(library: LibraryRegistration) {
        writableDatabase.insertWithOnConflict(
            "libraries",
            null,
            library.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
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

    @Synchronized
    fun upsertMedia(item: MediaItem) {
        writableDatabase.insertWithOnConflict(
            "media",
            null,
            item.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
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
    fun markMissing(libraryId: String, foundPaths: Set<String>) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            media(libraryId).forEach { item ->
                if (item.relativePath !in foundPaths && !item.trashed) {
                    val values = ContentValues().apply { put("needs_repair", 1) }
                    database.update("media", values, "id = ?", arrayOf(item.id))
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun removeMedia(itemId: String) {
        writableDatabase.delete("media", "id = ?", arrayOf(itemId))
    }

    @Synchronized
    fun clearMediaIndex(libraryId: String) {
        writableDatabase.delete("media", "library_id = ?", arrayOf(libraryId))
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
        put("source_kind", sourceKind.name)
        put("display_title", displayTitle)
        originalTitle?.let { put("original_title", it) } ?: putNull("original_title")
        mimeType?.let { put("mime_type", it) } ?: putNull("mime_type")
        put("size", size)
        put("modified_at", modifiedAt)
        capturedAt?.let { put("captured_at", it) } ?: putNull("captured_at")
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
        sourceKind = SourceKind.valueOf(cursor.string("source_kind")),
        displayTitle = cursor.string("display_title"),
        originalTitle = cursor.nullableString("original_title"),
        mimeType = cursor.nullableString("mime_type"),
        size = cursor.long("size"),
        modifiedAt = cursor.long("modified_at"),
        capturedAt = cursor.nullableLong("captured_at"),
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

    private inline fun <T> Cursor.mapRows(transform: (Cursor) -> T): List<T> {
        val output = mutableListOf<T>()
        while (moveToNext()) output += transform(this)
        return output
    }

    companion object {
        private const val DATABASE_NAME = "gallery-index.db"
        private const val DATABASE_VERSION = 1
    }
}
