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
        return try {
            val text = writer.read(LIBRARY_JSON) ?: return LibraryInspection.Missing
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
        check(writer.read(LIBRARY_JSON) == null) { "Library 已经初始化" }
        val existingSchema = writer.read(SCHEMA_FILE) ?: writer.read(LEGACY_SCHEMA_FILE)
        val declaredSchema = existingSchema?.let { text ->
            try {
                json.decodeFromString<SchemaVersion>(text).schemaVersion
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
        val hasGuide = writer.read(GUIDE_FILE) != null

        val initializationLock = claimInitializationLock()
        try {
            // The identity may have appeared after the first inspection but before this
            // caller won the lock. Report a race so the repository re-reads the winner.
            if (writer.read(LIBRARY_JSON) != null) throw InitializationInProgressException()
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
        val text = writer.read(path) ?: return null
        return try {
            json.decodeFromString<PortableDocumentHeader>(text)
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

        fun libraryGuide(library: PortableLibrary): String =
            "# ${sanitizeDisplayName(library.name)}\n\n" +
                "这是一个 Rem 便携媒体库。Schema v${library.schemaVersion}；Library ID: ${library.libraryId}。\n\n" +
                dev.susnowy.gallery.portable.LibraryAgentInstructions.text()

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
