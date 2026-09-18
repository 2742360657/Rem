package dev.susnowy.gallery.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A failed speculative page must not terminate the reader's viewport observer. */
internal suspend fun preloadComicPages(indices: List<Int>, load: suspend (Int) -> Unit) = coroutineScope {
    val permits = Semaphore(2)
    indices.map { index ->
        async {
            permits.withPermit {
                coroutineContext.ensureActive()
                try { load(index) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // A visible page has its own failure UI and retry. Prefetch is best effort.
                }
            }
        }
    }.awaitAll()
    Unit
}
