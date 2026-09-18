package dev.susnowy.gallery.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import java.util.UUID

@Serializable
enum class MediaKind {
    @SerialName("image") IMAGE,
    @SerialName("image_set") IMAGE_SET,
    @SerialName("video") VIDEO,
    @SerialName("photo") PHOTO,
    @SerialName("photo_video") PHOTO_VIDEO,
    @SerialName("live_photo") LIVE_PHOTO,
}

/** The product surface an item belongs to; independent from its file format. */
@Serializable
enum class MediaDomain {
    @SerialName("album") ALBUM,
    @SerialName("classified") CLASSIFIED,
    @SerialName("works") WORKS,
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
    @SerialName("sort_index") val sortIndex: Double? = null,
    val season: Int? = null,
    val episode: Double? = null,
    val volume: Double? = null,
    val chapter: Double? = null,
)

/** User-editable series assignment before a stable Library-local series id is resolved. */
data class SeriesAssignment(
    val title: String,
    val sortIndex: Double? = null,
    val season: Int? = null,
    val episode: Double? = null,
    val volume: Double? = null,
    val chapter: Double? = null,
)

fun SeriesAssignment.toSeriesRef(
    libraryId: String,
    existing: Sequence<SeriesRef> = emptySequence(),
): SeriesRef {
    val normalizedTitle = title.trim().lowercase(Locale.ROOT)
    require(normalizedTitle.isNotEmpty()) { "系列标题不能为空" }
    val existingId = existing
        .filter { it.title.trim().lowercase(Locale.ROOT) == normalizedTitle }
        .map(SeriesRef::id)
        .minOrNull()
    return SeriesRef(
        id = existingId ?: UUID.nameUUIDFromBytes("series:$libraryId:$normalizedTitle".encodeToByteArray()).toString(),
        title = title.trim(),
        sortIndex = sortIndex,
        season = season,
        episode = episode,
        volume = volume,
        chapter = chapter,
    )
}

data class MediaItem(
    val id: String,
    val libraryId: String,
    val relativePath: String,
    val uri: String,
    val kind: MediaKind,
    val domain: MediaDomain = MediaDomain.CLASSIFIED,
    val sourceKind: SourceKind,
    val displayTitle: String,
    val originalTitle: String? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    val modifiedAt: Long = 0,
    val contentHash: String? = null,
    val capturedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val pageCount: Int? = null,
    val authors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    val series: SeriesRef? = null,
    val coverPath: String? = null,
    val secondaryPath: String? = null,
    val favorite: Boolean = false,
    /** True while the item is still waiting for a user decision in Inbox. */
    val inInbox: Boolean = true,
    /**
     * Portable Inbox decision for this Work, if the user already made one. The device
     * index mirrors `.gallery/state/inbox.json`; it is never the only place a decision lives.
     */
    val inboxDisposition: InboxDisposition? = null,
    val trashed: Boolean = false,
    val deletedAt: Long? = null,
    val needsRepair: Boolean = false,
    val revision: Long = 0,
    /** Portable field name → provenance tag. See [dev.susnowy.gallery.metadata.FieldSource]. */
    val fieldSources: Map<String, String> = emptyMap(),
)

data class PlaybackProgress(
    val itemId: String,
    val page: Int = 0,
    val positionMs: Long = 0,
    val finished: Boolean = false,
    val lastOpenedAt: Long = 0,
    /**
     * When the user first opened this Work, or null when nobody has opened it yet.
     *
     * Page 0 is a real reading position, so "the reader was opened" cannot be inferred from the
     * page number alone: a chapter showing its first page must not look untouched. This is also
     * what "continue reading" orders by, and it is portable, because losing it to a rebuilt
     * device index would make an already-opened Work look unread.
     */
    val openedAt: Long? = null,
) {
    /** True once the reader was opened, whatever page it currently shows. */
    val opened: Boolean get() = openedAt != null || finished || page > 0
}

/**
 * Runtime projection used by the current scanner and UI while the portable catalog is
 * normalized. It is not serialized as a top-level v4 document.
 */
