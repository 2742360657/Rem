package dev.susnowy.gallery.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.LibraryRegistration
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PermissionState
import dev.susnowy.gallery.model.SourceKind
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Work that lives in `.gallery/` but has no media on this device is a reachable state, not
 * damage. It must survive a scan, and it must never be relabelled as needing repair, because the
 * scan cannot find a path that was never copied here in the first place.
 */
@RunWith(AndroidJUnit4::class)
class GalleryDatabaseMissingMediaInstrumentedTest {
    private lateinit var database: GalleryDatabase
    private lateinit var libraryId: String

    @Before
    fun setUp() {
        database = GalleryDatabase(ApplicationProvider.getApplicationContext())
        libraryId = "test-${UUID.randomUUID()}"
        database.upsertLibrary(
            LibraryRegistration(
                libraryId = libraryId,
                name = "missing-media-test",
                treeUri = "content://missing/$libraryId",
                permissionState = PermissionState.AVAILABLE,
                schemaVersion = CURRENT_SCHEMA_VERSION,
            ),
        )
    }

    @After
    fun tearDown() {
        database.removeLibrary(libraryId)
        database.close()
    }

    @Test
    fun catalogOnlyRowSurvivesAScanAndStaysReachable() {
        val catalogOnly = item("Works/其他设备/NO.001 未拷到本机", missingMedia = true)
        database.upsertMedia(catalogOnly)

        // The traversal finds nothing: the media is genuinely not on this device.
        database.replaceScannedMedia(
            libraryId = libraryId,
            items = emptyList(),
            foundPaths = emptySet(),
        )

        val stored = requireNotNull(database.mediaItem(catalogOnly.id))
        assertTrue("媒体不在本机的行必须保留", stored.missingMedia)
        assertFalse("可达状态不能被标成需修复", stored.needsRepair)
    }

    @Test
    fun previouslyIndexedRowThatDisappearedStillNeedsRepair() {
        val indexed = item("Works/本机/NO.002 曾经存在", missingMedia = false)
        database.upsertMedia(indexed)

        database.replaceScannedMedia(
            libraryId = libraryId,
            items = emptyList(),
            foundPaths = emptySet(),
        )

        val stored = requireNotNull(database.mediaItem(indexed.id))
        assertTrue("本机索引过但读取不到的文件是异常", stored.needsRepair)
        assertFalse("它不是换设备场景", stored.missingMedia)
    }

    @Test
    fun aReachableRowThatReappearsIsNoLongerMissing() {
        val item = item("Works/其他设备/NO.003 后来拷进来了", missingMedia = false)
        database.upsertMedia(item)

        database.replaceScannedMedia(
            libraryId = libraryId,
            items = listOf(item),
            foundPaths = setOf(item.relativePath),
        )

        val stored = requireNotNull(database.mediaItem(item.id))
        assertFalse(stored.missingMedia)
        assertFalse(stored.needsRepair)
    }

    private fun item(relativePath: String, missingMedia: Boolean) = MediaItem(
        id = UUID.randomUUID().toString(),
        libraryId = libraryId,
        relativePath = relativePath,
        uri = if (missingMedia) "" else "content://missing/$relativePath",
        kind = MediaKind.IMAGE_SET,
        domain = MediaDomain.WORKS,
        sourceKind = SourceKind.DIRECTORY,
        displayTitle = relativePath.substringAfterLast("/"),
        size = if (missingMedia) 0 else 1_024,
        modifiedAt = 1_700_000_000_000,
        missingMedia = missingMedia,
    )
}
