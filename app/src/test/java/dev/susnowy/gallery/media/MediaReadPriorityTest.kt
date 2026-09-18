package dev.susnowy.gallery.media

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MediaReadPriorityTest {
    @Test fun foregroundCancelsPreviewAndPreviewResumesAfterAllReadersLeave() = runBlocking {
        val gate = MediaReadPriority()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var attempts = 0
        val preview = async {
            gate.background {
                attempts++
                if (attempts == 1) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                "ready"
            }
        }
        started.await()
        gate.foreground {
            cancelled.await()
            gate.foreground { yield(); assertEquals(1, attempts) }
            yield()
            assertEquals(1, attempts)
        }
        assertEquals("ready", withTimeout(2_000) { preview.await() })
        assertEquals(2, attempts)
    }

    @Test fun cancelledCallerDoesNotRestartAndFailureReleasesForeground() = runBlocking {
        val gate = MediaReadPriority()
        var attempts = 0
        val started = CompletableDeferred<Unit>()
        val preview = launch { gate.background { attempts++; started.complete(Unit); awaitCancellation() } }
        started.await()
        preview.cancelAndJoin()
        assertEquals(1, attempts)
        runCatching { gate.foreground { error("read failed") } }
        assertEquals("next", withTimeout(2_000) { gate.background { "next" } })
    }
}
