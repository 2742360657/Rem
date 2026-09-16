package dev.susnowy.gallery.importer

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemMediaImporterTest {
    @Test
    fun preservesSystemAlbumSourceHierarchy() {
        assertEquals(
            "Photos/DCIM/Camera",
            SystemMediaImporter.targetParent("DCIM/Camera/", Instant.now().toEpochMilli()),
        )
        assertEquals(
            "Photos/Pictures/Screenshots",
            SystemMediaImporter.targetParent("Pictures/Screenshots", Instant.now().toEpochMilli()),
        )
    }

    @Test
    fun sanitizesSourceFoldersForPortableLibrary() {
        assertEquals(
            "Photos/Pictures/WeChat_Images",
            SystemMediaImporter.targetParent("Pictures/WeChat:Images", Instant.now().toEpochMilli()),
        )
    }

    @Test
    fun unknownSourceFallsBackToCapturedMonth() {
        assertEquals(
            "Photos/Unsorted/2024/06",
            SystemMediaImporter.targetParent("", Instant.parse("2024-06-15T12:00:00Z").toEpochMilli()),
        )
    }

    @Test
    fun preservesSourceHierarchyForOrdinaryImageAndVideoViews() {
        val capturedAt = Instant.parse("2024-06-15T12:00:00Z").toEpochMilli()
        assertEquals(
            "Images/Pictures/WeChat",
            SystemMediaImporter.targetWorkParent("Images", "Pictures/WeChat/", capturedAt),
        )
        assertEquals(
            "Videos/DCIM/Camera",
            SystemMediaImporter.targetWorkParent("Videos", "DCIM/Camera", capturedAt),
        )
        assertEquals(
            "Images/未分类/2024/06",
            SystemMediaImporter.targetWorkParent("Images", "", capturedAt),
        )
    }
}
