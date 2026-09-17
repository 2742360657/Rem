package dev.susnowy.gallery.library

import dev.susnowy.gallery.model.LibraryInspection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A provider that behaves like Android's own when a rename targets a name that already
 * exists: it keeps the source document where it was and publishes a copy under a ` (1)`
 * qualified name. This is how a lost initialization race used to produce two
 * `library.json` identities side by side.
 */
private class CollisionSuffixingAccess(
    /** Only names matching this predicate are qualified; others are replaced in place. */
    private val collides: (String) -> Boolean = { true },
    /** Names the provider refuses to rename at all, leaving the source where it is. */
    private val refusesRename: (String) -> Boolean = { false },
) : LibraryDocumentAccess {
    val files = mutableMapOf<String, ByteArray>()
    val directories = mutableSetOf<String>()

    override fun find(relativePath: String): LibraryDocument? = when {
        relativePath in directories ->
            LibraryDocument(relativePath, relativePath.substringAfterLast('/'), true)
        relativePath in files ->
            LibraryDocument(relativePath, relativePath.substringAfterLast('/'), false)
        else -> null
    }

    override fun ensureDirectory(relativePath: String): LibraryDocument {
        directories += relativePath
        return LibraryDocument(relativePath, relativePath.substringAfterLast('/'), true)
    }

    override fun createFile(relativePath: String, mimeType: String): LibraryDocument {
        // A freshly generated staging name never collides.
        files[relativePath] = byteArrayOf()
        return LibraryDocument(
            key = relativePath,
            name = relativePath.substringAfterLast('/'),
            isDirectory = false,
            locator = relativePath,
        )
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
        val parent = source.substringBeforeLast('/', "")
        val requested = if (parent.isEmpty()) displayName else "$parent/$displayName"
        val content = files[source] ?: return false
        if (refusesRename(requested)) return false
        if (requested !in files || !collides(requested)) {
            files[requested] = content
            files.remove(source)
            return true
        }
        // Android publishes a copy under a qualified name and leaves the source document
        // exactly where it was, which is how a duplicate appears next to the original.
        val stem = requested.substringAfterLast('/').substringBeforeLast('.')
        val extension = requested.substringAfterLast('.', "")
        val qualified = if (extension.isEmpty()) "$stem (1)" else "$stem (1).$extension"
        files[if (parent.isEmpty()) qualified else "$parent/$qualified"] = content
        return true
    }

    override fun delete(document: LibraryDocument): Boolean =
        files.remove(document.locator ?: document.key) != null
}

class PortableDocumentWriterCommitTest {
    @Test
    fun writeCommitsWhenTheProviderKeepsTheRequestedName() {
        val access = CollisionSuffixingAccess()
        PortableLibraryManager(access).initialize("Library")

        PortableDocumentWriter(access).write("plain.json", """{"ok":true}""", "application/json")

        assertEquals("""{"ok":true}""", access.files.getValue("plain.json").decodeToString())
    }

    @Test
    fun writeRejectsACommitThatTheProviderQualified() {
        // The provider refuses to move the existing document aside and also qualifies the
        // staged rename instead of replacing the target. Both are how a duplicate appears
        // next to the original, and the write must be refused rather than committed.
        val access = CollisionSuffixingAccess(refusesRename = { true })
        access.files["counter.json"] = "original".encodeToByteArray()

        val thrown = runCatching {
            PortableDocumentWriter(access).write(
                "counter.json",
                """{"replaced":true}""",
                "application/json",
            )
        }.exceptionOrNull()

        assertTrue("写入必须被拒绝，实际抛出 $thrown", thrown is IllegalStateException)
        assertEquals("original", access.files.getValue("counter.json").decodeToString())
        assertEquals(1, access.files.keys.count { it.startsWith("counter") })
        assertTrue(
            "不得留下暂存或恢复文件",
            access.files.keys.none { it.contains(".tmp") || it.endsWith(".rem-backup") },
        )
    }

    @Test
    fun writeLeavesNoStagingFilesBehindOnSuccess() {
        val access = CollisionSuffixingAccess()
        PortableLibraryManager(access).initialize("Library")

        PortableDocumentWriter(access).write("plain.json", """{"ok":true}""", "application/json")

        assertTrue(
            access.files.keys.none { it.contains(".tmp") || it.endsWith(".rem-backup") },
        )
    }

    @Test
    fun failedCommitRestoresThePreviousRevisionFromTheStableBackup() {
        var targetAttempts = 0
        val access = CollisionSuffixingAccess(
            refusesRename = { requested ->
                requested == "counter.json" && targetAttempts++ == 0
            },
        ).apply {
            files["counter.json"] = "original".encodeToByteArray()
        }

        val thrown = runCatching {
            PortableDocumentWriter(access).write("counter.json", "replacement", "application/json")
        }.exceptionOrNull()

        assertTrue("提交失败必须向调用方报告", thrown is IllegalStateException)
        assertEquals("original", access.files.getValue("counter.json").decodeToString())
        assertTrue(
            "回滚后不得留下暂存或恢复文件",
            access.files.keys.none { it.contains(".tmp") || it.endsWith(".rem-backup") },
        )
    }

    @Test
    fun readRecoversThePreviousRevisionAfterAnInterruptedCommit() {
        val access = CollisionSuffixingAccess().apply {
            files[".counter.json.rem-backup"] = "original".encodeToByteArray()
        }

        val recovered = PortableDocumentWriter(access).read("counter.json")

        assertEquals("original", recovered)
        assertEquals("original", access.files.getValue("counter.json").decodeToString())
        assertTrue("恢复后不得保留备份占位", ".counter.json.rem-backup" !in access.files)
    }

