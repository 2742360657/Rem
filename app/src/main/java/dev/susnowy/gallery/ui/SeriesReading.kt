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

    val started: Boolean get() = progress != null

    /** "第 12 / 40 页", "未开始" or "已读完" for the chapter row. */
    fun progressLabel(): String {
        val count = pageCount
        return when {
            finished -> "已读完"
            progress != null && count != null -> "第 ${lastPage + 1} / $count 页"
            progress != null -> "第 ${lastPage + 1} 页"
            else -> "未开始"
        }
    }
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
 * still have most of its content below the viewport.
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

data class SeriesSummary(val total: Int, val finished: Int, val started: Int) {
    val untouched: Boolean get() = total > 0 && started == 0

    fun label(): String = when {
        total == 0 -> "没有作品"
        finished == total -> "$total 话 · 已读完"
        started == 0 -> "$total 话 · 未开始"
        else -> "$total 话 · 已读 $finished"
    }
}
