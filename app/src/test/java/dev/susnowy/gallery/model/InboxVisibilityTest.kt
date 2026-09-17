package dev.susnowy.gallery.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxVisibilityTest {
    private fun item(
        inInbox: Boolean = false,
        disposition: InboxDisposition? = null,
        trashed: Boolean = false,
    ) = MediaItem(
        id = "work-1",
        libraryId = "library-id",
        relativePath = "Comics/Work",
        uri = "content://work",
        kind = MediaKind.IMAGE_SET,
        sourceKind = SourceKind.DIRECTORY,
        displayTitle = "Work",
        inInbox = inInbox,
        inboxDisposition = disposition,
        trashed = trashed,
    )

    @Test
    fun acceptedWorkIsVisibleAndIgnoredWorkIsHidden() {
        assertTrue(item(disposition = InboxDisposition.ACCEPTED).visibleInLibrary)
        assertTrue(item(disposition = InboxDisposition.CLASSIFIED).visibleInLibrary)
        assertFalse(item(disposition = InboxDisposition.IGNORED).visibleInLibrary)
        assertTrue(item(disposition = InboxDisposition.IGNORED).mutedByInboxDecision)
    }

    @Test
    fun pendingAndTrashedWorkStayOutOfTheNormalViews() {
        assertFalse(item(inInbox = true).visibleInLibrary)
        assertFalse(item(disposition = InboxDisposition.ACCEPTED, trashed = true).visibleInLibrary)
    }
}
