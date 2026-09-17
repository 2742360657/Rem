package dev.susnowy.gallery.library

import dev.susnowy.gallery.metadata.PortableMetadataStore
import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.GALLERY_FORMAT
import dev.susnowy.gallery.model.LibraryInspection
import dev.susnowy.gallery.model.PortableLibrary
import dev.susnowy.gallery.model.UnsupportedSchemaException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Another caller is currently creating this Library's identity. Callers should re-inspect
 * the directory shortly after: the winner publishes `library.json` when it finishes.
 */
class InitializationInProgressException(cause: Throwable? = null) : IllegalStateException(
    "该目录正在被另一个 Rem 实例初始化，请稍后重试",
    cause,
)

/** Reads only the declared version out of an existing `schema/v*.json` document. */
@Serializable
private data class SchemaVersion(
    @SerialName("schema_version") val schemaVersion: Int = 0,
)

@Serializable
private data class PortableDocumentHeader(
    @SerialName("schema_version") val schemaVersion: Int = 0,
    @SerialName("library_id") val libraryId: String,
)

class PortableLibraryManager(
    private val access: LibraryDocumentAccess,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    private val writer = PortableDocumentWriter(access)

    fun inspect(): LibraryInspection {
        val document = access.find(LIBRARY_JSON) ?: return LibraryInspection.Missing
        return try {
            val text = access.openInput(document).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val library = json.decodeFromString<PortableLibrary>(text)
            when {
                library.format != GALLERY_FORMAT -> LibraryInspection.Invalid("未知的 Library 格式")
                library.schemaVersion > CURRENT_SCHEMA_VERSION ->
                    LibraryInspection.Unsupported(library.schemaVersion)
                library.schemaVersion < LEGACY_SCHEMA_VERSION -> LibraryInspection.Invalid(
                    "Schema v${library.schemaVersion} 是已停止支持的测试格式",
                )
                runCatching { UUID.fromString(library.libraryId) }.isFailure ->
                    LibraryInspection.Invalid("library_id 不是有效 UUID")
                else -> LibraryInspection.Valid(library)
            }
        } catch (error: SerializationException) {
            LibraryInspection.Invalid("library.json 无法解析：${error.message.orEmpty()}")
        } catch (error: Exception) {
            LibraryInspection.Invalid("无法读取 Library：${error.message.orEmpty()}")
        }
    }

    /**
     * Creates a Library identity, writing it exactly once.
     *
     * [LIBRARY_JSON] is committed last because it is the completion marker, which leaves
     * a window where a second caller — a repeated folder-selection tap, a second device —
     * could see "not initialized yet" and create a competing identity. The window is
     * closed by claiming [INIT_LOCK_FILE] first: creation is atomic at the provider, so
     * exactly one caller wins and the others are told to retry.
     *
     * A directory that already carries a compatible [SCHEMA_FILE] or [GUIDE_FILE] is
     * *adopted* rather than rejected: those files are Rem's own, and their presence means
     * the identity was lost — a deleted `.gallery/library.json`, an interrupted first
     * attach, a hand-copied folder — not that the user has to clear them out by hand.
     * Only a document this client could not write safely is refused.
     */
    fun initialize(name: String): PortableLibrary {
        check(access.find(LIBRARY_JSON) == null) { "Library 已经初始化" }
        val existingSchema = access.find(SCHEMA_FILE) ?: access.find(LEGACY_SCHEMA_FILE)
        val declaredSchema = existingSchema?.let { document ->
            try {
                json.decodeFromString<SchemaVersion>(access.openInput(document)
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }).schemaVersion
            } catch (error: Exception) {
                throw IllegalStateException("残留 Schema 文档无法解析，已拒绝自动认领", error)
            }
        }
        if (declaredSchema != null && declaredSchema > CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaException(
                "目录中的 Schema v$declaredSchema 高于本客户端支持的版本，已拒绝写入",
            )
        }
        if (declaredSchema != null && declaredSchema < 1) {
            throw IllegalStateException("残留 Schema 文档没有有效的 schema_version")
        }
        val recoveredHeaders = listOf(PortableMetadataStore.CATALOG_PATH, PortableMetadataStore.STATE_PATH)
            .mapNotNull(::readPortableHeader)
        require(recoveredHeaders.all { it.schemaVersion > 0 }) {
            "残留便携文档没有有效的 schema_version"
        }
        val recoveredIds = recoveredHeaders.map(PortableDocumentHeader::libraryId).distinct()
        require(recoveredIds.size <= 1) { "残留便携文档的 library_id 不一致，已拒绝自动认领" }
        val recoveredId = recoveredIds.singleOrNull()?.also { value ->
            require(runCatching { UUID.fromString(value) }.isSuccess) { "残留便携文档的 library_id 无效" }
        }
        val recoveredVersions = recoveredHeaders.map(PortableDocumentHeader::schemaVersion)
            .filter { it > 0 }
        val highestVersion = (recoveredVersions + listOfNotNull(declaredSchema)).maxOrNull()
        if (highestVersion != null && highestVersion > CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaException(
                "残留便携文档 Schema v$highestVersion 高于本客户端支持的版本，已拒绝写入",
            )
        }
        val unsupportedOldVersion = (recoveredVersions + listOfNotNull(declaredSchema))
            .filter { it in 1 until LEGACY_SCHEMA_VERSION }
            .minOrNull()
        if (unsupportedOldVersion != null) {
            throw UnsupportedSchemaException(
                "Schema v$unsupportedOldVersion 是已停止支持的测试格式；请从备份恢复或使用对应旧版导出",
            )
        }
        val needsLegacyConversion = recoveredId != null &&
            (recoveredVersions + listOfNotNull(declaredSchema)).any { it == LEGACY_SCHEMA_VERSION }
        val hasGuide = access.find(GUIDE_FILE) != null

        val initializationLock = claimInitializationLock()
        try {
            // The identity may have appeared after the first inspection but before this
            // caller won the lock. Report a race so the repository re-reads the winner.
            if (access.find(LIBRARY_JSON) != null) throw InitializationInProgressException()
            REQUIRED_DIRECTORIES.forEach(access::ensureDirectory)
            val now = Instant.now().toString()
            val library = PortableLibrary(
                schemaVersion = if (needsLegacyConversion) LEGACY_SCHEMA_VERSION else CURRENT_SCHEMA_VERSION,
                libraryId = recoveredId ?: UUID.randomUUID().toString(),
                name = sanitizeDisplayName(name),
                createdAt = now,
                updatedAt = now,
            )
            ensureMediaStoreIgnored()
            if (needsLegacyConversion) {
                return migrateSchema(library)
            }
            writeAtomically(SCHEMA_FILE, schemaDocument(), "application/json")
            // A guide that is already present is the user's copy of the Library rules. Adopting
            // the directory must not silently reset a document they may have edited.
            if (!hasGuide) writeAtomically(GUIDE_FILE, libraryGuide(library), "text/markdown")
            // library.json is the completion marker and must be committed last.
            writeAtomically(LIBRARY_JSON, json.encodeToString(library), "application/json")
            access.find(LEGACY_SCHEMA_FILE)?.let { runCatching { access.delete(it) } }
            return library
        } finally {
            // This is an in-progress claim, not permanent Library metadata. Releasing it
            // allows a directory whose identity is later lost to be adopted again.
            runCatching { access.delete(initializationLock) }
        }
    }

    /**
     * Takes the initialization lock by creating it and verifying the provider kept the
     * requested name. A provider that already had the file reports it back suffixed —
     * ` (1)` on most Android builds — which is how a lost race is detected, since SAF
     * offers no other compare-and-set.
     */
    private fun claimInitializationLock(allowStaleRecovery: Boolean = true): LibraryDocument {
        val created = try {
            access.createFileExclusive(INIT_LOCK_FILE, "application/octet-stream")
        } catch (error: Exception) {
            throw InitializationInProgressException(error)
        }
        val recorded = created.name.ifEmpty { access.find(INIT_LOCK_FILE)?.name.orEmpty() }
        if (recorded != INIT_LOCK_FILE.substringAfterLast('/')) {
            runCatching { access.delete(created) }
            val existing = access.find(INIT_LOCK_FILE)
            if (allowStaleRecovery && existing != null && initializationLockExpired(existing)) {
                if (runCatching { access.delete(existing) }.getOrDefault(false)) {
                    return claimInitializationLock(allowStaleRecovery = false)
                }
            }
            throw InitializationInProgressException()
        }
        return try {
            access.openOutput(created).bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(Instant.now().toString())
            }
            created
        } catch (error: Exception) {
            runCatching { access.delete(created) }
            throw InitializationInProgressException(error)
        }
    }

    private fun initializationLockExpired(document: LibraryDocument): Boolean = runCatching {
        val claimedAt = access.openInput(document).bufferedReader(Charsets.UTF_8).use { reader ->
            Instant.parse(reader.readText().trim())
        }
        Duration.between(claimedAt, Instant.now()) > INITIALIZATION_LOCK_LEASE
    }.getOrDefault(false)

    private fun readPortableHeader(path: String): PortableDocumentHeader? {
        val document = access.find(path) ?: return null
        return try {
            json.decodeFromString<PortableDocumentHeader>(
                access.openInput(document).bufferedReader(Charsets.UTF_8).use { it.readText() },
            )
        } catch (error: Exception) {
            throw IllegalStateException("残留便携文档无法解析，已拒绝自动认领：$path", error)
        }
    }

    /**
     * Converts the last test format (v3) to the normalized v4 model. Every existing
     * portable document is snapshotted before conversion, and `library.json` remains
     * v3 until the catalog, state, schema, and guide have all committed successfully.
     */
    fun migrateSchema(library: PortableLibrary): PortableLibrary {
        if (library.schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaException(
                "Library Schema v${library.schemaVersion} 高于本客户端支持的版本，已拒绝写入",
            )
        }
        if (library.schemaVersion == CURRENT_SCHEMA_VERSION) return library
        if (library.schemaVersion != LEGACY_SCHEMA_VERSION) {
            throw UnsupportedSchemaException(
                "当前测试版只支持把 Schema v$LEGACY_SCHEMA_VERSION 转换为 v$CURRENT_SCHEMA_VERSION",
            )
        }

        val stamp = Instant.now().toString().replace(':', '-')
        versionedDocuments(library.schemaVersion).forEach { path ->
            val name = path.substringAfterLast('/')
            writer.copyTo(path, ".gallery/backups/schema-v${library.schemaVersion}-$stamp-$name", "application/json")
        }
        writer.copyTo(
            GUIDE_FILE,
            ".gallery/backups/schema-v${library.schemaVersion}-$stamp-$GUIDE_FILE",
            "text/markdown",
        )
        PortableMetadataStore(access).migrateV3ToV4(library.libraryId)
        val migrated = library.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            updatedAt = Instant.now().toString(),
        )
        writer.write(SCHEMA_FILE, schemaDocument(), "application/json")
        writer.write(GUIDE_FILE, libraryGuide(migrated), "text/markdown")
        writer.write(LIBRARY_JSON, json.encodeToString(migrated), "application/json")
        access.find(LEGACY_SCHEMA_FILE)?.let { runCatching { access.delete(it) } }
        return migrated
    }

    fun ensureMediaStoreIgnored() {
        if (access.find(MEDIA_IGNORE_FILE) != null) return
        val created = access.createFile(MEDIA_IGNORE_FILE, "application/octet-stream")
        access.openOutput(created).use { /* Empty marker file. */ }
        if (access.find(MEDIA_IGNORE_FILE) == null) {
            check(access.rename(created, MEDIA_IGNORE_FILE)) {
                "无法创建 $MEDIA_IGNORE_FILE；Library 媒体可能会被系统相册重复收录"
            }
        }
    }

    private fun writeAtomically(relativePath: String, value: String, mimeType: String) =
        writer.write(relativePath, value, mimeType)

    companion object {
        const val GUIDE_FILE = "GALLERY_LIBRARY.md"
        const val LIBRARY_JSON = ".gallery/library.json"
        const val SCHEMA_FILE = ".gallery/schema/v4.json"
        const val LEGACY_SCHEMA_FILE = ".gallery/schema/v3.json"
        private const val LEGACY_SCHEMA_VERSION = 3
        const val MEDIA_IGNORE_FILE = ".nomedia"

        /**
         * Root-level so two callers also serialize creation of the `.gallery` directory.
         * It is removed after the identity is committed; it is not portable metadata.
         */
        const val INIT_LOCK_FILE = ".rem-library-initializing.lock"
        private val INITIALIZATION_LOCK_LEASE: Duration = Duration.ofMinutes(15)

        /**
         * Documents whose `schema_version` is bumped by [migrateSchema]; kept in
         * sync with [PortableMetadataStore]. Snapshotted before migration.
         */
        private fun versionedDocuments(version: Int) = listOf(
            LIBRARY_JSON,
            PortableMetadataStore.CATALOG_PATH,
            PortableMetadataStore.STATE_PATH,
            ".gallery/schema/v$version.json",
        )

        val REQUIRED_DIRECTORIES = listOf(
            ".gallery",
            ".gallery/schema",
            ".gallery/items",
            ".gallery/state",
            ".gallery/imports",
            ".gallery/transactions",
            ".gallery/backups",
        )

        fun sanitizeDisplayName(value: String): String =
            value.trim().replace(Regex("[\\r\\n\\t]+"), " ").take(120).ifBlank { "Rem Library" }

        fun libraryGuide(library: PortableLibrary): String = """
            # ${library.name}

            这是一个 Rem 便携媒体库。媒体原文件属于用户；`.gallery/` 只保存可以随盘移动的身份、逻辑关系、人工元数据、进度和安全事务。当前格式为 Schema v${library.schemaVersion}，规范位于 `.gallery/schema/v4.json`。

            根目录中的 `.nomedia` 用于阻止 Android 系统相册重复收录 Library 内的媒体副本；Rem 自己通过 SAF 扫描，不受影响。

            ## Agent 开始前必须读取

            1. 本文件；
            2. `.gallery/library.json` 和 `.gallery/schema/v4.json`；
            3. `.gallery/items/catalog.json` 中即将修改的实体及其 `field_sources`；
            4. `.gallery/state/inbox.json` 中已经存在的 Inbox 决定，避免重复处理或复活被忽略内容；
            5. `.gallery/transactions/` 中是否存在未完成事务；`.gallery/imports/` 是程序维护的导入/派生来源清单。

            不要直接修改 Android 本机数据库。它只是可重建索引，不是便携真相。

            ## Schema v4 的实体

            - `assets`：物理文件、目录或压缩包，只保存 Library 相对路径和来源技术信息。
            - `works`：用户看到和编辑的逻辑作品；标题、作者、标签、归属和收藏状态在这里。
            - `editions`：某个 Work 的一个取得版本，按角色引用一个或多个 Asset。多版本不得靠覆盖路径表达。
            - `groups`：为了“一起浏览”建立的 Work 集合，可用于写真集、图片/视频混合组或人工集合；它不是 Series。
            - `series`：有先后关系的 Work 序列，可使用手动顺序、季/集或卷/章；成员允许不编号。
            - `.gallery/state/state.json`：以 `work_id` 保存进度和逻辑回收站状态。
            - `.gallery/state/inbox.json`：以 Library 相对路径（有 Work 时同时记录 `work_id`）保存 Inbox 决定：`accepted`（接受建议）、`classified`（人工归类）、`ignored`（不再出现在普通视图）、`handled`（待判断路径已由用户处理）。`target` 说明决定针对媒体还是待判断路径。

            每个关系只在一个方向保存：Edition 指向 Work/Asset，Group 和 Series 指向 Work。不要再在 Work 内复制成员列表。

            ## 三个内容区域

            - 相册：`Photos/` 中的图片和视频，按拍摄时间浏览，不需要作者或标签。
            - 图片 / 视频：普通图片、视频、写真集及图片视频混合组，主要按目录或 Group 浏览。
            - 漫画 / 阅读：独立漫画和 Series，使用连续阅读与进度。

            `domain`（`album`、`classified`、`works`）是最终归属；目录名只是识别证据。修改归属、Group 或 Series 不需要移动媒体。

            ## 识别和来源

            `JM/`、纯数字目录和相似标题都不是权威。只有结构、sidecar 或稳定来源 ID 足够明确时才提出建议；不确定内容留在 Inbox，由用户或本地 Agent 处理。
            常见来源包括 `JM/<album_id>`、带 `.ehviewer` 标记的 EhViewer GID 目录，以及 Pixiv `<illust_id>_p<page>` 文件。
            `source:jm` / `jm:album:<id>`、`source:ehviewer` / `eh:gid:<id>`、`source:pixiv` / `pixiv:id:<id>` 是稳定的来源 Tag，Agent 整理时应保留。账号、Cookie、Token 不得写入 Library。

            ## Agent 写入规则

            - 先备份将修改的 `.gallery` 文档，再逐字段合并；不得整条覆盖。
            - 任何来源为 `manual` 的字段都不得修改、清空、追加、翻译、规范化或去重。
            `tags` 是字段级保护：只要 `field_sources.tags` 为 `manual`，整组标签必须原样保留；未锁定时可以同步并去重，但必须保留 `source:*` 和来源 ID Tag。
            - 可靠的外部结果标记为 `provider:<来源>`；只有用户明确指定的值才标为 `manual`。多个候选或低置信度时保持原值并请求确认。
            - 路径必须使用 `/` 分隔的 Library 相对路径，禁止 Android URI、盘符、绝对路径和 `..`。
            - 不得擅自修改 ID、`revision`、时间戳、哈希、事务或活动回收站记录。
            - `.gallery/state/inbox.json` 是用户的决定：不要删除已有决定，不要让被 `ignored` 的路径重新出现在建议里。Agent 自己写入时必须把 `by` 标为 `agent:<标识>`，且 `handled` 只能用于 `target` 为 `discovery` 的路径；`classified` 的权威归属仍在 Work 的 `domain`。
            - Group 只表达一起浏览；Series 只表达顺序；Edition 表达同一 Work 的不同来源版本。不要用同作者或相似标题自动建立永久关系。
            Group 成员的 `sort_index` 就是用户看到的顺序；不要擅自重排、增删用户建立的分组或改它的封面，删除分组只允许删除关系。
            Series 的 `sort_index` 是阅读顺序，季/集/卷/章是用户编号；不要擅自重排、清空编号或改写 `field_sources.series` 为 `manual` 的归属。
            - 自动整理不得移动、改名、合并或删除媒体。物理操作必须由用户确认计划，并写入可恢复事务。
            - 完成后让 Rem 重扫，并报告实际修改、未匹配项目和冲突。

            不要删除 `.gallery`、`.nomedia` 或活动事务。账号、Cookie、Token 只能在用户授权会话中临时使用，不能保存到 Library、日志或 Git。
        """.trimIndent() + "\n"

        fun schemaDocument(): String = """
            {
              "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "Rem portable metadata schema v4",
              "schema_version": 4,
              "path_rule": "All media paths are slash-separated and relative to the Library root.",
              "relationship_rule": "Edition references Work and Asset; Group and Series reference Work. Relationships are not duplicated in Work.",
              "documents": {
                ".gallery/library.json": {
                  "required": ["format", "schema_version", "library_id", "name", "created_at", "updated_at"],
                  "program_managed": ["format", "schema_version", "library_id", "created_at", "updated_at"],
                  "editable": ["name"]
                },
                ".gallery/items/catalog.json": {
                  "required": ["schema_version", "library_id", "revision", "updated_at", "assets", "works", "editions", "groups", "series"],
                  "assets_required": ["id", "relative_path", "media_type", "source", "revision", "updated_at"],
                  "works_required": ["id", "type", "domain", "display_title", "revision", "updated_at"],
                  "editions_required": ["id", "work_id", "assets", "revision", "updated_at"],
                  "groups_required": ["id", "title", "type", "ordered", "members", "revision", "updated_at"],
                  "series_required": ["id", "title", "members", "revision", "updated_at"],
                  "editable_work_fields": ["domain", "display_title", "original_title", "authors", "tags", "collections", "preferred_edition_id", "cover_path", "favorite"],
                  "field_source_values": ["manual", "import", "filename", "comic_info", "system_import", "provider:<id>"]
                },
                ".gallery/state/state.json": {
                  "required": ["schema_version", "library_id", "revision", "updated_at", "progress", "trash"],
                  "reference": "work_id",
                  "program_managed": ["revision", "updated_at"]
                },
                ".gallery/state/inbox.json": {
                  "required": ["schema_version", "library_id", "revision", "updated_at", "decisions"],
                  "decision_required": ["relative_path", "target", "disposition", "by", "decided_at"],
                  "reference": "relative_path, work_id when the target is a Work",
                  "dispositions": ["accepted", "classified", "ignored", "handled"],
                  "targets": ["media", "discovery"],
                  "program_managed": ["revision", "updated_at"],
                  "note": "handled is only valid for discovery targets; ignored paths must stay hidden from normal views."
                },
                ".gallery/transactions/*.json": {
                  "description": "Recoverable physical file operation journals. Do not edit active transactions."
                },
                ".gallery/imports/*.json": {
                  "description": "Program-managed import and derivation manifests. Do not edit."
                }
              },
              "media_types": ["image", "image_set", "video", "photo", "photo_video", "live_photo"],
              "media_domains": ["album", "classified", "works"],
              "source_types": ["file", "directory", "archive", "system_import"],
              "edition_asset_roles": ["primary", "page", "image", "video", "bonus", "cover", "alternate"],
              "group_types": ["media_set", "manual_collection"],
              "group_member_roles": ["item", "image", "video", "bonus", "cover"]
            }
        """.trimIndent() + "\n"
    }
}
