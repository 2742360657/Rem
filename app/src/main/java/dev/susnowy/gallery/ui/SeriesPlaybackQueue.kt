package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.MediaItem

fun nextSeriesItem(current: MediaItem, queue: List<MediaItem>): MediaItem? {
    val ordered = queue.filter { it.libraryId == current.libraryId && !it.trashed }.distinctBy { it.id }
    val index = ordered.indexOfFirst { it.id == current.id }
    return if (index < 0) null else ordered.getOrNull(index + 1)
}
