package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.model.EditionAssetRole
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PortableEdition
import dev.susnowy.gallery.model.PortableEditionAsset
import dev.susnowy.gallery.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableEditionStoreTest {
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

    private fun seed() {
        store.saveItem(item("work-1"), expectedRevision = 0)
        store.saveItem(item("work-2"), expectedRevision = 0)
    }

    private fun assetIds(): List<String> = store.loadCatalog("library-id").assets.map { it.id }

    private fun mergedEdition(label: String = "合并版") = PortableEdition(
        id = "merge-edition",
        workId = "work-1",
        label = label,
        assets = listOf(
            PortableEditionAsset(assetIds()[0], EditionAssetRole.PAGE, 0.0, null),
            PortableEditionAsset(assetIds()[1], EditionAssetRole.PAGE, 1.0, "chapter/003.jpg"),
        ),
        revision = 1,
        updatedAt = "2026-09-17T00:00:00Z",
    )

    @Test
    fun createsAPagePlanEditionAndKeepsBothSources() {
        seed()

        val saved = store.upsertEdition("library-id", mergedEdition())

        val catalog = store.loadCatalog("library-id")
        assertEquals(2, catalog.works.size)
        // Two original Editions (one per seeded Work) plus the merged page plan.
        assertEquals(3, catalog.editions.size)
        val merged = catalog.editions.first { it.id == "merge-edition" }
        assertEquals(listOf(0.0, 1.0), merged.assets.map(PortableEditionAsset::sortIndex))
        assertEquals("chapter/003.jpg", merged.assets.last().entryPath)
        assertEquals(1, saved.revision)
        assertTrue(catalog.assets.size == 2)
    }

    @Test
    fun preferMakesTheMergedEditionTheDefaultInTheSameWrite() {
        seed()

        store.upsertEdition("library-id", mergedEdition(), prefer = true)

        val catalog = store.loadCatalog("library-id")
        assertEquals("merge-edition", catalog.works.first { it.id == "work-1" }.preferredEditionId)
        // The projection now reads the merged plan's first page source.
        assertEquals(
            catalog.assets.first { it.id == mergedEdition().assets.first().assetId }.relativePath,
            catalog.items.first { it.id == "work-1" }.relativePath,
        )
    }

    @Test
    fun updatingTheSameEditionKeepsOneRowAndBumpsRevision() {
        seed()
        store.upsertEdition("library-id", mergedEdition())

        val updated = store.upsertEdition(
            "library-id",
            mergedEdition(label = "合并版（改名）"),
            expectedRevision = 1,
        )

        assertEquals(2, updated.revision)
        val catalog = store.loadCatalog("library-id")
        assertEquals(1, catalog.editions.count { it.id == "merge-edition" })
        assertEquals("合并版（改名）", catalog.editions.first { it.id == "merge-edition" }.label)
        assertEquals(3, catalog.editions.size)
    }

    @Test
    fun rejectsStaleRevisionAndUnknownReferences() {
        seed()
        store.upsertEdition("library-id", mergedEdition())

        assertThrows(RevisionConflictException::class.java) {
            store.upsertEdition("library-id", mergedEdition(), expectedRevision = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.upsertEdition(
                "library-id",
                mergedEdition().copy(
                    id = "other",
                    assets = listOf(PortableEditionAsset("missing-asset")),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.upsertEdition("library-id", mergedEdition().copy(id = "other", assets = emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.upsertEdition("library-id", mergedEdition().copy(id = "other", workId = "missing-work"))
        }
    }
}
