package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedMediaPresentationTest {
    @Test
    fun directoryImageSetAndItsVideosBecomeOneNaturallyOrderedGroup() {
        val imageSet = item("set", "Images/album", MediaKind.IMAGE_SET, SourceKind.DIRECTORY)
        val second = item("v2", "Images/album/clip10.mp4", MediaKind.VIDEO)
        val first = item("v1", "Images/album/clip2.mp4", MediaKind.VIDEO)
        val nested = item("nested", "Images/album/bonus/clip.mp4", MediaKind.VIDEO)
        val unrelated = item("other", "Images/other/clip.mp4", MediaKind.VIDEO)

        val groups = MixedMediaPresentation.groups(listOf(second, unrelated, imageSet, nested, first))

        assertEquals(1, groups.size)
        assertEquals(imageSet, groups.single().primary)
        assertEquals(listOf(first, second), groups.single().videos)
        assertEquals(listOf(imageSet, first, second), groups.single().members)
    }

    @Test
    fun archivesAndImageOnlyDirectoriesAreNotPretendedToBeMixedGroups() {
        val archive = item("archive", "downloads/work.cbz", MediaKind.IMAGE_SET, SourceKind.ARCHIVE)
        val directory = item("directory", "Images/plain", MediaKind.IMAGE_SET, SourceKind.DIRECTORY)

        assertTrue(MixedMediaPresentation.groups(listOf(archive, directory)).isEmpty())
    }

    private fun item(
        id: String,
        path: String,
        kind: MediaKind,
        source: SourceKind = SourceKind.FILE,
    ) = MediaItem(
        id = id,
        libraryId = "library",
        relativePath = path,
        uri = "content://test/$id",
        kind = kind,
        domain = MediaDomain.CLASSIFIED,
        sourceKind = source,
        displayTitle = id,
    )
}
