package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.scanner.MediaClassifier

/**
 * A read-only presentation derived from one physical leaf directory.
 *
 * The image set remains the primary item and bonus videos remain independent media rows;
 * this layer only gives them one entrance until portable manual groups are introduced.
 */
data class MixedMediaGroup(
    val key: String,
    val primary: MediaItem,
    val videos: List<MediaItem>,
) {
    val members: List<MediaItem> get() = listOf(primary) + videos
}

object MixedMediaPresentation {
    fun groups(items: List<MediaItem>): List<MixedMediaGroup> {
        val videosByParent = items.asSequence()
            .filter { it.kind == MediaKind.VIDEO }
            .groupBy { it.libraryId to it.relativePath.parentPath() }
        return items.asSequence()
            .filter { it.kind == MediaKind.IMAGE_SET && it.sourceKind == SourceKind.DIRECTORY }
            .mapNotNull { imageSet ->
                val videos = videosByParent[imageSet.libraryId to imageSet.relativePath]
                    .orEmpty()
                    .sortedWith { left, right ->
                        MediaClassifier.naturalCompare(left.relativePath, right.relativePath)
                    }
                videos.takeIf(List<MediaItem>::isNotEmpty)?.let {
                    MixedMediaGroup(
                        key = "${imageSet.libraryId}:${imageSet.relativePath}",
                        primary = imageSet,
                        videos = videos,
                    )
                }
            }
            .sortedWith { left, right ->
                MediaClassifier.naturalCompare(left.primary.relativePath, right.primary.relativePath)
            }
            .toList()
    }
}

private fun String.parentPath(): String = substringBeforeLast('/', "")
