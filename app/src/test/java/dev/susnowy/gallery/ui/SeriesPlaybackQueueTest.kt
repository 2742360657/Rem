package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import org.junit.Assert.*
import org.junit.Test

class SeriesPlaybackQueueTest {
    private fun item(id: String, library: String = "l", trashed: Boolean = false) =
        MediaItem(id, library, "$id.mp4", "", MediaKind.VIDEO, sourceKind = SourceKind.FILE, displayTitle = id, trashed = trashed)

    @Test fun nextUsesSeriesOrderAndDoesNotCrossLibraries() {
        val a = item("a")
        val b = item("b")
        val c = item("c", trashed = true)
        assertEquals("b", nextSeriesItem(a, listOf(c, a, b))?.id)
        assertNull(nextSeriesItem(b, listOf(a, item("x", "other"))))
    }

    @Test fun openingStandaloneVideoHasNoNext() {
        val a = item("a")
        assertNull(nextSeriesItem(a, listOf(a)))
    }
}
