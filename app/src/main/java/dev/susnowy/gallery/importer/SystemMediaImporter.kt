package dev.susnowy.gallery.importer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
    @SerialName("source_uri") val sourceUri: String,
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
)

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
                    val date = Instant.ofEpochMilli(capturedAt).atZone(ZoneId.systemDefault())
                    val parent = "Photos/${date.year}/${date.monthValue.toString().padStart(2, '0')}"
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
                        sourceUri = uri.toString(),
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
            ImportResult(imported.size, skipped.size, warnings)
        }

    private fun querySource(uri: Uri): SourceInfo {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "media"
        var size = -1L
        var modified = System.currentTimeMillis()
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
            }
        }
        val mime = resolver.getType(uri) ?: mimeFromName(name)
        return SourceInfo(name.safeFileName(), mime, size, modified)
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
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    ?.let(::parseVideoDate)
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

    private fun mimeFromName(name: String): String = when (
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    ) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heif"
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
    )

    companion object {
        private val JSON = Json { prettyPrint = true; encodeDefaults = true }
        private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
        private val VIDEO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX", Locale.ROOT)
    }
}