data class PortableItemMetadata(
    val id: String,
    @SerialName("relative_path") val relativePath: String,
    val type: MediaKind,
    /** Nullable only in the runtime projection used while converting pre-v4 test data. */
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
    /**
     * Provenance of every editable field: `manual`, `filename`, `comic_info`,
     * `import`, `library`, or `provider:<id>`. A field tagged `manual` must
     * never be overwritten by automatic recognition.
     */
    @SerialName("field_sources") val fieldSources: Map<String, String> = emptyMap(),
    val revision: Long = 1,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class PortableAsset(
    val id: String,
    @SerialName("relative_path") val relativePath: String,
    @SerialName("media_type") val mediaType: MediaKind,
    val source: SourceKind,
    @SerialName("secondary_path") val secondaryPath: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    val revision: Long = 1,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class PortableWork(
    val id: String,
    val type: MediaKind,
    val domain: MediaDomain,
    @SerialName("display_title") val displayTitle: String,
    @SerialName("original_title") val originalTitle: String? = null,
    val authors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val collections: List<String> = emptyList(),
    @SerialName("preferred_edition_id") val preferredEditionId: String? = null,
    @SerialName("cover_path") val coverPath: String? = null,
    val favorite: Boolean = false,
    @SerialName("field_sources") val fieldSources: Map<String, String> = emptyMap(),
    val revision: Long = 1,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
enum class EditionAssetRole {
    @SerialName("primary") PRIMARY,
    @SerialName("page") PAGE,
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("bonus") BONUS,
    @SerialName("cover") COVER,
    @SerialName("alternate") ALTERNATE,
}

@Serializable
data class PortableEditionAsset(
    @SerialName("asset_id") val assetId: String,
    val role: EditionAssetRole = EditionAssetRole.PRIMARY,
    @SerialName("sort_index") val sortIndex: Double? = null,
    /** Optional path inside a directory or archive asset. */
    @SerialName("entry_path") val entryPath: String? = null,
)

@Serializable
data class PortableEdition(
    val id: String,
    @SerialName("work_id") val workId: String,
    val label: String? = null,
    val assets: List<PortableEditionAsset>,
    val revision: Long = 1,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
enum class GroupType {
    @SerialName("media_set") MEDIA_SET,
    @SerialName("manual_collection") MANUAL_COLLECTION,
}

@Serializable
enum class GroupMemberRole {
    @SerialName("item") ITEM,
    @SerialName("image") IMAGE,
    @SerialName("video") VIDEO,
    @SerialName("bonus") BONUS,
    @SerialName("cover") COVER,
}

@Serializable
data class PortableGroupMember(
    @SerialName("work_id") val workId: String,
    val role: GroupMemberRole = GroupMemberRole.ITEM,
    @SerialName("sort_index") val sortIndex: Double? = null,
)

@Serializable
data class PortableGroup(
    val id: String,
    val title: String,
    val type: GroupType = GroupType.MEDIA_SET,
    val ordered: Boolean = true,
    val members: List<PortableGroupMember> = emptyList(),
    @SerialName("cover_work_id") val coverWorkId: String? = null,
    @SerialName("field_sources") val fieldSources: Map<String, String> = emptyMap(),
    val revision: Long = 1,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class PortableSeriesMember(
    @SerialName("work_id") val workId: String,
    @SerialName("sort_index") val sortIndex: Double? = null,
    val season: Int? = null,
    val episode: Double? = null,
    val volume: Double? = null,
    val chapter: Double? = null,
)

@Serializable
data class PortableSeries(
    val id: String,
    val title: String,
    val aliases: List<String> = emptyList(),
    val members: List<PortableSeriesMember> = emptyList(),
    @SerialName("field_sources") val fieldSources: Map<String, String> = emptyMap(),
    val revision: Long = 1,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class PortableCatalog(
    @SerialName("schema_version") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerialName("library_id") val libraryId: String,
    val revision: Long = 0,
    @SerialName("updated_at") val updatedAt: String,
    val assets: List<PortableAsset> = emptyList(),
    val works: List<PortableWork> = emptyList(),
    val editions: List<PortableEdition> = emptyList(),
    val groups: List<PortableGroup> = emptyList(),
    val series: List<PortableSeries> = emptyList(),
) {
    /** Transitional projection; portable v4 never serializes an `items` array. */
    val items: List<PortableItemMetadata>
        get() {
            val assetsById = assets.associateBy(PortableAsset::id)
            val editionsByWork = editions.groupBy(PortableEdition::workId)
            val seriesByWork = buildMap<String, Pair<PortableSeries, PortableSeriesMember>> {
                series.sortedBy(PortableSeries::id).forEach { sequence ->
                    sequence.members.forEach { member -> putIfAbsent(member.workId, sequence to member) }
                }
            }
            return works.mapNotNull { work ->
                val available = editionsByWork[work.id].orEmpty()
                val edition = available.firstOrNull { it.id == work.preferredEditionId }
                    ?: available.minByOrNull(PortableEdition::id)
                    ?: return@mapNotNull null
                val primary = edition.assets.firstOrNull { it.role == EditionAssetRole.PRIMARY }
                    ?: edition.assets.minWithOrNull(
                        compareBy<PortableEditionAsset> { it.sortIndex ?: Double.MAX_VALUE }
                            .thenBy(PortableEditionAsset::assetId),
                    )
                    ?: return@mapNotNull null
                val asset = assetsById[primary.assetId] ?: return@mapNotNull null
                val sequence = seriesByWork[work.id]
                PortableItemMetadata(
                    id = work.id,
                    relativePath = asset.relativePath,
                    type = work.type,
                    domain = work.domain,
                    displayTitle = work.displayTitle,
                    originalTitle = work.originalTitle,
                    source = asset.source,
                    authors = work.authors,
                    tags = work.tags,
                    collections = work.collections,
                    series = sequence?.let { (series, member) ->
                        SeriesRef(
                            id = series.id,
                            title = series.title,
                            sortIndex = member.sortIndex,
                            season = member.season,
                            episode = member.episode,
                            volume = member.volume,
                            chapter = member.chapter,
                        )
                    },
                    coverPath = work.coverPath,
                    secondaryPath = asset.secondaryPath,
                    contentHash = asset.contentHash,
                    favorite = work.favorite,
                    fieldSources = work.fieldSources,
                    revision = work.revision,
                    updatedAt = work.updatedAt,
                )
            }
        }
}

@Serializable
data class PortableProgress(
    @SerialName("work_id") val itemId: String,
    val page: Int = 0,
    @SerialName("position_ms") val positionMs: Long = 0,
    val finished: Boolean = false,
    @SerialName("last_opened_at") val lastOpenedAt: String,
    /**
     * First time the user opened this Work. Optional so a `state.json` written before this field
     * existed still loads; a missing value simply means "no recorded open event".
     */
    @SerialName("opened_at") val openedAt: String? = null,
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
    @SerialName("work_id") val itemId: String,
    @SerialName("relative_path") val relativePath: String,
    @SerialName("deleted_at") val deletedAt: String,
)
