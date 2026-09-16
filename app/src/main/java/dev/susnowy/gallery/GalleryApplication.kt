package dev.susnowy.gallery

import android.app.Application
import android.content.Context
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.svg.SvgDecoder
import coil3.video.VideoFrameDecoder
import dev.susnowy.gallery.data.GalleryRepository

class GalleryApplication : Application(), SingletonImageLoader.Factory {
    val repository: GalleryRepository by lazy { GalleryRepository(this) }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .components {
            if (Build.VERSION.SDK_INT >= 28) {
                add(AnimatedImageDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
            add(SvgDecoder.Factory())
            add(VideoFrameDecoder.Factory())
        }
        .build()
}
