package dev.susnowy.gallery.library

import dev.susnowy.gallery.metadata.PortableMetadataStore
import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.GALLERY_FORMAT
import dev.susnowy.gallery.model.LibraryInspection
import dev.susnowy.gallery.model.PortableLibrary
import dev.susnowy.gallery.model.UnsupportedSchemaException
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
                library.schemaVersion < 1 -> LibraryInspection.Invalid("无效的 Schema 版本")
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
        val existingSchema = access.find(SCHEMA_FILE)
        if (existingSchema != null) {
            val declared = runCatching {
                json.decodeFromString<SchemaVersion>(access.openInput(existingSchema)
                    .bufferedReader(Charsets.UTF_8).use { it.readText() }).schemaVersion
            }.getOrNull()
            if (declared != null && declared > CURRENT_SCHEMA_VERSION) {
                throw UnsupportedSchemaException(
                    "目录中的 Schema v$declared 高于本客户端支持的版本，已拒绝写入",
                )
            }
        }
        val hasGuide = access.find(GUIDE_FILE) != null

        claimInitializationLock()
        REQUIRED_DIRECTORIES.forEach(access::ensureDirectory)
        val now = Instant.now().toString()
        val library = PortableLibrary(
            libraryId = UUID.randomUUID().toString(),
            name = sanitizeDisplayName(name),
            createdAt = now,
            updatedAt = now,
        )
        ensureMediaStoreIgnored()
        writeAtomically(SCHEMA_FILE, schemaDocument(), "application/json")
        // A guide that is already present is the user's copy of the Library rules. Adopting
        // the directory must not silently reset a document they may have edited.
        if (!hasGuide) writeAtomically(GUIDE_FILE, libraryGuide(library), "text/markdown")
        // library.json is the completion marker and must be committed last.
        writeAtomically(LIBRARY_JSON, json.encodeToString(library), "application/json")
        return library
    }

    /**
     * Takes the initialization lock by creating it and verifying the provider kept the
     * requested name. A provider that already had the file reports it back suffixed —
     * ` (1)` on most Android builds — which is how a lost race is detected, since SAF
     * offers no other compare-and-set.
     */
    private fun claimInitializationLock() {
        val created = try {
            access.createFile(INIT_LOCK_FILE, "application/octet-stream")
        } catch (error: Exception) {
            throw InitializationInProgressException(error)
        }
        val recorded = created.name.ifEmpty { access.find(INIT_LOCK_FILE)?.name.orEmpty() }
        if (recorded != INIT_LOCK_FILE.substringAfterLast('/')) {
            throw InitializationInProgressException()
        }
    }

    /**
     * Brings an older Library up to [CURRENT_SCHEMA_VERSION]. Portable metadata is
     * snapshotted under `.gallery/backups/` before anything is rewritten, and a
     * Library that declares a newer Schema is never touched.
     */
    fun migrateSchema(library: PortableLibrary): PortableLibrary {
        if (library.schemaVersion > CURRENT_SCHEMA_VERSION) {
            throw UnsupportedSchemaException(
                "Library Schema v${library.schemaVersion} 高于本客户端支持的版本，已拒绝写入",
            )
        }
        if (library.schemaVersion == CURRENT_SCHEMA_VERSION) return library

        val stamp = Instant.now().toString().replace(':', '-')
        VERSIONED_DOCUMENTS.forEach { path ->
            val name = path.substringAfterLast('/')
            writer.copyTo(path, ".gallery/backups/schema-v${library.schemaVersion}-$stamp-$name", "application/json")
        }
        writer.copyTo(
            GUIDE_FILE,
            ".gallery/backups/schema-v${library.schemaVersion}-$stamp-$GUIDE_FILE",
            "text/markdown",
        )
        val migrated = library.copy(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            updatedAt = Instant.now().toString(),
        )
        writer.write(SCHEMA_FILE, schemaDocument(), "application/json")
        writer.write(GUIDE_FILE, libraryGuide(migrated), "text/markdown")
        writer.write(LIBRARY_JSON, json.encodeToString(migrated), "application/json")
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
        const val SCHEMA_FILE = ".gallery/schema/v3.json"
        const val MEDIA_IGNORE_FILE = ".nomedia"

        /**
         * Claimed before the identity is written and deliberately left behind: it is the
         * evidence that this directory was initialized once, so a later `initialize`
         * fails on [libraryGuide] check rather than creating a second identity.
         */
        const val INIT_LOCK_FILE = ".gallery/init.lock"

        /**
         * Documents whose `schema_version` is bumped by [migrateSchema]; kept in
         * sync with [PortableMetadataStore]. Snapshotted before migration.
         */
        val VERSIONED_DOCUMENTS = listOf(
            LIBRARY_JSON,
            PortableMetadataStore.CATALOG_PATH,
            PortableMetadataStore.STATE_PATH,
        )

        val REQUIRED_DIRECTORIES = listOf(
            ".gallery",
            ".gallery/schema",
            ".gallery/items",
            ".gallery/series",
            ".gallery/collections",
            ".gallery/authors",
            ".gallery/state",
            ".gallery/imports",
            ".gallery/trash",
            ".gallery/transactions",
            ".gallery/backups",
        )

        fun sanitizeDisplayName(value: String): String =
            value.trim().replace(Regex("[\\r\\n\\t]+"), " ").take(120).ifBlank { "Rem Library" }

        fun libraryGuide(library: PortableLibrary): String = """
            # ${library.name}

            这是一个 Rem 便携媒体库。为兼容旧版本，其内部格式仍使用 `.gallery` 和 `Gallery` Schema 命名。Library 身份位于 `.gallery/library.json`，当前 Schema 版本为 ${library.schemaVersion}，规范位于 `.gallery/schema/v3.json`。

            根目录中的 `.nomedia` 用于阻止 Android 系统相册重复收录 Library 内的媒体副本；Rem 自己通过 SAF 扫描，不受影响。

            ## 目录职责

            - `.gallery/items/`：作品及其便携元数据。
            - `.gallery/authors/`、`.gallery/collections/`、`.gallery/series/`：独立分类实体。
            - `.gallery/state/`：阅读与观看进度。
            - `.gallery/trash/`：逻辑回收站记录。
            - `.gallery/transactions/`：文件整理事务；未完成事务不得随意删除。
            - `.gallery/backups/`：Schema 迁移和高风险操作前的元数据快照。

            ## 三类内容

            - 相册：`Photos/` 中的图片和视频，按拍摄时间浏览，不需要作者或标签。
            - 分类媒体：普通图片和视频，主要按真实文件夹浏览；推荐放在 `Images/`、`Videos/`。
            - 作品：漫画、写真集、动漫、电影和剧集；可使用作者、标签、系列与进度。图片目录或 ZIP/CBZ 默认属于作品；作品视频推荐放在 `Anime/`、`Movies/`、`Series/` 或 `Works/`。

            以上目录名是推荐约定而不是硬限制。条目在 `.gallery/items/catalog.json` 中的 `domain`（`album`、`classified`、`works`）是最终归属，Agent 可以按规则修正它，不需要为了改视图而移动媒体。

            常见“作者目录/NO.序号 作品名[页数-体积]/顺序图片”结构应保留原目录；Rem 会尝试从父目录和作品目录名识别作者、标题与顺序。视频优先使用 `S01E02` 等通用集数命名。

            下载器兼容规则：`JM/<纯数字 album_id>/` 识别为禁漫来源；EhViewer 的 `<gid>-<title>/` 需有 `.ehviewer` 标记；Pixiv 文件使用 `<illust_id>_p<page>`，动图转换常见 `<illust_id>_ugoira<尺寸>.webp/gif`。同一扁平目录出现多个 Pixiv 作品 ID 时保持为独立图片，不要合并成一本漫画。
            `source:jm` / `jm:album:<id>`、`source:ehviewer` / `eh:gid:<id>`、`source:pixiv` / `pixiv:id:<id>` 是稳定的来源 Tag，Agent 整理时应保留。账号、Cookie、Token 不得写入 Library。

            ## Agent 辅助识别与同步

            当前版本不由 App 抓取站点或自动联网同步。用户可以明确要求 Agent 根据上述来源 Tag、来源 ID、目录和当前站点信息，补全或校正作品的标题、作者、标签、Series 等元数据。
            Agent 写入前必须先读取当前条目及其 `field_sources`，逐字段合并，禁止用在线结果替换整条记录。任何来源为 `manual` 的字段都不得修改、清空、追加、翻译、规范化或去重。
            `tags` 是字段级保护：只要 `field_sources.tags` 为 `manual`，整组标签必须原样保留；未锁定时可以同步并去重，但必须保留 `source:*` 和来源 ID Tag。
            Agent 获取且已可靠匹配的字段应标记为 `provider:<来源>`；只有用户明确指定的值才标为 `manual`。低置信度、多个候选或来源不可访问时保持原值并请求用户确认，不得猜测。
            批量写入前先备份 `.gallery`；不得随意改写 `id`、相对路径、`revision`、时间戳或事务状态。完成后让 App 重新扫描，并报告未匹配、冲突与实际变更。账号、Cookie、Token 只能在用户授权的会话中临时使用，不能保存到 Library、日志或 Git。

            ## 修改规则

            所有媒体路径必须使用相对于 Library 根目录的路径，禁止写入 Android URI、Windows 盘符或绝对路径。
            可以修改内容归属、显示标题、作者、标签、Collection、Series、封面选择以及阅读状态；`id`、`revision`、时间戳和事务状态由程序维护。
            新增字段前必须先更新 Schema 说明，不要直接修改 Android 本机索引数据库。
            修改可编辑字段时，请同时把该字段写入条目的 `field_sources` 并标记为 `manual`；标记为 `manual` 的字段不会被自动识别或在线元数据覆盖。

            新增漫画或图集时，可把按自然文件名排序的图片目录或 ZIP/CBZ 放入 Library。重新接入或在 App 中执行扫描后，新内容会进入 Inbox。
            普通分类不会移动真实文件；只有在 App 中预览并确认 Organizer 计划后才会改变底层目录。

            不要随意删除 `.gallery`。删除它会丢失分类、进度、回收站和事务信息。
        """.trimIndent() + "\n"

        fun schemaDocument(): String = """
            {
              "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
              "title": "Gallery portable metadata schema v3",
              "schema_version": 3,
              "path_rule": "All media paths are slash-separated and relative to the Library root.",
              "migration": {
                "from_v1": "Additive. Catalog items gain field_sources; missing values mean the field is still automatic.",
                "from_v2": "Additive. Catalog items gain domain (album, classified, or works). Missing values are inferred from media type, path convention, and filename."
              },
              "documents": {
                ".gallery/library.json": {
                  "required": ["format", "schema_version", "library_id", "name", "created_at", "updated_at"],
                  "program_managed": ["format", "schema_version", "library_id", "created_at", "updated_at"],
                  "editable": ["name"]
                },
                ".gallery/items/catalog.json": {
                  "required": ["schema_version", "library_id", "revision", "updated_at", "items"],
                  "item_required": ["id", "relative_path", "type", "display_title", "source", "revision", "updated_at"],
                  "item_optional": ["domain"],
                  "item_editable": ["domain", "display_title", "original_title", "authors", "tags", "collections", "series", "cover_path", "favorite"],
                  "item_program_managed": ["id", "relative_path", "source", "content_hash", "revision", "updated_at"],
                  "item_field_sources": {
                    "type": "object",
                    "description": "Provenance per editable field. 'manual' means a human set it and automatic metadata must never overwrite it.",
                    "keys": ["domain", "display_title", "original_title", "authors", "tags", "collections", "series", "cover_path", "favorite"],
                    "values": ["manual", "import", "filename", "comic_info", "system_import", "provider:<id>"]
                  }
                },
                ".gallery/state/state.json": {
                  "required": ["schema_version", "library_id", "revision", "updated_at", "progress", "trash"],
                  "program_managed": ["revision", "updated_at"]
                },
                ".gallery/transactions/*.json": {
                  "description": "Recoverable physical file operation journals. Do not edit active transactions."
                }
              },
              "media_types": ["image", "image_set", "video", "photo", "photo_video", "live_photo"],
              "media_domains": ["album", "classified", "works"],
              "source_types": ["file", "directory", "archive", "system_import"]
            }
        """.trimIndent() + "\n"
    }
}
