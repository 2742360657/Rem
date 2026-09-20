package dev.susnowy.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scrollbar label and the scrollbar jump both derive from [targetIndex]. If the two ever
 * disagreed, the control would name one file and land on another.
 */
class TargetIndexTest {

    @Test
    fun `top and bottom of the track are the first and last item`() {
        assertEquals(0, targetIndex(100, 0f))
        assertEquals(99, targetIndex(100, 1f))
    }

    @Test
    fun `the middle of the track is the middle of the list`() {
        assertEquals(50, targetIndex(101, 0.5f))
    }

    @Test
    fun `a fraction outside the track is clamped`() {
        assertEquals(0, targetIndex(100, -1f))
        assertEquals(99, targetIndex(100, 2f))
    }

    @Test
    fun `a single item list has only one place to land`() {
        assertEquals(0, targetIndex(1, 0f))
        assertEquals(0, targetIndex(1, 1f))
    }
}
