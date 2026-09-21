package dev.susnowy.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The tree is uniform: `画集/Alice` is a first-level folder held by the `画集` section. */
class FolderTest {

    @Test
    fun `a first level folder belongs to its section`() {
        // Not null. A level's children are the folders whose parent is that level, so a first-level
        // folder has to name the section holding it. Treating it as parentless grouped it with the
        // Library's other sections, and the collection then listed `相册` among its projects.
        assertEquals("画集", Folder("画集/Alice").parent)
    }

    @Test
    fun `only the sections themselves have no parent`() {
        assertNull(Folder("画集").parent)
        assertNull(Folder("相册").parent)
        assertNull(Folder("待分类").parent)
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
