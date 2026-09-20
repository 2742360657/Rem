package dev.susnowy.gallery.model

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
        )

        val restored = json.decodeFromString<Index>(json.encodeToString(Index.serializer(), index))

        assertEquals(index, restored)
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
        val text = """{"version":1,"entries":[],"violations":[],"extra":"ignored"}"""
        assertEquals(Index(), json.decodeFromString<Index>(text))
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
