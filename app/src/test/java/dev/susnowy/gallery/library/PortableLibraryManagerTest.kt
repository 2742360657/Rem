package dev.susnowy.gallery.library

import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.LibraryInspection
import dev.susnowy.gallery.model.PortableLibrary
import dev.susnowy.gallery.model.UnsupportedSchemaException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableLibraryManagerTest {
    @Test
    fun initializeCreatesPortableIdentityAndGuide() {
        val access = MemoryDocumentAccess()
        val library = PortableLibraryManager(access).initialize("  My\nLibrary  ")

        assertEquals("My Library", library.name)
        assertTrue(access.files.containsKey(".gallery/library.json"))
        assertTrue(access.files.containsKey("GALLERY_LIBRARY.md"))
        val guide = access.files.getValue("GALLERY_LIBRARY.md").decodeToString()
        assertTrue(guide.contains("这是一个 Rem 便携媒体库"))
        assertTrue(guide.contains("Agent 辅助识别与同步"))
        assertTrue(guide.contains("任何来源为 `manual` 的字段都不得修改"))
        assertTrue(guide.contains("`field_sources.tags` 为 `manual`，整组标签必须原样保留"))
        assertTrue(access.files.containsKey(PortableLibraryManager.SCHEMA_FILE))
        assertEquals(".gallery/schema/v3.json", PortableLibraryManager.SCHEMA_FILE)
        assertTrue(access.files.containsKey(PortableLibraryManager.MEDIA_IGNORE_FILE))
        assertTrue(PortableLibraryManager(access).inspect() is LibraryInspection.Valid)
    }

    @Test
    fun migratesVersionOneLibraryWithBackups() {
        val original = """{
            "format":"gallery-library",
            "schema_version":1,
            "library_id":"d421f1ce-59e7-4f9f-85a0-250a586cdca5",
            "name":"Old Library",
            "created_at":"2026-01-01T00:00:00Z",
            "updated_at":"2026-01-02T00:00:00Z"
        }"""
        val access = MemoryDocumentAccess().apply {
            files[PortableLibraryManager.LIBRARY_JSON] = original.encodeToByteArray()
            files[".gallery/items/catalog.json"] = """{"schema_version":1,"library_id":"d421f1ce-59e7-4f9f-85a0-250a586cdca5","revision":5,"updated_at":"2026-01-02T00:00:00Z","items":[]}""".encodeToByteArray()
            files[".gallery/state/state.json"] = """{"schema_version":1,"library_id":"d421f1ce-59e7-4f9f-85a0-250a586cdca5","revision":2,"updated_at":"2026-01-02T00:00:00Z","progress":[],"trash":[]}""".encodeToByteArray()
        }
        val manager = PortableLibraryManager(access)

        val migrated = manager.migrateSchema((manager.inspect() as LibraryInspection.Valid).library)

        assertEquals(CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertTrue(access.files.containsKey(".gallery/schema/v3.json"))
        assertTrue(access.files.getValue(PortableLibraryManager.LIBRARY_JSON).decodeToString().contains("\"schema_version\": 3"))
        // The original documents stay available as a pre-migration snapshot.
        val backups = access.files.keys.filter { it.startsWith(".gallery/backups/schema-v1-") }
        assertEquals(3, backups.size)
        assertTrue(backups.any { access.files.getValue(it).decodeToString() == original })
        assertTrue(manager.inspect() is LibraryInspection.Valid)
    }

    @Test
    fun migrationRefusesNewerSchema() {
        val newer = PortableLibrary(
            schemaVersion = CURRENT_SCHEMA_VERSION + 1,
            libraryId = "d421f1ce-59e7-4f9f-85a0-250a586cdca5",
            name = "Future",
            createdAt = "now",
            updatedAt = "now",
        )

        assertThrows(UnsupportedSchemaException::class.java) {
            PortableLibraryManager(MemoryDocumentAccess()).migrateSchema(newer)
        }
    }

    @Test
    fun migrationIsIdempotentAtCurrentVersion() {
        val access = MemoryDocumentAccess()
        val manager = PortableLibraryManager(access)
        val library = manager.initialize("Library")
        val backupsBefore = access.files.keys.count { it.startsWith(".gallery/backups/") }

        val unchanged = manager.migrateSchema(library)

        assertEquals(library, unchanged)
        assertEquals(backupsBefore, access.files.keys.count { it.startsWith(".gallery/backups/") })
    }

    @Test
    fun invalidIdentityIsRejected() {
        val access = MemoryDocumentAccess().apply {
            files[".gallery/library.json"] = """{
                "format":"gallery-library",
                "schema_version":1,
                "library_id":"not-a-uuid",
                "name":"Bad",
                "created_at":"now",
                "updated_at":"now"
            }""".encodeToByteArray()
        }

        assertTrue(PortableLibraryManager(access).inspect() is LibraryInspection.Invalid)
    }

    @Test
    fun newerSchemaIsOpenedSafely() {
        val access = MemoryDocumentAccess().apply {
            files[".gallery/library.json"] = """{
                "format":"gallery-library",
                "schema_version":99,
                "library_id":"d421f1ce-59e7-4f9f-85a0-250a586cdca5",
                "name":"Future",
                "created_at":"now",
                "updated_at":"now"
            }""".encodeToByteArray()
        }

        assertEquals(LibraryInspection.Unsupported(99), PortableLibraryManager(access).inspect())
    }

    @Test
    fun adoptsADirectoryWhoseGuideSurvivedButIdentityDidNot() {
        // The state a lost `.gallery/library.json` leaves behind. The guide is Rem's own
        // document, so a fresh identity is written around it instead of refusing to attach.
        val access = MemoryDocumentAccess().apply {
            files[PortableLibraryManager.GUIDE_FILE] = "# 用户自己的库说明".encodeToByteArray()
        }

        val library = PortableLibraryManager(access).initialize("Library")

        assertTrue(access.files.containsKey(PortableLibraryManager.LIBRARY_JSON))
        assertTrue(PortableLibraryManager(access).inspect() is LibraryInspection.Valid)
        // Adopting must not reset a document the user may have edited.
        assertEquals(
            "# 用户自己的库说明",
            access.files.getValue(PortableLibraryManager.GUIDE_FILE).decodeToString(),
        )
        assertEquals(library.libraryId, (PortableLibraryManager(access).inspect() as LibraryInspection.Valid).library.libraryId)
    }

    @Test
    fun adoptsADirectoryWhoseSchemaSurvivedAndRegeneratesTheGuide() {
        val access = MemoryDocumentAccess().apply {
            files[PortableLibraryManager.SCHEMA_FILE] =
                """{"schema_version":3}""".encodeToByteArray()
        }

        PortableLibraryManager(access).initialize("Library")

        assertTrue(access.files.containsKey(PortableLibraryManager.GUIDE_FILE))
        assertTrue(PortableLibraryManager(access).inspect() is LibraryInspection.Valid)
    }

    @Test
    fun adoptionStillRefusesASchemaFromTheFuture() {
        val access = MemoryDocumentAccess().apply {
            files[PortableLibraryManager.SCHEMA_FILE] =
                """{"schema_version":99}""".encodeToByteArray()
        }

        assertThrows(UnsupportedSchemaException::class.java) {
            PortableLibraryManager(access).initialize("Library")
        }
        assertTrue(!access.files.containsKey(PortableLibraryManager.LIBRARY_JSON))
    }

    @Test
    fun initializationSurvivesProviderAdjustedTemporaryNames() {
        val access = MemoryDocumentAccess(adjustCreatedNames = true)

        val library = PortableLibraryManager(access).initialize("Provider Library")

        assertEquals("Provider Library", library.name)
        assertTrue(access.files.containsKey(PortableLibraryManager.LIBRARY_JSON))
        assertTrue(access.files.containsKey(PortableLibraryManager.SCHEMA_FILE))
        assertTrue(PortableLibraryManager(access).inspect() is LibraryInspection.Valid)
    }
}

private class MemoryDocumentAccess(
    private val adjustCreatedNames: Boolean = false,
) : LibraryDocumentAccess {
    val files = mutableMapOf<String, ByteArray>()
    private val directories = mutableSetOf<String>()

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
        files[providerPath] = byteArrayOf()
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
        val parent = source.substringBeforeLast('/', missingDelimiterValue = "")
        val target = if (parent.isEmpty()) displayName else "$parent/$displayName"
        files[target] = files.remove(source) ?: return false
        return true
    }

    override fun delete(document: LibraryDocument): Boolean = files.remove(document.storageKey()) != null

    private fun LibraryDocument.storageKey(): String = locator ?: key
}
