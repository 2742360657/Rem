package dev.susnowy.gallery.data

import dev.susnowy.gallery.model.SeriesRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesProjectionTest {
    @Test
    fun sameNamedSeriesNeverResolveAcrossDifferentPortableIds() {
        val current = SeriesRef(id = "series-b", title = "同名系列")
        val other = SeriesRef(id = "series-a", title = "同名系列")

        assertNull(resolveCanonicalSeries(current, mapOf(other.id to other)))
    }

    @Test
    fun matchingPortableIdCanRefreshTheProjectedTitle() {
        val current = SeriesRef(id = "series-a", title = "旧标题", chapter = 3.0)
        val renamed = SeriesRef(id = "series-a", title = "新标题")

        assertEquals(renamed, resolveCanonicalSeries(current, mapOf(renamed.id to renamed)))
    }
}
