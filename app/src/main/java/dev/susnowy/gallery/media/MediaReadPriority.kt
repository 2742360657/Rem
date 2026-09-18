package dev.susnowy.gallery.media

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext

/** Foreground reads preempt repeatable background reads; caller cancellation never retries. */
class MediaReadPriority {
    private val monitor = Any()
    private val foregroundCount = MutableStateFlow(0)
    private val backgroundReads = mutableSetOf<Job>()
    private class Preempted : CancellationException("Foreground media read")

    suspend fun <T> foreground(block: suspend () -> T): T {
        val cancelled = synchronized(monitor) {
            foregroundCount.value++
            backgroundReads.toList()
        }
        cancelled.forEach { it.cancel(Preempted()) }
        try { return block() }
        finally { synchronized(monitor) { foregroundCount.value-- } }
    }

    suspend fun awaitBackgroundTurn() {
        coroutineContext.ensureActive()
        foregroundCount.first { it == 0 }
    }

    /** [block] must be read-only or write disposable caches: preemption can run it again. */
    suspend fun <T> background(block: suspend () -> T): T {
        while (true) {
            coroutineContext.ensureActive()
            awaitBackgroundTurn()
            try {
                return supervisorScope {
                    val task = async(start = CoroutineStart.LAZY) { block() }
                    val admitted = synchronized(monitor) {
                        if (foregroundCount.value == 0) { backgroundReads += task; true } else false
                    }
                    if (!admitted) task.cancel(Preempted())
                    try { task.await() }
                    finally { synchronized(monitor) { backgroundReads -= task } }
                }
            } catch (preempted: Preempted) {
                coroutineContext.ensureActive()
            }
        }
    }
}
