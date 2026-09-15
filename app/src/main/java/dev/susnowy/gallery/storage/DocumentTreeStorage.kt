package dev.susnowy.gallery.storage

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.library.LibraryDocumentAccess
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

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

class DocumentTreeStorage(
    private val context: Context,
    val treeUri: Uri,
) : LibraryDocumentAccess {
    private val resolver = context.contentResolver
    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalArgumentException("无效的目录 URI")

    val isAvailable: Boolean
        get() = root.exists() && root.canRead()

    override fun find(relativePath: String): LibraryDocument? =
        resolve(relativePath)?.toLibraryDocument(relativePath.normalizePath())

    override fun ensureDirectory(relativePath: String): LibraryDocument {
        val normalized = relativePath.normalizePath()
        var current = root
        var walked = ""
        normalized.pathParts().forEach { segment ->
            walked = if (walked.isEmpty()) segment else "$walked/$segment"
            val existing = current.findFile(segment)
            current = when {
                existing == null -> current.createDirectory(segment)
                    ?: throw FileNotFoundException("无法创建目录 $walked")
                existing.isDirectory -> existing
                else -> throw IllegalStateException("$walked 已存在且不是目录")
            }
        }
        return current.toLibraryDocument(normalized)
    }

    override fun createFile(relativePath: String, mimeType: String): LibraryDocument {
        val normalized = relativePath.normalizePath()
        val name = normalized.substringAfterLast('/')
        val parentPath = normalized.substringBeforeLast('/', "")
        val parent = if (parentPath.isBlank()) root else resolve(parentPath)
            ?: ensureDirectory(parentPath).let { resolve(it.key) }
            ?: throw FileNotFoundException("无法创建父目录 $parentPath")
        parent.findFile(name)?.let { existing ->
            if (existing.isFile) return existing.toLibraryDocument(normalized)
            throw IllegalStateException("$normalized 已存在且不是文件")
        }
        return (parent.createFile(mimeType, name)
            ?: throw FileNotFoundException("无法创建文件 $normalized"))
            .toLibraryDocument(normalized)
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
            val renamed = DocumentsContract.renameDocument(resolver, sourceUri, displayName) != null
            if (!renamed) {
                Log.e(TAG, "Provider returned no URI while renaming ${document.key} to $displayName")
            }
            renamed
        } catch (error: Exception) {
            Log.e(TAG, "Provider failed to rename ${document.key} to $displayName", error)
            throw error
        }
    }

    override fun delete(document: LibraryDocument): Boolean {
        val documentUri = document.resolveUri()
        return try {
            DocumentsContract.deleteDocument(resolver, documentUri)
        } catch (error: Exception) {
            Log.e(TAG, "Provider failed to delete ${document.key}", error)
            throw error
        }
    }

    fun list(relativePath: String = ""): List<StorageEntry> {
        val normalized = relativePath.normalizePath()
        val directory = resolve(normalized) ?: return emptyList()
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().mapNotNull { child ->
            val name = child.name ?: return@mapNotNull null
            val path = if (normalized.isEmpty()) name else "$normalized/$name"
            child.toStorageEntry(path)
        }
    }

    fun entry(relativePath: String): StorageEntry? {
        val normalized = relativePath.normalizePath()
        return resolve(normalized)?.toStorageEntry(normalized)
    }

    fun contentUri(relativePath: String): Uri? = resolve(relativePath.normalizePath())?.uri

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

    private fun resolve(relativePath: String): DocumentFile? {
        var current = root
        for (segment in relativePath.pathParts()) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    private fun resolveRequired(relativePath: String): DocumentFile =
        resolve(relativePath.normalizePath()) ?: throw FileNotFoundException(relativePath)

    private fun LibraryDocument.resolveUri(): Uri =
        locator?.let(Uri::parse) ?: resolveRequired(key).uri

    private fun DocumentFile.toLibraryDocument(path: String) = LibraryDocument(
        key = path,
        name = name.orEmpty(),
        isDirectory = isDirectory,
        locator = uri.toString(),
    )

    private fun DocumentFile.toStorageEntry(path: String) = StorageEntry(
        relativePath = path,
        uri = uri.toString(),
        name = name.orEmpty(),
        mimeType = type,
        isDirectory = isDirectory,
        size = if (isFile) length() else 0,
        lastModified = lastModified(),
    )

    private companion object {
        const val TAG = "GalleryStorage"
    }
}

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
