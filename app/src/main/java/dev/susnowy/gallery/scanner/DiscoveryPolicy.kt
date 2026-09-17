package dev.susnowy.gallery.scanner

import java.util.Locale

/** Entries owned by Rem or a known media sidecar must not become Inbox noise. */
object DiscoveryPolicy {
    private val ignoredFiles = setOf(
        ".nomedia",
        ".ehviewer",
        ".thumb",
        ".ds_store",
        "thumbs.db",
        "desktop.ini",
        "comicinfo.xml",
        "gallery_library.md",
    )

    fun ignoreFile(name: String): Boolean = name.lowercase(Locale.ROOT) in ignoredFiles

    fun ignoreRootDirectory(name: String): Boolean {
        val normalized = name.lowercase(Locale.ROOT)
        return normalized == ".gallery" || normalized.startsWith(".gallery-quarantine-")
    }
}
