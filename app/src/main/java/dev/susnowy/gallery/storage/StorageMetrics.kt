package dev.susnowy.gallery.storage

import java.util.concurrent.atomic.AtomicLong

/**
 * Aggregated cost of the provider calls that dominate scanning on removable storage.
 *
 * Only totals and a slow-call count are kept: the point is to answer "how much of the scan was
 * spent waiting on the provider" without writing a log line per query, because on a real drive
 * there can be tens of thousands of them. Numbers are reported through [RemLog] by the scanner,
 * which is what makes a run possible when ADB cannot be attached at the same time as the drive.
 */
object StorageMetrics {
    private val calls = AtomicLong()
    private val totalNanos = AtomicLong()
    private val slowCalls = AtomicLong()
    private val worstNanos = AtomicLong()

    /** A provider query slower than this is counted as slow (matches the earlier field data). */
    const val SLOW_CALL_MILLIS = 200L

    fun reset() {
        calls.set(0)
        totalNanos.set(0)
        slowCalls.set(0)
        worstNanos.set(0)
    }

    inline fun <T> measure(block: () -> T): T {
        val startedAt = System.nanoTime()
        try {
            return block()
        } finally {
            record(System.nanoTime() - startedAt)
        }
    }

    fun record(nanos: Long) {
        calls.incrementAndGet()
        totalNanos.addAndGet(nanos)
        if (nanos >= SLOW_CALL_MILLIS * 1_000_000) slowCalls.incrementAndGet()
        worstNanos.accumulateAndGet(nanos) { current, candidate -> maxOf(current, candidate) }
    }

    fun snapshot(): Snapshot = Snapshot(
        calls = calls.get(),
        totalMillis = totalNanos.get() / 1_000_000,
        slowCalls = slowCalls.get(),
        worstMillis = worstNanos.get() / 1_000_000,
    )

    /** One-line summary for the diagnostic log. */
    fun summary(): String = snapshot().let { state ->
        if (state.calls == 0L) {
            "provider 调用=0"
        } else {
            "provider 调用=${state.calls}，累计=${state.totalMillis} ms，" +
                "平均=${state.totalMillis / state.calls} ms，" +
                "超过 ${SLOW_CALL_MILLIS} ms=${state.slowCalls}，最慢=${state.worstMillis} ms"
        }
    }

    data class Snapshot(
        val calls: Long,
        val totalMillis: Long,
        val slowCalls: Long,
        val worstMillis: Long,
    )
}
