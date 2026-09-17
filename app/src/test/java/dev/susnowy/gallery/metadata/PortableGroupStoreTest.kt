package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.library.LibraryDocumentAccess
import dev.susnowy.gallery.model.GroupMemberRole
import dev.susnowy.gallery.model.GroupType
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.PortableGroup
import dev.susnowy.gallery.model.PortableGroupMember
import dev.susnowy.gallery.model.SourceKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableGroupStoreTest {
    private val access = GroupMemoryAccess()
    private val store = PortableMetadataStore(access)

    private fun item(id: String, path: String) = MediaItem(
        id = id,
        libraryId = "library-id",
        relativePath = path,
        uri = "content://$id",
        kind = MediaKind.IMAGE_SET,
        sourceKind = SourceKind.DIRECTORY,
        displayTitle = id,
    )

    private fun group(
        id: String = "group-1",
        title: String = "写真集",
        workIds: List<String> = listOf("work-1", "work-2"),
        coverWorkId: String? = "work-1",
        type: GroupType = GroupType.MEDIA_SET,
    ) = PortableGroup(
        id = id,
        title = title,
        type = type,
        members = workIds.mapIndexed { index, workId ->
            PortableGroupMember(workId, GroupMemberRole.ITEM, index.toDouble())
        },
        coverWorkId = coverWorkId,
        revision = 1,
        updatedAt = "2026-09-17T00:00:00Z",
    )

    @Test
    fun createsAndReloadsAGroup() {
        store.saveItems(listOf(item("work-1", "Inbox/A"), item("work-2", "Inbox/B")))

        val saved = store.upsertGroup("library-id", group())

        assertEquals(1, saved.revision)
        assertEquals("写真集", saved.title)
        val reloaded = store.loadCatalog("library-id").groups.single()
        assertEquals(listOf("work-1", "work-2"), reloaded.members.map(PortableGroupMember::workId))
        assertEquals("work-1", reloaded.coverWorkId)
        assertEquals(2, store.loadCatalog("library-id").revision)
    }

    @Test
    fun updatingReplacesMembersAndBumpsRevision() {
        store.saveItems(listOf(item("work-1", "Inbox/A"), item("work-2", "Inbox/B")))
        store.upsertGroup("library-id", group())

        val updated = store.upsertGroup(
            "library-id",
            group(title = "改名后的写真集", workIds = listOf("work-2")),
            expectedRevision = 1,
        )

        assertEquals(2, updated.revision)
        val reloaded = store.loadCatalog("library-id").groups.single()
        assertEquals("改名后的写真集", reloaded.title)
        assertEquals(listOf("work-2"), reloaded.members.map(PortableGroupMember::workId))
        assertEquals(1, store.loadCatalog("library-id").groups.size)
    }

    @Test
    fun rejectsStaleGroupRevision() {
        store.saveItems(listOf(item("work-1", "Inbox/A")))
        store.upsertGroup("library-id", group(workIds = listOf("work-1")))

        assertThrows(RevisionConflictException::class.java) {
            store.upsertGroup(
                "library-id",
                group(title = "过期写入", workIds = listOf("work-1")),
                expectedRevision = 0,
            )
        }
    }

    @Test
    fun rejectsUnknownMembersAndBlankTitles() {
        store.saveItems(listOf(item("work-1", "Inbox/A")))

        assertThrows(IllegalArgumentException::class.java) {
            store.upsertGroup("library-id", group(workIds = listOf("work-1", "missing-work")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.upsertGroup("library-id", group(title = "   ", workIds = listOf("work-1")))
        }
    }

    @Test
    fun deletingAGroupOnlyRemovesTheRelationship() {
        store.saveItems(listOf(item("work-1", "Inbox/A"), item("work-2", "Inbox/B")))
        store.upsertGroup("library-id", group())
        val before = store.loadCatalog("library-id")

        assertTrue(store.deleteGroup("library-id", "group-1"))

        val after = store.loadCatalog("library-id")
        assertTrue(after.groups.isEmpty())
        assertEquals(before.works, after.works)
        assertEquals(before.assets, after.assets)
        assertEquals(before.editions, after.editions)
        assertFalse(store.deleteGroup("library-id", "group-1"))
    }

    @Test
    fun duplicateMembersAreCollapsedOnWrite() {
        store.saveItems(listOf(item("work-1", "Inbox/A")))

        val saved = store.upsertGroup(
            "library-id",
            group(workIds = listOf("work-1", "work-1")),
        )

        assertEquals(listOf("work-1"), saved.members.map(PortableGroupMember::workId))
    }

    private fun PortableMetadataStore.saveItems(items: List<MediaItem>) {
        items.forEachIndexed { index, item -> saveItem(item, index.toLong()) }
    }
}

private class GroupMemoryAccess : LibraryDocumentAccess {
    private val files = mutableMapOf<String, ByteArray>()
    private val directories = mutableSetOf<String>()

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
        files.putIfAbsent(relativePath, byteArrayOf())
        return LibraryDocument(relativePath, relativePath.substringAfterLast('/'), false)
    }

    override fun openInput(document: LibraryDocument): InputStream =
        ByteArrayInputStream(files.getValue(document.locator ?: document.key))

    override fun openOutput(document: LibraryDocument, truncate: Boolean): OutputStream =
        object : ByteArrayOutputStream() {
            override fun close() {
                files[document.locator ?: document.key] = toByteArray()
                super.close()
            }
        }

    override fun rename(document: LibraryDocument, displayName: String): Boolean {
        val source = document.locator ?: document.key
        val bytes = files.remove(source) ?: return false
        val parent = source.substringBeforeLast('/', "")
        val target = if (parent.isBlank()) displayName else "$parent/$displayName"
        files[target] = bytes
        return true
    }

    override fun delete(document: LibraryDocument): Boolean =
        files.remove(document.locator ?: document.key) != null
}
