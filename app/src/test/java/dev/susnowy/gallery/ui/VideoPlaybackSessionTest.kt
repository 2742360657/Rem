package dev.susnowy.gallery.ui

import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackSessionTest {
    @Test fun openingOrRestoringTheEndDoesNotCompleteVideo() {
        val session = VideoPlaybackSession()
        assertFalse(session.initialized)
        session.restore(false)
        session.ended()
        assertFalse(session.finished)
        session.playing()
        assertFalse(session.finished)
        session.ended()
        assertTrue(session.finished)
    }

    @Test fun reopeningFinishedVideoPreservesCompletion() {
        val session = VideoPlaybackSession()
        session.restore(true)
        assertTrue(session.finished)
    }
}
