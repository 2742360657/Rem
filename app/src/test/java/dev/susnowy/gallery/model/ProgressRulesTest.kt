package dev.susnowy.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering and first-open rules for reading progress.
 *
 * These are the two properties a coroutine scheduler can silently break: a slow write landing
 * after a fast one, and a page-turn write erasing or refreshing the open marker.
 */
class ProgressRulesTest {
    private fun stored(
        page: Int = 5,
        finished: Boolean = false,
        lastOpenedAt: Long = 100,
        openedAt: Long? = 40,
    ) = PlaybackProgress(
        itemId = "work",
        page = page,
        finished = finished,
        lastOpenedAt = lastOpenedAt,
        openedAt = openedAt,
    )

    @Test
    fun anOlderWriteCannotMoveTheReadingPositionBackwards() {
        val current = stored(page = 5, lastOpenedAt = 200)
        // The page-2 event was stamped before the page-5 event but ran after it.
        val stale = PlaybackProgress(itemId = "work", page = 2, lastOpenedAt = 150)

        assertNull("更旧的事件必须被拒绝", ProgressRules.resolve(current, stale))
    }

    @Test
    fun aNewerWriteIsApplied() {
        val current = stored(page = 5, lastOpenedAt = 200)
        val newer = PlaybackProgress(itemId = "work", page = 6, lastOpenedAt = 250)

        val resolved = ProgressRules.resolve(current, newer)

        assertEquals(6, resolved?.page)
        assertEquals(250L, resolved?.lastOpenedAt)
    }

    @Test
    fun theFirstOpenStampIsNeverRefreshedOrDropped() {
        val current = stored(page = 5, lastOpenedAt = 200, openedAt = 40)
        val newer = PlaybackProgress(itemId = "work", page = 9, lastOpenedAt = 300)

        val resolved = ProgressRules.resolve(current, newer)

        assertEquals("继续阅读按首次打开排序，翻页不能刷新它", 40L, resolved?.openedAt)
    }

    @Test
    fun aChapterOpenedOnItsFirstPageStaysStarted() {
        val current = stored(page = 0, lastOpenedAt = 200, openedAt = 40)
        val samePage = PlaybackProgress(itemId = "work", page = 0, lastOpenedAt = 260)

        val resolved = ProgressRules.resolve(current, samePage)

        assertEquals(40L, resolved?.openedAt)
        assertTrue(resolved?.opened == true)
    }

    /** A row written without an open marker (an older index) gains one on the next real save. */
    @Test
    fun aSaveWithoutAnOpenMarkerDerivesOneOnlyForARealPosition() {
        val untouched = ProgressRules.resolve(
            current = null,
            candidate = PlaybackProgress(itemId = "work", page = 0, lastOpenedAt = 100),
        )
        val moved = ProgressRules.resolve(
            current = null,
            candidate = PlaybackProgress(itemId = "work", page = 3, lastOpenedAt = 100),
        )
        val done = ProgressRules.resolve(
            current = null,
            candidate = PlaybackProgress(itemId = "work", page = 0, finished = true, lastOpenedAt = 100),
        )

        assertNull("第 1 页且没有打开标记时不能推断已开始", untouched?.openedAt)
        assertEquals(100L, moved?.openedAt)
        assertEquals(100L, done?.openedAt)
    }

    @Test
    fun openingAWorkRecordsTheEventWithoutChangingThePosition() {
        val current = stored(page = 7, lastOpenedAt = 100, openedAt = 40)

        val opened = ProgressRules.opened(current, itemId = "work", at = 300)

        assertEquals(7, opened.page)
        assertEquals(40L, opened.openedAt)
        assertEquals(300L, opened.lastOpenedAt)
    }

    @Test
    fun openingAFirstPageRecordsAnOpenMarkerWithPageZero() {
        val opened = ProgressRules.opened(current = null, itemId = "work", at = 500)

        assertEquals(0, opened.page)
        assertEquals(500L, opened.openedAt)
        assertTrue(opened.opened)
        assertEquals("第 1 页也算已经开始", true, opened.opened)
    }

    @Test
    fun openingNeverMovesTheStampBackwards() {
        val current = stored(page = 2, lastOpenedAt = 900, openedAt = null)

        val opened = ProgressRules.opened(current, itemId = "work", at = 100)

        assertEquals(900L, opened.lastOpenedAt)
        assertEquals(100L, opened.openedAt)
        assertNotNull(opened.openedAt)
    }
}
