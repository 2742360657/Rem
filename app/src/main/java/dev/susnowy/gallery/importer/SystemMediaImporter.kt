package dev.susnowy.gallery.importer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dev.susnowy.gallery.storage.DocumentTreeStorage
import dev.susnowy.gallery.scanner.MediaClassifier
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

@Serializable
data class MediaImportEntry(
    @SerialName("source_name") val sourceName: String,
    @SerialName("source_kind") val sourceKind: String = "android-system-picker",
    @SerialName("source_path") val sourcePath: String? = null,
    @SerialName("target_path") val targetPath: String,
    @SerialName("captured_at") val capturedAt: String,
    val size: Long,
)

@Serializable
data class MediaImportManifest(
    @SerialName("operation_id") val operationId: String,
    @SerialName("created_at") val createdAt: String,
    val entries: List<MediaImportEntry>,
    val skipped: List<String>,
)

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val warnings: List<String>,
    val importedPaths: List<String> = emptyList(),
)

enum class WorkImportKind(val directory: String, val mimePrefix: String) {
    IMAGE("Images", "image/"),
    VIDEO("Videos", "video/"),
}

class SystemMediaImporter(private val context: Context) {
    private val resolver = context.contentResolver

    suspend fun import(uris: List<Uri>, storage: DocumentTreeStorage): ImportResult =
        withContext(Dispatchers.IO) {
            val operationId = UUID.randomUUID().toString()
            val imported = mutableListOf<MediaImportEntry>()
            val skipped = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            uris.distinct().forEach { uri ->
                coroutineContext.ensureActive()
                runCatching {
                    val source = querySource(uri)
                    require(source.mimeType.startsWith("image/") || source.mimeType.startsWith("video/")) {
                        "不是受支持的图片或视频"
                    }
                    val capturedAt = extractCapturedAt(uri, source.mimeType, source.modifiedAt)
                    val parent = targetParent(source.relativePath, capturedAt)
                    storage.ensureDirectory(parent)
                    val target = uniqueTarget(storage, parent, source.displayName, source.size)
                    if (target == null) {
                        skipped += source.displayName
                        return@runCatching
                    }
                    val document = storage.createFile(target, source.mimeType)
                    resolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "无法读取源媒体" }
                        storage.openOutput(document).use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                    }
                    val actual = storage.entry(target) ?: error("复制后找不到目标文件")
                    if (source.size > 0) check(actual.size == source.size) { "复制后的文件大小不一致" }
                    imported += MediaImportEntry(
                        sourceName = source.displayName,
                        sourceKind = source.sourceKind,
                        sourcePath = source.relativePath.ifBlank { null },
                        targetPath = target,
                        capturedAt = Instant.ofEpochMilli(capturedAt).toString(),
                        size = actual.size,
                    )
                }.onFailure { error ->
                    warnings += "${uri.lastPathSegment.orEmpty()}：${error.message.orEmpty()}"
                }
            }
            val manifest = MediaImportManifest(
                operationId = operationId,
                createdAt = Instant.now().toString(),
                entries = imported,
                skipped = skipped,
            )
            val manifestPath = ".gallery/imports/$operationId.json"
            val document = storage.createFile(manifestPath, "application/json")
            storage.openOutput(document).bufferedWriter(Charsets.UTF_8).use {
                it.write(JSON.encodeToString(manifest))
            }
            ImportResult(imported.size, skipped.size, warnings, imported.map(MediaImportEntry::targetPath))
        }

    /**
     * Copies system media into the ordinary image/video namespaces. The source
     * directory is preserved below the namespace and is later presented as the
     * default virtual classification. Photos remain a separate mixed timeline.
     */
    suspend fun importWorks(
        uris: List<Uri>,
        kind: WorkImportKind,
        storage: DocumentTreeStorage,
    ): ImportResult = withContext(Dispatchers.IO) {
        require(uris.isNotEmpty()) { "没有选择媒体" }
        val operationId = UUID.randomUUID().toString()
        val imported = mutableListOf<MediaImportEntry>()
        val skipped = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        uris.distinct().forEach { uri ->
            coroutineContext.ensureActive()
            runCatching {
                val source = querySource(uri)
                require(source.mimeType.startsWith(kind.mimePrefix)) {
                    if (kind == WorkImportKind.IMAGE) "图片分类只能导入图片" else "视频分类只能导入视频"
                }
                val capturedAt = extractCapturedAt(uri, source.mimeType, source.modifiedAt)
                val parent = targetWorkParent(kind.directory, source.relativePath, capturedAt)
                storage.ensureDirectory(parent)
                val target = uniqueTarget(storage, parent, source.displayName, source.size)
                if (target == null) {
                    skipped += source.displayName
                    return@runCatching
                }
                val document = storage.createFile(target, source.mimeType)
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取源媒体" }
                    storage.openOutput(document).use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
                val actual = storage.entry(target) ?: error("复制后找不到目标文件")
                if (source.size > 0) check(actual.size == source.size) { "复制后的文件大小不一致" }
                imported += MediaImportEntry(
                    sourceName = source.displayName,
                    sourceKind = source.sourceKind,
                    sourcePath = source.relativePath.ifBlank { null },
                    targetPath = target,
                    capturedAt = Instant.ofEpochMilli(capturedAt).toString(),
                    size = actual.size,
                )
            }.onFailure { error ->
                warnings += "${uri.lastPathSegment.orEmpty()}：${error.message.orEmpty()}"
            }
        }
        writeManifest(
            storage,
            MediaImportManifest(
                operationId = operationId,
                createdAt = Instant.now().toString(),
                entries = imported,
                skipped = skipped,
            ),
        )
        ImportResult(imported.size, skipped.size, warnings, imported.map(MediaImportEntry::targetPath))
    }

    suspend fun importImageSet(
        uris: List<Uri>,
        title: String,
        storage: DocumentTreeStorage,
    ): ImportResult = withContext(Dispatchers.IO) {
        require(uris.distinct().size >= 2) { "漫画/图集至少需要两张图片" }
        val sources = uris.distinct().map { uri -> uri to querySource(uri) }
        require(sources.all { (_, source) -> source.mimeType.startsWith("image/") }) {
            "漫画/图集只能包含图片"
        }
        val operationId = UUID.randomUUID().toString()
        val directory = uniqueDirectory(storage, "ImageSets/Imported", title.safeFolderName())
        storage.ensureDirectory(directory)
        val imported = mutableListOf<MediaImportEntry>()
        val warnings = mutableListOf<String>()
        sources.sortedWith { left, right ->
            MediaClassifier.naturalCompare(left.second.displayName, right.second.displayName)
        }.forEach { (uri, source) ->
            coroutineContext.ensureActive()
            runCatching {
                val target = uniqueTarget(storage, directory, source.displayName, -1)
                    ?: error("目标文件已经存在：${source.displayName}")
                val document = storage.createFile(target, source.mimeType)
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "无法读取源图片" }
                    storage.openOutput(document).use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
                val actual = storage.entry(target) ?: error("复制后找不到目标图片")
                if (source.size > 0) check(actual.size == source.size) { "复制后的文件大小不一致" }
                val capturedAt = extractCapturedAt(uri, source.mimeType, source.modifiedAt)
                imported += MediaImportEntry(
                    sourceName = source.displayName,
                    sourceKind = source.sourceKind,
                    sourcePath = source.relativePath.ifBlank { null },
                    targetPath = target,
                    capturedAt = Instant.ofEpochMilli(capturedAt).toString(),
                    size = actual.size,
                )
            }.onFailure { error ->
                warnings += "${source.displayName}：${error.message.orEmpty()}"
            }
        }
        check(imported.size >= 2) { "成功复制的图片不足两张，未形成漫画/图集" }
        writeManifest(
            storage,
            MediaImportManifest(
                operationId = operationId,
                createdAt = Instant.now().toString(),
                entries = imported,
                skipped = emptyList(),
            ),
        )
        ImportResult(imported.size, 0, warnings, imported.map(MediaImportEntry::targetPath))
    }

    private fun querySource(uri: Uri): SourceInfo {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "media"
        var size = -1L
        var modified = System.currentTimeMillis()
        var relativePath = ""
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                    name = cursor.getString(it) ?: name
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let {
                    size = cursor.getLong(it)
                }
                cursor.getColumnIndex("last_modified").takeIf { it >= 0 && !cursor.isNull(it) }?.let {
                    modified = cursor.getLong(it)
                }
                relativePath = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                        cursor.stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH).orEmpty()
                    else -> cursor.stringOrNull(MediaStore.MediaColumns.DATA)
                        ?.let(::legacyRelativeParent)
                        .orEmpty()
                }
            }
        }
        val mime = resolver.getType(uri) ?: mimeFromName(name)
        return SourceInfo(
            displayName = name.safeFileName(),
            mimeType = mime,
            size = size,
            modifiedAt = modified,
            relativePath = relativePath.normalizeSourcePath(),
            sourceKind = if (uri.authority == MediaStore.AUTHORITY) {
                "android-mediastore"
            } else {
                "android-system-picker"
            },
        )
    }

    private fun extractCapturedAt(uri: Uri, mimeType: String, fallback: Long): Long = when {
        mimeType.startsWith("image/") -> runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                date?.let { EXIF_DATE.parse(it, java.time.LocalDateTime::from) }
                    ?.atZone(ZoneId.systemDefault())
                    ?.toInstant()
                    ?.toEpochMilli()
            }
        }.getOrNull() ?: fallback
        mimeType.startsWith("video/") -> runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    ?.let(::parseVideoDate)
            } finally {
                retriever.release()
            }
        }.getOrNull() ?: fallback
        else -> fallback
    }

    private fun parseVideoDate(value: String): Long? {
        val normalized = value.replace(Regex("\\.\\d+"), "")
        return runCatching { Instant.from(VIDEO_DATE.parse(normalized)).toEpochMilli() }.getOrNull()
    }

    private fun uniqueTarget(
        storage: DocumentTreeStorage,
        parent: String,
        fileName: String,
        expectedSize: Long,
    ): String? {
        val direct = "$parent/$fileName"
        storage.entry(direct)?.let { existing ->
            if (!existing.isDirectory && expectedSize > 0 && existing.size == expectedSize) return null
        } ?: return direct
        val base = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { ".$it" }.orEmpty()
        for (index in 1..9999) {
            val candidate = "$parent/$base ($index)$extension"
            if (storage.entry(candidate) == null) return candidate
        }
        error("同名文件过多：$fileName")
    }

    private fun String.safeFileName(): String = trim()
        .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
        .trim(' ', '.')
        .take(180)
        .ifBlank { "media" }

    private fun String.safeFolderName(): String = safeFileName().take(120)

    private fun String.normalizeSourcePath(): String = replace('\\', '/')
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() && it != "." && it != ".." }
        .joinToString("/")

    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun legacyRelativeParent(absolutePath: String): String {
        @Suppress("DEPRECATION")
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        return File(absolutePath).parent.orEmpty().removePrefix(storageRoot)
    }

    private fun uniqueDirectory(storage: DocumentTreeStorage, parent: String, name: String): String {
        storage.ensureDirectory(parent)
        val direct = "$parent/$name"
        if (storage.entry(direct) == null) return direct
        for (index in 1..9999) {
            val candidate = "$parent/$name ($index)"
            if (storage.entry(candidate) == null) return candidate
        }
        error("同名漫画/图集过多：$name")
    }

    private fun writeManifest(storage: DocumentTreeStorage, manifest: MediaImportManifest) {
        val manifestPath = ".gallery/imports/${manifest.operationId}.json"
        val document = storage.createFile(manifestPath, "application/json")
        storage.openOutput(document).bufferedWriter(Charsets.UTF_8).use {
            it.write(JSON.encodeToString(manifest))
        }
    }

    private fun mimeFromName(name: String): String = when (
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"
        "png" -> "image/png"
        "apng" -> "image/apng"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
        "avif" -> "image/avif"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "wbmp" -> "image/vnd.wap.wbmp"
        "dng" -> "image/x-adobe-dng"
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    private data class SourceInfo(
        val displayName: String,
        val mimeType: String,
        val size: Long,
        val modifiedAt: Long,
        val relativePath: String,
        val sourceKind: String,
    )

    companion object {
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }
        private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
        private val VIDEO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX", Locale.ROOT)

        internal fun targetParent(sourcePath: String, capturedAt: Long): String {
            val portableSource = sourcePath
                .replace('\\', '/')
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/") { segment ->
                    segment.trim()
                        .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
                        .trim(' ', '.')
                        .take(120)
                        .ifBlank { "Unnamed" }
                }
            if (portableSource.isNotBlank()) return "Photos/$portableSource"
            val date = Instant.ofEpochMilli(capturedAt).atZone(ZoneId.systemDefault())
            return "Photos/Unsorted/${date.year}/${date.monthValue.toString().padStart(2, '0')}"
        }

        internal fun targetWorkParent(root: String, sourcePath: String, capturedAt: Long): String {
            require(root == "Images" || root == "Videos") { "不受支持的作品根目录" }
            val portableSource = sourcePath
                .replace('\\', '/')
                .trim('/')
                .split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/") { segment ->
                    segment.trim()
                        .replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]"), "_")
                        .trim(' ', '.')
                        .take(120)
                        .ifBlank { "Unnamed" }
                }
            if (portableSource.isNotBlank()) return "$root/$portableSource"
            val date = Instant.ofEpochMilli(capturedAt).atZone(ZoneId.systemDefault())
            return "$root/未分类/${date.year}/${date.monthValue.toString().padStart(2, '0')}"
        }
    }
}
