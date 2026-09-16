package dev.susnowy.gallery.storage

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.library.LibraryDocumentAccess
import dev.susnowy.gallery.logging.RemLog
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class StorageEntry(
    val relativePath: String,
    val uri: String,
    val name: String,
    val mimeType: String?,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

data class TreeStats(val fileCount: Int, val totalBytes: Long)

/**
 * SAF access to one Library tree.
 *
 * Every `DocumentsContract` query is a Binder round-trip into the provider, and on a
 * removable volume that provider is a USB device, so round-trip *count* — not bytes
 * — dominates scan time. `DocumentFile` cannot help here: `listFiles()` returns URI
 * stubs carrying no metadata, so `name`, `type`, `isDirectory`, `length()` and
 * `lastModified()` are five separate queries per child, and `findFile()` re-lists the
 * whole parent directory and probes every child by name.
 *
 * This class therefore lists directories with a single projection query that returns
 * every column at once, remembers path-to-document resolution so a path is never
 * walked from the root twice, and hands back documents carrying their provider
 * locator so later reads and writes never re-resolve them.
 *
 * The cache is advisory: a change made outside this instance can make a listing
 * stale, so [invalidate] is exposed and every mutation performed here updates or
 * drops the affected entries. Anything that must observe external changes should
 * build a fresh instance, which starts with an empty cache.
 */
class DocumentTreeStorage(
    private val context: Context,
    val treeUri: Uri,
) : LibraryDocumentAccess {
    private val resolver = context.contentResolver
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("无效的目录 URI")

    private val lock = ReentrantLock()
    private val cache = SafDocumentCache()
    private var rootDocumentId: String? = null

    val isAvailable: Boolean
        get() = root.exists() && root.canRead()

    override fun find(relativePath: String): LibraryDocument? {
        val normalized = relativePath.normalizePath()
        val node = resolveNode(normalized) ?: return null
        return node.toLibraryDocument(normalized)
    }

    override fun ensureDirectory(relativePath: String): LibraryDocument {
        val normalized = relativePath.normalizePath()
        lock.withLock {
            cache.path(normalized)?.let { return it.toLibraryDocument(normalized) }
            var current = rootNode() ?: throw FileNotFoundException("Library 根目录不可用")
            var walked = ""
            for (segment in normalized.pathParts()) {
                walked = if (walked.isEmpty()) segment else "$walked/$segment"
                val existing = resolveChildLocked(walked, segment)
                current = when {
                    existing != null -> {
                        if (!existing.isDirectory) {
                            throw IllegalStateException("$walked 已存在且不是目录")
                        }
                        existing
                    }
                    // The directory may exist on disk while this instance has never
                    // listed its parent; refresh the parent once before creating.
                    else -> {
                        val discovered = lookupChildLocked(walked, segment)
                        when {
                            discovered != null && discovered.isDirectory -> discovered
                            discovered != null ->
                                throw IllegalStateException("$walked 已存在且不是目录")
                            else -> createDocumentChild(
                                parent = current,
                                name = segment,
                                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                            ) ?: throw FileNotFoundException("无法创建目录 $walked")
                        }
                    }
                }
            }
            return current.toLibraryDocument(normalized)
        }
    }

    override fun createFile(relativePath: String, mimeType: String): LibraryDocument {
        return createFile(relativePath, mimeType, requireAbsent = false)
    }

    override fun createFileExclusive(relativePath: String, mimeType: String): LibraryDocument {
        return createFile(relativePath, mimeType, requireAbsent = true)
    }

    private fun createFile(
        relativePath: String,
        mimeType: String,
        requireAbsent: Boolean,
    ): LibraryDocument {
        val normalized = relativePath.normalizePath()
        val name = normalized.substringAfterLast('/')
        val parentPath = normalized.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) {
            resolveNode("") ?: throw FileNotFoundException("Library 根目录不可用")
        } else {
            val existing = resolveNode(parentPath)
            if (existing != null) {
                existing
            } else {
                ensureDirectory(parentPath)
                resolveNode(parentPath) ?: throw FileNotFoundException("无法创建父目录 $parentPath")
            }
        }
        if (!requireAbsent) {
            val existing = lookupChild(parentPath, name)
            if (existing != null) {
                if (existing.isFile) return existing.toLibraryDocument(normalized)
                throw IllegalStateException("$normalized 已存在且不是文件")
            }
        }
        val created = createDocumentChild(parent, name, mimeType)
            ?: throw FileNotFoundException("无法创建文件 $normalized")
        return created.toLibraryDocument(normalized)
    }

    /**
     * Creates one child and returns the provider's own view of it, so a provider that
     * adjusted the requested display name is recorded under the name it actually used.
     */
    private fun createDocumentChild(
        parent: StorageNode,
        name: String,
        mimeType: String,
    ): StorageNode? {
        val createdUri = runCatching {
            DocumentsContract.createDocument(
                resolver,
                Uri.parse(parent.uri),
                mimeType,
                name,
            )
        }.getOrNull() ?: return null
        val documentId = DocumentsContract.getDocumentId(createdUri)
        val created = queryNode(createdUri, documentId) { actualName ->
            parent.relativePath.appendPath(actualName)
        }
            ?: StorageNode(
                relativePath = parent.relativePath.appendPath(name),
                name = name,
                documentId = documentId,
                uri = createdUri.toString(),
                isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
            )
        lock.withLock {
            cache.put(created)
            cache.putChild(parent.documentId, created)
        }
        return created
    }

    override fun openInput(document: LibraryDocument): InputStream =
        resolver.openInputStream(document.resolveUri())
            ?: throw FileNotFoundException(document.key)

    override fun openOutput(document: LibraryDocument, truncate: Boolean): OutputStream =
        resolver.openOutputStream(document.resolveUri(), if (truncate) "rwt" else "wa")
            ?: throw FileNotFoundException(document.key)

    override fun rename(document: LibraryDocument, displayName: String): Boolean {
        val sourceUri = document.resolveUri()
        return try {
            val relocated = DocumentsContract.renameDocument(resolver, sourceUri, displayName)
            if (relocated == null) {
                RemLog.error(TAG, "文件提供方未返回 URI：重命名 ${document.key} → $displayName")
                false
            } else {
                forgetSubtrees(document.key)
                true
            }
        } catch (error: Exception) {
            RemLog.failure(TAG, "重命名失败：${document.key} → $displayName", error)
            throw error
        }
    }

    override fun delete(document: LibraryDocument): Boolean {
        val documentUri = document.resolveUri()
        return try {
            DocumentsContract.deleteDocument(resolver, documentUri).also { deleted ->
                if (deleted) forgetSubtrees(document.key)
            }
        } catch (error: Exception) {
            RemLog.failure(TAG, "删除失败：${document.key}", error)
            throw error
        }
    }

    /**
     * Drops what a mutation invalidated: the affected path, its recorded descendants,
     * and the listings of every directory on the path. The rest of the cache is kept,
     * so a bulk operation does not pay for a full re-walk after each step.
     */
    private fun forgetSubtrees(relativePath: String) {
        val normalized = relativePath.normalizePath()
        lock.withLock {
            val parentPath = normalized.substringBeforeLast('/', "")
            val parentDocumentId = cache.path(parentPath)?.documentId
            cache.forgetSubtree(normalized)
            cache.forgetChildren(parentDocumentId)
        }
    }

    /**
     * Lists one directory with a single provider query. The projection asks for every
     * column at once, which replaces the five per-child queries `DocumentFile` needs to
     * answer `name`, `type`, `isDirectory`, `length()` and `lastModified()` separately.
     */
    fun list(relativePath: String = ""): List<StorageEntry> {
        val normalized = relativePath.normalizePath()
        val node = resolveNode(normalized) ?: return emptyList()
        if (!node.isDirectory) return emptyList()
        return childrenOf(node).values
            .map(StorageNode::toStorageEntry)
            .sortedWith { left, right -> left.name.compareTo(right.name, ignoreCase = true) }
    }

    fun entry(relativePath: String): StorageEntry? {
        val normalized = relativePath.normalizePath()
        val node = resolveNode(normalized) ?: return null
        return node.toStorageEntry()
    }

    fun contentUri(relativePath: String): Uri? =
        resolveNode(relativePath.normalizePath())?.uri?.let(Uri::parse)

    fun openFileDescriptor(relativePath: String): ParcelFileDescriptor? =
        contentUri(relativePath)?.let { resolver.openFileDescriptor(it, "r") }

    fun copyFile(source: StorageEntry, targetPath: String): StorageEntry {
        require(!source.isDirectory) { "目录复制需要使用 copyDirectory" }
        val sourceDocument = find(source.relativePath) ?: throw FileNotFoundException(source.relativePath)
        val targetDocument = createFile(targetPath, source.mimeType ?: "application/octet-stream")
        openInput(sourceDocument).use { input ->
            openOutput(targetDocument).use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }
        return entry(targetPath) ?: throw FileNotFoundException(targetPath)
    }

    fun copyDirectory(sourcePath: String, targetPath: String) {
        ensureDirectory(targetPath)
        list(sourcePath).forEach { child ->
            val targetChild = "$targetPath/${child.name}"
            if (child.isDirectory) copyDirectory(child.relativePath, targetChild)
            else copyFile(child, targetChild)
        }
    }

    fun treeStats(path: String): TreeStats {
        val rootEntry = entry(path) ?: return TreeStats(0, 0)
        if (!rootEntry.isDirectory) return TreeStats(1, rootEntry.size)
        return list(path).fold(TreeStats(0, 0)) { total, child ->
            val childStats = if (child.isDirectory) treeStats(child.relativePath)
            else TreeStats(1, child.size)
            TreeStats(total.fileCount + childStats.fileCount, total.totalBytes + childStats.totalBytes)
        }
    }

    /** Drops every remembered listing and path resolution. */
    fun invalidate() {
        lock.withLock {
            cache.clear()
            rootDocumentId = null
        }
    }

    // --- resolution -----------------------------------------------------------------

    private fun resolveNode(relativePath: String): StorageNode? {
        val normalized = relativePath.normalizePath()
        lock.withLock {
            cache.path(normalized)?.let { return it }
            var current = rootNode() ?: return null
            if (normalized.isEmpty()) return current
            var walked = ""
            for (segment in normalized.pathParts()) {
                walked = if (walked.isEmpty()) segment else "$walked/$segment"
                current = resolveChildLocked(walked, segment) ?: return null
            }
            return current
        }
    }

    /** Reads the cache, and on a miss re-lists the parent once before answering. */
    private fun lookupChild(parentPath: String, name: String): StorageNode? =
        lock.withLock { lookupChildLocked(parentPath, name) }

    private fun lookupChildLocked(parentPath: String, name: String): StorageNode? {
        cache.child(parentPath, name)?.let { return it }
        val parent = cache.path(parentPath) ?: return null
        if (!parent.isDirectory) return null
        childrenOfLocked(parent)
        return cache.child(parentPath, name)
    }

    private fun resolveChildLocked(parentPath: String, name: String): StorageNode? {
        cache.child(parentPath, name)?.let { return it }
        val parent = cache.path(parentPath.substringBeforeLast('/', "")) ?: return null
        if (!parent.isDirectory) return null
        childrenOfLocked(parent)
        return cache.child(parentPath, name)
    }

    private fun rootNode(): StorageNode? {
        cache.path("")?.let { return it }
        val documentId = rootDocumentId ?: DocumentsContract.getTreeDocumentId(treeUri)
            .also { rootDocumentId = it }
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val node = queryNode(uri, documentId) { "" } ?: return null
        cache.put(node)
        return node
    }

    /**
     * Lists a directory at most once per instance. The caller holds [lock], so a
     * concurrent miss cannot issue the same query twice.
     */
    private fun childrenOf(node: StorageNode): Map<String, StorageNode> =
        lock.withLock { childrenOfLocked(node) }

    private fun childrenOfLocked(directory: StorageNode): Map<String, StorageNode> {
        cache.children(directory.documentId)?.let { return it }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            directory.documentId,
        )
        val listed = mutableListOf<StorageNode>()
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null)
            ?: throw FileNotFoundException(
                "文件提供方未返回目录 ${directory.relativePath.ifEmpty { "Library 根目录" }}",
            )
        cursor.use {
            while (it.moveToNext()) {
                val documentId = it.string(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    ?: continue
                val name = it.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    ?: continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                listed += it.toNode(directory.relativePath.appendPath(name), name, documentId, uri)
            }
        }
        val byName = listed.associateBy(StorageNode::name)
        cache.putChildren(directory.documentId, byName)
        listed.forEach(cache::put)
        return byName
    }

    private fun queryNode(
        uri: Uri,
        documentId: String,
        relativePath: (actualName: String) -> String,
    ): StorageNode? =
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val name = cursor.string(DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: ""
            cursor.toNode(relativePath(name), name, documentId, uri)
        }

    private fun Cursor.toNode(
        relativePath: String,
        name: String,
        documentId: String,
        uri: Uri,
    ): StorageNode {
        val mimeType = string(DocumentsContract.Document.COLUMN_MIME_TYPE)
        val isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        return StorageNode(
            relativePath = relativePath,
            name = name,
            documentId = documentId,
            uri = uri.toString(),
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else long(DocumentsContract.Document.COLUMN_SIZE),
            lastModified = long(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            mimeType = mimeType,
        )
    }

    private fun Cursor.string(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

    private fun Cursor.long(column: String): Long =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0L

    private fun LibraryDocument.resolveUri(): Uri =
        locator?.let(Uri::parse)
            ?: resolveNode(key)?.uri?.let(Uri::parse)
            ?: throw FileNotFoundException(key)

    private companion object {
        const val TAG = "GalleryStorage"

        /**
         * One projection for every column [list] and [entry] must answer. Asking for the
         * whole row is what turns a directory listing into a single round-trip.
         */
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

private fun String.appendPath(name: String) = if (isEmpty()) name else "$this/$name"

fun String.normalizeRelativePath(): String = normalizePath()

private fun String.normalizePath(): String {
    val normalized = replace('\\', '/').trim('/')
    val parts = normalized.pathParts()
    require(parts.none { it == "." || it == ".." || it.contains(':') }) {
        "路径必须是 Library 根目录下的安全相对路径"
    }
    return parts.joinToString("/")
}

private fun String.pathParts(): List<String> =
    split('/').filter { it.isNotBlank() }
