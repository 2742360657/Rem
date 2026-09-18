package dev.susnowy.gallery.data

import android.content.Context
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.library.PortableLibraryManager
import dev.susnowy.gallery.metadata.PortableMetadataStore
import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.storage.DocumentTreeStorage
import dev.susnowy.gallery.storage.TestDocumentsProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchMetadataRepositoryTest {
    @Test fun freshPortableFieldsWinAndOfflineLibraryDoesNotBlockOtherLibrary() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
        context.contentResolver.call(root, TestDocumentsProvider.METHOD_RESET, null, Bundle())
        val storage = DocumentTreeStorage(context, root)
        val identity = PortableLibraryManager(storage).initialize("Batch fixture")
        val repository = GalleryRepository(context)
        val database = GalleryDatabase(context)
        // Provider calls are same-process fixtures, not a system picker permission grant.
        database.upsertLibrary(LibraryRegistration(identity.libraryId, "Batch fixture", root.toString(), PermissionState.AVAILABLE, 4))
        val store = PortableMetadataStore(storage)
        val offlineId = "batch-offline-${identity.libraryId}"
        try {
            fun item(id: String) = MediaItem(id, identity.libraryId, "$id.cbz", "", MediaKind.IMAGE_SET,
                sourceKind = SourceKind.ARCHIVE, displayTitle = id, tags = listOf("old"), missingMedia = true)
            val input = listOf(item("batch-a"), item("batch-b"))
            val saved = store.saveItems(input).associateBy { it.id }
            val baselines = input.map { it.copy(revision = saved.getValue(it.id).revision) }
            baselines.forEach(database::upsertMedia)
            // Portable truth changes without refreshing the local index: preserve title, conflict on tags.
            store.saveItem(baselines[0].copy(displayTitle = "Later manual title", fieldSources = mapOf("display_title" to "manual")), baselines[0].revision)
            store.saveItem(baselines[1].copy(tags = listOf("later"), fieldSources = mapOf("tags" to "manual")), baselines[1].revision)
            database.upsertLibrary(LibraryRegistration(offlineId, "Offline fixture", "content://absent/tree/root", PermissionState.AVAILABLE, 4))
            val offline = item("batch-offline").copy(libraryId = offlineId)
            database.upsertMedia(offline)
            val report = repository.editMetadataBatch(listOf(offline) + baselines,
                BatchMetadataEdit(tags = BatchListEdit(BatchListMode.CLEAR), authors = BatchListEdit(BatchListMode.CLEAR)))
            assertEquals(1, report.updated)
            assertEquals(2, report.issues.size)
            // Reopen after another storage instance atomically replaced the catalog document.
            val works = PortableMetadataStore(DocumentTreeStorage(context, root))
                .loadCatalog(identity.libraryId).works.associateBy { it.id }
            assertEquals("Later manual title", works.getValue("batch-a").displayTitle)
            assertTrue(works.getValue("batch-a").tags.isEmpty())
            assertEquals("manual", works.getValue("batch-a").fieldSources["tags"])
            assertEquals("manual", works.getValue("batch-a").fieldSources["authors"])
            assertEquals(listOf("later"), works.getValue("batch-b").tags)
            assertFalse(works.getValue("batch-b").fieldSources.containsKey("authors"))
            assertTrue(database.mediaItem("batch-a")!!.missingMedia)
            assertFalse(database.mediaItem("batch-a")!!.inInbox)
            assertNull(storage.find("batch-a.cbz"))
        } finally {
            database.removeLibrary(identity.libraryId)
            database.removeLibrary(offlineId)
            database.close()
        }
    }
}
