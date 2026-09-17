package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PortableSeries
import dev.susnowy.gallery.model.PortableSeriesMember
import dev.susnowy.gallery.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableSeriesStoreTest {
    private val access = MemoryLibraryAccess()
    private val store = PortableMetadataStore(access)

    private fun item(id: String) = MediaItem(
        id = id,
        libraryId = "library-id",
        relativePath = "Comics/$id",
        uri = "content://$id",
        kind = MediaKind.IMAGE_SET,
        sourceKind = SourceKind.DIRECTORY,
        displayTitle = id,
    )

    private fun series(
        title: String = "某系列",
        workIds: List<String> = listOf("work-1", "work-2"),
        numbers: Map<String, Pair<Int?, Double?>> = emptyMap(),
    ) = PortableSeries(
        id = "series-1",
        title = title,
        members = workIds.mapIndexed { index, workId ->
            PortableSeriesMember(
                workId = workId,
                sortIndex = index.toDouble(),
                season = numbers[workId]?.first,
                chapter = numbers[workId]?.second,
            )
        },
        revision = 1,
        updatedAt = "2026-09-17T00:00:00Z",
    )

    private fun seedWorks() {
        listOf("work-1", "work-2", "work-3").forEachIndexed { index, id ->
            store.saveItem(item(id), expectedRevision = index.toLong())
        }
    }

    @Test
    fun createsAndReloadsASeries() {
        seedWorks()

        val saved = store.upsertSeries("library-id", series())

        assertEquals(1, saved.revision)
        val reloaded = store.loadCatalog("library-id").series.single()
        assertEquals("某系列", reloaded.title)
        assertEquals(listOf("work-1", "work-2"), reloaded.members.map(PortableSeriesMember::workId))
    }

    @Test
    fun reorderWritesSortIndexAndStampsManual() {
        seedWorks()
        store.upsertSeries("library-id", series())

        store.upsertSeries(
            "library-id",
            series(workIds = listOf("work-2", "work-1")),
            expectedRevision = 1,
            markSeriesManualFor = setOf("work-1", "work-2"),
        )

        val catalog = store.loadCatalog("library-id")
        val reloaded = catalog.series.single()
        assertEquals(listOf("work-2", "work-1"), reloaded.members.map(PortableSeriesMember::workId))
        assertEquals(listOf(0.0, 1.0), reloaded.members.map(PortableSeriesMember::sortIndex))
        listOf("work-1", "work-2").forEach { workId ->
            val work = catalog.works.first { it.id == workId }
            assertEquals(FieldSource.MANUAL, work.fieldSources[MetadataField.SERIES])
        }
        assertNull(
            catalog.works.first { it.id == "work-3" }.fieldSources[MetadataField.SERIES],
        )
    }

    @Test
    fun removingAMemberStillStampsItManual() {
        seedWorks()
        store.upsertSeries("library-id", series(workIds = listOf("work-1", "work-2", "work-3")))

        store.upsertSeries(
            "library-id",
            series(workIds = listOf("work-1", "work-3")),
            expectedRevision = 1,
            markSeriesManualFor = setOf("work-2"),
        )

        val catalog = store.loadCatalog("library-id")
        assertEquals(listOf("work-1", "work-3"), catalog.series.single().members.map(PortableSeriesMember::workId))
        // The Work that left keeps a manual series decision with no value, which is what
        // stops the scanner from re-assigning it from a folder name.
        val removed = catalog.works.first { it.id == "work-2" }
        assertEquals(FieldSource.MANUAL, removed.fieldSources[MetadataField.SERIES])
        assertNull(catalog.items.first { it.id == "work-2" }.series)
    }

    @Test
    fun renamingKeepsNumbering() {
        seedWorks()
        store.upsertSeries(
            "library-id",
            series(numbers = mapOf("work-1" to (2 to 7.0))),
        )

        store.upsertSeries(
            "library-id",
            series(
                title = "改名后的系列",
                numbers = mapOf("work-1" to (2 to 7.0)),
            ),
            expectedRevision = 1,
        )

        val reloaded = store.loadCatalog("library-id").series.single()
        assertEquals("改名后的系列", reloaded.title)
        val first = reloaded.members.first { it.workId == "work-1" }
        assertEquals(2, first.season)
        assertEquals(7.0, first.chapter!!, 0.0001)
    }

    @Test
    fun rejectsStaleRevisionEmptyMembersAndBlankTitle() {
        seedWorks()
        store.upsertSeries("library-id", series())

        assertThrows(RevisionConflictException::class.java) {
            store.upsertSeries("library-id", series(title = "过期"), expectedRevision = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.upsertSeries("library-id", series(workIds = emptyList()), expectedRevision = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.upsertSeries("library-id", series(title = "  "), expectedRevision = 1)
        }
    }

    @Test
    fun deletingASeriesKeepsWorksAndAssets() {
        seedWorks()
        store.upsertSeries("library-id", series())
        val before = store.loadCatalog("library-id")

        assertTrue(store.deleteSeries("library-id", "series-1"))

        val after = store.loadCatalog("library-id")
        assertTrue(after.series.isEmpty())
        assertEquals(before.works, after.works)
        assertEquals(before.assets, after.assets)
        assertEquals(before.editions, after.editions)
        assertFalse(store.deleteSeries("library-id", "series-1"))
    }

    @Test
    fun refusesMembersThatAreNotWorks() {
        seedWorks()

        assertThrows(IllegalArgumentException::class.java) {
            store.upsertSeries("library-id", series(workIds = listOf("work-1", "missing")))
        }
    }
}
