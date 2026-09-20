package dev.susnowy.gallery

import android.app.Application
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.storage.LibraryTree
import okio.Path.Companion.toOkioPath

/**
 * The application singleton, which exists to configure the thumbnail loader.
 *
 * Thumbnails are the app's heaviest cost: a grid cell has to decode a downsampled frame from a
 * possibly huge JPEG or from a video container. Both caches are sized generously because the
 * alternative — re-decoding while the user scrolls — is what makes a media browser feel slow.
 *
 * The disk cache lives in the app's own cache directory, which is the only place an Android 11+
 * app may write with the file API: writing into the Library's `.gallery/` from this process is
 * refused with `EPERM` unless the app is granted all-files access. Coil keys a cached file by the
 * request URI and its transformations, so one file gets exactly one thumbnail — scrolling past the
 * same picture twice does not store it twice.
 */
@OptIn(DelicateCoilApi::class)
class RemApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // First, so every later line has somewhere to go.
        RemLog.initialize(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = buildLoader(context, defaultDiskCache(context))

    private fun buildLoader(context: Context, diskCache: DiskCache): ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                .build()
        }
        .diskCache(diskCache)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            add(VideoFrameDecoder.Factory())
        }
        .build()

    private fun defaultDiskCache(context: Context): DiskCache = diskCacheFor(
        context.cacheDir.resolve("thumbnails").apply { mkdirs() }.absolutePath,
    )

    private fun diskCacheFor(directory: String): DiskCache = DiskCache.Builder()
        .directory(java.io.File(directory).toOkioPath())
        // Deliberately unset as a limit: every media file gets one thumbnail and it stays until the
        // user removes `.gallery/thumbs/`, so a Library does not have to be decoded twice on the
        // same device. Coil needs a number, so this is a ceiling nothing reaches.
        .maxSizeBytes(NO_CACHE_LIMIT)
        .build()

    private companion object {
        const val SCOPE = "缩略图"
        const val MEMORY_CACHE_PERCENT = 0.25
        const val NO_CACHE_LIMIT = 512L * 1024L * 1024L * 1024L

        /** Which Library's cache is currently installed. */
        @Volatile
        var libraryCacheKey: String? = null
    }
}
