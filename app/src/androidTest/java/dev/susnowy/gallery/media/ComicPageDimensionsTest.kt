package dev.susnowy.gallery.media

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ComicPageDimensionsTest {
    private val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "dimensions-${UUID.randomUUID()}.jpg")
    @After fun clean() { file.delete() }

    private fun write() {
        val bitmap = Bitmap.createBitmap(80, 240, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }

    @Test fun dimensionsFollowExifRotationAndCloseEveryStream() = runBlocking {
        write()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        var opened = 0
        var closed = 0
        val dimensions = readComicPageDimensions {
            opened++
            object : java.io.FilterInputStream(file.inputStream()) {
                override fun close() { closed++; super.close() }
            }
        }
        assertEquals(ComicPageDimensions(240, 80), dimensions)
        assertEquals(opened, closed)
    }

    @Test fun unsupportedOrMissingPageFallsBackAndCancellationIsNotAnErrorResult() = runBlocking {
        assertNull(readComicPageDimensions { byteArrayOf(1, 2, 3).inputStream() })
        assertNull(readComicPageDimensions { throw java.io.FileNotFoundException() })
        var propagated = false
        try { readComicPageDimensions { throw CancellationException("closed") } }
        catch (_: CancellationException) { propagated = true }
        assertTrue(propagated)
    }
}
