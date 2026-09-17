package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.model.MediaItem

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
    if (previous.series != series) sources[MetadataField.SERIES] = FieldSource.MANUAL
    if (previous.coverPath != coverPath) sources[MetadataField.COVER_PATH] = FieldSource.MANUAL
    if (previous.favorite != favorite) sources[MetadataField.FAVORITE] = FieldSource.MANUAL
    return sources
}
