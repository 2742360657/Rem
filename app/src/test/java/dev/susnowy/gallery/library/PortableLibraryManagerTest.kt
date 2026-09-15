package dev.susnowy.gallery.library

import dev.susnowy.gallery.model.LibraryInspection
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortableLibraryManagerTest {
    @Test
    fun initializeCreatesPortableIdentityAndGuide() {
        val access = MemoryDocumentAccess()
        val library = PortableLibraryManager(access).initialize("  My\nLibrary  ")

        assertEquals("My Library", library.name)
        assertTrue(access.files.containsKey(".gallery/library.json"))
        assertTrue(access.files.containsKey("GALLERY_LIBRARY.md"))
        assertTrue(PortableLibraryManager(access).inspect() is LibraryInspection.Valid)
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
}

private class MemoryDocumentAccess : LibraryDocumentAccess {
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
        files[relativePath] = byteArrayOf()
        return LibraryDocument(relativePath, relativePath.substringAfterLast('/'), false)
    }

    override fun openInput(document: LibraryDocument): InputStream =
        ByteArrayInputStream(files.getValue(document.key))

    override fun openOutput(document: LibraryDocument, truncate: Boolean): OutputStream =
        object : ByteArrayOutputStream() {
            override fun close() {
                files[document.key] = toByteArray()
                super.close()
            }
        }

    override fun rename(document: LibraryDocument, displayName: String): Boolean {
        val parent = document.key.substringBeforeLast('/', missingDelimiterValue = "")
        val target = if (parent.isEmpty()) displayName else "$parent/$displayName"
        files[target] = files.remove(document.key) ?: return false
        return true
    }

    override fun delete(document: LibraryDocument): Boolean = files.remove(document.key) != null
}
