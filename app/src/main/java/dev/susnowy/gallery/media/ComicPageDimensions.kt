package dev.susnowy.gallery.media

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ComicPageDimensions(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
    val aspectRatio: Float get() = width.toFloat() / height
}

/** Reads headers for a visible page, never pixels or the entire book. */
internal suspend fun readComicPageDimensions(open: () -> InputStream): ComicPageDimensions? =
    withContext(Dispatchers.IO) {
        try {
            coroutineContext.ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open().buffered().use { BitmapFactory.decodeStream(it, null, bounds) }
            coroutineContext.ensureActive()
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
            // Coil applies EXIF orientation too; reserve the displayed, not encoded, dimensions.
            val rotated = try {
                open().buffered().use { ExifInterface(it).rotationDegrees in listOf(90, 270) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            coroutineContext.ensureActive()
            if (rotated) ComicPageDimensions(bounds.outHeight, bounds.outWidth)
            else ComicPageDimensions(bounds.outWidth, bounds.outHeight)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // Let the image decoder handle unsupported headers and errors.
    }

internal suspend fun readComicPageDimensions(resolver: ContentResolver, data: Any?): ComicPageDimensions? {
    val uri = when (data) {
        is Uri -> data
        is String -> Uri.parse(data)
        is File -> Uri.fromFile(data)
        else -> return null
    }
    return readComicPageDimensions {
        requireNotNull(resolver.openInputStream(uri)) { "Cannot open page" }
    }
}
