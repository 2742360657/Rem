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
    val manual = fieldSources.filterValues { it == FieldSource.MANUAL }.toMutableMap()
    if (previous == null) return manual
    if (previous.domain != domain) manual[MetadataField.DOMAIN] = FieldSource.MANUAL
    if (previous.displayTitle != displayTitle) manual[MetadataField.DISPLAY_TITLE] = FieldSource.MANUAL
    if (previous.originalTitle != originalTitle) manual[MetadataField.ORIGINAL_TITLE] = FieldSource.MANUAL
    if (previous.authors != authors) manual[MetadataField.AUTHORS] = FieldSource.MANUAL
    if (previous.tags != tags) manual[MetadataField.TAGS] = FieldSource.MANUAL
    if (previous.collections != collections) manual[MetadataField.COLLECTIONS] = FieldSource.MANUAL
    if (previous.series != series) manual[MetadataField.SERIES] = FieldSource.MANUAL
    if (previous.coverPath != coverPath) manual[MetadataField.COVER_PATH] = FieldSource.MANUAL
    if (previous.favorite != favorite) manual[MetadataField.FAVORITE] = FieldSource.MANUAL
    return manual
}
