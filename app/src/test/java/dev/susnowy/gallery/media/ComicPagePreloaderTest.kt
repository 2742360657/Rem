package dev.susnowy.gallery.media

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComicPagePreloaderTest {
    @Test fun failedPageDoesNotCancelPeersOrNextViewport() = runTest {
        val loaded = mutableListOf<Int>()
        preloadComicPages(listOf(0, 1, 2)) {
            if (it == 1) throw java.io.IOException("unavailable")
            loaded += it
        }
        preloadComicPages(listOf(3, 4)) { loaded += it }
        assertEquals(listOf(0, 2, 3, 4), loaded)
    }

    @Test fun atMostTwoReadsAndParentCancellationStopsQueuedPages() = runTest {
        val started = mutableListOf<Int>()
        val stopped = mutableListOf<Int>()
        val task = launch {
            preloadComicPages((0..9).toList()) {
                started += it
                try { awaitCancellation() } finally { stopped += it }
            }
        }
        runCurrent()
        assertEquals(listOf(0, 1), started)
        task.cancelAndJoin()
        assertEquals(listOf(0, 1), started)
        assertEquals(setOf(0, 1), stopped.toSet())
    }

    @Test fun cancellationFromLoaderIsNotSwallowed() = runTest {
        var propagated = false
        try { preloadComicPages(listOf(0)) { throw CancellationException("closed") } }
        catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
    }
}
