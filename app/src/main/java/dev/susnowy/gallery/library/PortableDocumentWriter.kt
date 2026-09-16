package dev.susnowy.gallery.library

import java.util.UUID

/**
 * The single atomic writer for `.gallery` documents.
 *
 * SAF providers may adjust a requested display name, and some publish a renamed
 * child slightly after the rename returns. A fixed `.name.tmp` staging file
 * therefore races with concurrent writers (a progress save can observe a file a
 * previous save just renamed or deleted), so every commit stages through a
 * private unique name, keeps the previous revision as a private backup until the
 * new revision is committed, and never deletes the live document before the
 * replacement exists.
 */
class PortableDocumentWriter(private val access: LibraryDocumentAccess) {

    fun write(relativePath: String, value: String, mimeType: String) {
        val name = relativePath.substringAfterLast('/')
        val parent = relativePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) access.ensureDirectory(parent)
        val stagingId = UUID.randomUUID().toString()
        val temporaryName = ".$stagingId.$name.tmp"
        val backupName = ".$stagingId.$name.bak"
        val temporaryPath = if (parent.isEmpty()) temporaryName else "$parent/$temporaryName"
        val backupPath = if (parent.isEmpty()) backupName else "$parent/$backupName"

        val temporary = access.createFile(temporaryPath, mimeType)
        access.openOutput(temporary).bufferedWriter(Charsets.UTF_8).use { it.write(value) }

        val current = access.find(relativePath)
        if (current != null && !access.rename(current, backupName)) {
            access.delete(temporary)
            error("无法备份 $relativePath")
        }
        if (!access.rename(temporary, name)) {
            access.find(backupPath)?.let { access.rename(it, name) }
            runCatching { access.find(temporaryPath)?.let(access::delete) }
            error("无法替换 $relativePath")
        }
        // Cleanup must not make a committed write look like a failure: a provider
        // can briefly expose the backup before it becomes addressable by path.
        runCatching { access.find(backupPath)?.let(access::delete) }
    }

    fun read(relativePath: String): String? {
        val document = access.find(relativePath) ?: return null
        return access.openInput(document).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /** Copies a document into `targetPath`, used for pre-migration snapshots. */
    fun copyTo(sourcePath: String, targetPath: String, mimeType: String): Boolean {
        val text = read(sourcePath) ?: return false
        write(targetPath, text, mimeType)
        return true
    }
}
