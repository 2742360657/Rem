package dev.susnowy.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The tree is rooted at the section: `画集/Alice` is a first-level folder, not a child of `画集`. */
class FolderTest {

    @Test
    fun `a first level folder has no parent`() {
        assertNull(Folder("画集/Alice").parent)
    }

    @Test
    fun `a nested folder points at the folder that holds it`() {
        assertEquals("画集/Alice", Folder("画集/Alice/花絮").parent)
        assertEquals("画集/Alice/花絮", Folder("画集/Alice/花絮/原图").parent)
    }

    @Test
    fun `the name is the last segment`() {
        assertEquals("原图", Folder("画集/Alice/花絮/原图").name)
        assertEquals("Alice", Folder("画集/Alice").name)
    }

    @Test
    fun `ancestors run from the first level down to the folder's parent`() {
        assertEquals(emptyList<String>(), Folder("画集/Alice").ancestors())
        assertEquals(listOf("画集/Alice"), Folder("画集/Alice/花絮").ancestors())
        assertEquals(listOf("画集/Alice", "画集/Alice/花絮"), Folder("画集/Alice/花絮/原图").ancestors())
    }
}
