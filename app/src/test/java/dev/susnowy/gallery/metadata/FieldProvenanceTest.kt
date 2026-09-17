package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FieldProvenanceTest {
    private val recognized = MediaItem(
        id = "item",
        libraryId = "library",
        relativePath = "downloads/work.cbz",
        uri = "content://work",
        kind = MediaKind.IMAGE_SET,
        domain = MediaDomain.WORKS,
        sourceKind = SourceKind.ARCHIVE,
        displayTitle = "识别标题",
        authors = listOf("识别作者"),
        fieldSources = mapOf(
            MetadataField.DOMAIN to FieldSource.FILENAME,
            MetadataField.DISPLAY_TITLE to FieldSource.COMIC_INFO,
            MetadataField.AUTHORS to FieldSource.FILENAME,
        ),
    )

    @Test
    fun acceptingUnchangedSuggestionKeepsAutomaticSources() {
        assertEquals(recognized.fieldSources, recognized.withManualEdits(recognized))
    }

    @Test
    fun editingOneFieldLocksOnlyThatField() {
        val edited = recognized.copy(displayTitle = "我的标题")

        val sources = edited.withManualEdits(recognized)

        assertEquals(FieldSource.MANUAL, sources[MetadataField.DISPLAY_TITLE])
        assertEquals(FieldSource.FILENAME, sources[MetadataField.AUTHORS])
        assertEquals(FieldSource.FILENAME, sources[MetadataField.DOMAIN])
    }
}
