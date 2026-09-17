package dev.susnowy.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesModelsTest {
    private fun series(vararg members: MediaSeriesMember) = MediaSeries(
        id = "series-1",
        libraryId = "library-id",
        title = "某系列",
        members = members.toList(),
    )

    @Test
    fun listOrderBecomesSortIndex() {
        val portable = series(
            MediaSeriesMember("work-b", chapter = 9.0),
            MediaSeriesMember("work-a", chapter = 3.0),
        ).toPortableSeries(order = listOf("work-a", "work-b"))

        assertEquals(listOf("work-a", "work-b"), portable.members.map(PortableSeriesMember::workId))
        assertEquals(listOf(0.0, 1.0), portable.members.map(PortableSeriesMember::sortIndex))
        // Numbering travels with its own member, not with the position.
        assertEquals(3.0, portable.members.first().chapter!!, 0.0001)
        assertEquals(9.0, portable.members.last().chapter!!, 0.0001)
    }

    @Test
    fun clearingNumberingDropsPositionsButKeepsOrder() {
        val portable = series(
            MediaSeriesMember("work-a", season = 1, episode = 2.0, volume = 3.0, chapter = 4.0),
        ).toPortableSeries(order = listOf("work-a"), clearPositions = true)

        val member = portable.members.single()
        assertNull(member.season)
        assertNull(member.episode)
        assertNull(member.volume)
        assertNull(member.chapter)
        assertEquals(0.0, member.sortIndex)
    }

    @Test
    fun readingOrderPrefersManualOrderThenNumbering() {
        val model = series(
            MediaSeriesMember("work-c"),
            MediaSeriesMember("work-b", sortIndex = 1.0),
            MediaSeriesMember("work-a", sortIndex = 0.0),
        )

        assertEquals(listOf("work-a", "work-b", "work-c"), model.membersInOrder().map(MediaSeriesMember::workId))

        val numbered = series(
            MediaSeriesMember("work-b", season = 2, episode = 1.0),
            MediaSeriesMember("work-a", season = 1, episode = 4.0),
        )
        assertEquals(listOf("work-a", "work-b"), numbered.membersInOrder().map(MediaSeriesMember::workId))
    }

    @Test
    fun positionLabelSummarisesNumbering() {
        val model = series(
            MediaSeriesMember("work-a", season = 1, episode = 2.0, chapter = 12.0),
            MediaSeriesMember("work-b", chapter = 7.5),
            MediaSeriesMember("work-c"),
        )

        assertEquals("第 1 季 · 第 2 集 · 第 12 章", model.positionLabel("work-a"))
        assertEquals("第 7.5 章", model.positionLabel("work-b"))
        assertNull(model.positionLabel("work-c"))
    }
}
