package dev.susnowy.gallery.storage

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.FileNotFoundException

/**
 * One attached Library, read through the Storage Access Framework.
 *
 * Every `DocumentsContract` call is a Binder round-trip into the provider, and on a removable
 * volume that provider is a USB device, so the number of round-trips — not the number of bytes
 * — decides how long a scan takes. `DocumentFile.listFiles()` is unusable for that reason: it
 * returns URI stubs carrying no metadata, so reading a child's name, size and timestamp costs
 * one query each, and `findFile()` re-lists the whole parent and probes every child by name.
 *
 * This class therefore lists a directory with a single projection query that returns every
 * column at once. Nothing is cached between instances: a scan builds a fresh tree, so a file
 * added outside Rem is always visible on the next scan.
 */
class LibraryTree(private val context: Context, val treeUri: Uri) {

    private val resolver = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /** False once the grant is revoked or the volume is gone. */
    val isAvailable: Boolean
        get() = runCatching {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            root.exists() && root.canRead()
        }.getOrDefault(false)

    /** The folder the user picked, used as the default Library name. */
    val name: String
        get() = runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull().orEmpty()

    fun documentUri(relativePath: String): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        if (relativePath.isEmpty()) rootId else "$rootId/${relativePath.trim('/')}",
    )

    /** Resolves one path, or `null` when it does not exist. */
    fun find(relativePath: String): Child? = query(documentUri(relativePath), relativePath, rootId)

    /**
     * Lists a directory's direct children in one query. Returns an empty list when the
     * directory is missing, so callers treat "no such folder" and "empty folder" alike.
     */
    fun list(relativePath: String): List<Child> {
        val parent = find(relativePath) ?: return emptyList()
        if (!parent.isDirectory) return emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId)
        return runCatching {
            resolver.query(childrenUri, PROJECTION, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.text(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: continue
                        val childName = cursor.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: continue
                        add(cursor.toChild(childPath(relativePath, childName), childName, id))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** True when the path exists and is a directory. */
    fun isDirectory(relativePath: String): Boolean = find(relativePath)?.isDirectory == true

    /** True when the path exists at all. */
    fun exists(relativePath: String): Boolean = find(relativePath) != null

    /**
     * Creates `.gallery/` if needed and writes [fileName] inside it.
     *
     * Only Rem's own state directory is ever written; media files are read-only to this app.
     * A failed write is not an error the user needs to see — the index is a cache, so the
     * caller can carry on with what it has in memory.
     */
    fun writeInternal(fileName: String, text: String): Boolean = runCatching {
        val directory = ensureInternalDirectory() ?: return@runCatching false
        val target = directory.findFile(fileName)?.takeIf { !it.isDirectory }
        val uri = target?.uri ?: directory.createFile("text/markdown", fileName)?.uri
        if (uri == null) return@runCatching false
        // "wt" truncates, so a shorter replacement never leaves the tail of the old file behind.
        resolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            true
        } ?: false
    }.getOrDefault(false)

    /** Reads one of Rem's own state files, or `null` when it is absent. */
    fun readInternal(fileName: String): String? = runCatching {
        val uri = documentUri("$INTERNAL_DIR/$fileName")
        resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    private fun ensureInternalDirectory(): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val existing = root.findFile(INTERNAL_DIR)
        if (existing != null && existing.isDirectory) return existing
        if (existing != null) return null
        return root.createDirectory(INTERNAL_DIR)
    }

    private fun query(uri: Uri, relativePath: String, documentId: String): Child? = runCatching {
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: return@use null
            cursor.toChild(relativePath, name, documentId)
        }
    }.getOrNull()

    private fun Cursor.toChild(relativePath: String, name: String, id: String): Child {
        val mimeType = text(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val directory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        return Child(
            documentId = id,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
            path = relativePath,
            name = name,
            isDirectory = directory,
            size = if (directory) 0L else number(DocumentsContract.Document.COLUMN_SIZE),
            modified = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            mimeType = mimeType,
        )
    }

    private fun childPath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    companion object {
        const val INTERNAL_DIR = ".gallery"

        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /** Keeps the grant alive across reboots so a Library stays attached. */
        fun persistPermission(context: Context, treeUri: Uri) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }

        fun throwIfMissing(child: Child?): Child =
            child ?: throw FileNotFoundException("Library 目录不可用")
    }
}

private fun Cursor.text(column: String): String? {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) null else getString(index)
}

private fun Cursor.number(column: String): Long {
    val index = getColumnIndex(column)
    return if (index < 0 || isNull(index)) 0L else getLong(index)
}
