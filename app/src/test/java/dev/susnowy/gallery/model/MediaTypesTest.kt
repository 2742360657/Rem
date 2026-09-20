package dev.susnowy.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Only these extensions are read out of a Library. A wrong answer here means either a file the
 * user expects to see is missing, or a document is offered to the system viewer as media.
 */
class MediaTypesTest {

    @Test
    fun `recognises image extensions regardless of case`() {
        assertEquals(MediaType.IMAGE, MediaTypes.of("相册/a.JPG"))
        assertEquals(MediaType.IMAGE, MediaTypes.of("相册/a.jpeg"))
        assertEquals(MediaType.IMAGE, MediaTypes.of("相册/a.HEIC"))
        assertEquals(MediaType.IMAGE, MediaTypes.of("相册/a.webp"))
    }

    @Test
    fun `recognises video extensions`() {
        assertEquals(MediaType.VIDEO, MediaTypes.of("相册/a.mp4"))
        assertEquals(MediaType.VIDEO, MediaTypes.of("画集/作者-项目/0001.MOV"))
        assertEquals(MediaType.VIDEO, MediaTypes.of("相册/a.mkv"))
    }

    @Test
    fun `rejects anything else`() {
        assertNull(MediaTypes.of("相册/notes.txt"))
        assertNull(MediaTypes.of("相册/archive.zip"))
        assertNull(MediaTypes.of("相册/no-extension"))
    }

    @Test
    fun `uses the last dot so a dotted name still resolves`() {
        assertEquals(MediaType.IMAGE, MediaTypes.of("相册/holiday.2024.01.jpg"))
    }
}
