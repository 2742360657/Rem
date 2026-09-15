package dev.susnowy.gallery.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MediaKind {
    @SerialName("image") IMAGE,
    @SerialName("image_set") IMAGE_SET,
    @SerialName("video") VIDEO,
    @SerialName("photo") PHOTO,
    @SerialName("photo_video") PHOTO_VIDEO,
    @SerialName("live_photo") LIVE_PHOTO,
}

@Serializable
enum class SourceKind {
    @SerialName("file") FILE,
    @SerialName("directory") DIRECTORY,
    @SerialName("archive") ARCHIVE,
    @SerialName("system_import") SYSTEM_IMPORT,
}

@Serializable
data class SeriesRef(
    val id: String,
    val title: String,
    @SerialName("sort_index") val sortIndex: Double = 0.0,
    val season: Int? = null,
    val episode: Double? = null,
    val volume: Double? = null,
    val chapter: Double? = null,
)

data class MediaItem(
    val id: String,
    val libraryId: String,
    val relativePath: String,
    val uri: String,
    val kind: MediaKind,
    val sourceKind: SourceKind,
    val displayTitle: String,
    val originalTitle: String? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    val modifiedAt: Long = 0,
    val contentHash: String? = null,
    val capturedAt: Long? = null,
    val pageCount: Int? = null,
    val authors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val series: SeriesRef? = null,
    val coverPath: String? = null,
    val secondaryPath: String? = null,
    val favorite: Boolean = false,
    val inInbox: Boolean = true,
    val trashed: Boolean = false,
    val deletedAt: Long? = null,
    val needsRepair: Boolean = false,
    val revision: Long = 0,
)

data class PlaybackProgress(
    val itemId: String,
    val page: Int = 0,
    val positionMs: Long = 0,
    val finished: Boolean = false,
    val lastOpenedAt: Long = 0,
)

@Serializable
data class PortableItemMetadata(
    val id: String,
    @SerialName("relative_path") val relativePath: String,
    val type: MediaKind,
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
    val revision: Long = 1,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class PortableCatalog(
    @SerialName("schema_version") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerialName("library_id") val libraryId: String,
    val revision: Long = 0,
    @SerialName("updated_at") val updatedAt: String,
    val items: List<PortableItemMetadata> = emptyList(),
)

@Serializable
data class PortableProgress(
    @SerialName("item_id") val itemId: String,
    val page: Int = 0,
    @SerialName("position_ms") val positionMs: Long = 0,
    val finished: Boolean = false,
    @SerialName("last_opened_at") val lastOpenedAt: String,
)

@Serializable
data class PortableState(
    @SerialName("schema_version") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerialName("library_id") val libraryId: String,
    val revision: Long = 0,
    @SerialName("updated_at") val updatedAt: String,
    val progress: List<PortableProgress> = emptyList(),
    val trash: List<PortableTrashEntry> = emptyList(),
)

@Serializable
data class PortableTrashEntry(
    @SerialName("item_id") val itemId: String,
    @SerialName("relative_path") val relativePath: String,
    @SerialName("deleted_at") val deletedAt: String,
)
