package dev.susnowy.gallery.media

import dev.susnowy.gallery.model.EditionAssetRole
import dev.susnowy.gallery.model.PortableAsset
import dev.susnowy.gallery.model.PortableEditionAsset
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditionPlanTest {
    private val directory = PortableAsset(
        id = "asset-dir",
        relativePath = "Comics/A",
        mediaType = MediaKind.IMAGE_SET,
        source = SourceKind.DIRECTORY,
        updatedAt = "2026-09-17T00:00:00Z",
    )
    private val archive = PortableAsset(
        id = "asset-cbz",
        relativePath = "Comics/B.cbz",
        mediaType = MediaKind.IMAGE_SET,
        source = SourceKind.ARCHIVE,
        updatedAt = "2026-09-17T00:00:00Z",
    )
    private val image = PortableAsset(
        id = "asset-img",
        relativePath = "Photos/cover.jpg",
        mediaType = MediaKind.IMAGE,
        source = SourceKind.FILE,
        updatedAt = "2026-09-17T00:00:00Z",
    )
    private val assets = listOf(directory, archive, image).associateBy(PortableAsset::id)

    @Test
    fun pagesFromDifferentContainersKeepTheirOwnReaderPath() {
        val pages = editionPlanPages(
            members = listOf(
                PortableEditionAsset("asset-dir", EditionAssetRole.PAGE, 0.0, "001.jpg"),
                PortableEditionAsset("asset-cbz", EditionAssetRole.PAGE, 1.0, "chapter/002.jpg"),
                PortableEditionAsset("asset-img", EditionAssetRole.PAGE, 2.0, null),
            ),
            assetsById = assets,
        )!!

        assertEquals(3, pages.size)
        assertEquals("Comics/A/001.jpg", pages[0].relativePath)
        assertNull(pages[0].archiveEntry)
        assertEquals("Comics/B.cbz", pages[1].relativePath)
        assertEquals("chapter/002.jpg", pages[1].archiveEntry)
        assertEquals("002.jpg", pages[1].name)
        assertEquals("Photos/cover.jpg", pages[2].relativePath)
        assertNull(pages[2].archiveEntry)
    }

    @Test
    fun orderComesFromSortIndexNotFromTheStoredOrder() {
        val pages = editionPlanPages(
            members = listOf(
                PortableEditionAsset("asset-img", EditionAssetRole.PAGE, 5.0, null),
                PortableEditionAsset("asset-dir", EditionAssetRole.PAGE, 1.0, "001.jpg"),
            ),
            assetsById = assets,
        )!!

        assertEquals(listOf("Comics/A/001.jpg", "Photos/cover.jpg"), pages.map { it.relativePath })
    }

    @Test
    fun wholeContainerMembersAreNotPretendedToBeOnePage() {
        // A member without an entry path means "this whole directory/archive"; the reader has
        // to fall back to the single-source path instead of opening a directory as an image.
        assertNull(
            editionPlanPages(
                members = listOf(PortableEditionAsset("asset-dir", EditionAssetRole.PAGE, 0.0, null)),
                assetsById = assets,
            ),
        )
        assertNull(
            editionPlanPages(
                members = listOf(PortableEditionAsset("missing", EditionAssetRole.PAGE, 0.0, "a.jpg")),
                assetsById = assets,
            ),
        )
        assertNull(editionPlanPages(emptyList(), assets))
    }
}
