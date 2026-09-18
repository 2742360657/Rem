package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Series is one entry point, so these rules decide what "continue reading" and "next chapter"
 * mean. They are pure so a shelf with hundreds of chapters behaves the same as one with three.
 */
class SeriesReadingTest {
    private fun chapter(
        id: String,
        pageCount: Int? = 20,
    ) = MediaItem(
        id = id,
        libraryId = "library",
        relativePath = "Comics/$id.cbz",
        uri = "content://$id",
        kind = MediaKind.IMAGE_SET,
        domain = MediaDomain.WORKS,
        sourceKind = SourceKind.ARCHIVE,
        displayTitle = id,
        pageCount = pageCount,
    )

    private fun progress(
        id: String,
        page: Int = 0,
        finished: Boolean = false,
        lastOpenedAt: Long = 0,
        openedAt: Long? = if (page > 0 || finished) lastOpenedAt else null,
    ) = PlaybackProgress(
        itemId = id,
        page = page,
        finished = finished,
        lastOpenedAt = lastOpenedAt,
        openedAt = openedAt,
    )

    @Test
    fun anOpenedButUnfinishedChapterIsWhereReadingContinues() {
        val chapters = SeriesReading.chapters(
            listOf(chapter("a"), chapter("b"), chapter("c")),
            mapOf("a" to progress("a", page = 19, finished = true), "b" to progress("b", page = 4)),
        )

        val entry = SeriesReading.entry(chapters)

        assertEquals("b", entry.chapter?.item?.id)
        assertTrue(entry.label.startsWith("继续阅读"))
        assertTrue(entry.label.contains("第 5 / 20 页"))
    }

    @Test
    fun anUntouchedSeriesStartsAtItsFirstChapter() {
        val chapters = SeriesReading.chapters(listOf(chapter("a"), chapter("b")), emptyMap())

        val entry = SeriesReading.entry(chapters)

        assertEquals("a", entry.chapter?.item?.id)
        assertTrue(entry.label.startsWith("开始阅读"))
    }

    @Test
    fun aFullyReadSeriesStartsOverFromTheFirstChapter() {
        val chapters = SeriesReading.chapters(
            listOf(chapter("a"), chapter("b")),
            mapOf(
                "a" to progress("a", finished = true),
                "b" to progress("b", page = 19, finished = true),
            ),
        )

        val entry = SeriesReading.entry(chapters)
        val summary = SeriesReading.summarize(chapters)

        assertEquals("a", entry.chapter?.item?.id)
        assertTrue(entry.label.startsWith("重新阅读"))
        assertEquals(2, summary.finished)
        assertEquals("2 话 · 已读完", summary.label())
    }

    @Test
    fun restoringTheLastPageDoesNotPretendTheChapterWasFinished() {
        val chapters = SeriesReading.chapters(
            listOf(chapter("a", pageCount = 20), chapter("b")),
            mapOf("a" to progress("a", page = 19)),
        )

        assertFalse(chapters.first().finished)
        assertEquals("第 20 / 20 页", chapters.first().progressLabel())
        assertEquals("a", SeriesReading.entry(chapters).chapter?.item?.id)
    }

    @Test
    fun anUnknownPageCountDoesNotPretendAChapterIsFinished() {
        val chapters = SeriesReading.chapters(
            listOf(chapter("a", pageCount = null), chapter("b")),
            mapOf("a" to progress("a", page = 0, lastOpenedAt = 4, openedAt = 4)),
        )

        assertFalse(chapters.first().finished)
        assertEquals("第 1 页", chapters.first().progressLabel())
        assertTrue("打开过就属于已开始", chapters.first().started)
    }

    @Test
    fun theNextChapterIsTheFollowingEntryOrNothing() {
        val chapters = SeriesReading.chapters(
            listOf(chapter("a"), chapter("b"), chapter("c")),
            emptyMap(),
        )

        assertEquals("b", SeriesReading.nextAfter(chapters, chapters[0])?.item?.id)
        assertEquals("c", SeriesReading.nextAfter(chapters, chapters[1])?.item?.id)
        assertNull("最后一话没有下一话", SeriesReading.nextAfter(chapters, chapters[2]))
    }

    @Test
    fun anEmptySeriesHasNothingToOpen() {
        val entry = SeriesReading.entry(emptyList())

        assertFalse(entry.enabled)
        assertNull(entry.chapter)
    }

    @Test
    fun theSummaryReportsHowFarTheSeriesWasRead() {
        val chapters = SeriesReading.chapters(
            listOf(chapter("a"), chapter("b"), chapter("c")),
            mapOf("a" to progress("a", finished = true)),
        )

        val summary = SeriesReading.summarize(chapters)

        assertEquals(3, summary.total)
        assertEquals(1, summary.finished)
        assertEquals(1, summary.started)
        assertFalse(summary.untouched)
        assertEquals("3 话 · 已读 1", summary.label())
    }

