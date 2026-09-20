package dev.susnowy.gallery.model

import dev.susnowy.gallery.storage.indexOf
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `index.json` is a cache, so the only thing that must hold is that a scan's result survives a
 * write and a read unchanged. Precision loss in a coordinate or a timestamp would silently move
 * a file's date or place on the next launch.
 */
class IndexTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `round trips every field`() {
        val index = Index(
            entries = listOf(
                StoredEntry(
                    path = "相册/a.jpg",
                    size = 2048L,
                    modified = 1_700_000_000_000L,
                    captured = 1_600_000_000_000L,
                    latitude = 31.23040,
                    longitude = 121.47370,
                ),
                StoredEntry(path = "画集/作者-项目/0001.mp4", size = 10L, modified = 5L),
            ),
            violations = listOf("画集/坏文件夹：项目文件夹必须命名为「作者名称-项目名称」"),
            doneProjects = listOf("画集/作者-项目"),
        )

        val restored = json.decodeFromString<Index>(json.encodeToString(Index.serializer(), index))

        assertEquals(index, restored)
    }

    @Test
    fun `the finished projects survive the cache so a resumed walk can skip them`() {
        // The listing walk is the part of a scan that cannot be shortened, so where it stopped has
        // to outlive the process. If this list is lost, the next pass lists the whole Library again
        // — which is the behaviour this field exists to stop.
        val written = indexOf(
            entries = emptyList(),
            folders = emptyList(),
            violations = emptyList(),
            doneProjects = listOf("画集/乙-项目", "画集/甲-项目"),
        )

        val restored = json.decodeFromString<Index>(json.encodeToString(Index.serializer(), written))

        // Order is not the point — survival is. `indexOf` sorts for a stable file, not for meaning.
        assertEquals(setOf("画集/甲-项目", "画集/乙-项目"), restored.doneProjects.toSet())
    }

    @Test
    fun `a cache without a resume record reports no finished projects`() {
        // Version 4 caches predate the field. Decoding one must not invent progress, and the pass
        // has to be free to walk the whole tree rather than trust an empty-looking resume point.
        val old = json.decodeFromString<Index>("""{"version":4,"entries":[],"violations":[]}""")

        assertEquals(emptyList<String>(), old.doneProjects)
    }

    @Test
    fun `omits absent optional fields so the file stays readable`() {
        val text = json.encodeToString(
            Index.serializer(),
            Index(entries = listOf(StoredEntry(path = "相册/a.jpg", size = 1L, modified = 2L))),
        )

        assertEquals(false, text.contains("captured"))
        assertEquals(false, text.contains("latitude"))
    }

    @Test
    fun `tolerates a field written by a newer reader`() {
        // An editor may add its own key; that must not cost the user a rescan.
        val text = """{"version":${Index.VERSION},"entries":[],"violations":[],"extra":"ignored"}"""
        assertEquals(Index(version = Index.VERSION), json.decodeFromString<Index>(text))
    }

    @Test
    fun `the version is always written so a reader can compare it`() {
        // The whole invalidation mechanism rests on this: a version that is never encoded decodes
        // back to the default and matches every file ever written.
        val text = json.encodeToString(Index.serializer(), Index(version = Index.VERSION))
        assertEquals(true, text.contains("\"version\""))
        assertEquals(true, text.contains(Index.VERSION.toString()))
    }

    @Test
    fun `a cache written before the version key existed is not trusted`() {
        // `encodeDefaults = false` used to drop the key entirely, so such a file has no version.
        val legacy = json.decodeFromString<Index>("""{"entries":[],"violations":[]}""")
        assertEquals(Index.LEGACY_VERSION, legacy.version)
        assertEquals(false, legacy.version == Index.VERSION)
    }

    @Test
    fun `a cache Rem writes claims the current version`() {
        val written = indexOf(entries = emptyList(), folders = emptyList(), violations = emptyList())
        assertEquals(Index.VERSION, written.version)
    }

    @Test
    fun `place survives the entry conversion`() {
        val entry = StoredEntry(
            path = "相册/a.jpg",
            size = 1L,
            modified = 2L,
            latitude = -33.86880,
            longitude = 151.20930,
        ).toEntry()

        assertEquals(Place(-33.86880, 151.20930), entry.place)
        assertEquals("-33.86880, 151.20930", entry.place?.label)
    }

    @Test
    fun `a half coordinate becomes no place at all`() {
        // Location is only reported when the media actually carries both halves.
        assertNull(StoredEntry(path = "相册/a.jpg", size = 1L, modified = 2L, latitude = 31.23).toEntry().place)
    }
}
