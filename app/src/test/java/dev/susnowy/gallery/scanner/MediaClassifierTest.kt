package dev.susnowy.gallery.scanner

import dev.susnowy.gallery.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaClassifierTest {
    @Test
    fun naturallySortsNumberedPages() {
        val input = listOf("10.jpg", "001.jpg", "2.jpg", "1.jpg")
        assertEquals(
            listOf("001.jpg", "1.jpg", "2.jpg", "10.jpg"),
            input.sortedWith(MediaClassifier::naturalCompare),
        )
    }

    @Test
    fun recognizesArchiveAndPhotoSemantics() {
        assertEquals(MediaKind.IMAGE_SET, MediaClassifier.kindForFile("book.cbz", null, false))
        assertEquals(MediaKind.PHOTO, MediaClassifier.kindForFile("IMG_1.HEIC", null, true))
        assertEquals(MediaKind.PHOTO_VIDEO, MediaClassifier.kindForFile("VID_1.mp4", null, true))
        assertNull(MediaClassifier.kindForFile("notes.txt", "text/plain", false))
    }
}
