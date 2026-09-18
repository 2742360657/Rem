package dev.susnowy.gallery.media

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.coroutines.coroutineContext

/** Foreground reads preempt disposable previews; caller cancellation never triggers a retry. */
class MediaReadPriority {
    private val monitor = Any()
    private val foregroundCount = MutableStateFlow(0)
    private val previews = mutableSetOf<Job>()
    private class Preempted : CancellationException("Foreground media read")

    suspend fun <T> foreground(block: suspend () -> T): T {
        val cancelled = synchronized(monitor) {
            foregroundCount.value++
            previews.toList()
        }
        cancelled.forEach { it.cancel(Preempted()) }
        try { return block() }
        finally { synchronized(monitor) { foregroundCount.value-- } }
    }

    suspend fun <T> preview(block: suspend () -> T): T {
        while (true) {
            coroutineContext.ensureActive()
            foregroundCount.first { it == 0 }
            try {
                return supervisorScope {
                    val task = async(start = CoroutineStart.LAZY) { block() }
                    val admitted = synchronized(monitor) {
                        if (foregroundCount.value == 0) { previews += task; true } else false
                    }
                    if (!admitted) task.cancel(Preempted())
                    try { task.await() }
                    finally { synchronized(monitor) { previews -= task } }
                }
            } catch (preempted: Preempted) {
                coroutineContext.ensureActive()
            }
        }
    }
}
