package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.library.LibraryDocumentAccess
import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.model.UnsupportedSchemaException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableMetadataStoreTest {
    private val access = MetadataMemoryAccess()
    private val store = PortableMetadataStore(access)
    private val item = MediaItem(
        id = "item-id",
        libraryId = "library-id",
        relativePath = "Inbox/work.cbz",
        uri = "content://work",
        kind = MediaKind.IMAGE_SET,
        sourceKind = SourceKind.ARCHIVE,
        displayTitle = "Work",
        contentHash = "abc",
        tags = listOf("theme:school"),
    )

    @Test
    fun savesAndRejectsStaleRevision() {
        val first = store.saveItem(item, expectedRevision = 0)
        assertEquals(1, first.revision)
        assertEquals("abc", store.loadCatalog("library-id").items.single().contentHash)

        assertThrows(RevisionConflictException::class.java) {
            store.saveItem(item.copy(displayTitle = "Stale"), expectedRevision = 0)
        }
    }

    @Test
    fun relocatesPortablePaths() {
        store.saveItem(
            item.copy(
                relativePath = "Inbox/Work",
                sourceKind = SourceKind.DIRECTORY,
                coverPath = "Inbox/Work/cover.jpg",
            ),
            0,
        )
        assertTrue(
            store.relocateItem(
                "library-id",
                "item-id",
                "Inbox/Work",
                "ImageSets/Artist/Work",
            ),
        )
        val relocated = store.loadCatalog("library-id").items.single()
        assertEquals("ImageSets/Artist/Work", relocated.relativePath)
        assertEquals("ImageSets/Artist/Work/cover.jpg", relocated.coverPath)
        assertEquals(2, relocated.revision)
    }

    @Test
    fun progressAndTrashRemainIndependentFromMetadata() {
        store.saveProgress(
            "library-id",
            PlaybackProgress("item-id", page = 8, lastOpenedAt = 1_000),
        )
        store.setTrashed(item, true, deletedAt = 2_000)
        val trashed = store.loadState("library-id")
        assertEquals(8, trashed.progress.single().page)
        assertEquals(1, trashed.trash.size)

        store.setTrashed(item, false)
        val restored = store.loadState("library-id")
        assertEquals(8, restored.progress.single().page)
        assertFalse(restored.trash.isNotEmpty())
    }

    @Test
    fun batchMetadataAndTrashAreWrittenTogether() {
        val second = item.copy(
            id = "item-2",
            relativePath = "Photos/second.jpg",
            displayTitle = "Second",
            kind = MediaKind.PHOTO,
            sourceKind = SourceKind.SYSTEM_IMPORT,
        )
        val saved = store.saveItems(
            listOf(
                item.copy(tags = listOf("batch")),
                second.copy(collections = listOf("Trip")),
            ),
        )

        assertEquals(listOf(1L, 1L), saved.map { it.revision })
        val catalog = store.loadCatalog("library-id")
        assertEquals(1, catalog.revision)
        assertEquals(2, catalog.items.size)
        assertEquals(listOf("batch"), catalog.items.first { it.id == "item-id" }.tags)

        store.setTrashed(listOf(item, second), true, deletedAt = 3_000)
        assertEquals(setOf("item-id", "item-2"), store.loadState("library-id").trash.map { it.itemId }.toSet())
    }

    @Test
    fun batchMetadataConflictDoesNotPartiallyWrite() {
        val second = item.copy(
            id = "item-2",
            relativePath = "Photos/second.jpg",
            displayTitle = "Second",
            kind = MediaKind.PHOTO,
            sourceKind = SourceKind.SYSTEM_IMPORT,
        )
        val initial = store.saveItems(listOf(item, second))
        store.saveItem(item.copy(revision = initial[0].revision, displayTitle = "Changed elsewhere"), initial[0].revision)

        assertThrows(RevisionConflictException::class.java) {
            store.saveItems(
                listOf(
                    item.copy(revision = initial[0].revision, tags = listOf("must-not-write")),
                    second.copy(revision = initial[1].revision, tags = listOf("must-not-write")),
                ),
            )
        }

        val catalog = store.loadCatalog("library-id")
        assertEquals(2, catalog.revision)
        assertEquals("Changed elsewhere", catalog.items.first { it.id == "item-id" }.displayTitle)
        assertFalse(catalog.items.first { it.id == "item-2" }.tags.contains("must-not-write"))
    }

    @Test
    fun stateWritesTolerateProvidersThatAdjustStagingNames() {
        val adjusted = PortableMetadataStore(MetadataMemoryAccess(adjustCreatedNames = true))

        adjusted.saveProgress("library-id", PlaybackProgress("item-id", page = 3, lastOpenedAt = 1))
        adjusted.saveProgress("library-id", PlaybackProgress("item-id", page = 9, lastOpenedAt = 2))

        assertEquals(9, adjusted.loadState("library-id").progress.single().page)
    }

    @Test
    fun keepsFieldProvenanceAndStampsCurrentSchema() {
        store.saveItem(
            item.copy(fieldSources = mapOf("display_title" to FieldSource.MANUAL)),
            expectedRevision = 0,
        )

        val catalog = store.loadCatalog("library-id")
        assertEquals(CURRENT_SCHEMA_VERSION, catalog.schemaVersion)
        assertEquals(
            mapOf("display_title" to "manual"),
            catalog.items.single().fieldSources,
        )
    }

    @Test
    fun olderSchemaIsStillReadable() {
        val seeded = MetadataMemoryAccess().apply {
            seed(
                PortableMetadataStore.CATALOG_PATH,
                """
                {
                  "schema_version": 1,
                  "library_id": "library-id",
                  "revision": 3,
                  "updated_at": "2026-01-01T00:00:00Z",
                  "items": []
                }
                """.trimIndent(),
            )
        }

        val catalog = PortableMetadataStore(seeded).loadCatalog("library-id")

        assertEquals(1, catalog.schemaVersion)
        assertEquals(3, catalog.revision)
    }

    @Test
    fun newerSchemaIsNeverWritten() {
        val seeded = MetadataMemoryAccess().apply {
            seed(
                PortableMetadataStore.STATE_PATH,
                """
                {
                  "schema_version": 99,
                  "library_id": "library-id",
                  "revision": 1,
                  "updated_at": "2026-01-01T00:00:00Z"
                }
                """.trimIndent(),
            )
        }

        assertThrows(UnsupportedSchemaException::class.java) {
            PortableMetadataStore(seeded).saveProgress(
                "library-id",
                PlaybackProgress("item-id", page = 1, lastOpenedAt = 1),
            )
        }
        assertTrue(
            seeded.read(PortableMetadataStore.STATE_PATH)!!.contains("\"schema_version\": 99"),
        )
    }
}

