package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.library.LibraryDocumentAccess
import dev.susnowy.gallery.library.PortableDocumentWriter
import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.InboxDisposition
import dev.susnowy.gallery.model.InboxTarget
import dev.susnowy.gallery.model.PortableInbox
import dev.susnowy.gallery.model.PortableInboxDecision
import dev.susnowy.gallery.model.UnsupportedSchemaException
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Portable Inbox decisions.
 *
 * Accepting a suggestion, ignoring a folder, or confirming that an unsupported file needs
 * no further work are user decisions. They therefore live in the Library
 * (`.gallery/state/inbox.json`) instead of only in the disposable device index — deleting
 * the database must not bring ignored content back to Inbox.
 *
 * `catalog.json` stays the authority for Work metadata; this document only records what
 * happened to a path in Inbox and which paths should stay out of the normal views.
 */
class PortableInboxStore(
    private val access: LibraryDocumentAccess,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    private val writer = PortableDocumentWriter(access)

    fun load(libraryId: String): PortableInbox = read()?.let { text ->
        runCatching { json.decodeFromString<PortableInbox>(text) }
            .getOrElse { throw SerializationException("inbox.json 无法解析", it) }
            .also { inbox ->
                require(inbox.libraryId == libraryId) { "Inbox 决策不属于当前 Library" }
                requireCurrentSchema(inbox.schemaVersion)
                validate(inbox)
            }
    } ?: PortableInbox(libraryId = libraryId, updatedAt = Instant.EPOCH.toString())

    /**
     * Adds or replaces decisions in one atomic write. Decisions are keyed by Work id when
     * one exists and by relative path otherwise, so re-deciding the same target updates
     * the existing record instead of appending a second one.
     */
    fun upsert(
        libraryId: String,
        decisions: List<PortableInboxDecision>,
        expectedRevision: Long? = null,
    ): PortableInbox {
        val current = load(libraryId)
        require(
            decisions.all {
                it.disposition != InboxDisposition.HANDLED || it.target == InboxTarget.DISCOVERY
            },
        ) { "只有待判断路径可以标记为已处理" }
        if (expectedRevision != null && current.revision != expectedRevision) {
            throw RevisionConflictException(
                "Inbox 决策已被其他设备修改（磁盘 ${current.revision}，本机 $expectedRevision）",
            )
        }
        val replaced = decisions.mapTo(mutableSetOf(), PortableInboxDecision::key)
        val merged = current.decisions.filterNot { it.key in replaced } + decisions
        return commit(libraryId, current, merged)
    }

    /** Removes decisions so the targets return to Inbox on the next scan. */
    fun remove(libraryId: String, keys: Set<String>): PortableInbox {
        if (keys.isEmpty()) return load(libraryId)
        val current = load(libraryId)
        val remaining = current.decisions.filterNot { it.key in keys }
        if (remaining.size == current.decisions.size) return current
        return commit(libraryId, current, remaining)
    }

    /** Drops the decisions of Works that were permanently removed from the catalog. */
    fun removeWorks(libraryId: String, workIds: Set<String>): PortableInbox {
        if (workIds.isEmpty()) return load(libraryId)
        val current = load(libraryId)
        val remaining = current.decisions.filterNot { it.workId != null && it.workId in workIds }
        if (remaining.size == current.decisions.size) return current
        return commit(libraryId, current, remaining)
    }

    /**
     * Keeps a decision attached to its Work after the underlying path changed, so an
     * Organizer move or rename cannot silently turn an ignored Work back into Inbox noise.
     */
    fun relocate(libraryId: String, workId: String, source: String, target: String) {
        val current = load(libraryId)
        var changed = false
        val updated = current.decisions.map { decision ->
            if (decision.workId != workId) return@map decision
            val relocated = decision.relativePath.replacePathPrefix(source, target)
            if (relocated == decision.relativePath) {
                decision
            } else {
                changed = true
                decision.copy(relativePath = relocated)
            }
        }
        if (!changed) return
        commit(libraryId, current, updated)
    }

    private fun commit(
        libraryId: String,
        current: PortableInbox,
        decisions: List<PortableInboxDecision>,
    ): PortableInbox {
        val updated = current.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            libraryId = libraryId,
            revision = current.revision + 1,
            updatedAt = Instant.now().toString(),
            decisions = decisions.sortedWith(
                compareBy<PortableInboxDecision> { it.relativePath }.thenBy { it.key },
            ),
        )
        validate(updated)
        writer.write(PATH, json.encodeToString(updated), "application/json")
        return updated
    }

    private fun validate(inbox: PortableInbox) {
        require(inbox.schemaVersion == CURRENT_SCHEMA_VERSION) { "Inbox 决策必须使用当前 Schema v4" }
        requireUnique("Inbox 决策目标", inbox.decisions.map(PortableInboxDecision::key))
        requireUnique("Inbox 决策路径", inbox.decisions.map(PortableInboxDecision::relativePath))
        inbox.decisions.forEach { decision ->
            requirePortablePath(decision.relativePath)
            decision.workId?.let { require(it.isNotBlank()) { "Inbox 决策的 Work ID 不能为空" } }
        }
    }

    private fun declaredSchema(text: String): Int? = runCatching {
        json.parseToJsonElement(text).jsonObject["schema_version"]?.jsonPrimitive?.intOrNull
    }.getOrNull()

    private fun requireCurrentSchema(schemaVersion: Int) {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaException(
                if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                    "Library 元数据 Schema v$schemaVersion 高于本客户端支持的版本，已拒绝写入"
                } else {
                    "Library 元数据仍是测试期 Schema v$schemaVersion，请先完成 Library 升级"
                },
            )
        }
    }

    private fun requirePortablePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path) {
            "媒体路径必须是 Library 内的相对路径：$path"
        }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "媒体路径包含无效片段：$path"
        }
    }

    private fun requireUnique(label: String, values: List<String>) {
        require(values.distinct().size == values.size) { "$label 必须唯一" }
    }

    private fun String.replacePathPrefix(source: String, target: String): String = when {
        this == source -> target
        startsWith("$source/") -> target + removePrefix(source)
        else -> this
    }

    private fun read(): String? = writer.read(PATH)

    /** Declared schema of the stored document, used only by diagnostics. */
    fun storedSchema(): Int? = read()?.let(::declaredSchema)

    companion object {
        const val PATH = ".gallery/state/inbox.json"
    }
}
