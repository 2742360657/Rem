package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.library.LibraryDocumentAccess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * In-memory `LibraryDocumentAccess` shared by the portable-store tests.
 *
 * Two provider behaviours that have caused real bugs are switchable:
 * [adjustCreatedNames] appends an extension to staged documents, and
 * [qualifyStagingCommit] publishes a committed staging document under a ` (1)` name while
 * leaving the staged file behind.
 */
internal class MemoryLibraryAccess(
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

    fun paths(): Set<String> = files.keys.toSet()

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
