package dev.susnowy.gallery.library

import dev.susnowy.gallery.model.CURRENT_SCHEMA_VERSION
import dev.susnowy.gallery.model.GALLERY_FORMAT
import dev.susnowy.gallery.model.LibraryInspection
import dev.susnowy.gallery.model.PortableLibrary
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PortableLibraryManager(
    private val access: LibraryDocumentAccess,
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
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

    fun initialize(name: String): PortableLibrary {
        check(access.find(LIBRARY_JSON) == null) { "Library 已经初始化" }
        REQUIRED_DIRECTORIES.forEach(access::ensureDirectory)
        val now = Instant.now().toString()
        val library = PortableLibrary(
            libraryId = UUID.randomUUID().toString(),
            name = sanitizeDisplayName(name),
            createdAt = now,
            updatedAt = now,
        )
        writeAtomically(LIBRARY_JSON, json.encodeToString(library), "application/json")
        writeAtomically(GUIDE_FILE, libraryGuide(library), "text/markdown")
        return library
    }

    private fun writeAtomically(relativePath: String, value: String, mimeType: String) {
        val fileName = relativePath.substringAfterLast('/')
        val parent = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotEmpty()) access.ensureDirectory(parent)
        val temporaryPath = if (parent.isEmpty()) ".$fileName.tmp" else "$parent/.$fileName.tmp"
        access.find(temporaryPath)?.let(access::delete)
        val temporary = access.createFile(temporaryPath, mimeType)
        access.openOutput(temporary).bufferedWriter(Charsets.UTF_8).use { it.write(value) }
        access.find(relativePath)?.let(access::delete)
        check(access.rename(temporary, fileName)) { "无法完成 $relativePath 的原子替换" }
    }

    companion object {
        const val GUIDE_FILE = "GALLERY_LIBRARY.md"
        const val LIBRARY_JSON = ".gallery/library.json"

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
            value.trim().replace(Regex("[\\r\\n\\t]+"), " ").take(120).ifBlank { "Gallery Library" }

        fun libraryGuide(library: PortableLibrary): String = """
            # ${library.name}

            这是一个 Gallery 便携媒体库。Library 身份位于 `.gallery/library.json`，当前 Schema 版本为 ${library.schemaVersion}。

            ## 目录职责

            - `.gallery/items/`：作品及其便携元数据。
            - `.gallery/authors/`、`.gallery/collections/`、`.gallery/series/`：独立分类实体。
            - `.gallery/state/`：阅读与观看进度。
            - `.gallery/trash/`：逻辑回收站记录。
            - `.gallery/transactions/`：文件整理事务；未完成事务不得随意删除。
            - `.gallery/backups/`：Schema 迁移和高风险操作前的元数据快照。

            ## 修改规则

            所有媒体路径必须使用相对于 Library 根目录的路径，禁止写入 Android URI、Windows 盘符或绝对路径。
            可以修改显示标题、作者、标签、Collection、Series、封面选择以及阅读状态；`id`、`revision`、时间戳和事务状态由程序维护。
            新增字段前必须先更新 Schema 说明，不要直接修改 Android 本机索引数据库。

            新增漫画或图集时，可把按自然文件名排序的图片目录或 ZIP/CBZ 放入 Library。重新接入或在 App 中执行扫描后，新内容会进入 Inbox。
            普通分类不会移动真实文件；只有在 App 中预览并确认 Organizer 计划后才会改变底层目录。

            不要随意删除 `.gallery`。删除它会丢失分类、进度、回收站和事务信息。
        """.trimIndent() + "\n"
    }
}
