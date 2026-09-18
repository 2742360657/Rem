package dev.susnowy.gallery.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRulesTest {
    private val item = MediaItem(
        id = "work", libraryId = "library", relativePath = "book.cbz", uri = "",
        kind = MediaKind.IMAGE_SET, sourceKind = SourceKind.ARCHIVE, displayTitle = "Book",
        size = 100, modifiedAt = 10, trashed = true, deletedAt = 20,
    )

    @Test fun unchangedConfirmationIsAccepted() = TrashRules.requireUnchanged(item, item)

    private val source = PortableAsset(
        id = "asset", relativePath = item.relativePath, mediaType = item.kind,
        source = item.sourceKind, updatedAt = "now",
    )
    private val edition = PortableEdition(
        id = "edition", workId = item.id, assets = listOf(PortableEditionAsset(source.id)), updatedAt = "now",
    )
    private val catalog = PortableCatalog(
        libraryId = item.libraryId, assets = listOf(source), editions = listOf(edition), updatedAt = "now",
    )

    @Test fun exclusiveSourceCanBeDeleted() = TrashRules.requireExclusiveSource(item, catalog)

    @Test fun mergedEditionProtectsItsOriginalSourceEvenWhenOtherWorkIsTrashed() {
        val shared = catalog.copy(editions = listOf(edition, edition.copy(id = "merged", workId = "other")))
        assertTrue(runCatching { TrashRules.requireExclusiveSource(item, shared) }.isFailure)
    }

    @Test fun multiplePhysicalSourcesCannotBeDeletedUsingOneCardPath() {
        val other = source.copy(id = "other", relativePath = "other.cbz")
        val multiple = catalog.copy(assets = listOf(source, other), editions = listOf(
            edition.copy(assets = listOf(PortableEditionAsset(source.id), PortableEditionAsset(other.id))),
        ))
        assertTrue(runCatching { TrashRules.requireExclusiveSource(item, multiple) }.isFailure)
    }

    @Test fun deletingDirectoryCannotRemoveAnotherWorksChildOrSecondaryFile() {
        val folderItem = item.copy(relativePath = "folder", sourceKind = SourceKind.DIRECTORY)
        val folder = source.copy(relativePath = "folder", source = SourceKind.DIRECTORY)
        val child = source.copy(id = "child", relativePath = "elsewhere.jpg", secondaryPath = "folder/motion.mp4")
        val nested = catalog.copy(assets = listOf(folder, child), editions = listOf(
            edition, edition.copy(id = "child-edition", workId = "other", assets = listOf(PortableEditionAsset(child.id))),
        ))
        assertTrue(runCatching { TrashRules.requireExclusiveSource(folderItem, nested) }.isFailure)
    }

    @Test fun staleConfirmationsAreRejected() {
        listOf(
            item.copy(trashed = false), item.copy(deletedAt = 30),
            item.copy(relativePath = "replacement.cbz"), item.copy(libraryId = "other"),
            item.copy(size = 200), item.copy(modifiedAt = 11), item.copy(revision = 1),
            item.copy(secondaryPath = "motion.mp4"),
        ).forEach { changed ->
            assertTrue(runCatching { TrashRules.requireUnchanged(item, changed) }.isFailure)
        }
    }

    @Test fun reviewIsOnlyAnAgeIndicatorForTrashedDatedItems() {
        val now = 20 + 30 * 86_400_000L
        assertTrue(TrashRules.needsReview(item, 30, now))
        assertFalse(TrashRules.needsReview(item, 30, now - 1))
        assertFalse(TrashRules.needsReview(item, 0, now))
        assertFalse(TrashRules.needsReview(item.copy(trashed = false), 30, now))
        assertFalse(TrashRules.needsReview(item.copy(deletedAt = null), 30, now))
    }
}
