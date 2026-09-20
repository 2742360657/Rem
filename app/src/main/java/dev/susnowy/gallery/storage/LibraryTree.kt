package dev.susnowy.gallery.storage

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dev.susnowy.gallery.logging.RemLog
import java.io.FileNotFoundException

/**
 * One attached Library, read through the Storage Access Framework.
 *
 * Every `DocumentsContract` call is a Binder round-trip into the provider, and on a removable
 * volume that provider is a USB device, so the number of round-trips — not the number of bytes —
 * decides how long a scan takes.
 *
 * Document IDs are only ever taken from what the provider returned in a cursor. They look
 * reconstructible (`root` + `/` + `name`) and they are not: a hand-built ID is silently resolved
 * back to the parent by the provider rather than rejected, so a wrong guess shows up as a
 * directory that lists its own parent's contents instead of failing. Navigation therefore walks
 * one level at a time, and a directory already listed is remembered for the rest of the instance.
 */
class LibraryTree(private val context: Context, val treeUri: Uri) {

    private val resolver = context.contentResolver
    private val rootId: String = DocumentsContract.getTreeDocumentId(treeUri)

    /**
     * Listings already fetched, keyed by document ID.
     *
     * The scan lists a directory and then immediately asks for the files inside each of its
     * children, so without this every level would be queried twice: once to discover it, once to
     * resolve it.
     */
    private val listings = mutableMapOf<String, Map<String, Child>>()

    /** The picked folder itself, resolved by the one document ID that is certainly correct. */
    private val rootChild: Child? by lazy { query(documentsUri(rootId), "", rootId) }

    /** False once the grant is revoked or the volume is gone. */
    val isAvailable: Boolean
        get() = runCatching {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            root.exists() && root.canRead()
        }.getOrDefault(false)

