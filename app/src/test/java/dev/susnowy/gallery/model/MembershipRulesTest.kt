package dev.susnowy.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipRulesTest {
    private fun work(id: String, seriesId: String? = null, seriesTitle: String = "其他系列") = MediaItem(
        id = id,
        libraryId = "library-id",
        relativePath = "Comics/$id",
        uri = "content://$id",
        kind = MediaKind.IMAGE_SET,
        sourceKind = SourceKind.DIRECTORY,
        displayTitle = id,
        series = seriesId?.let { SeriesRef(id = it, title = seriesTitle) },
    )

    @Test
    fun appendingKeepsExistingOrderAndDropsDuplicates() {
        val result = MembershipRules.appendMembers(
            existing = listOf("a", "b"),
            added = listOf("b", "c", "a", "d"),
        )

        assertEquals(listOf("a", "b", "c", "d"), result)
    }

    @Test
    fun appendingToAnEmptyGroupKeepsSelectionOrder() {
        assertEquals(
            listOf("c", "a"),
            MembershipRules.appendMembers(emptyList(), listOf("c", "a")),
        )
    }

    @Test
    fun worksInAnotherSeriesAreSkippedInsteadOfMoved() {
        val (joinable, skipped) = MembershipRules.seriesJoinable(
            targetSeriesId = "series-1",
            works = listOf(
                work("free"),
                work("member", seriesId = "series-1"),
                work("elsewhere", seriesId = "series-2"),
            ),
        )

        assertEquals(listOf("free", "member"), joinable.map(MediaItem::id))
        assertEquals(listOf("elsewhere"), skipped.map(MediaItem::id))
    }

    @Test
    fun alreadyMembersAreReportedForGroups() {
        assertEquals(
            listOf("a"),
            MembershipRules.alreadyMembers(existing = listOf("a", "b"), added = listOf("a", "c")),
        )
        assertTrue(MembershipRules.alreadyMembers(emptyList(), listOf("a")).isEmpty())
    }
}
