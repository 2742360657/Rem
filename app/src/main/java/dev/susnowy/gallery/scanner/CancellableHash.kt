package dev.susnowy.gallery.scanner

import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Caller owns the stream. A synchronous Provider read must return before cancellation is observed. */
internal suspend fun cancellableSha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        coroutineContext.ensureActive()
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
