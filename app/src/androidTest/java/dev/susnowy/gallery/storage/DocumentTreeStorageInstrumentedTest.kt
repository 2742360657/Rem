package dev.susnowy.gallery.storage

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.susnowy.gallery.library.PortableLibraryManager
import dev.susnowy.gallery.model.LibraryInspection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentTreeStorageInstrumentedTest {
    private lateinit var context: Context
    private lateinit var treeUri: Uri

    @Before
    fun resetProvider() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        treeUri = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.ROOT_ID,
        )
        providerCall(TestDocumentsProvider.METHOD_RESET)
    }

    @Test
    fun existingNestedDirectoryIsResolvedWithoutCreatingQualifiedDuplicate() {
        providerCall(
            TestDocumentsProvider.METHOD_SEED_DIRECTORY,
            extras = Bundle().apply { putString(TestDocumentsProvider.ARG_PATH, ".gallery/schema") },
        )
        val storage = DocumentTreeStorage(context, treeUri)

        val directory = storage.ensureDirectory(".gallery/schema")

        assertEquals(".gallery/schema", directory.key)
        assertEquals(listOf("schema"), storage.list(".gallery").map(StorageEntry::name))
        assertEquals(".gallery/schema", storage.find(".gallery/schema")?.key)
    }

    @Test
    fun exclusiveCreateReturnsProviderQualifiedNameAndPreservesOriginal() {
        val storage = DocumentTreeStorage(context, treeUri)
        val requested = ".rem-library-initializing.lock"

        val original = storage.createFileExclusive(requested, "application/octet-stream")
        val collision = storage.createFileExclusive(requested, "application/octet-stream")

        assertEquals(requested, original.key)
        assertNotEquals(requested, collision.key)
        assertTrue(collision.name.startsWith(".rem-library-initializing (1)"))
        assertEquals(original.locator, storage.find(requested)?.locator)
    }

    @Test
    fun renameInvalidatesOnlyTheTouchedParentListing() {
        val storage = DocumentTreeStorage(context, treeUri)
        storage.ensureDirectory("A")
        storage.ensureDirectory("B")
        val source = storage.createFile("A/a.jpg", "image/jpeg")
        storage.createFile("B/b.jpg", "image/jpeg")
        val b = requireNotNull(storage.entry("B"))
        val bDocumentId = DocumentsContract.getDocumentId(Uri.parse(b.uri))
        storage.list("B")
        val queriesBefore = childQueryCount(bDocumentId)

        assertTrue(storage.rename(source, "renamed.jpg"))
        assertEquals(listOf("b.jpg"), storage.list("B").map(StorageEntry::name))

        assertEquals(queriesBefore, childQueryCount(bDocumentId))
    }

    @Test
    fun failedChildQueryIsNotCachedAsAnEmptyDirectory() {
        val storage = DocumentTreeStorage(context, treeUri)
        providerCall(TestDocumentsProvider.METHOD_FAIL_CHILDREN, TestDocumentsProvider.ROOT_ID)

        assertTrue(runCatching { storage.list("") }.isFailure)

        providerCall(TestDocumentsProvider.METHOD_FAIL_CHILDREN, null)
        assertEquals(emptyList<StorageEntry>(), storage.list(""))
        assertEquals(2, childQueryCount(TestDocumentsProvider.ROOT_ID))
    }

    @Test
    fun portableLibraryInitializationCommitsIdentityAndReleasesLease() {
        val storage = DocumentTreeStorage(context, treeUri)
        val manager = PortableLibraryManager(storage)

        val initialized = manager.initialize("Provider Library")
        val inspected = manager.inspect() as LibraryInspection.Valid

        assertEquals(initialized.libraryId, inspected.library.libraryId)
        assertNull(storage.find(PortableLibraryManager.INIT_LOCK_FILE))
        assertEquals(3, inspected.library.schemaVersion)
    }

    private fun childQueryCount(documentId: String): Int =
        requireNotNull(
            providerCall(TestDocumentsProvider.METHOD_CHILD_QUERY_COUNT, documentId),
        ).getInt(TestDocumentsProvider.RESULT_COUNT)

    private fun providerCall(method: String, arg: String? = null, extras: Bundle? = null): Bundle? =
        context.contentResolver.call(
            Uri.parse("content://${TestDocumentsProvider.AUTHORITY}"),
            method,
            arg,
            extras,
        )
}
