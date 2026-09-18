package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.PlaybackProgress

/**
 * One chapter of a Series as the shelf lists it: the Work plus what the reader has done with it.
 *
 * A Series is one entry point, so the list has to answer "where was I" without opening anything.
 */
data class SeriesChapter(
    val item: MediaItem,
    val position: Int,
    val progress: PlaybackProgress?,
) {
    /** Page count known from the local index, or null while it is still unknown. */
    val pageCount: Int? get() = item.pageCount?.takeIf { it > 0 }

    val lastPage: Int get() = progress?.page?.coerceAtLeast(0) ?: 0

    /** A chapter counts as read only after the reader explicitly records reaching its end. */
    val finished: Boolean
        get() = progress?.finished == true

    /**
     * Opened at least once.
     *
     * Page 0 is a real position, so "not started" means "no reading state at all", not "page 1":
     * a chapter whose first page is on screen must not look untouched in the list.
     */
    val started: Boolean get() = progress?.opened == true

    /** "第 12 / 40 页", "未开始" or "已读完" for the chapter row. */
    fun progressLabel(): String {
        val count = pageCount
        return when {
            finished -> "已读完"
            started && count != null -> "第 ${lastPage + 1} / $count 页"
            started -> "第 ${lastPage + 1} 页"
            else -> "未开始"
        }
    }

    /**
     * Where the reader should resume this chapter: page 1 for an unopened or finished chapter,
     * otherwise the saved page. Resuming must never silently reopen a finished chapter at its
     * last page.
     */
    fun resumePageIndex(): Int = resumePageIndex(pageCount ?: 0, progress)
}

/** What the "continue reading" entry should do for a whole series. */
data class SeriesReadingEntry(
    val chapter: SeriesChapter?,
    val label: String,
) {
    val enabled: Boolean get() = chapter != null
}

/**
 * Pure reading state of one ordered chapter list.
 *
 * Reading a Series is "start where I stopped, then keep going", so the entry point and the
 * next-chapter link come from the same three rules: an unfinished chapter that was already
 * opened wins, otherwise the first unread one, otherwise start over from the beginning.
 */
object SeriesReading {

    fun chapters(items: List<MediaItem>, progress: Map<String, PlaybackProgress>): List<SeriesChapter> =
        items.mapIndexed { index, item ->
            SeriesChapter(item = item, position = index, progress = progress[item.id])
        }

    /** The chapter "继续阅读" should open, or null for an empty series. */
    fun continueChapter(chapters: List<SeriesChapter>): SeriesChapter? {
        if (chapters.isEmpty()) return null
        chapters.asSequence()
            .filter { it.started && !it.finished }
            .maxByOrNull { it.progress?.lastOpenedAt ?: Long.MIN_VALUE }
            ?.let { return it }
        chapters.firstOrNull { !it.finished }?.let { return it }
        // Everything is finished: reading again starts from the first chapter.
        return chapters.first()
    }

    fun entry(chapters: List<SeriesChapter>): SeriesReadingEntry {
        val chapter = continueChapter(chapters) ?: return SeriesReadingEntry(null, "没有可阅读的话")
        val label = when {
            chapter.started && !chapter.finished ->
                "继续阅读 · 第 ${chapter.position + 1} 话（${chapter.progressLabel()}）"
            chapters.all(SeriesChapter::finished) -> "重新阅读 · 第 1 话"
            else -> "开始阅读 · 第 ${chapter.position + 1} 话"
        }
        return SeriesReadingEntry(chapter, label)
    }

    fun nextAfter(chapters: List<SeriesChapter>, current: SeriesChapter): SeriesChapter? =
        chapters.getOrNull(current.position + 1)

    /** Overall shelf progress: how many chapters are finished and whether anything was opened. */
    fun summarize(chapters: List<SeriesChapter>): SeriesSummary = SeriesSummary(
        total = chapters.size,
        finished = chapters.count(SeriesChapter::finished),
        started = chapters.count(SeriesChapter::started),
    )
}

