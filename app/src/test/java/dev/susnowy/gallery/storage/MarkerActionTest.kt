package dev.susnowy.gallery.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The hide switch must be safe to press repeatedly.
 *
 * `setSystemGalleryHidden` cannot be unit tested — it talks to a SAF provider — so the decision it
 * makes is separated into [markerAction] and pinned here. Two properties matter: asking for a state
 * the Library is already in must not create a second marker, and a directory that happens to be
 * named `.nomedia` must never be deleted, because Rem did not create it and cannot see inside it.
 */
class MarkerActionTest {

    @Test
    fun `hiding a library with no marker creates one`() {
        assertEquals(MarkerAction.Create, markerAction(markerExists = false, markerIsDirectory = false, hidden = true))
    }

    @Test
    fun `hiding an already hidden library changes nothing`() {
        assertEquals(MarkerAction.None, markerAction(markerExists = true, markerIsDirectory = false, hidden = true))
    }

    @Test
    fun `showing a hidden library deletes the marker`() {
        assertEquals(MarkerAction.Delete, markerAction(markerExists = true, markerIsDirectory = false, hidden = false))
    }

    @Test
    fun `showing a library that is not hidden changes nothing`() {
        assertEquals(MarkerAction.None, markerAction(markerExists = false, markerIsDirectory = false, hidden = false))
    }

    @Test
    fun `a directory named nomedia is a conflict in both directions`() {
        assertEquals(MarkerAction.Conflict, markerAction(markerExists = true, markerIsDirectory = true, hidden = true))
        assertEquals(MarkerAction.Conflict, markerAction(markerExists = true, markerIsDirectory = true, hidden = false))
    }

    /** Replaying the same request against its own result must settle, never create a second file. */
    @Test
    fun `repeating a request never asks for a second creation`() {
        var exists = false
        var isDirectory = false
        repeat(4) {
            when (markerAction(exists, isDirectory, hidden = true)) {
                MarkerAction.Create -> exists = true
                MarkerAction.Delete -> exists = false
                MarkerAction.None, MarkerAction.Conflict -> Unit
            }
        }
        assertEquals(MarkerAction.None, markerAction(exists, isDirectory, hidden = true))
    }
}
