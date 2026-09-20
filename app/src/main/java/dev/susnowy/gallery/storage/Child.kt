package dev.susnowy.gallery.storage

import android.net.Uri
import dev.susnowy.gallery.model.MediaType
import dev.susnowy.gallery.model.MediaTypes

/**
 * One directory entry as the document provider reported it, with everything a scan needs
 * already resolved so no path is ever walked twice.
 */
data class Child(
    val documentId: String,
    val uri: Uri,
    /** Path relative to the Library root. */
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val mimeType: String?,
) {
    /** The playable media type by extension, or `null` for anything Rem does not display. */
    val mediaType: MediaType? get() = MediaTypes.of(name)
}
