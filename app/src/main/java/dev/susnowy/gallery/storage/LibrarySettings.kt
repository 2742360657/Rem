package dev.susnowy.gallery.storage

import android.content.Context
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.ALBUM
import dev.susnowy.gallery.model.COLLECTION

/**
 * The one setting, plus the marker files that carry it into the Library.
 *
 * Android's system gallery lists any folder on shared storage unless the folder contains a
 * `.nomedia` marker. A Library is browsed through Rem, so having every file appear twice — once
 * in Rem and once in the gallery — is usually unwanted, but it is a preference rather than a
 * rule, which is why it is a switch instead of something Rem always does.
 *
 * Both markers are written empty. A `.nomedia` file with content is still a valid marker, but
 * there is no reason to put bytes into the user's Library.
 */
class LibrarySettings(private val context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Whether the browsable directories should carry a `.nomedia` marker. */
    var hideFromSystemGallery: Boolean
        get() = preferences.getBoolean(KEY_HIDE, false)
        set(value) {
            preferences.edit().putBoolean(KEY_HIDE, value).apply()
            RemLog.info(SCOPE, "设置 隐藏于系统相册=$value")
        }

    /**
     * Creates or removes the markers to match [hideFromSystemGallery].
     *
     * Returns false when a marker could not be written or removed, so the switch can be put back
     * rather than claiming a state the Library is not in.
     */
    fun applyMarkers(tree: LibraryTree): Boolean {
        val wanted = hideFromSystemGallery
        val results = writableDirectories.map { directory ->
            if (wanted) {
                tree.writeMarker(directory, MARKER)
            } else {
                tree.deleteMarker(directory, MARKER)
            }
        }
        val succeeded = results.all { it }
        if (!succeeded) {
            RemLog.warn(SCOPE, "标记文件未能全部应用（目标=$wanted）")
        }
        return succeeded
    }

    private companion object {
        const val SCOPE = "Settings"
        const val PREFERENCES = "rem.settings"
        const val KEY_HIDE = "hide_from_system_gallery"
        const val MARKER = ".nomedia"

        /** Only the two browsable directories are ever touched. */
        val writableDirectories = listOf(ALBUM, COLLECTION)
    }
}
