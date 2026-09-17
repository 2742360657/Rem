package dev.susnowy.gallery.library

import java.util.UUID

/**
 * The single atomic writer for `.gallery` documents.
 *
 * SAF providers may adjust a requested display name, and some publish a renamed
 * child slightly after the rename returns. A fixed `.name.tmp` staging file
 * therefore races with concurrent writers (a progress save can observe a file a
 * previous save just renamed or deleted), so every commit stages through a
 * private unique name. The previous revision moves to a stable recovery name:
 * if the process stops in the short interval before the staged revision reaches
 * its final name, the next read restores that backup instead of treating the
 * portable document as missing.
 *
 * A write only counts as committed once the staged document has actually moved onto the
 * requested path. Providers differ in what they do when a rename targets a name that
 * already exists: some replace it, while Android's own document providers publish a copy
 * under a ` (1)` qualified name and leave the staged document where it was. Accepting
 * that silently would leave two documents where the caller believes there is one — which
 * is how a repeated attach once produced a second `library.json` — so the duplicate is
 * removed, the previous revision is restored, and the write fails.
 */
class PortableDocumentWriter(private val access: LibraryDocumentAccess) {

    fun write(relativePath: String, value: String, mimeType: String) {
        val name = relativePath.substringAfterLast('/')
        val parent = relativePath.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) access.ensureDirectory(parent)
        val backupPath = recoveryPath(relativePath)
        val backupName = backupPath.substringAfterLast('/')
        val previous = recoverIfInterrupted(relativePath)
        // A live target plus a recovery file means the new revision reached its final
        // name and the process stopped before cleanup. The live target is authoritative.
        access.find(backupPath)?.let { stale ->
            check(previous != null && runCatching { access.delete(stale) }.getOrDefault(false)) {
                "无法清理 $relativePath 的旧恢复文件"
            }
        }
        val stagingId = UUID.randomUUID().toString()
        val temporaryPath = stagingPath(parent, ".$stagingId.$name.tmp")

        val temporary = access.createFile(temporaryPath, mimeType)
        access.openOutput(temporary).bufferedWriter(Charsets.UTF_8).use { it.write(value) }

        if (previous != null && !access.rename(previous, backupName)) {
            runCatching { access.find(temporaryPath)?.let(access::delete) }
            error("无法备份 $relativePath")
        }
        if (previous != null && (access.find(relativePath) != null || access.find(backupPath) == null)) {
            // A provider that copied or qualified the rename did not actually move the
            // live revision into the recovery slot. Keep the original and refuse to commit.
            runCatching { access.find(backupPath)?.let(access::delete) }
            runCatching { access.find(temporaryPath)?.let(access::delete) }
            error("无法备份 $relativePath：文件提供方没有提交到恢复位置")
        }

        val moved = runCatching { access.rename(temporary, name) }.getOrDefault(false)
        // A staged document still addressable by its staging path means the provider
        // published a copy under a qualified name rather than moving it onto the target.
        if (!moved || access.find(temporaryPath) != null) {
            discard(relativePath, backupPath, temporaryPath, name)
            error("无法提交 $relativePath：文件提供方改用了其他名称，已放弃写入以避免产生重复文件")
        }
        if (access.find(relativePath) == null) {
            discard(relativePath, backupPath, temporaryPath, name)
            error("无法提交 $relativePath：写入后目标文件不可见")
        }

        // Cleanup must not make a committed write look like a failure. If the process
        // stops here, the next write recognizes the live target and removes this stale
        // recovery file before starting another commit.
        runCatching { access.find(backupPath)?.let(access::delete) }
    }

    /**
     * Removes whatever the refused attempt left behind — the duplicate the provider
     * published under a qualified name and the staged document — and puts the previous
     * revision back under its own name, so the caller's path keeps holding exactly the
     * document it held before. A refused write is recoverable; two documents under one
     * path is not, because nothing could then tell which identity is current.
     */
    private fun discard(
        relativePath: String,
        backupPath: String,
        temporaryPath: String,
        name: String,
    ) {
        // Deletes the target first, which for a refused write is the duplicate.
        runCatching { access.find(relativePath)?.let(access::delete) }
        runCatching { access.find(temporaryPath)?.let(access::delete) }
        val backup = access.find(backupPath)
        if (backup != null && !runCatching { access.rename(backup, name) }.getOrDefault(false)) {
            error("$relativePath 写入失败，且原文件无法恢复")
        }
    }

    private fun stagingPath(parent: String, stagingName: String) =
        if (parent.isEmpty()) stagingName else "$parent/$stagingName"

    fun read(relativePath: String): String? {
        val document = recoverIfInterrupted(relativePath) ?: return null
        return access.openInput(document).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun recoverIfInterrupted(relativePath: String): LibraryDocument? {
        access.find(relativePath)?.let { return it }
        val backupPath = recoveryPath(relativePath)
        val backup = access.find(backupPath) ?: return null
        val name = relativePath.substringAfterLast('/')
        check(runCatching { access.rename(backup, name) }.getOrDefault(false)) {
            "$relativePath 上次写入中断，且旧版本无法恢复"
        }
        return access.find(relativePath)
            ?: error("$relativePath 上次写入中断，恢复后目标文件不可见")
    }

    private fun recoveryPath(relativePath: String): String {
        val parent = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        return stagingPath(parent, ".$name.rem-backup")
    }

    /** Copies a document into `targetPath`, used for pre-migration snapshots. */
    fun copyTo(sourcePath: String, targetPath: String, mimeType: String): Boolean {
        val text = read(sourcePath) ?: return false
        write(targetPath, text, mimeType)
        return true
    }
}