    @Test
    fun progressLabelsCoverTheThreeVisibleStates() {
        val untouched = SeriesReading.chapters(listOf(chapter("a")), emptyMap()).first()
        val started = SeriesReading.chapters(
            listOf(chapter("a")),
            mapOf("a" to progress("a", page = 2)),
        ).first()
        val done = SeriesReading.chapters(
            listOf(chapter("a")),
            mapOf("a" to progress("a", finished = true)),
        ).first()

        assertEquals("未开始", untouched.progressLabel())
        assertEquals("第 3 / 20 页", started.progressLabel())
        assertEquals("已读完", done.progressLabel())
    }

    @Test
    fun openingTheFirstPageCountsAsStarted() {
        val opened = SeriesReading.chapters(
            listOf(chapter("a")),
            mapOf("a" to progress("a", page = 0, lastOpenedAt = 10, openedAt = 10)),
        ).first()

        assertTrue(opened.started)
        assertEquals("第 1 / 20 页", opened.progressLabel())
        assertFalse(opened.finished)
    }

    /**
     * A reading row can exist without the Work ever being opened (a video position, a leftover
     * row from an older index). "Not started" must be decided by the open marker, not by the
     * presence of a row.
     */
    @Test
    fun aRowWithoutAnOpenMarkerIsStillNotStarted() {
        val row = SeriesReading.chapters(
            listOf(chapter("a")),
            mapOf("a" to progress("a", page = 0, lastOpenedAt = 10, openedAt = null)),
        ).first()

        assertFalse(row.started)
        assertEquals("未开始", row.progressLabel())
    }

    /** Page 0 is a real position, so a chapter that is genuinely at page 1 is "started". */
    @Test
    fun aChapterShowingItsFirstPageIsNotUnstarted() {
        val chapters = SeriesReading.chapters(
            listOf(chapter("a"), chapter("b")),
            mapOf("a" to progress("a", page = 0, lastOpenedAt = 5, openedAt = 5)),
        )

        assertEquals(1, SeriesReading.summarize(chapters).started)
        assertEquals("a", SeriesReading.entry(chapters).chapter?.item?.id)
    }

    @Test
    fun resumingAFinishedChapterStartsFromItsFirstPage() {
        val done = SeriesReading.chapters(
            listOf(chapter("a")),
            mapOf("a" to progress("a", page = 19, finished = true, lastOpenedAt = 30)),
        ).first()
        val partial = SeriesReading.chapters(
            listOf(chapter("a")),
            mapOf("a" to progress("a", page = 7, lastOpenedAt = 30)),
        ).first()
        val untouched = SeriesReading.chapters(listOf(chapter("a")), emptyMap()).first()

        assertEquals("重新阅读必须从第 1 页开始", 0, done.resumePageIndex())
        assertEquals(7, partial.resumePageIndex())
        assertEquals(0, untouched.resumePageIndex())
    }

    /** Every entry point resolves the start page with the same rule, so they cannot disagree. */
    @Test
    fun theStartPageRuleIsSharedByAllEntryPoints() {
        val opened = progress("a", page = 7, lastOpenedAt = 30)
        val done = progress("a", page = 19, finished = true, lastOpenedAt = 30)

        assertEquals(7, resumePageIndex(pageCount = 20, progress = opened))
        assertEquals(0, resumePageIndex(pageCount = 20, progress = done))
        assertEquals(0, resumePageIndex(pageCount = 20, progress = opened, restart = true))
        assertEquals("未知页数时回到第一页", 0, resumePageIndex(pageCount = 0, progress = opened))
        assertEquals("越界的旧进度必须被钳制", 19, resumePageIndex(pageCount = 20, progress = progress("a", page = 99, lastOpenedAt = 30)))
    }

    /**
     * Continuing a chapter that was left scrolled onto its last item must not land exactly on the
     * end-of-chapter entry, or the chapter would look read the moment it opens.
     */
    @Test
    fun resumingNeverLandsExactlyOnTheEndOfChapterEntry() {
        val opened = progress("a", page = 4, lastOpenedAt = 30)

        assertEquals(
            "最后一页还有内容时照常恢复到该页",
            4,
            resumePageIndex(pageCount = 5, progress = opened),
        )
        assertEquals(
            "显式重读从第 1 页开始",
            0,
            resumePageIndex(pageCount = 1, progress = opened, restart = true),
        )
    }

