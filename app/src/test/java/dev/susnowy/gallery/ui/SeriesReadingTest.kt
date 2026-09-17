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
    ) = PlaybackProgress(itemId = id, page = page, finished = finished, lastOpenedAt = lastOpenedAt)

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
    fun reachingTheLastPageCountsAsFinishedEvenWithoutTheFlag() {
        val chapters = SeriesReading.chapters(
            listOf(chapter("a", pageCount = 20), chapter("b")),
            mapOf("a" to progress("a", page = 19)),
        )

        assertTrue(chapters.first().finished)
        assertEquals("已读完", chapters.first().progressLabel())
        assertEquals("b", SeriesReading.entry(chapters).chapter?.item?.id)
    }

    @Test
    fun anUnknownPageCountDoesNotPretendAChapterIsFinished() {
        val chapters = SeriesReading.chapters(
            listOf(chapter("a", pageCount = null), chapter("b")),
            mapOf("a" to progress("a", page = 0)),
        )

        assertFalse(chapters.first().finished)
        assertEquals("未开始", chapters.first().progressLabel())
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
}
