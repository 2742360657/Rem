package dev.susnowy.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicPreloadPolicyTest {
    @Test
    fun forwardScrollingWarmsFiveAheadAndTwoBehind() {
        assertEquals(
            listOf(12, 13, 14, 15, 16, 9, 8),
            comicPreloadOrder(firstVisible = 10, lastVisible = 11, pageCount = 30, scrollingForward = true),
        )
    }

    @Test
    fun backwardScrollingPrioritizesPreviousPagesAndClampsEdges() {
        assertEquals(
            listOf(2, 1, 0, 4, 5),
            comicPreloadOrder(firstVisible = 3, lastVisible = 3, pageCount = 6, scrollingForward = false),
        )
    }
}
