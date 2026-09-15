package dev.susnowy.gallery

import android.app.Application
import dev.susnowy.gallery.data.GalleryRepository

class GalleryApplication : Application() {
    val repository: GalleryRepository by lazy { GalleryRepository(this) }
}
