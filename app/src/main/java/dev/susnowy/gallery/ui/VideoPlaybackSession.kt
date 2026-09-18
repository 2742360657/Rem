package dev.susnowy.gallery.ui

/** Restoration and seeking alone do not mark a video watched. */
class VideoPlaybackSession {
    var initialized = false
        private set
    var finished = false
        private set
    private var played = false

    fun restore(wasFinished: Boolean) {
        initialized = true
        finished = wasFinished
    }

    fun playing() { played = true }

    fun ended() {
        if (initialized && played) finished = true
    }
}
