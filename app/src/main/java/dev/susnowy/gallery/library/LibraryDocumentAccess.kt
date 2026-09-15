package dev.susnowy.gallery.library

import java.io.InputStream
import java.io.OutputStream

interface LibraryDocumentAccess {
    fun find(relativePath: String): LibraryDocument?
    fun ensureDirectory(relativePath: String): LibraryDocument
    fun createFile(relativePath: String, mimeType: String): LibraryDocument
    fun openInput(document: LibraryDocument): InputStream
    fun openOutput(document: LibraryDocument, truncate: Boolean = true): OutputStream
    fun rename(document: LibraryDocument, displayName: String): Boolean
    fun delete(document: LibraryDocument): Boolean
}

data class LibraryDocument(
    val key: String,
    val name: String,
    val isDirectory: Boolean,
    /**
     * Provider-native locator for a document. SAF providers are allowed to
     * adjust a requested display name (for example by appending an extension),
     * so newly created documents must not be looked up again only by [key].
     */
    val locator: String? = null,
)
