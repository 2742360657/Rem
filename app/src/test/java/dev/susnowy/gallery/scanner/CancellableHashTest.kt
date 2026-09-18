package dev.susnowy.gallery.scanner

import dev.susnowy.gallery.media.MediaReadPriority
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CancellableHashTest {
    @Test fun preemptedHashClosesStreamAndCommitsOnlyTheRetriedResult() = runBlocking {
        val gate = MediaReadPriority()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val attempts = AtomicInteger()
        val reads = AtomicInteger()
        val commits = AtomicInteger()
        val task = async(Dispatchers.IO) {
            val result = gate.background {
                val first = attempts.incrementAndGet() == 1
                object : ByteArrayInputStream("abc".toByteArray()) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (first) {
                            reads.incrementAndGet()
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        }
                        return super.read(bytes, offset, length)
                    }
                    override fun close() { super.close(); if (first) closed.countDown() }
                }.use { cancellableSha256(it) }
            }
            commits.incrementAndGet()
            result
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            gate.foreground {
                release.countDown()
                assertTrue(closed.await(5, TimeUnit.SECONDS))
                assertEquals(1, reads.get())
                assertEquals(1, attempts.get())
                assertEquals(0, commits.get())
            }
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                withTimeout(2_000) { task.await() })
            assertEquals(2, attempts.get())
            assertEquals(1, commits.get())
        } finally { release.countDown(); task.cancelAndJoin() }
    }
}
