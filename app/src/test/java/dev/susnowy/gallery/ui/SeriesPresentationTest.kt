package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SeriesAssignment
import dev.susnowy.gallery.model.SeriesRef
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.model.toSeriesRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesPresentationTest {
    @Test
    fun assignmentReusesCanonicalIdAndPreservesEveryOptionalNumber() {
        val resolved = SeriesAssignment(
            title = " example ",
            sortIndex = 8.0,
            season = 2,
            episode = 3.5,
            volume = 4.0,
            chapter = 5.0,
        ).toSeriesRef(
            libraryId = "library-id",
            existing = sequenceOf(
                SeriesRef("z-id", "Example"),
                SeriesRef("a-id", "EXAMPLE"),
            ),
        )

        assertEquals("a-id", resolved.id)
        assertEquals("example", resolved.title)
        assertEquals(8.0, resolved.sortIndex)
        assertEquals(2, resolved.season)
        assertEquals(3.5, resolved.episode)
        assertEquals(4.0, resolved.volume)
        assertEquals(5.0, resolved.chapter)
    }

    @Test
    fun keepsSameNamedSeriesSeparateByTheirPortableIdentity() {
        val shelves = SeriesPresentation.shelves(
            listOf(
                item("a", "First", SeriesRef("legacy-a", "Example", sortIndex = 2.0)),
                item("b", "Second", SeriesRef("legacy-b", "example", sortIndex = 1.0)),
                item("c", "Standalone", null),
            ),
        )

        assertEquals(3, shelves.size)
        assertEquals(listOf("a"), shelves[0].items.map(MediaItem::id))
        assertEquals(listOf("b"), shelves[1].items.map(MediaItem::id))
        assertFalse(shelves.first().isUnassigned)
        assertEquals(SeriesPresentation.UNASSIGNED_KEY, shelves.last().key)
        assertEquals(listOf("c"), shelves.last().items.map(MediaItem::id))
    }

    @Test
    fun explicitOrderWinsThenStructuredNumbersAndUnnumberedItems() {
        val ordered = SeriesPresentation.orderEntries(
            listOf(
                item("unassigned-order", "Zeta", series()),
                item("chapter-two", "Chapter 2", series(volume = 1.0, chapter = 2.0)),
                item("chapter-one", "Chapter 1", series(volume = 1.0, chapter = 1.0)),
                item("manual", "Manual", series(sortIndex = 9.0)),
            ),
        )

        assertEquals(
            listOf("manual", "chapter-one", "chapter-two", "unassigned-order"),
            ordered.map(MediaItem::id),
        )
        assertNull(ordered.last().series?.sortIndex)
    }

    private fun series(
        sortIndex: Double? = null,
        volume: Double? = null,
        chapter: Double? = null,
    ) = SeriesRef(
        id = "series-id",
        title = "Example",
        sortIndex = sortIndex,
        volume = volume,
        chapter = chapter,
    )

    private fun item(id: String, title: String, series: SeriesRef?) = MediaItem(
        id = id,
        libraryId = "library-id",
        relativePath = "Works/$id.cbz",
        uri = "content://$id",
        kind = MediaKind.IMAGE_SET,
        domain = MediaDomain.WORKS,
        sourceKind = SourceKind.ARCHIVE,
        displayTitle = title,
        series = series,
        inInbox = false,
    )
}