/**
 * True only after the reader moved forward from its restored position and reached the physical
 * end of the chapter. Merely restoring the last page is not completion: a tall last page may
 * still have most of its content below the viewport, and reopening a finished chapter must not
 * immediately jump onward.
 */
fun chapterEndReached(
    pageCount: Int,
    lastVisibleItemIndex: Int,
    canScrollForward: Boolean,
    advancedAfterRestore: Boolean,
): Boolean = pageCount > 0 &&
    advancedAfterRestore &&
    lastVisibleItemIndex >= pageCount - 1 &&
    !canScrollForward

/**
 * True when the whole chapter fits on screen, so there is no forward movement left to make.
 *
 * A chapter whose end is visible from the start — a one-page chapter, or a chapter opened while
 * the restored position is still being resolved — can never satisfy [chapterEndReached]. This is
 * the second way to record that such a chapter was read. It is deliberately *not* what drives the
 * automatic hand-over: an end that was already on screen when the chapter opened is not an
 * arrival, and a single-page chapter would otherwise mark itself finished and jump onward before
 * the reader saw anything.
 */
fun chapterFitsOnScreen(
    pageCount: Int,
    lastVisibleItemIndex: Int,
    canScrollForward: Boolean,
    footnoteVisible: Boolean,
): Boolean = pageCount > 0 &&
    !canScrollForward &&
    footnoteVisible &&
    lastVisibleItemIndex >= pageCount - 1

/** What the reader should do about the end of the current chapter. */
data class ChapterEndState(
    /** The chapter may be recorded as read. */
    val reachedEnd: Boolean,
    /** The reader actually moved to the end, so it may continue automatically. */
    val arrivedByScrolling: Boolean,
)

/**
 * Decides the end-of-chapter outcome in one place.
 *
 * [settled] must be false while the restored position is still being applied. On the first frame
 * of a one-page chapter the list is already showing page 1 and not yet scrolled to the restored
 * position, which looks exactly like "the whole chapter fits" — that is how opening such a chapter
 * used to mark it read and hand over before the reader saw anything.
 */
fun chapterEndState(
    pageCount: Int,
    lastVisibleItemIndex: Int,
    canScrollForward: Boolean,
    advancedAfterRestore: Boolean,
    footnoteVisible: Boolean,
    settled: Boolean,
): ChapterEndState {
    if (!settled) return ChapterEndState(reachedEnd = false, arrivedByScrolling = false)
    val arrived = chapterEndReached(
        pageCount = pageCount,
        lastVisibleItemIndex = lastVisibleItemIndex,
        canScrollForward = canScrollForward,
        advancedAfterRestore = advancedAfterRestore,
    )
    if (arrived) return ChapterEndState(reachedEnd = true, arrivedByScrolling = true)
    val fits = chapterFitsOnScreen(
        pageCount = pageCount,
        lastVisibleItemIndex = lastVisibleItemIndex,
        canScrollForward = canScrollForward,
        footnoteVisible = footnoteVisible,
    )
    return ChapterEndState(reachedEnd = fits, arrivedByScrolling = false)
}

/**
 * Page index the reader must open at.
 *
 * One rule for every entry point, so "继续阅读", the chapter list and the detail page cannot
 * disagree: an explicit restart, an unopened Work and a finished Work all start at page 1;
 * anything else resumes where it stopped. Restoring the saved page of a finished chapter is what
 * used to re-open it at its last page instead of reading it again.
 */
fun resumePageIndex(pageCount: Int, progress: PlaybackProgress?, restart: Boolean = false): Int {
    if (pageCount <= 0) return 0
    if (restart) return 0
    if (progress?.opened != true || progress.finished) return 0
    return progress.page.coerceIn(0, pageCount - 1)
}

data class SeriesSummary(val total: Int, val finished: Int, val started: Int) {
    val untouched: Boolean get() = total > 0 && started == 0

    fun label(): String = when {
        total == 0 -> "没有作品"
        finished == total -> "$total 话 · 已读完"
        started == 0 -> "$total 话 · 未开始"
        else -> "$total 话 · 已读 $finished"
    }
}
