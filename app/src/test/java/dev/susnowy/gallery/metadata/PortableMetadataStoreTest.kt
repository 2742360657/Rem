package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.library.LibraryDocumentAccess
import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PlaybackProgress
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.model.SeriesRef
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
    @Test
    fun batchRemovalPreservesNumberingAndLocksRemovedSeriesMembers() {
        store.saveItems(listOf(item.copy(series = SeriesRef("s", "S", chapter = 1.0)),
            item.copy(id = "b", relativePath = "b.cbz", series = SeriesRef("s", "S", chapter = 7.0))))
        val before = store.loadCatalog(item.libraryId)
        val series = before.series.single()
        assertEquals(1, store.removeRelationMembers(item.libraryId, "s", true, setOf(item.id), series.revision))
        val after = store.loadCatalog(item.libraryId)
        assertEquals(before.assets, after.assets)
        assertEquals(before.editions, after.editions)
        assertEquals(series.members.filter { it.workId == "b" }, after.series.single().members)
        assertEquals("manual", after.works.first { it.id == item.id }.fieldSources["series"])
        val raw = access.read(PortableMetadataStore.CATALOG_PATH)
        assertThrows(RevisionConflictException::class.java) {
            store.removeRelationMembers(item.libraryId, "s", true, setOf("b"), series.revision)
        }
        assertEquals(raw, access.read(PortableMetadataStore.CATALOG_PATH))
        store.removeRelationMembers(item.libraryId, "s", true, setOf("b"), after.series.single().revision)
        assertTrue(store.loadCatalog(item.libraryId).series.single().members.isEmpty())
        assertEquals(2, store.loadCatalog(item.libraryId).works.size)
    }

    @Test
    fun batchGroupRemovalClearsCoverAndRefusesDeletedTarget() {
        store.saveItem(item, 0)
        val group = store.upsertGroup(item.libraryId, dev.susnowy.gallery.model.PortableGroup(
            id = "g", title = "G", members = listOf(dev.susnowy.gallery.model.PortableGroupMember(item.id)),
            coverWorkId = item.id, updatedAt = "2026-09-19T00:00:00Z"))
        assertEquals(1, store.removeRelationMembers(item.libraryId, "g", false, setOf(item.id), group.revision))
        val after = store.loadCatalog(item.libraryId)
        assertTrue(after.groups.single().members.isEmpty())
        assertEquals(null, after.groups.single().coverWorkId)
        store.deleteGroup(item.libraryId, "g")
        assertThrows(RevisionConflictException::class.java) {
            store.removeRelationMembers(item.libraryId, "g", false, setOf(item.id), group.revision)
        }
    }

    @Test
    fun batchFieldsPreserveSourcesRelationshipsAndUnknownExtensions() {
        val saved = store.saveItem(item.copy(series = SeriesRef("series-id", "Series")), 0)
        val before = store.loadCatalog(item.libraryId)
        val raw = access.read(PortableMetadataStore.CATALOG_PATH)!!
            .replace("\"content_hash\": \"abc\"", "\"content_hash\": \"abc\", \"future_asset\": true")
            .replace("\"display_title\": \"Work\"", "\"display_title\": \"Work\", \"future_work\": {\"v\": 7}")
            .replace("\"schema_version\": 4", "\"schema_version\": 4, \"future_root\": [1,2]")
        access.seed(PortableMetadataStore.CATALOG_PATH, raw)
        store.saveBatchFields(listOf(item.copy(revision = saved.revision, tags = listOf("new"),
            contentHash = "stale", fieldSources = mapOf("tags" to "manual"))))
        val after = store.loadCatalog(item.libraryId)
        assertEquals(before.assets, after.assets)
        assertEquals(before.editions, after.editions)
        assertEquals(before.series, after.series)
        assertEquals(listOf("new"), after.works.single().tags)
        val result = access.read(PortableMetadataStore.CATALOG_PATH)!!
        assertTrue(result.contains("future_asset"))
        assertTrue(result.contains("future_work"))
        assertTrue(result.contains("future_root"))
    }

    @Test
    fun batchClearOfEmptyFieldPersistsManualLockAndPreservesOtherWorkFields() {
        val original = item.copy(authors = emptyList(), displayTitle = "Original")
        val saved = store.saveItem(original, 0)
        val baseline = original.copy(revision = saved.revision)
        val edit = dev.susnowy.gallery.model.BatchMetadataEdit(
            authors = dev.susnowy.gallery.model.BatchListEdit(dev.susnowy.gallery.model.BatchListMode.CLEAR),
        )
        val result = edit.merge(baseline, baseline)
        store.saveBatchFields(listOf(result.item))
        val reloaded = PortableMetadataStore(access).loadCatalog(original.libraryId).works.single()
        assertTrue(reloaded.authors.isEmpty())
        assertEquals("manual", reloaded.fieldSources["authors"])
        assertEquals("Original", reloaded.displayTitle)
        assertEquals(original.tags, reloaded.tags)
    }

    @Test
    fun batchFieldsCreateNewInboxWorkAndRejectStaleRevisionWithoutPartialWrite() {
        val saved = store.saveBatchFields(listOf(item)).single()
        val before = access.read(PortableMetadataStore.CATALOG_PATH)
        assertThrows(RevisionConflictException::class.java) {
            store.saveBatchFields(listOf(item.copy(id = "new", relativePath = "new.cbz"), item.copy(revision = 0)))
        }
        assertEquals(before, access.read(PortableMetadataStore.CATALOG_PATH))
        assertEquals(1L, saved.revision)
        assertEquals(item.relativePath, store.loadCatalog(item.libraryId).assets.single().relativePath)
    }

    private val access = MetadataMemoryAccess()
    private val store = PortableMetadataStore(access)
    private val item = MediaItem(
        id = "item-id",
        libraryId = "library-id",
        relativePath = "Inbox/work.cbz",
        uri = "content://work",
        kind = MediaKind.IMAGE_SET,
        domain = MediaDomain.WORKS,
        sourceKind = SourceKind.ARCHIVE,
        displayTitle = "Work",
        contentHash = "abc",
        tags = listOf("theme:school"),
    )

    @Test
    fun savesAndRejectsStaleRevision() {
        val first = store.saveItem(item, expectedRevision = 0)
        assertEquals(1, first.revision)
        val catalog = store.loadCatalog("library-id")
        assertEquals("abc", catalog.items.single().contentHash)
        assertEquals(MediaDomain.WORKS, catalog.items.single().domain)
        assertEquals(1, catalog.assets.size)
        assertEquals(1, catalog.works.size)
        assertEquals(1, catalog.editions.size)
        assertFalse(access.read(PortableMetadataStore.CATALOG_PATH)!!.contains("\"items\""))

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
    fun seriesMembershipHasOnePortableOwner() {
        val first = store.saveItem(
            item.copy(series = SeriesRef("series-a", "A", chapter = 1.0)),
            expectedRevision = 0,
        )

        store.saveItem(
            item.copy(
                revision = first.revision,
                series = SeriesRef("series-b", "B", sortIndex = 2.0),
            ),
            expectedRevision = first.revision,
        )

        val catalog = store.loadCatalog("library-id")
        assertEquals(listOf("series-b"), catalog.series.map { it.id })
        assertEquals("item-id", catalog.series.single().members.single().workId)
        assertEquals("B", catalog.items.single().series?.title)
    }

    @Test
    fun v3RequiresExplicitConversionAndBecomesNormalizedV4() {
        val seeded = MetadataMemoryAccess().apply {
            seed(
                PortableMetadataStore.CATALOG_PATH,
                """
                {
                  "schema_version": 3,
                  "library_id": "library-id",
                  "revision": 3,
                  "updated_at": "2026-01-01T00:00:00Z",
                  "items": [
                    {
                      "id": "old-work",
                      "relative_path": "Works/old.cbz",
                      "type": "image_set",
                      "domain": "works",
                      "display_title": "Old Work",
                      "source": "archive",
                      "series": {"id":"series-z","title":"Series","chapter":2.0},
                      "revision": 2,
                      "updated_at": "2026-01-01T00:00:00Z"
                    },
                    {
                      "id": "other-work",
                      "relative_path": "Works/other.cbz",
                      "type": "image_set",
                      "domain": "works",
                      "display_title": "Other Work",
                      "source": "archive",
                      "series": {"id":"series-a","title":"series","chapter":1.0},
                      "revision": 1,
                      "updated_at": "2026-01-01T00:00:00Z"
                    }
                  ]
                }
                """.trimIndent(),
            )
            seed(
                PortableMetadataStore.STATE_PATH,
                """
                {
                  "schema_version": 3,
                  "library_id": "library-id",
                  "revision": 2,
                  "updated_at": "2026-01-01T00:00:00Z",
                  "progress": [{
                    "item_id": "old-work",
                    "page": 8,
                    "last_opened_at": "2026-01-01T00:00:00Z"
                  }],
                  "trash": []
                }
                """.trimIndent(),
            )
        }

        val legacyStore = PortableMetadataStore(seeded)
        assertThrows(UnsupportedSchemaException::class.java) {
            legacyStore.loadCatalog("library-id")
        }
        legacyStore.migrateV3ToV4("library-id")
        val catalog = legacyStore.loadCatalog("library-id")

        assertEquals(CURRENT_SCHEMA_VERSION, catalog.schemaVersion)
        assertEquals(4, catalog.revision)
        assertEquals(2, catalog.assets.size)
        assertEquals(2, catalog.works.size)
        assertEquals(2, catalog.editions.size)
        // v3 carried a SeriesRef per item and matched by title; v4 gives every Series a stable id,
        // so two differently identified series stay two entities even when their names match.
        assertEquals(2, catalog.series.size)
        assertEquals(
            listOf("series-a", "series-z"),
            catalog.series.map { it.id }.sorted(),
        )
        assertEquals(
            listOf("other-work"),
            catalog.series.first { it.id == "series-a" }.members.map { it.workId },
        )
        assertEquals(
            listOf("old-work"),
            catalog.series.first { it.id == "series-z" }.members.map { it.workId },
        )
        assertEquals("Works/old.cbz", catalog.items.first { it.id == "old-work" }.relativePath)
        assertEquals("old-work", legacyStore.loadState("library-id").progress.single().itemId)
        assertFalse(seeded.read(PortableMetadataStore.CATALOG_PATH)!!.contains("\"items\""))
        assertTrue(seeded.read(PortableMetadataStore.STATE_PATH)!!.contains("\"work_id\""))
        assertFalse(seeded.read(PortableMetadataStore.STATE_PATH)!!.contains("\"item_id\""))
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
