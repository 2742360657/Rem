package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.library.LibraryDocumentAccess
import dev.susnowy.gallery.model.InboxDisposition
import dev.susnowy.gallery.model.InboxTarget
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.PortableInboxDecision
import dev.susnowy.gallery.model.UnsupportedSchemaException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableInboxStoreTest {
    private val access = InboxMemoryAccess()
    private val store = PortableInboxStore(access)

    @Test
    fun writesAndReloadsDecisions() {
        val accepted = decision("Comics/Stray/Work", workId = "work-1", disposition = InboxDisposition.ACCEPTED)
        val ignored = decision(
            "Comics/Raw/broken.rar",
            disposition = InboxDisposition.IGNORED,
            target = InboxTarget.DISCOVERY,
        )
        val saved = store.upsert("library-id", listOf(accepted, ignored))

        assertEquals(1, saved.revision)
        val reloaded = store.load("library-id")
        assertEquals(2, reloaded.decisions.size)
        assertEquals(InboxDisposition.ACCEPTED, reloaded.forWork("work-1", "Comics/Stray/Work")?.disposition)
        assertEquals(
            InboxDisposition.IGNORED,
            reloaded.forDiscovery("Comics/Raw/broken.rar")?.disposition,
        )
        assertNull(reloaded.forDiscovery("Comics/Stray/Work"))
        assertTrue(access.read(PortableInboxStore.PATH)!!.contains("\"schema_version\": 4"))
    }

    @Test
    fun reDecidingTheSameWorkReplacesTheRecord() {
        store.upsert("library-id", listOf(decision("A/Work", workId = "work-1", disposition = InboxDisposition.ACCEPTED)))
        val saved = store.upsert(
            "library-id",
            listOf(decision("A/Work", workId = "work-1", disposition = InboxDisposition.IGNORED)),
        )

        assertEquals(1, saved.decisions.size)
        assertEquals(InboxDisposition.IGNORED, saved.decisions.single().disposition)
        assertEquals(2, saved.revision)
    }

    @Test
    fun rejectsStaleRevision() {
        store.upsert("library-id", listOf(decision("A/Work", workId = "work-1", disposition = InboxDisposition.ACCEPTED)))

        assertThrows(RevisionConflictException::class.java) {
            store.upsert(
                "library-id",
                listOf(decision("B/Work", workId = "work-2", disposition = InboxDisposition.ACCEPTED)),
                expectedRevision = 0,
            )
        }
    }

    @Test
    fun removesDecisionSoTargetReturnsToInbox() {
        store.upsert(
            "library-id",
            listOf(
                decision("A/Work", workId = "work-1", disposition = InboxDisposition.IGNORED),
                decision("Raw/file.rar", disposition = InboxDisposition.HANDLED, target = InboxTarget.DISCOVERY),
            ),
        )

        val afterWork = store.remove("library-id", setOf("work-1"))
        assertEquals(1, afterWork.decisions.size)
        val afterAll = store.remove("library-id", setOf("Raw/file.rar"))
        assertTrue(afterAll.decisions.isEmpty())
    }

    @Test
    fun removesDecisionsOfDeletedWorksOnly() {
        store.upsert(
            "library-id",
            listOf(
                decision("A/Work", workId = "work-1", disposition = InboxDisposition.ACCEPTED),
                decision("Raw/file.rar", disposition = InboxDisposition.IGNORED, target = InboxTarget.DISCOVERY),
            ),
        )

        val remaining = store.removeWorks("library-id", setOf("work-1"))
        assertEquals(1, remaining.decisions.size)
        assertEquals(InboxTarget.DISCOVERY, remaining.decisions.single().target)
    }

    @Test
    fun relocatesDecisionWithItsWork() {
        store.upsert("library-id", listOf(decision("Comics/A", workId = "work-1", disposition = InboxDisposition.IGNORED)))

        store.relocate("library-id", "work-1", "Comics/A", "Works/Author/A")

        val relocated = store.load("library-id").decisions.single()
        assertEquals("Works/Author/A", relocated.relativePath)
        assertEquals("work-1", relocated.workId)
    }

    @Test
    fun matchesWorkByidAfterItsPathChanged() {
        store.upsert("library-id", listOf(decision("Comics/A", workId = "work-1", disposition = InboxDisposition.IGNORED)))

        val inbox = store.load("library-id")
        // A scan that runs before the Organizer's relocation is visible still resolves the
        // Work through its id, so an ignored Work cannot fall back into Inbox.
        assertEquals(
            InboxDisposition.IGNORED,
            inbox.forWork("work-1", "Works/Author/A")?.disposition,
        )
    }

    @Test
    fun rejectsNonPortablePaths() {
        assertThrows(IllegalArgumentException::class.java) {
            store.upsert("library-id", listOf(decision("/sdcard/Work", disposition = InboxDisposition.IGNORED)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.upsert("library-id", listOf(decision("Comics/../../etc/passwd", disposition = InboxDisposition.IGNORED)))
        }
    }

    @Test
    fun refusesDecisionsOfAnotherLibrary() {
        store.upsert("library-id", listOf(decision("A/Work", workId = "work-1", disposition = InboxDisposition.ACCEPTED)))

        assertThrows(IllegalArgumentException::class.java) { store.load("other-library") }
    }

    @Test
    fun refusesHandledForAMediaWork() {
        assertThrows(IllegalArgumentException::class.java) {
            store.upsert(
                "library-id",
                listOf(
                    decision(
                        "A/Work",
                        workId = "work-1",
                        disposition = InboxDisposition.HANDLED,
                        target = InboxTarget.MEDIA,
                    ),
                ),
            )
        }
    }

    @Test
    fun refusesUnknownNewerSchema() {
        access.seed(
            PortableInboxStore.PATH,
            """
            {"schema_version":5,"library_id":"library-id","revision":1,"updated_at":"2026-09-18T00:00:00Z","decisions":[]}
            """.trimIndent(),
        )

        assertThrows(UnsupportedSchemaException::class.java) { store.load("library-id") }
    }

    @Test
    fun refusedWriteKeepsThePreviousDocument() {
        val qualifying = InboxMemoryAccess(qualifyStagingCommit = true)
        val guarded = PortableInboxStore(qualifying)
        guarded.upsert("library-id", listOf(decision("A/Work", workId = "work-1", disposition = InboxDisposition.ACCEPTED)))
        val before = qualifying.read(PortableInboxStore.PATH)

        // The provider publishes the committed revision under a qualified name and leaves
        // the staged document behind. Accepting that would leave two Inbox documents, so the
        // write must fail and the previous revision must come back.
        assertThrows(IllegalStateException::class.java) {
            guarded.upsert("library-id", listOf(decision("B/Work", workId = "work-2", disposition = InboxDisposition.ACCEPTED)))
        }

        assertEquals(before, qualifying.read(PortableInboxStore.PATH))
        assertEquals(1, guarded.load("library-id").decisions.size)
    }

    @Test
    fun toleratesProvidersThatAdjustStagingNames() {
        val adjusted = PortableInboxStore(InboxMemoryAccess(adjustCreatedNames = true))

        adjusted.upsert("library-id", listOf(decision("A/Work", workId = "work-1", disposition = InboxDisposition.ACCEPTED)))
        adjusted.upsert("library-id", listOf(decision("A/Work", workId = "work-1", disposition = InboxDisposition.IGNORED)))

        val reloaded = adjusted.load("library-id")
        assertEquals(2, reloaded.revision)
        assertEquals(InboxDisposition.IGNORED, reloaded.decisions.single().disposition)
    }

    private fun decision(
        path: String,
        workId: String? = null,
        disposition: InboxDisposition,
        target: InboxTarget = InboxTarget.MEDIA,
        domain: MediaDomain? = null,
    ) = PortableInboxDecision(
        relativePath = path,
        target = target,
        workId = workId,
        disposition = disposition,
        domain = domain,
        decidedAt = "2026-09-18T00:00:00Z",
    )
}

private class InboxMemoryAccess(
    private val adjustCreatedNames: Boolean = false,
    private val qualifyStagingCommit: Boolean = false,
) : LibraryDocumentAccess {
    private val files = mutableMapOf<String, ByteArray>()
    private val directories = mutableSetOf<String>()
    private val usedNames = mutableSetOf<String>()

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
        val bytes = files[source] ?: return false
        val parent = source.substringBeforeLast('/', "")
        val target = if (parent.isBlank()) displayName else "$parent/$displayName"
        // A provider that has not retired the previous revision publishes the committed
        // document under a ` (1)` name and leaves the staged one in place.
        if (qualifyStagingCommit && source.endsWith(".tmp") && target in usedNames) {
            files["$target (1)"] = bytes
            return true
        }
        files.remove(source)
        files[target] = bytes
        usedNames += target
        return true
    }

    override fun delete(document: LibraryDocument): Boolean = files.remove(document.storageKey()) != null

    private fun LibraryDocument.storageKey(): String = locator ?: key
}
