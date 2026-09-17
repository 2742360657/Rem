package dev.susnowy.gallery.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageMetricsTest {
    @Test
    fun countsCallsAndFlagsSlowOnes() {
        StorageMetrics.reset()

        StorageMetrics.record(10L * 1_000_000)
        StorageMetrics.record(250L * 1_000_000)
        StorageMetrics.record(50L * 1_000_000)

        val snapshot = StorageMetrics.snapshot()
        assertEquals(3, snapshot.calls)
        assertEquals(310, snapshot.totalMillis)
        assertEquals(1, snapshot.slowCalls)
        assertEquals(250, snapshot.worstMillis)
        assertTrue(StorageMetrics.summary().contains("超过 200 ms=1"))
    }

    @Test
    fun measuresTheBlockEvenWhenItThrows() {
        StorageMetrics.reset()

        runCatching { StorageMetrics.measure<Unit> { error("provider refused") } }

        assertEquals(1, StorageMetrics.snapshot().calls)
    }

    @Test
    fun reportsZeroWithoutCalls() {
        StorageMetrics.reset()

        assertEquals("provider 调用=0", StorageMetrics.summary())
    }
}
