package dev.susnowy.gallery.importer

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

enum class SystemMediaAccess {
    NONE,
    PARTIAL,
    FULL,
}

enum class SystemMediaType {
    IMAGE,
    VIDEO,
}

data class SystemMediaEntry(
    val uri: String,
    val displayName: String,
    val mimeType: String,
    val mediaType: SystemMediaType,
    val size: Long,
    val capturedAt: Long,
    val sourcePath: String,
    val bucketName: String,
)

/** Read-only view over the media that Android currently allows Gallery to see. */
class SystemMediaCatalog(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun access(): SystemMediaAccess = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (
            granted(Manifest.permission.READ_MEDIA_IMAGES) ||
                granted(Manifest.permission.READ_MEDIA_VIDEO)
            ) -> SystemMediaAccess.FULL

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> SystemMediaAccess.PARTIAL

        Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2 &&
            granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> SystemMediaAccess.FULL

        else -> SystemMediaAccess.NONE
    }

    fun query(): List<SystemMediaEntry> {
        if (access() == SystemMediaAccess.NONE) return emptyList()
        return buildList {
            if (canReadImages()) addAll(queryCollection(imageCollection(), SystemMediaType.IMAGE))
            if (canReadVideos()) addAll(queryCollection(videoCollection(), SystemMediaType.VIDEO))
        }.sortedByDescending(SystemMediaEntry::capturedAt)
    }

    private fun queryCollection(collection: Uri, mediaType: SystemMediaType): List<SystemMediaEntry> {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(DATE_TAKEN)
            add(BUCKET_DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()
        val output = mutableListOf<SystemMediaEntry>()
        resolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.SIZE} > 0",
            null,
            "$DATE_TAKEN DESC, ${MediaStore.MediaColumns.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val relativePath = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                        cursor.stringOrNull(MediaStore.MediaColumns.RELATIVE_PATH).normalizeSourcePath()

                    else -> cursor.stringOrNull(MediaStore.MediaColumns.DATA)
                        ?.let(::legacyRelativeParent)
                        .normalizeSourcePath()
                }
                val dateTaken = cursor.longOrNull(DATE_TAKEN)?.takeIf { it > 0 }
                val dateModified = cursor.longOrNull(MediaStore.MediaColumns.DATE_MODIFIED)
                    ?.takeIf { it > 0 }
                    ?.times(1_000)
                val dateAdded = cursor.longOrNull(MediaStore.MediaColumns.DATE_ADDED)
                    ?.takeIf { it > 0 }
                    ?.times(1_000)
                val displayName = cursor.stringOrNull(MediaStore.MediaColumns.DISPLAY_NAME)
                    ?.takeIf(String::isNotBlank)
                    ?: "media-$id"
                output += SystemMediaEntry(
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    displayName = displayName,
                    mimeType = cursor.stringOrNull(MediaStore.MediaColumns.MIME_TYPE)
                        ?: if (mediaType == SystemMediaType.IMAGE) "image/*" else "video/*",
                    mediaType = mediaType,
                    size = cursor.longOrNull(MediaStore.MediaColumns.SIZE) ?: 0,
                    capturedAt = dateTaken ?: dateModified ?: dateAdded ?: 0,
                    sourcePath = relativePath,
                    bucketName = cursor.stringOrNull(BUCKET_DISPLAY_NAME)
                        ?.takeIf(String::isNotBlank)
                        ?: relativePath.substringAfterLast('/').ifBlank { "未分类" },
                )
            }
        }
        return output
    }

    private fun canReadImages(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) || partialAccessGranted()
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun canReadVideos(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(Manifest.permission.READ_MEDIA_VIDEO) || partialAccessGranted()
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun partialAccessGranted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun imageCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun videoCollection(): Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    private fun android.database.Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun android.database.Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private fun legacyRelativeParent(absolutePath: String): String {
        @Suppress("DEPRECATION")
        val storageRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        return File(absolutePath).parent.orEmpty().removePrefix(storageRoot)
    }

    private fun String?.normalizeSourcePath(): String = this.orEmpty()
        .replace('\\', '/')
        .trim('/')
        .split('/')
        .filter { it.isNotBlank() && it != "." && it != ".." }
        .joinToString("/")

    private companion object {
        const val DATE_TAKEN = "datetaken"
        const val BUCKET_DISPLAY_NAME = "bucket_display_name"
    }
}
