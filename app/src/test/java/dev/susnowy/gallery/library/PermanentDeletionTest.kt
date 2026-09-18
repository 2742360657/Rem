package dev.susnowy.gallery.library

import dev.susnowy.gallery.metadata.MemoryLibraryAccess
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import org.junit.Assert.*
import org.junit.Test

class PermanentDeletionTest {
    private val item = MediaItem(
        id = "work", libraryId = "library", relativePath = "image.jpg", secondaryPath = "motion.mp4",
        uri = "", kind = MediaKind.LIVE_PHOTO, sourceKind = SourceKind.FILE,
        displayTitle = "Live", size = 20, trashed = true, deletedAt = 1,
    )
    private fun sources() = listOf("image.jpg", "motion.mp4").associateWith {
        DeletionSource(it, 10, 5, false)
    }.toMutableMap()

    @Test fun failureAfterOneSourceCanBeRetriedWithoutDeletingItTwice() {
        val access = MemoryLibraryAccess()
        val sources = sources()
        var finished = false
        assertTrue(runCatching {
            PermanentDeletion(access).execute(item, sources::get, { path ->
                if (path == "image.jpg") error("provider offline")
                sources.remove(path)
            }, { finished = true })
        }.isFailure)
        assertFalse(finished)
        assertFalse(sources.containsKey("motion.mp4"))
        PermanentDeletion(access).execute(item, sources::get, { sources.remove(it) }, { finished = true })
        assertTrue(finished)
        assertTrue(sources.isEmpty())
    }

    @Test fun retryAfterMetadataFailureNeverDeletesReplacementFiles() {
        val access = MemoryLibraryAccess()
        val sources = sources()
        assertTrue(runCatching {
            PermanentDeletion(access).execute(item, sources::get, { sources.remove(it) }, { error("write failed") })
        }.isFailure)
        sources["image.jpg"] = DeletionSource("image.jpg", 999, 9, false)
        var finished = false
        PermanentDeletion(access).execute(item, sources::get, { error("must not delete again") }, { finished = true })
        assertTrue(finished)
        assertEquals(999L, sources.getValue("image.jpg").bytes)
    }

    @Test fun changedRemainingSourceStopsRetry() {
        val access = MemoryLibraryAccess()
        val sources = sources()
        runCatching {
            PermanentDeletion(access).execute(item, sources::get, { error("offline") }, {})
        }
        sources["image.jpg"] = sources.getValue("image.jpg").copy(modifiedAt = 9)
        assertTrue(runCatching {
            PermanentDeletion(access).execute(item, sources::get, { error("must not delete") }, {})
        }.isFailure)
        assertEquals(2, sources.size)
    }

    @Test fun initialMissingOrChangedSourceCannotStartDeletion() {
        for (missing in listOf(true, false)) {
            val access = MemoryLibraryAccess()
            val sources = sources()
            if (missing) sources.remove("image.jpg")
            else sources["image.jpg"] = sources.getValue("image.jpg").copy(bytes = 99)
            assertTrue(runCatching {
                PermanentDeletion(access).execute(item, sources::get, { error("must not delete") }, {})
            }.isFailure)
            assertFalse(PermanentDeletion(access).hasStarted(item))
        }
    }

    @Test fun sameSizeReplacementWithNewTimestampCannotStartDeletion() {
        val access = MemoryLibraryAccess()
        val sources = sources()
        assertTrue(runCatching {
            PermanentDeletion(access).execute(item.copy(modifiedAt = 4), sources::get,
                { error("must not delete replacement") }, {})
        }.isFailure)
        assertFalse(PermanentDeletion(access).hasStarted(item))
    }
}