    @Test
    fun nextWriteDiscardsAStaleBackupLeftAfterACommittedRevision() {
        val access = CollisionSuffixingAccess().apply {
            files["counter.json"] = "current".encodeToByteArray()
            files[".counter.json.rem-backup"] = "older".encodeToByteArray()
        }

        PortableDocumentWriter(access).write("counter.json", "next", "application/json")

        assertEquals("next", access.files.getValue("counter.json").decodeToString())
        assertTrue("成功提交后不得保留旧备份", ".counter.json.rem-backup" !in access.files)
    }

    @Test
    fun libraryInspectionRecoversAnIdentityWhoseCommitWasInterrupted() {
        val access = CollisionSuffixingAccess()
        val manager = PortableLibraryManager(access)
        val initialized = manager.initialize("Library")
        access.files[".gallery/.library.json.rem-backup"] =
            access.files.remove(".gallery/library.json")!!

        val inspection = manager.inspect()

        assertEquals(initialized, (inspection as LibraryInspection.Valid).library)
        assertTrue("Library 身份应恢复到正式路径", ".gallery/library.json" in access.files)
        assertTrue(
            "恢复后不得保留备份占位",
            ".gallery/.library.json.rem-backup" !in access.files,
        )
    }
}

/**
 * A provider that never overwrites: creating or renaming onto a name it already holds
 * publishes a ` (1)` copy instead. This is exactly how a lost initialization race used
 * to end up with two `library.json` identities.
 */
private class AlreadyTakenNameQualifyingAccess : LibraryDocumentAccess {
    val files = mutableMapOf<String, ByteArray>()
    private val directories = mutableSetOf<String>()

    private fun qualify(relativePath: String): String {
        if (relativePath !in files) return relativePath
        val parent = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        val qualified = if (extension.isEmpty()) "$stem (1)" else "$stem (1).$extension"
        return if (parent.isEmpty()) qualified else "$parent/$qualified"
    }

    override fun find(relativePath: String): LibraryDocument? = when {
        relativePath in directories ->
            LibraryDocument(relativePath, relativePath.substringAfterLast('/'), true)
        relativePath in files ->
            LibraryDocument(relativePath, relativePath.substringAfterLast('/'), false)
        else -> null
    }

    override fun ensureDirectory(relativePath: String): LibraryDocument {
        directories += relativePath
        return LibraryDocument(relativePath, relativePath.substringAfterLast('/'), true)
    }

    override fun createFile(relativePath: String, mimeType: String): LibraryDocument {
        val published = qualify(relativePath)
        files[published] = byteArrayOf()
        return LibraryDocument(
            key = relativePath,
            name = published.substringAfterLast('/'),
            isDirectory = false,
            locator = published,
        )
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
        val content = files[source] ?: return false
        val parent = source.substringBeforeLast('/', "")
        val published = qualify(if (parent.isEmpty()) displayName else "$parent/$displayName")
        files[published] = content
        if (published == (if (parent.isEmpty()) displayName else "$parent/$displayName")) {
            files.remove(source)
        }
        return true
    }

    override fun delete(document: LibraryDocument): Boolean =
        files.remove(document.locator ?: document.key) != null
}

class PortableLibraryInitializationLockTest {
    @Test
    fun initializeReleasesTheLockAfterPublishingTheIdentity() {
        val access = CollisionSuffixingAccess()

        val library = PortableLibraryManager(access).initialize("Library")

        assertTrue(!access.files.containsKey(PortableLibraryManager.INIT_LOCK_FILE))
        assertEquals(
            library.libraryId,
            (PortableLibraryManager(access).inspect() as LibraryInspection.Valid).library.libraryId,
        )
    }

    @Test
    fun initializeRefusesWhenAnotherInstanceHoldsTheLock() {
        // A provider that qualifies an already-taken name instead of overwriting it: the
        // second initializer's lock claim comes back renamed, which is how it learns it
        // lost the race.
        val access = AlreadyTakenNameQualifyingAccess()
        access.files[PortableLibraryManager.INIT_LOCK_FILE] = byteArrayOf()

        val thrown = runCatching {
            PortableLibraryManager(access).initialize("Library")
        }.exceptionOrNull()

        assertTrue(
            "期望锁定冲突，实际抛出 $thrown",
            thrown is InitializationInProgressException,
        )
        // No competing identity may be published by the loser.
        assertTrue(access.files.keys.none { it == PortableLibraryManager.LIBRARY_JSON })
        assertEquals(
            1,
            access.files.keys.count { it == PortableLibraryManager.INIT_LOCK_FILE },
        )
    }

    @Test
    fun initializeRecoversAnExpiredLockLeftByAStoppedProcess() {
        val access = AlreadyTakenNameQualifyingAccess()
        access.files[PortableLibraryManager.INIT_LOCK_FILE] =
            "2020-01-01T00:00:00Z".encodeToByteArray()

        val library = PortableLibraryManager(access).initialize("Recovered Library")

        assertEquals(
            library.libraryId,
            (PortableLibraryManager(access).inspect() as LibraryInspection.Valid).library.libraryId,
        )
        assertTrue(!access.files.containsKey(PortableLibraryManager.INIT_LOCK_FILE))
    }

    @Test
    fun aSecondInitializeNeverCreatesASecondIdentity() {
        val access = CollisionSuffixingAccess()
        val first = PortableLibraryManager(access).initialize("Library")

        assertThrows(IllegalStateException::class.java) {
            PortableLibraryManager(access).initialize("Library")
        }
        assertEquals(1, access.files.keys.count { it == PortableLibraryManager.LIBRARY_JSON })
        assertEquals(
            first.libraryId,
            (PortableLibraryManager(access).inspect() as LibraryInspection.Valid).library.libraryId,
        )
    }
}
