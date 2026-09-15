package dev.susnowy.gallery.organizer

import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SeriesRef
import dev.susnowy.gallery.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OrganizerServiceTest {
    private val service = OrganizerService()

    @Test
    fun createsPortableAuthorFirstPath() {
        val item = item(
            title = "A: Title?",
            path = "downloads/book.cbz",
            kind = MediaKind.IMAGE_SET,
            source = SourceKind.ARCHIVE,
            authors = listOf("Artist"),
        )
        assertEquals(
            "ImageSets/Artist/A_ Title_.cbz",
            service.targetPath(item, OrganizerTemplate.AUTHOR_FIRST),
        )
    }

    @Test
    fun omitsUnknownLayersAndUsesSeriesForVideo() {
        val item = item(
            title = "Episode 2",
            path = "raw/02.mkv",
            kind = MediaKind.VIDEO,
            source = SourceKind.FILE,
            series = SeriesRef("id", "Show", sortIndex = 2.0),
        )
        val path = service.targetPath(item, OrganizerTemplate.SERIES_FIRST)!!
        assertEquals("Videos/Series/Show/Episode 2.mkv", path)
        assertFalse(path.contains("Unknown"))
    }

    @Test
    fun protectsWindowsReservedNames() {
        val item = item("CON", "CON.jpg", MediaKind.IMAGE, SourceKind.FILE)
        assertEquals("Images/_CON.jpg", service.targetPath(item, OrganizerTemplate.AUTHOR_FIRST))
    }

    private fun item(
        title: String,
        path: String,
        kind: MediaKind,
        source: SourceKind,
        authors: List<String> = emptyList(),
        series: SeriesRef? = null,
    ) = MediaItem(
        id = "item",
        libraryId = "library",
        relativePath = path,
        uri = "content://item",
        kind = kind,
        sourceKind = source,
        displayTitle = title,
        authors = authors,
        series = series,
    )
}
