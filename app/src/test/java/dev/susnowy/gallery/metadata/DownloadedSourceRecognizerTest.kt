package dev.susnowy.gallery.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadedSourceRecognizerTest {
    @Test
    fun recognizesNumericJmAlbumDirectory() {
        val metadata = DownloadedSourceRecognizer.fromDirectory(
            "JM/438696",
            listOf("00001.webp", "00002.webp"),
        )!!

        assertEquals("JM438696", metadata.title)
        assertEquals(listOf("source:jm", "jm:album:438696"), metadata.tags)
    }

    @Test
    fun recognizesNestedJmPhotoDirectory() {
        val metadata = DownloadedSourceRecognizer.fromDirectory(
            "JM/438696/438697",
            listOf("00001.jpg", "00002.jpg"),
        )!!

        assertEquals("JM438696 · 438697", metadata.title)
        assertEquals(438697.0, metadata.sortIndex)
        assertTrue("jm:photo:438697" in metadata.tags)
    }

    @Test
    fun recognizesEhViewerMarkerAndDefaultDirectoryName() {
        val metadata = DownloadedSourceRecognizer.fromDirectory(
            "EhViewer/123456-A Gallery Title",
            listOf(".ehviewer", "1.jpg"),
        )!!

        assertEquals("A Gallery Title", metadata.title)
        assertEquals(listOf("source:ehviewer", "eh:gid:123456"), metadata.tags)
    }

    @Test
    fun recognizesPixivPagesAndUgoira() {
        val page = DownloadedSourceRecognizer.fromFile("Pixiv/12345678_p03.png")!!
        val animation = DownloadedSourceRecognizer.fromFile(
            "Pixiv/87654321_ugoira1920x1080.webp",
        )!!

        assertEquals("Pixiv 12345678 · 4", page.title)
        assertEquals(3.0, page.sortIndex)
        assertTrue("pixiv:id:12345678" in page.tags)
        assertTrue("format:ugoira" in animation.tags)
    }

    @Test
    fun detectsFlatPixivFolderContainingSeveralWorks() {
        assertFalse(
            DownloadedSourceRecognizer.containsMultiplePixivWorks(
                listOf("12345678_p0.jpg", "12345678_p1.jpg"),
            ),
        )
        assertTrue(
            DownloadedSourceRecognizer.containsMultiplePixivWorks(
                listOf("12345678_p0.jpg", "87654321_p0.jpg"),
            ),
        )
    }
}
