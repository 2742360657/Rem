package dev.susnowy.gallery.model

/**
 * Pure rules for adding Works to an existing Group or Series from a selection.
 *
 * Kept free of Android types so the behaviour that decides what a batch action actually
 * writes can be unit-tested: the UI only reports the outcome.
 */
object MembershipRules {
    /**
     * Appends [added] to [existing]: existing order is preserved, duplicates are dropped and
     * the newly added Works keep the order the user selected them in.
     */
    fun appendMembers(existing: List<String>, added: List<String>): List<String> {
        val result = existing.toMutableList()
        added.forEach { workId ->
            if (workId !in result) result += workId
        }
        return result
    }

    /**
     * Splits a selection into the Works that may join [targetSeriesId] and the ones that
     * cannot.
     *
     * A Work belongs to at most one Series, so a Work already numbered in a different series
     * is reported instead of being silently moved: the user has to make that decision
     * explicitly.
     */
    fun seriesJoinable(
        targetSeriesId: String,
        works: List<MediaItem>,
    ): Pair<List<MediaItem>, List<MediaItem>> {
        val joinable = mutableListOf<MediaItem>()
        val skipped = mutableListOf<MediaItem>()
        works.forEach { work ->
            val current = work.series
            if (current == null || current.id == targetSeriesId) joinable += work else skipped += work
        }
        return joinable to skipped
    }

    /** Works that are already Group members, so adding them again changes nothing. */
    fun alreadyMembers(existing: List<String>, added: List<String>): List<String> =
        added.filter { it in existing }
}
