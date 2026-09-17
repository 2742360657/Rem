package dev.susnowy.gallery.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PermissionState
import dev.susnowy.gallery.model.SourceKind
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryDatabaseEnrichmentInstrumentedTest {
    private lateinit var database: GalleryDatabase
    private lateinit var libraryId: String

    @Before
    fun setUp() {
        database = GalleryDatabase(ApplicationProvider.getApplicationContext())
        libraryId = "test-${UUID.randomUUID()}"
        database.upsertLibrary(
            LibraryRegistration(
                libraryId = libraryId,
                name = "checkpoint-test",
                treeUri = "content://checkpoint/$libraryId",
                permissionState = PermissionState.AVAILABLE,
                schemaVersion = 3,
            ),
        )
    }

    @After
    fun tearDown() {
        database.removeLibrary(libraryId)
        database.close()
    }

    @Test
    fun pendingRowBecomesReusableOnlyAfterBatchCommit() {
        val item = MediaItem(
            id = UUID.randomUUID().toString(),
            libraryId = libraryId,
            relativePath = "downloads/work.cbz",
            uri = "content://checkpoint/work",
            kind = MediaKind.IMAGE_SET,
            domain = MediaDomain.WORKS,
            sourceKind = SourceKind.ARCHIVE,
            displayTitle = "work",
            size = 1_024,
            modifiedAt = 1_700_000_000_000,
        )
        database.replaceScannedMedia(
            libraryId = libraryId,
            items = listOf(item),
            foundPaths = setOf(item.relativePath),
            pendingEnrichment = setOf(item.relativePath),
        )

        assertEquals(1, database.pendingEnrichmentCount(libraryId))
        assertFalse(database.scanSnapshot(libraryId).getValue(item.relativePath).enriched)

        database.applyEnrichmentBatch(
            libraryId,
            listOf(item.copy(contentHash = "abc", pageCount = 42)),
        )

        val completed = database.scanSnapshot(libraryId).getValue(item.relativePath)
        assertEquals(0, database.pendingEnrichmentCount(libraryId))
        assertTrue(completed.enriched)
        assertEquals("abc", completed.contentHash)
        assertEquals(42, completed.pageCount)
    }
}
