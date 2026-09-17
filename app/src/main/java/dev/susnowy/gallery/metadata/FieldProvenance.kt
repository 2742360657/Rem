package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.SeriesRef
import java.util.Locale
import java.util.UUID

/**
 * Where an editable metadata field came from. Manual edits always win; a
 * provider refresh may only fill fields that are still automatic.
 */
object FieldSource {
    const val MANUAL = "manual"
    const val IMPORT = "import"
    const val FILENAME = "filename"
    const val COMIC_INFO = "comic_info"
    const val SYSTEM_IMPORT = "system_import"

    fun provider(providerId: String): String = "provider:$providerId"

    fun isManual(source: String?): Boolean = source == MANUAL

    fun isProvider(source: String?): Boolean = source?.startsWith("provider:") == true
}

/** Portable field names used as keys in `field_sources`. */
object MetadataField {
    const val DOMAIN = "domain"
    const val DISPLAY_TITLE = "display_title"
    const val ORIGINAL_TITLE = "original_title"
    const val AUTHORS = "authors"
    const val TAGS = "tags"
    const val COLLECTIONS = "collections"
    const val SERIES = "series"
    const val COVER_PATH = "cover_path"
    const val FAVORITE = "favorite"

    val EDITABLE = listOf(
        DOMAIN,
        DISPLAY_TITLE,
        ORIGINAL_TITLE,
        AUTHORS,
        TAGS,
        COLLECTIONS,
        SERIES,
        COVER_PATH,
        FAVORITE,
    )
}

/**
 * Marks the fields a human actually changed as [FieldSource.MANUAL]. Portable
 * metadata keeps these locks forever, so automatic recognition and online
 * providers can fill in the rest without ever overwriting a manual choice.
 */
fun MediaItem.withManualEdits(previous: MediaItem?): Map<String, String> {
    val sources = fieldSources.toMutableMap()
    if (previous == null) return sources
    if (previous.domain != domain) sources[MetadataField.DOMAIN] = FieldSource.MANUAL
    if (previous.displayTitle != displayTitle) sources[MetadataField.DISPLAY_TITLE] = FieldSource.MANUAL
    if (previous.originalTitle != originalTitle) sources[MetadataField.ORIGINAL_TITLE] = FieldSource.MANUAL
    if (previous.authors != authors) sources[MetadataField.AUTHORS] = FieldSource.MANUAL
    if (previous.tags != tags) sources[MetadataField.TAGS] = FieldSource.MANUAL
    if (previous.collections != collections) sources[MetadataField.COLLECTIONS] = FieldSource.MANUAL
    if (!previous.series.sameAssignmentAs(series)) sources[MetadataField.SERIES] = FieldSource.MANUAL
    if (previous.coverPath != coverPath) sources[MetadataField.COVER_PATH] = FieldSource.MANUAL
    if (previous.favorite != favorite) sources[MetadataField.FAVORITE] = FieldSource.MANUAL
    return sources
}

/**
 * True when two series assignments mean the same thing to the user.
 *
 * The id of a series reference is derived from the Library and the title, so a reference
 * rebuilt by the UI can carry a different id while still describing the same assignment.
 * Comparing ids would then stamp `series = manual` on an untouched field and permanently
 * stop automatic recognition from correcting it.
 */
fun SeriesRef?.sameAssignmentAs(other: SeriesRef?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> title.trim().lowercase(Locale.ROOT) == other.title.trim().lowercase(Locale.ROOT) &&
        sortIndex == other.sortIndex &&
        season == other.season &&
        episode == other.episode &&
        volume == other.volume &&
        chapter == other.chapter
}

/**
 * Merges a user edit into the database row read at commit time.
 *
 * A UI action is planned against a snapshot; the row may have been refreshed by the scanner
 * in the meantime. Only the fields the user actually changed are written, so an automatic
 * value that arrived in between (a hash, a page count, a capture time) survives the edit.
 *
 * The caller keeps the responsibility for provenance: [dev.susnowy.gallery.data.GalleryRepository]
 * computes [MediaItem.fieldSources] from the edit itself.
 */
fun MediaItem.mergeEdit(previous: MediaItem, current: MediaItem): MediaItem = current.copy(
    domain = if (domain != previous.domain) domain else current.domain,
    displayTitle = if (displayTitle != previous.displayTitle) displayTitle else current.displayTitle,
    originalTitle = if (originalTitle != previous.originalTitle) originalTitle else current.originalTitle,
    authors = if (authors != previous.authors) authors else current.authors,
    tags = if (tags != previous.tags) tags else current.tags,
    collections = if (collections != previous.collections) collections else current.collections,
    series = if (!previous.series.sameAssignmentAs(series)) series else current.series,
    coverPath = if (coverPath != previous.coverPath) coverPath else current.coverPath,
    favorite = if (favorite != previous.favorite) favorite else current.favorite,
    fieldSources = withManualEdits(previous),
)

/**
 * Merges one completed enrichment result into the database row current at commit time.
 *
 * This is the automatic half of the same rule: media bytes are read outside the portable
 * write mutex, so the merge happens against a freshly read row and field by field.
 * `manual` always wins; everything else is filled from the recognition result.
 */
fun MediaItem.mergeEnrichment(
    previous: MediaItem,
    result: dev.susnowy.gallery.scanner.ScanEnrichment,
): MediaItem {
    require(relativePath == result.relativePath) {
        "补全结果与媒体行不匹配：${result.relativePath}"
    }
    val recognized = result.recognizedMetadata
    val sources = fieldSources.toMutableMap()
    recognized?.fieldSources.orEmpty().forEach { (field, source) ->
        if (!FieldSource.isManual(sources[field])) sources[field] = source
    }
    fun manual(field: String): Boolean = FieldSource.isManual(sources[field])
    return copy(
        contentHash = result.contentHash ?: contentHash,
        capturedAt = result.capturedAt ?: capturedAt,
        latitude = result.latitude ?: latitude,
        longitude = result.longitude ?: longitude,
        pageCount = result.pageCount ?: pageCount,
        displayTitle = if (!manual(MetadataField.DISPLAY_TITLE) && recognized?.title != null) {
            requireNotNull(recognized.title)
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
        series = if (!manual(MetadataField.SERIES)) {
            recognized?.toSeriesRef(previous.libraryId) ?: series
        } else {
            series
        },
        fieldSources = sources,
    )
}

/**
 * Series reference proposed by automatic recognition.
 *
 * The id is derived from the Library and the title, so the same series recognised on two
 * devices lands on one entity instead of two rows that only look alike.
 */
fun RecognizedMetadata.toSeriesRef(libraryId: String): SeriesRef? {
    val title = series?.trim().orEmpty()
    if (title.isEmpty()) return null
    return SeriesRef(
        id = UUID.nameUUIDFromBytes("$libraryId:$title".encodeToByteArray()).toString(),
        title = title,
        sortIndex = sortIndex,
        season = season,
        episode = episode,
        volume = volume,
        chapter = chapter,
    )
}
