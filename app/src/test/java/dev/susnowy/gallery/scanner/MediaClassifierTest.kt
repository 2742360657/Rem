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
        assertEquals(MediaKind.IMAGE, MediaClassifier.kindForFile("animation.webp", null, false))
        assertEquals(MediaKind.IMAGE, MediaClassifier.kindForFile("vector.svg", null, false))
        assertEquals(MediaKind.IMAGE, MediaClassifier.kindForFile("raw.dng", null, false))
        assertEquals(MediaKind.PHOTO_VIDEO, MediaClassifier.kindForFile("VID_1.mp4", null, true))
        assertNull(MediaClassifier.kindForFile("notes.txt", "text/plain", false))
    }

    @Test
    fun explicitMediaRootsDoNotTurnLeafFoldersIntoComics() {
        assertEquals(
            false,
            LibraryScanner.shouldTreatDirectoryAsImageSet("Images/Pictures/WeChat", 20, false),
        )
        assertEquals(
            false,
            LibraryScanner.shouldTreatDirectoryAsImageSet("Videos/Covers", 20, false),
        )
        assertEquals(
            false,
            LibraryScanner.shouldTreatDirectoryAsImageSet("Photos/DCIM/Camera", 20, false),
        )
        assertEquals(
            true,
            LibraryScanner.shouldTreatDirectoryAsImageSet("ImageSets/Imported/Book", 20, false),
        )
        assertEquals(
            true,
            LibraryScanner.shouldTreatDirectoryAsImageSet("Legacy/Book", 20, false),
        )
    }
}
