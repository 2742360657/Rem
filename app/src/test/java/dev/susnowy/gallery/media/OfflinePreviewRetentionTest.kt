package dev.susnowy.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflinePreviewRetentionTest {
    @Test
    fun removesOldestUntilBothBudgetsFit() {
        val records = listOf(
            PreviewRetentionRecord("old", bytes = 40, lastAccess = 1),
            PreviewRetentionRecord("middle", bytes = 40, lastAccess = 2),
            PreviewRetentionRecord("new", bytes = 40, lastAccess = 3),
        )

        assertEquals(
            listOf("old", "middle"),
            previewRetentionVictims(records, maxBytes = 50, maxFiles = 2),
        )
    }

    @Test
    fun neverEvictsThePreviewJustWritten() {
        val records = listOf(
            PreviewRetentionRecord("protected", bytes = 100, lastAccess = 1),
            PreviewRetentionRecord("other", bytes = 10, lastAccess = 2),
        )

        assertEquals(
            listOf("other"),
            previewRetentionVictims(
                records,
                maxBytes = 50,
                maxFiles = 1,
                protectedKey = "protected",
            ),
        )
    }
}
