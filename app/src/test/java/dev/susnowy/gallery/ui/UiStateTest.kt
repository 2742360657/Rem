package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.COLLECTION
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.SortMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collection is a tree, and every visible list is derived from one entry list plus one folder
 * list. These cases pin the derivations that the screens rely on: which level shows folders, which
 * shows media, what each order means, and where the breadcrumb comes from.
 */
class UiStateTest {

    private fun album(name: String, captured: Long? = null, modified: Long = 0) =
        Entry(path = "相册/$name", size = 1, modified = modified, captured = captured)

    private fun collection(path: String, size: Long = 1, modified: Long = 0) =
        Entry(path = "画集/$path", size = size, modified = modified)

    private fun state(
        entries: List<Entry> = emptyList(),
        folders: List<String> = emptyList(),
        openFolder: String? = null,
        search: String = "",
        filter: MediaFilter = MediaFilter.ALL,
        sortMode: SortMode = SortMode.SEQUENCE,
    ) = UiState(
        entries = entries,
        folders = folders,
        openFolder = openFolder,
        search = search,
        filter = filter,
        sortMode = sortMode,
    )

    @Test
    fun `the album lists only album files, newest first`() {
        val state = state(
            entries = listOf(
                album("old.jpg", captured = 100),
                collection("作者-项目/0001.jpg"),
                album("new.jpg", captured = 900),
            ),
        )
        assertEquals(listOf("new.jpg", "old.jpg"), state.visibleAlbum.map(Entry::fileName))
    }

    @Test
    fun `the album filter drops the other media type`() {
        val state = state(
            entries = listOf(album("a.jpg", captured = 1), album("b.mp4", captured = 2)),
            filter = MediaFilter.IMAGE,
        )
        assertEquals(listOf("a.jpg"), state.visibleAlbum.map(Entry::fileName))
    }

    @Test
    fun `the top level lists first-level folders and counts everything below them`() {
        val state = state(
            entries = listOf(collection("作者-项目/0001.jpg"), collection("作者-项目/子/0002.jpg")),
            folders = listOf("画集/作者-项目", "画集/作者-项目/子"),
        )
        val row = state.folderRows.single()
        assertEquals("作者-项目", row.folder.name)
        assertEquals(2, row.mediaCount)
        assertEquals(1, row.folderCount)
        assertTrue("同层同时有文件夹与媒体时要在行里说明", row.mixed)
    }

    @Test
    fun `a folder with subfolders shows folders and hides its own media`() {
        val state = state(
            entries = listOf(collection("作者-项目/0001.jpg"), collection("作者-项目/子/0002.jpg")),
            folders = listOf("画集/作者-项目", "画集/作者-项目/子"),
            openFolder = "画集/作者-项目",
        )
        assertEquals(listOf("子"), state.folderRows.map { it.folder.name })
        assertTrue(state.showsFolders)
        assertTrue("同层有子文件夹时该层媒体不显示", state.folderMedia.isEmpty())
    }

    @Test
    fun `a folder with no subfolders shows its media`() {
        val state = state(
            entries = listOf(collection("作者-项目/子/0002.jpg"), collection("作者-项目/子/0001.jpg")),
            folders = listOf("画集/作者-项目", "画集/作者-项目/子"),
            openFolder = "画集/作者-项目/子",
        )
        assertFalse(state.showsFolders)
        assertEquals(listOf("0001.jpg", "0002.jpg"), state.folderMedia.map(Entry::fileName))
    }

    @Test
    fun `an empty folder is browsable and reports itself as empty`() {
        val state = state(folders = listOf("画集/作者-项目"), openFolder = "画集/作者-项目")
        assertTrue(state.folderRows.isEmpty())
        assertTrue(state.folderMedia.isEmpty())
        assertEquals("空文件夹", FolderRow(state.currentFolder!!, mediaCount = 0, folderCount = 0, mixed = false).detail())
    }

    @Test
    fun `sequence order puts numbered files first and unnumbered last`() {
        val state = state(
            entries = listOf(
                collection("作者-项目/封面.jpg"),
                collection("作者-项目/10.jpg"),
                collection("作者-项目/2.jpg"),
                collection("作者-项目/zz001_002.jpg"),
            ),
            folders = listOf("画集/作者-项目"),
            openFolder = "画集/作者-项目",
            sortMode = SortMode.SEQUENCE,
        )
        // 1 read from zz001_002, then 2, then 10, and the file with no number at all last.
        assertEquals(
            listOf("zz001_002.jpg", "2.jpg", "10.jpg", "封面.jpg"),
            state.folderMedia.map(Entry::fileName),
        )
    }

    @Test
    fun `name order is natural, so 2 comes before 10`() {
        val state = state(
            entries = listOf(
                collection("作者-项目/10.jpg"),
                collection("作者-项目/2.jpg"),
                collection("作者-项目/1.jpg"),
            ),
            folders = listOf("画集/作者-项目"),
            openFolder = "画集/作者-项目",
            sortMode = SortMode.NAME,
        )
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), state.folderMedia.map(Entry::fileName))
    }

    @Test
    fun `size order is largest first`() {
        val state = state(
            entries = listOf(
                collection("作者-项目/a.jpg", size = 10),
                collection("作者-项目/b.jpg", size = 300),
                collection("作者-项目/c.jpg", size = 20),
            ),
            folders = listOf("画集/作者-项目"),
            openFolder = "画集/作者-项目",
            sortMode = SortMode.SIZE,
        )
        assertEquals(listOf("b.jpg", "c.jpg", "a.jpg"), state.folderMedia.map(Entry::fileName))
    }

    @Test
    fun `modified order is newest first`() {
        val state = state(
            entries = listOf(
                collection("作者-项目/a.jpg", modified = 10),
                collection("作者-项目/b.jpg", modified = 300),
                collection("作者-项目/c.jpg", modified = 20),
            ),
            folders = listOf("画集/作者-项目"),
            openFolder = "画集/作者-项目",
            sortMode = SortMode.MODIFIED,
        )
        assertEquals(listOf("b.jpg", "c.jpg", "a.jpg"), state.folderMedia.map(Entry::fileName))
    }

    @Test
    fun `search matches a folder name anywhere in it`() {
        val state = state(
            folders = listOf("$COLLECTION/abcdef", "$COLLECTION/xyz"),
            search = "cde",
        )
        assertEquals(listOf("abcdef"), state.folderRows.map { it.folder.name })
    }

    @Test
    fun `breadcrumbs run from the first level down to the open folder`() {
        val state = state(
            folders = listOf("画集/作者-项目", "画集/作者-项目/子", "画集/作者-项目/子/更深"),
            openFolder = "画集/作者-项目/子/更深",
        )
        assertEquals(listOf("作者-项目", "子", "更深"), state.breadcrumb.map { it.name })
    }

    @Test
    fun `folders derived from entry paths are listed even without an index entry`() {
        // A pass that is still running reports entries before it reports their folder, and the tree
        // has to stay browsable in that window.
        val state = state(entries = listOf(collection("作者-项目/0001.jpg")))
        assertEquals(listOf("作者-项目"), state.folderRows.map { it.folder.name })
    }

    @Test
    fun `a deeper folder is not listed at the top level`() {
        val state = state(folders = listOf("画集/作者-项目", "画集/作者-项目/子"))
        assertEquals(listOf("作者-项目"), state.folderRows.map { it.folder.name })
    }
}
