package dev.susnowy.gallery.library

import dev.susnowy.gallery.model.MediaItem
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DeletionSource(val path: String, val bytes: Long, val modifiedAt: Long, val directory: Boolean)

@Serializable
private data class DeletionRecord(
    val version: Int = 1,
    val libraryId: String,
    val workId: String,
    val deletedAt: Long?,
    val sources: List<DeletionSource>,
    val removed: Set<String> = emptySet(),
    val complete: Boolean = false,
)

/** Forward-only recovery: explicit retry finishes the confirmed deletion, never restores bytes. */
class PermanentDeletion(private val access: LibraryDocumentAccess) {
    private val writer = PortableDocumentWriter(access)
    private val json = Json { encodeDefaults = true }

    private fun path(item: MediaItem): String {
        val key = MessageDigest.getInstance("SHA-256")
            .digest("${item.libraryId}:${item.id}:${item.deletedAt}".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return ".gallery/transactions/purge-$key.json"
    }

    fun hasStarted(item: MediaItem): Boolean = writer.read(path(item)) != null

    fun mediaRemoved(item: MediaItem): Boolean = writer.read(path(item))?.let {
        val record = json.decodeFromString<DeletionRecord>(it)
        validate(item, record)
        record.sources.all { source -> source.path in record.removed }
    } ?: false

    fun execute(
        item: MediaItem,
        inspect: (String) -> DeletionSource?,
        delete: (String) -> Unit,
        finish: () -> Unit,
    ) {
        val journalPath = path(item)
        val previous = writer.read(journalPath)
        val paths = listOfNotNull(item.secondaryPath, item.relativePath).distinct()
        var record = previous?.let { json.decodeFromString<DeletionRecord>(it) } ?: DeletionRecord(
            libraryId = item.libraryId, workId = item.id, deletedAt = item.deletedAt,
            sources = paths.map { inspect(it) ?: error("来源已不存在，请先重新扫描：$it") },
        )
        if (previous == null) {
            check(item.size <= 0 || record.sources.sumOf { it.bytes } == item.size) {
                "媒体内容大小已变化，为避免误删已停止操作"
            }
            val primary = record.sources.first { it.path == item.relativePath }
            check(primary.directory || item.modifiedAt <= 0 || primary.modifiedAt <= 0 ||
                primary.modifiedAt == item.modifiedAt) {
                "媒体修改时间已变化，为避免误删已停止操作"
            }
        }
        validate(item, record)
        if (record.complete) return
        // Validate every remaining source before deleting the first one. A partially deleted
        // directory whose size changed is deliberately refused; its remaining bytes need review.
        record.sources.filterNot { it.path in record.removed }.forEach { expected ->
            val current = inspect(expected.path)
            check(current == expected || (previous != null && current == null)) {
                "来源已变化，已停止删除：${expected.path}"
            }
        }
        fun commit() = writer.write(journalPath, json.encodeToString(record), "application/json")
        commit()
        record.sources.filterNot { it.path in record.removed }.forEach { source ->
            val current = inspect(source.path)
            if (current != null) {
                check(current == source) { "来源已变化，已停止删除：${source.path}" }
                delete(source.path)
            }
            record = record.copy(removed = record.removed + source.path)
            commit()
        }
        finish()
        record = record.copy(complete = true)
        commit()
    }

    private fun validate(item: MediaItem, record: DeletionRecord) {
        check(record.version == 1) { "不支持此删除事务版本，已停止" }
        val paths = listOfNotNull(item.secondaryPath, item.relativePath).distinct()
        check(record.libraryId == item.libraryId && record.workId == item.id &&
            record.deletedAt == item.deletedAt && record.sources.map { it.path } == paths &&
            record.removed.all { it in paths }
        ) { "删除事务与当前项目不符，已停止" }
    }
}
