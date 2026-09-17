package dev.susnowy.gallery.scanner

import dev.susnowy.gallery.storage.StorageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A rescan must re-read any file it cannot prove is unchanged. These cases are the
 * safety net for the incremental scan: a false negative only costs a redundant read,
 * but a false positive would keep a stale fingerprint or stale EXIF coordinates.
 */
class ScanSnapshotReuseTest {
    private fun recorded(
        size: Long = 1_024,
        modifiedAt: Long = 1_700_000_000_000,
        contentHash: String? = "abc",
        pageCount: Int? = 42,
        enriched: Boolean = true,
    ) = ScannedFile(
        relativePath = "Images/a.jpg",
        size = size,
        modifiedAt = modifiedAt,
        contentHash = contentHash,
        pageCount = pageCount,
        enriched = enriched,
    )

    @Test
    fun unchangedFileIsReused() {
        assertTrue(recorded().canBeReused(1_024, 1_700_000_000_000))
    }

    @Test
    fun sizeChangeForcesARead() {
        assertFalse(recorded().canBeReused(2_048, 1_700_000_000_000))
    }

    @Test
    fun timestampChangeForcesARead() {
        assertFalse(recorded().canBeReused(1_024, 1_700_000_001_000))
    }

    @Test
    fun sameSizeButTouchedFileForcesARead() {
        // Editors that overwrite in place keep the size; only the timestamp reveals it.
        assertFalse(recorded().canBeReused(1_024, 1_700_000_500_000))
    }

    @Test
    fun missingHashIsNotTreatedAsReusable() {
        // The caller must additionally require a recorded hash before skipping a read.
        val withoutHash = recorded(contentHash = null)

        assertTrue(withoutHash.canBeReused(1_024, 1_700_000_000_000))
        assertFalse(withoutHash.contentHash != null)
    }

    @Test
    fun unchangedArchiveReusesItsRecordedPageCount() {
        assertEquals(42, recorded().reusableArchivePageCount(1_024, 1_700_000_000_000))
        assertNull(recorded().reusableArchivePageCount(2_048, 1_700_000_000_000))
        assertNull(recorded(pageCount = null).reusableArchivePageCount(1_024, 1_700_000_000_000))
    }

    @Test
    fun unfinishedEnrichmentIsNeverReusedAsComplete() {
        val pending = recorded(enriched = false)

        assertTrue(pending.canBeReused(1_024, 1_700_000_000_000))
        assertFalse(pending.canReuseEnrichment(1_024, 1_700_000_000_000))
        assertNull(pending.reusableArchivePageCount(1_024, 1_700_000_000_000))
    }

    @Test
    fun directoryFingerprintChangesWhenAnEqualSizedPageIsModified() {
        fun page(modifiedAt: Long) = StorageEntry(
            relativePath = "Comics/work/001.jpg",
            uri = "content://test/page",
            name = "001.jpg",
            mimeType = "image/jpeg",
            isDirectory = false,
            size = 1_024,
            lastModified = modifiedAt,
        )
        val scanner = LibraryScanner()

        assertNotEquals(
            scanner.directoryFingerprint(listOf(page(1_700_000_000_000))),
            scanner.directoryFingerprint(listOf(page(1_700_000_001_000))),
        )
    }

    @Test
    fun unreadableSubtreeProtectsOnlyItsPreviouslyIndexedItems() {
        val partial = ScanResult(
            candidates = emptyList(),
            ambiguousDirectories = emptyList(),
            warnings = listOf("无法读取 Comics/Offline"),
            unreadableDirectories = listOf("Comics/Offline"),
        )

        assertTrue(partial.protectsPreviouslyIndexed("Comics/Offline"))
        assertTrue(partial.protectsPreviouslyIndexed("Comics/Offline/001.jpg"))
        assertFalse(partial.protectsPreviouslyIndexed("Comics/Online/001.jpg"))
    }

    @Test
    fun unreadableRootProtectsTheWholePreviousIndex() {
        val failed = ScanResult(
            candidates = emptyList(),
            ambiguousDirectories = emptyList(),
            warnings = listOf("无法读取 Library 根目录"),
            unreadableDirectories = listOf(""),
        )

        assertTrue(failed.protectsPreviouslyIndexed("Images/a.jpg"))
    }
}
