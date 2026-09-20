package dev.susnowy.gallery

import android.app.Application
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import coil3.video.VideoFrameDecoder
import dev.susnowy.gallery.logging.RemLog
import okio.Path.Companion.toOkioPath

/**
 * The application singleton, which exists only to configure the thumbnail loader.
 *
 * Thumbnails are the app's heaviest cost: a grid cell has to decode a downsampled frame from a
 * possibly huge JPEG or from a video container. Both caches are sized generously because the
 * alternative — re-decoding while the user scrolls — is what makes a media browser feel slow.
 */
class RemApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // First, so every later line has somewhere to go.
        RemLog.initialize(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("thumbnails").toOkioPath())
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            add(VideoFrameDecoder.Factory())
        }
        .build()

    private companion object {
        const val DISK_CACHE_BYTES = 256L * 1024L * 1024L
    }
}
