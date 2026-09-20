package dev.susnowy.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The entry carries its own filing, so these derived values are what the whole UI sorts and
 * labels by. A mistake here shows up as a file in the wrong section or a scrollbar with no date.
 */
class EntryTest {

    private fun album(name: String, captured: Long? = null, modified: Long = 0L) =
        Entry(path = "相册/$name", size = 1L, modified = modified, captured = captured)

    private fun collection(folder: String, name: String) =
        Entry(path = "画集/$folder/$name", size = 1L, modified = 0L)

    @Test
    fun `album file has no project folder and no sequence`() {
        val entry = album("a.jpg")
        assertNull(entry.projectFolder)
        assertNull(entry.sequence)
    }

    @Test
    fun `collection file reports its folder`() {
        assertEquals("作者-项目", collection("作者-项目", "0001.jpg").projectFolder)
    }

    @Test
    fun `sequence is the first run of digits in the name`() {
        assertEquals(1L, collection("作者-项目", "0001.jpg").sequence)
        assertEquals(42L, collection("作者-项目", "0042.png").sequence)
        // Zero padding no longer matters: names are free, so 1.jpg is item 1.
        assertEquals(1L, collection("作者-项目", "1.jpg").sequence)
        assertEquals(12L, collection("作者-项目", "0012.png").sequence)
    }

    @Test
    fun `sequence reads the first digits anywhere in the name`() {
        assertEquals(1L, collection("作者-项目", "zz001_002.jpg").sequence)
        assertEquals(2024L, collection("作者-项目", "IMG-2024-001.jpg").sequence)
        // An extra extension is still just part of the stem, and the digits still count.
        assertEquals(1L, collection("作者-项目", "0001.jpg.bak").sequence)
    }

    @Test
    fun `a name with no digits has no sequence`() {
        assertNull(collection("作者-项目", "封面.jpg").sequence)
        assertNull(collection("作者-项目", "cover.png").sequence)
    }

    @Test
    fun `order time prefers the capture time and falls back to the modification time`() {
        assertEquals(500L, album("a.jpg", captured = 500L, modified = 100L).orderTime)
        assertEquals(100L, album("a.jpg", captured = null, modified = 100L).orderTime)
    }

    @Test
    fun `position label is the padded number for collection files`() {
        assertEquals("0007", collection("作者-项目", "0007.jpg").positionLabel)
    }

    @Test
    fun `position label is the date for album files`() {
        // 2021-01-01T00:00:00Z; the rendered hour depends on the host zone, so only the shape is asserted.
        val label = album("a.jpg", modified = 1_609_459_200_000L).positionLabel
        assertEquals(16, label.length)
        assertEquals('-', label[4])
        assertEquals(' ', label[10])
    }

    @Test
    fun `an off-rule collection file still gets a label`() {
        // It sorts last, so the scrollbar must still be able to name it.
        assertEquals(16, collection("作者-项目", "封面.jpg").positionLabel.length)
    }

    @Test
    fun `display name drops only the last extension`() {
        assertEquals("holiday.2024.01", album("holiday.2024.01.jpg").displayName)
    }

    @Test
    fun `media type comes from the path`() {
        assertEquals(MediaType.IMAGE, album("a.jpg").mediaType)
        assertEquals(MediaType.VIDEO, collection("作者-项目", "0001.mp4").mediaType)
        assertNull(album("notes.txt").mediaType)
    }
}
