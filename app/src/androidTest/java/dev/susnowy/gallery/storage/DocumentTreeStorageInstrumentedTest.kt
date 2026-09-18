package dev.susnowy.gallery.storage

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.susnowy.gallery.library.PortableLibraryManager
import dev.susnowy.gallery.model.LibraryInspection
import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
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
    fun agentPlanRoundTripsThroughSafWithoutTouchingManualFields() {
        val storage = DocumentTreeStorage(context, treeUri)
        val library = PortableLibraryManager(storage).initialize("Agent readback fixture")
        val writer = dev.susnowy.gallery.library.PortableDocumentWriter(storage)
        assertTrue(writer.read("GALLERY_LIBRARY.md")!!.contains("expected_catalog_sha256"))
        val store = dev.susnowy.gallery.metadata.PortableMetadataStore(storage)
        store.saveItem(dev.susnowy.gallery.model.MediaItem(
            id = "agent-work", libraryId = library.libraryId, relativePath = "Missing/book.cbz", uri = "",
            kind = dev.susnowy.gallery.model.MediaKind.IMAGE_SET,
            sourceKind = dev.susnowy.gallery.model.SourceKind.ARCHIVE,
            displayTitle = "人工标题", fieldSources = mapOf("display_title" to "manual"),
        ), 0)
        val original = writer.read(".gallery/items/catalog.json")!!
        val plan = dev.susnowy.gallery.portable.AgentEditPlan(
            library.libraryId,
            dev.susnowy.gallery.portable.AgentCatalogEdits.sha256(original.toByteArray(Charsets.UTF_8)),
            "readback-test",
            listOf(dev.susnowy.gallery.portable.AgentWorkEdit("agent-work",
                kotlinx.serialization.json.Json.parseToJsonElement(
                    """{"display_title":"不应覆盖","collections":["Agent 分类"]}""",
                ) as kotlinx.serialization.json.JsonObject,
            )),
        )
        val prepared = dev.susnowy.gallery.portable.AgentCatalogEdits.prepare(original, plan)
        writer.write(".gallery/items/catalog.json", prepared.catalog, "application/json")
        val reopened = dev.susnowy.gallery.metadata.PortableMetadataStore(DocumentTreeStorage(context, treeUri))
        val work = reopened.loadCatalog(library.libraryId).works.single()
        assertEquals("人工标题", work.displayTitle)
        assertEquals(listOf("Agent 分类"), work.collections)
        assertEquals("provider:readback-test", work.fieldSources["collections"])
        assertNull(storage.find("Missing/book.cbz"))
    }

    @Test
    fun outputCloseRefreshesCachedFileSize() {
        val storage = DocumentTreeStorage(context, treeUri)
        val document = storage.createFile("size.jpg", "image/jpeg")
        assertEquals(0L, storage.entry("size.jpg")?.size)
        storage.openOutput(document).use { it.write(byteArrayOf(1, 2, 3)) }
        assertEquals(3L, storage.entry("size.jpg")?.size)
    }

    @Test
    fun deletionJournalSurvivesReopeningSafAndDoesNotDeleteReplacement() {
        val storage = DocumentTreeStorage(context, treeUri)
        val document = storage.createFile("fixture.jpg", "image/jpeg")
        storage.openOutput(document).use { it.write(byteArrayOf(1, 2, 3)) }
        val item = dev.susnowy.gallery.model.MediaItem(
            id = "purge-fixture", libraryId = "test", relativePath = "fixture.jpg", uri = "",
            kind = dev.susnowy.gallery.model.MediaKind.IMAGE,
            sourceKind = dev.susnowy.gallery.model.SourceKind.FILE, displayTitle = "fixture",
            size = 3, trashed = true, deletedAt = 1,
        )
        fun inspect(access: DocumentTreeStorage, path: String) = access.entry(path)?.let {
            dev.susnowy.gallery.library.DeletionSource(path, it.size, it.lastModified, it.isDirectory)
        }
        assertTrue(runCatching {
            dev.susnowy.gallery.library.PermanentDeletion(storage).execute(item,
                inspect = { inspect(storage, it) },
                delete = { check(storage.delete(requireNotNull(storage.find(it)))) },
                finish = { error("模拟元数据提交中断") },
            )
        }.isFailure)
        assertNull(storage.entry("fixture.jpg"))
        val replacement = storage.createFile("fixture.jpg", "image/jpeg")
        storage.openOutput(replacement).use { it.write(byteArrayOf(4, 5)) }
        val reopened = DocumentTreeStorage(context, treeUri)
        var finished = false
        dev.susnowy.gallery.library.PermanentDeletion(reopened).execute(item,
            inspect = { inspect(reopened, it) },
            delete = { error("不得再次删除已完成来源") },
            finish = { finished = true },
        )
        assertTrue(finished)
        assertEquals(2L, reopened.entry("fixture.jpg")?.size)
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
        assertEquals(4, inspected.library.schemaVersion)
    }

    @Test
    fun legacyPortableDocumentsRecoverIdentityAndConvertThroughRealProvider() {
        val libraryId = "d421f1ce-59e7-4f9f-85a0-250a586cdca5"
        val storage = DocumentTreeStorage(context, treeUri)
        writeText(storage, PortableLibraryManager.LEGACY_SCHEMA_FILE, """{"schema_version":3}""")
        writeText(
            storage,
            ".gallery/items/catalog.json",
            """{"schema_version":3,"library_id":"$libraryId","revision":1,"updated_at":"now","items":[]}""",
        )
        writeText(
            storage,
            ".gallery/state/state.json",
            """{"schema_version":3,"library_id":"$libraryId","revision":1,"updated_at":"now","progress":[],"trash":[]}""",
        )

        val recovered = PortableLibraryManager(storage).initialize("Recovered Provider Library")

        assertEquals(libraryId, recovered.libraryId)
        assertEquals(CURRENT_SCHEMA_VERSION, recovered.schemaVersion)
        assertNull(storage.find(PortableLibraryManager.LEGACY_SCHEMA_FILE))
        assertTrue(readText(storage, ".gallery/items/catalog.json").contains("\"assets\""))
        assertTrue(storage.list(".gallery/backups").isNotEmpty())
        assertNull(storage.find(PortableLibraryManager.INIT_LOCK_FILE))
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

    private fun writeText(storage: DocumentTreeStorage, path: String, text: String) {
        val document = storage.createFile(path, "application/json")
        storage.openOutput(document).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    private fun readText(storage: DocumentTreeStorage, path: String): String {
        val document = requireNotNull(storage.find(path))
        return storage.openInput(document).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
