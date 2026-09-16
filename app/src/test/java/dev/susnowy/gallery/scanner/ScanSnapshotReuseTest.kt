package dev.susnowy.gallery.scanner

import org.junit.Assert.assertFalse
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
    ) = ScannedFile(
        relativePath = "Images/a.jpg",
        size = size,
        modifiedAt = modifiedAt,
        contentHash = contentHash,
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
}