    @Test
    fun continueReadingChoosesTheMostRecentlyOpenedPartialChapter() {
        val chapters = SeriesReading.chapters(
            listOf(chapter("a"), chapter("b"), chapter("c")),
            mapOf(
                "a" to progress("a", page = 4, lastOpenedAt = 20),
                "c" to progress("c", page = 2, lastOpenedAt = 40),
            ),
        )

        assertEquals("c", SeriesReading.continueChapter(chapters)?.item?.id)
    }

    /**
     * The exact state of a one-page chapter on its first frame: the list already shows page 1, it
     * cannot scroll forward, but the restored position has not been applied yet. Treating that as
     * "the whole chapter fits" is what made opening such a chapter mark it read on the spot.
     */
    @Test
    fun anUnsettledReaderNeverReportsTheChapterAsFinished() {
        val unsettled = chapterEndState(
            pageCount = 1,
            lastVisibleItemIndex = 0,
            canScrollForward = false,
            advancedAfterRestore = false,
            footnoteVisible = false,
            settled = false,
        )

        assertFalse(unsettled.reachedEnd)
        assertFalse(unsettled.arrivedByScrolling)
    }

    @Test
    fun aFittingChapterIsRecordedButNeverHandsOverAutomatically() {
        val settled = chapterEndState(
            pageCount = 1,
            lastVisibleItemIndex = 1,
            canScrollForward = false,
            advancedAfterRestore = false,
            footnoteVisible = true,
            settled = true,
        )
        val arrived = chapterEndState(
            pageCount = 3,
            lastVisibleItemIndex = 3,
            canScrollForward = false,
            advancedAfterRestore = true,
            footnoteVisible = true,
            settled = true,
        )

        assertTrue("整话都在屏幕上时可以记为已读", settled.reachedEnd)
        assertFalse("没有向前移动过就不能自动衔接", settled.arrivedByScrolling)
        assertTrue(arrived.reachedEnd)
        assertTrue("真正读到末尾才自动衔接", arrived.arrivedByScrolling)
    }

    private fun chapterEndState(
        pageCount: Int,
        lastVisibleItemIndex: Int,
        canScrollForward: Boolean,
        advancedAfterRestore: Boolean,
        footnoteVisible: Boolean,
        settled: Boolean,
    ) = dev.susnowy.gallery.ui.chapterEndState(
        pageCount = pageCount,
        lastVisibleItemIndex = lastVisibleItemIndex,
        canScrollForward = canScrollForward,
        advancedAfterRestore = advancedAfterRestore,
        footnoteVisible = footnoteVisible,
        settled = settled,
    )

    @Test
    fun chapterEndRequiresForwardMovementAndThePhysicalEndOfContent() {
        assertFalse(
            chapterEndReached(
                pageCount = 3,
                lastVisibleItemIndex = 2,
                canScrollForward = false,
                advancedAfterRestore = false,
            ),
        )
        assertFalse(
            chapterEndReached(
                pageCount = 3,
                lastVisibleItemIndex = 2,
                canScrollForward = true,
                advancedAfterRestore = true,
            ),
        )
        assertTrue(
            chapterEndReached(
                pageCount = 3,
                lastVisibleItemIndex = 3,
                canScrollForward = false,
                advancedAfterRestore = true,
            ),
        )
        assertFalse(
            chapterEndReached(
                pageCount = 0,
                lastVisibleItemIndex = 0,
                canScrollForward = false,
                advancedAfterRestore = true,
            ),
        )
    }

    /**
     * A chapter whose end fits in the viewport — one page, or a short last page — leaves no
     * forward movement to prove that the reader arrived, so completion could never be recorded.
     * Fitting on screen records it, but it must not be what drives the automatic hand-over.
     */
    @Test
    fun aChapterThatFitsOnScreenCanBeRecordedWithoutPretendingTheReaderArrived() {
        assertFalse(
            "恢复位置就是末尾时不算到达",
            chapterEndReached(
                pageCount = 1,
                lastVisibleItemIndex = 1,
                canScrollForward = false,
                advancedAfterRestore = false,
            ),
        )
        assertTrue(
            "整话都在屏幕上，可以记为已读",
            chapterFitsOnScreen(
                pageCount = 1,
                lastVisibleItemIndex = 1,
                canScrollForward = false,
                footnoteVisible = true,
            ),
        )
        assertFalse(
            "没看到末尾入口时不能记为已读",
            chapterFitsOnScreen(
                pageCount = 1,
                lastVisibleItemIndex = 0,
                canScrollForward = false,
                footnoteVisible = false,
            ),
        )
        assertFalse(
            "还能继续滚动时不能记为已读",
            chapterFitsOnScreen(
                pageCount = 2,
                lastVisibleItemIndex = 2,
                canScrollForward = true,
                footnoteVisible = true,
            ),
        )
    }}
