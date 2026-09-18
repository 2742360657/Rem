package dev.susnowy.gallery.media

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test

class ArchiveOrientationTest {
    @Test fun allExifOrientationsPlaceCornersCorrectly() {
        // Encoded corners: top-left A, top-right B, bottom-left C, bottom-right D.
        val expected = listOf("ABCD", "BADC", "DCBA", "CDAB", "ACBD", "CADB", "DBCA", "BDAC")
        for (orientation in 1..8) {
            val source = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
            val colors = (0..3).map { 0xff000000.toInt() or ((it + 1) * 0x222222) }
            source.setPixel(0, 0, colors[0]); source.setPixel(1, 0, colors[1])
            source.setPixel(0, 2, colors[2]); source.setPixel(1, 2, colors[3])
            val result = orientArchiveBitmap(source, orientation)
            val actual = listOf(result.getPixel(0, 0), result.getPixel(result.width - 1, 0),
                result.getPixel(0, result.height - 1), result.getPixel(result.width - 1, result.height - 1))
                .map { ('A'.code + colors.indexOf(it)).toChar() }.joinToString("")
            assertEquals("orientation=$orientation", expected[orientation - 1], actual)
            assertEquals(if (orientation >= 5) 3 else 2, result.width)
            assertEquals(if (orientation >= 5) 2 else 3, result.height)
            result.recycle()
        }
    }
}
