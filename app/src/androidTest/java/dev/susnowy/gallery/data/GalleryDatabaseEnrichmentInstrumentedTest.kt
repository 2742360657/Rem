package dev.susnowy.gallery.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PermissionState
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.SourceKind
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
                schemaVersion = CURRENT_SCHEMA_VERSION,
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

    /**
     * The commit path re-reads the row for every item in a completed batch. That read has to
     * return what is on disk right now — including an edit the user made while the batch was
     * reading media bytes — and it must do so in one query per chunk, not one per item.
     */
    @Test
    fun batchReReadReturnsTheCurrentRowAfterAConcurrentEdit() {
        val first = scanItem("downloads/a.cbz", "a")
        val second = scanItem("downloads/b.cbz", "b")
        val third = scanItem("downloads/c.cbz", "c")
        database.replaceScannedMedia(
            libraryId = libraryId,
            items = listOf(first, second, third),
            foundPaths = setOf(first.relativePath, second.relativePath, third.relativePath),
            pendingEnrichment = setOf(first.relativePath, second.relativePath, third.relativePath),
        )
        // The user renames one Work while the batch is still reading bytes.
        database.upsertMedia(
            first.copy(displayTitle = "用户改的标题", favorite = true),
        )

        val current = database.mediaItems(listOf(first.id, second.id, third.id, "missing"))

        assertEquals(3, current.size)
        assertEquals("用户改的标题", current.getValue(first.id).displayTitle)
        assertTrue(current.getValue(first.id).favorite)
        // A row the batch no longer finds simply stays absent instead of failing the commit.
        assertFalse(current.containsKey("missing"))
    }

    @Test
    fun portableStateReplacementRemovesStaleProgressAndTrashProjection() {
        val first = scanItem("downloads/a.cbz", "a")
        val second = scanItem("downloads/b.cbz", "b")
        database.replaceScannedMedia(
            libraryId = libraryId,
            items = listOf(first.copy(trashed = true, deletedAt = 1), second),
            foundPaths = setOf(first.relativePath, second.relativePath),
        )
        database.upsertProgress(PlaybackProgress(first.id, page = 8, lastOpenedAt = 8))
        database.upsertProgress(PlaybackProgress(second.id, page = 9, lastOpenedAt = 9))

        database.replacePortableState(
            libraryId = libraryId,
            progress = listOf(PlaybackProgress(first.id, page = 2, lastOpenedAt = 20)),
            trashedAt = mapOf(second.id to 30L),
        )

        assertEquals(2, database.progress(first.id)?.page)
        assertEquals("便携状态已删除的进度不能留在 SQLite", null, database.progress(second.id))
        assertFalse(database.mediaItem(first.id)!!.trashed)
        assertTrue(database.mediaItem(second.id)!!.trashed)
        assertEquals(30L, database.mediaItem(second.id)!!.deletedAt)
    }

    /**
     * The first-open marker has to survive the round trip through both projections: page 0 with an
     * open marker is "started", page 0 without one is "untouched", and rebuilding the index from
     * `state.json` must not turn the first into the second.
     */
    @Test
    fun theFirstOpenMarkerSurvivesTheRoundTripThroughBothProjections() {
        val opened = scanItem("downloads/opened.cbz", "opened")
        val untouched = scanItem("downloads/untouched.cbz", "untouched")
        database.replaceScannedMedia(
            libraryId = libraryId,
            items = listOf(opened, untouched),
            foundPaths = setOf(opened.relativePath, untouched.relativePath),
        )
        database.upsertProgress(PlaybackProgress(opened.id, page = 0, lastOpenedAt = 40, openedAt = 40))
        database.upsertProgress(PlaybackProgress(untouched.id, page = 0, lastOpenedAt = 40))

        assertTrue(database.progress(opened.id)!!.opened)
        assertFalse(database.progress(untouched.id)!!.opened)

        database.replacePortableState(
            libraryId = libraryId,
            progress = listOf(
                PlaybackProgress(opened.id, page = 0, lastOpenedAt = 40, openedAt = 40),
                PlaybackProgress(untouched.id, page = 0, lastOpenedAt = 40),
            ),
            trashedAt = emptyMap(),
        )

        assertEquals(40L, database.progress(opened.id)?.openedAt)
        assertNull(database.progress(untouched.id)?.openedAt)
        assertTrue(database.progress(opened.id)!!.opened)
        assertFalse(database.progress(untouched.id)!!.opened)
        assertEquals(
            opened.id,
            database.progressFor(listOf(opened.id, untouched.id)).getValue(opened.id).itemId,
        )
    }

    private fun scanItem(relativePath: String, title: String) = MediaItem(
        id = UUID.randomUUID().toString(),
        libraryId = libraryId,
        relativePath = relativePath,
        uri = "content://checkpoint/$title",
        kind = MediaKind.IMAGE_SET,
        domain = MediaDomain.WORKS,
        sourceKind = SourceKind.ARCHIVE,
        displayTitle = title,
        size = 1_024,
        modifiedAt = 1_700_000_000_000,
    )
}