private class MetadataMemoryAccess(
    private val adjustCreatedNames: Boolean = false,
) : LibraryDocumentAccess {
    private val files = mutableMapOf<String, ByteArray>()
    private val directories = mutableSetOf<String>()

    fun seed(relativePath: String, text: String) {
        files[relativePath] = text.encodeToByteArray()
    }

    fun read(relativePath: String): String? = files[relativePath]?.decodeToString()

    override fun find(relativePath: String): LibraryDocument? = when {
        relativePath in directories -> LibraryDocument(relativePath, relativePath.substringAfterLast('/'), true)
        relativePath in files -> LibraryDocument(relativePath, relativePath.substringAfterLast('/'), false)
        else -> null
    }

    override fun ensureDirectory(relativePath: String): LibraryDocument {
        directories += relativePath
        return LibraryDocument(relativePath, relativePath.substringAfterLast('/'), true)
    }

    override fun createFile(relativePath: String, mimeType: String): LibraryDocument {
        val providerPath = if (adjustCreatedNames && relativePath.endsWith(".tmp")) {
            "$relativePath.json"
        } else {
            relativePath
        }
        files.putIfAbsent(providerPath, byteArrayOf())
        return LibraryDocument(
            key = relativePath,
            name = providerPath.substringAfterLast('/'),
            isDirectory = false,
            locator = providerPath,
        )
    }

    override fun openInput(document: LibraryDocument): InputStream =
        ByteArrayInputStream(files.getValue(document.storageKey()))

    override fun openOutput(document: LibraryDocument, truncate: Boolean): OutputStream =
        object : ByteArrayOutputStream() {
            override fun close() {
                files[document.storageKey()] = toByteArray()
                super.close()
            }
        }

    override fun rename(document: LibraryDocument, displayName: String): Boolean {
        val source = document.storageKey()
        val bytes = files.remove(source) ?: return false
        val parent = source.substringBeforeLast('/', "")
        val target = if (parent.isBlank()) displayName else "$parent/$displayName"
        files[target] = bytes
        return true
    }

    override fun delete(document: LibraryDocument): Boolean = files.remove(document.storageKey()) != null

    private fun LibraryDocument.storageKey(): String = locator ?: key
}
