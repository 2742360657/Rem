package dev.susnowy.gallery.media

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class CancellableInputStreamTest {
    @Test fun cancellationDuringReadClosesStreamWithoutDeliveringBytes() {
        val job = Job()
        var closed = false
        var delivered = false
        val source = object : InputStream() {
            override fun read(): Int { job.cancel(); return 42 }
            override fun close() { closed = true }
        }
        try { CancellableInputStream(source, job).use { it.read(); delivered = true } }
        catch (_: CancellationException) { }
        assertFalse(delivered)
        assertTrue(closed)
    }

    @Test fun cancelledStreamDoesNotReadOrSkipUnderlyingSource() {
        val job = Job().apply { cancel() }
        var operations = 0
        val source = object : InputStream() {
            override fun read(): Int { operations++; return -1 }
            override fun skip(n: Long): Long { operations++; return n }
        }
        CancellableInputStream(source, job).use { input ->
            listOf<() -> Unit>({ input.read() }, { input.read(ByteArray(8)) }, { input.skip(10) }).forEach { operation ->
                var cancelled = false
                try { operation() } catch (_: CancellationException) { cancelled = true }
                assertTrue(cancelled)
            }
        }
        assertEquals(0, operations)
    }

    @Test fun normalBufferedReadsAndSkipsKeepContents() {
        val bytes = (0..127).map(Int::toByte).toByteArray()
        CancellableInputStream(ByteArrayInputStream(bytes), Job()).buffered().use {
            assertEquals(0, it.read())
            assertEquals(9L, it.skip(9))
            assertArrayEquals(bytes.drop(10).toByteArray(), it.readBytes())
        }
    }
}
