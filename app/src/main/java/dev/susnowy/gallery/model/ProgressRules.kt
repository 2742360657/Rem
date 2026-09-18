package dev.susnowy.gallery.model

/**
 * What a reading-progress write is allowed to do to the state already stored.
 *
 * The rule is here, pure and unit tested, because the two properties it protects are the ones the
 * UI depends on and the ones a coroutine scheduler can silently break:
 *
 * 1. **Ordering.** Page observers, the video player and chapter hand-over all launch writes from
 *    coroutines. A write stamped before the stored one is a stale event that started earlier and
 *    finished later; applying it would move the reading position backwards.
 * 2. **First open.** `opened_at` records when the Work was first opened, never when it was last
 *    saved. Refreshing it on every page turn would destroy "continue reading" ordering, and
 *    clearing it would make an already-opened Work look untouched.
 */
object ProgressRules {
    /**
     * The row to store, or null when [candidate] is a stale event that must be dropped.
     *
     * [current] is the row read at commit time, so this also rejects a write that lost a race with
     * another one for the same Work.
     */
    fun resolve(current: PlaybackProgress?, candidate: PlaybackProgress): PlaybackProgress? {
        if (current != null && current.lastOpenedAt > candidate.lastOpenedAt) return null
        return candidate.copy(
            // Keep the earliest recorded open: it is the one reading session that started.
            openedAt = current?.openedAt
                ?: candidate.openedAt
                ?: candidate.lastOpenedAt.takeIf { candidate.page > 0 || candidate.finished },
        )
    }

    /**
     * The row that records "the user opened this Work" without changing where they are in it.
     *
     * Opening a chapter and turning to its first page are separate events: the first one is what
     * makes the chapter list show "第 1 / N 页" instead of "未开始".
     */
    fun opened(current: PlaybackProgress?, itemId: String, at: Long): PlaybackProgress {
        val base = current ?: PlaybackProgress(itemId = itemId)
        return base.copy(
            lastOpenedAt = maxOf(at, base.lastOpenedAt),
            openedAt = base.openedAt ?: at,
        )
    }
}