    /** The folder the user picked, used as the default Library name. */
    val name: String
        get() = runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }.getOrNull().orEmpty()

    /** The provider's ID for the picked folder. Logged once per scan; the whole scheme rests on it. */
    fun rootDocumentId(): String = rootId

    /** The document URI an entry's path resolves to, for opening and thumbnail requests. */
    fun documentUri(relativePath: String): Uri =
        navigate(relativePath)?.uri ?: documentsUri(rootId)

    /** Resolves one path, or `null` when it does not exist. */
    fun find(relativePath: String): Child? {
        if (relativePath.isBlank()) return rootChild
        return navigate(relativePath)
    }

    /**
     * Walks a path one level at a time, using only document IDs the provider handed back.
     *
     * A level already listed costs nothing, which is what makes resolving the files inside a
     * directory free right after that directory was scanned.
     */
    private fun navigate(relativePath: String): Child? {
        val segments = relativePath.trim('/').split('/').filter(String::isNotBlank)
        var documentId = rootId
        var parentPath = ""
        var found: Child? = null
        for (segment in segments) {
            found = childrenOf(documentId, parentPath)[segment] ?: return null
            documentId = found.documentId
            parentPath = found.path
        }
        return found
    }

    /**
     * Lists a directory's direct children. Returns an empty list when the directory is missing, so
     * callers treat "no such folder" and "empty folder" alike.
     */
    fun list(relativePath: String): List<Child> {
        val parent = find(relativePath) ?: return emptyList()
        if (!parent.isDirectory) return emptyList()
        return childrenOf(parent.documentId, parent.path).values.toList()
    }

    /** True when the path exists and is a directory. */
    fun isDirectory(relativePath: String): Boolean = find(relativePath)?.isDirectory == true

    /** True when the path exists at all. */
    fun exists(relativePath: String): Boolean = find(relativePath) != null

    /**
     * Writes one of Rem's own files into `.gallery/`.
     *
     * The MIME type is deliberately one no provider recognises. A provider maps a MIME type to an
     * extension and appends it when the name has none, which is how `text/plain` turned
     * `index.json` into `index.json.txt` — a file Rem could then never read back, so it wrote a
     * fresh one on every launch instead of updating the existing one.
     */
    fun writeInternal(fileName: String, text: String): Boolean {
        val result = runCatching {
            val directory = ensureInternalDirectory() ?: return@runCatching false
            val target = directory.findFile(fileName)?.takeIf { !it.isDirectory }
            val uri = target?.uri ?: directory.createFile(OPAQUE_MIME, fileName)?.uri
            if (uri == null) return@runCatching false
            // "wt" truncates, so a shorter replacement never leaves the tail of the old file behind.
            resolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                true
            } ?: false
        }
        val succeeded = result.getOrDefault(false)
        if (succeeded) {
            RemLog.info(SCOPE, "写入 $INTERNAL_DIR/$fileName 成功 ${text.toByteArray(Charsets.UTF_8).size}B")
        } else {
            RemLog.error(
                SCOPE,
                "写入 $INTERNAL_DIR/$fileName 失败：${result.exceptionOrNull()?.message ?: "无法创建或打开文件"}",
                result.exceptionOrNull(),
            )
        }
        listings.clear()
        return succeeded
    }

    /**
     * Clears out copies a previous build created under a provider-appended extension.
     *
     * Runs once per launch and is cheap when there is nothing to do: it only looks at the one
     * directory Rem owns.
     */
    fun cleanUpMangledNames(fileName: String) {
        val directory = find(INTERNAL_DIR) ?: return
        if (!directory.isDirectory) return
        val children = childrenOf(directory.documentId, directory.path)
        children.values
            .filter { it.name.startsWith("$fileName.") || it.name.startsWith("$fileName (") }
            .forEach { stale ->
                RemLog.info(SCOPE, "清理被改名的旧文件 $INTERNAL_DIR/${stale.name}")
                runCatching { DocumentsContract.deleteDocument(resolver, stale.uri) }
            }
        listings.remove(directory.documentId)
    }

    /** Reads one of Rem's own state files, or `null` when it is absent. */
    fun readInternal(fileName: String): String? {
        val child = find("$INTERNAL_DIR/$fileName")
        if (child == null) {
            RemLog.debug(SCOPE, "$INTERNAL_DIR/$fileName 不存在")
            return null
        }
        return runCatching {
            resolver.openInputStream(child.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.onFailure { RemLog.warn(SCOPE, "读取 $INTERNAL_DIR/$fileName 失败 uri=${child.uri}", it) }
            .getOrNull()
            .also { RemLog.debug(SCOPE, "读取 $INTERNAL_DIR/$fileName -> ${it?.length ?: -1}B") }
    }

    private fun ensureInternalDirectory(): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val existing = root.findFile(INTERNAL_DIR)
        if (existing != null && existing.isDirectory) return existing
        if (existing != null) return null
        return root.createDirectory(INTERNAL_DIR)
    }

    /** Children of one document ID, fetched at most once per instance. */
    private fun childrenOf(documentId: String, parentPath: String): Map<String, Child> {
        listings[documentId]?.let { return it }
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val result = runCatching {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.text(DocumentsContract.Document.COLUMN_DOCUMENT_ID) ?: continue
                        val childName = cursor.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: continue
                        add(cursor.toChild(id, childName, childPath(parentPath, childName)))
                    }
                }
            }.orEmpty()
        }
        result.exceptionOrNull()?.let {
            RemLog.error(SCOPE, "列举子项失败 docId='$documentId' uri=$uri", it)
        }
        val byName = result.getOrDefault(emptyList()).associateBy(Child::name)
        listings[documentId] = byName
        return byName
    }

    /**
     * Resolves one document by ID.
     *
     * A provider answers an unknown ID with an exception rather than an empty cursor, and that
     * exception is the only place the reason is written down, so it is logged instead of dropped.
     */
    private fun query(uri: Uri, relativePath: String, documentId: String): Child? {
        val result = runCatching {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val name = cursor.text(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: return@use null
                cursor.toChild(documentId, name, relativePath)
            }
        }
        result.exceptionOrNull()?.let {
            RemLog.error(SCOPE, "解析失败 path='$relativePath' docId='$documentId' uri=$uri", it)
        }
        return result.getOrNull()
    }

    private fun Cursor.toChild(documentId: String, name: String, documentPath: String): Child {
        val mimeType = text(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val directory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        return Child(
            documentId = documentId,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            path = documentPath,
            name = name,
            isDirectory = directory,
            size = if (directory) 0L else number(DocumentsContract.Document.COLUMN_SIZE),
            modified = number(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            mimeType = mimeType,
        )
    }

    private fun childPath(parent: String, name: String): String =
        if (parent.isEmpty()) name else "$parent/$name"

    private fun documentsUri(documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    companion object {
        const val INTERNAL_DIR = ".gallery"

        private const val SCOPE = "Tree"

        /**
         * Sent for every file Rem creates.
         *
         * A provider maps a MIME type to an extension and appends it when the display name has
         * none, so this must be a type no provider recognises. `text/plain` would produce
         * `.nomedia.txt` and `index.json.txt`.
         */
        private const val OPAQUE_MIME = "application/x-rem-state"

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
