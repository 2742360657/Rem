package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.Project
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The search box is the only way into a large collection, and the rules define the match as the
 * typed text appearing anywhere in either name. These cases pin that down, along with the filter
 * interaction that decides whether a project is listed at all.
 */
class UiStateTest {

    private val projects = listOf(
        // Query "cde" lands inside the author name here.
        project(author = "abcdef", name = "夏日", entries = 2),
        // ...and inside the project name here.
        project(author = "xyz", name = "cde", entries = 1),
        project(author = "山田", name = "abcde", entries = 3),
    )

    private fun project(author: String, name: String, entries: Int): Project = Project(
        folder = "$author-$name",
        author = author,
        name = name,
        entries = List(entries) { index ->
            Entry(path = "画集/$author-$name/${"%04d".format(index + 1)}.jpg", size = 1L, modified = 0L)
        },
    )

    private fun state(search: String = "", filter: MediaFilter = MediaFilter.ALL) =
        UiState(projects = projects, search = search, filter = filter)

    @Test
    fun `an empty query lists every project`() {
        assertEquals(3, state().visibleProjects.size)
    }

    @Test
    fun `a match in the middle of the author or the name counts`() {
        // `cde` is a substring of the author `abcdef` and of the name `cde`; `abcde` also holds it.
        assertEquals(3, state(search = "cde").visibleProjects.size)
    }

    @Test
    fun `a prefix and a suffix of the same text both match`() {
        // `abcde` opens the author `abcdef` and closes the project name `abcde`.
        assertEquals(
            listOf("abcdef-夏日", "山田-abcde"),
            state(search = "abcde").visibleProjects.map(Project::folder).sorted(),
        )
    }

    @Test
    fun `a query matches the author as well as the project name`() {
        assertEquals(listOf("山田-abcde"), state(search = "山田").visibleProjects.map(Project::folder))
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(listOf("abcdef-夏日"), state(search = "ABCDEF").visibleProjects.map(Project::folder))
    }

    @Test
    fun `a whitespace-only query is treated as empty`() {
        assertEquals(3, state(search = "   ").visibleProjects.size)
    }

    @Test
    fun `a project with no file of the selected type is dropped`() {
        // The filter hides the project entirely rather than listing it with an empty grid.
        assertEquals(0, state(filter = MediaFilter.VIDEO).visibleProjects.size)
    }

    @Test
    fun `album entries are filtered by type`() {
        val album = listOf(
            Entry(path = "相册/a.jpg", size = 1L, modified = 0L),
            Entry(path = "相册/b.mp4", size = 1L, modified = 0L),
        )
        assertEquals(1, UiState(album = album, filter = MediaFilter.IMAGE).visibleAlbum.size)
        assertEquals(1, UiState(album = album, filter = MediaFilter.VIDEO).visibleAlbum.size)
        assertEquals(2, UiState(album = album, filter = MediaFilter.ALL).visibleAlbum.size)
    }
}
