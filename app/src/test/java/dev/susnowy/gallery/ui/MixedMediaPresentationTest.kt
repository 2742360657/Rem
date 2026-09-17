package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaGroup
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.model.derivedGroupId
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

class UnsavedDerivedGroupTest {
    private fun imageSet(id: String, path: String) = MediaItem(
        id = id,
        libraryId = "library-id",
        relativePath = path,
        uri = "content://$id",
        kind = MediaKind.IMAGE_SET,
        sourceKind = SourceKind.DIRECTORY,
        displayTitle = id,
    )

    @Test
    fun savedDerivedFoldersLeaveTheCandidateList() {
        val primary = imageSet("work-1", "写真集A")
        val derived = listOf(MixedMediaGroup(key = "library-id:写真集A", primary = primary, videos = emptyList()))
        val saved = listOf(
            MediaGroup(
                id = derivedGroupId("library-id", "work-1"),
                libraryId = "library-id",
                title = "写真集A",
            ),
        )

        assertTrue(MixedMediaPresentation.unsavedGroups(derived, saved, "library-id").isEmpty())
        assertEquals(1, MixedMediaPresentation.unsavedGroups(derived, emptyList(), "library-id").size)
        // A group saved for another Library must not hide this Library's candidate.
        assertEquals(1, MixedMediaPresentation.unsavedGroups(derived, saved, "other-library").size)
    }
}
