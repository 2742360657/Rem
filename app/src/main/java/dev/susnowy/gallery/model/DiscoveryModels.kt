package dev.susnowy.gallery.model

enum class DiscoveryReason {
    UNSUPPORTED_FILE,
    AMBIGUOUS_DIRECTORY,
}

/**
 * A path the scanner found but cannot safely turn into a media item yet.
 * This is a rebuildable device-side index, not a user classification decision.
 */
data class DiscoveredEntry(
    val libraryId: String,
    val relativePath: String,
    val uri: String? = null,
    val isDirectory: Boolean,
    val mimeType: String? = null,
    val size: Long = 0,
    val modifiedAt: Long = 0,
    val reason: DiscoveryReason,
) {
    val id: String get() = "$libraryId:$relativePath"
}
