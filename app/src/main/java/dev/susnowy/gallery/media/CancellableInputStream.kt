package dev.susnowy.gallery.media

import java.io.FilterInputStream
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** A blocking read must return, but no next read/skip starts after cancellation. */
internal class CancellableInputStream(input: InputStream, private val context: CoroutineContext) : FilterInputStream(input) {
    override fun read(): Int = checked { `in`.read() }
    override fun read(bytes: ByteArray, offset: Int, length: Int): Int = checked { `in`.read(bytes, offset, length) }
    override fun skip(count: Long): Long = checked { `in`.skip(count) }

    private inline fun <T> checked(block: () -> T): T {
        context.ensureActive()
        val result = block()
        context.ensureActive()
        return result
    }
}
