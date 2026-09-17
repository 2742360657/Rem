package dev.susnowy.gallery.model

enum class DiscoveryReason {
    UNSUPPORTED_FILE,
    AMBIGUOUS_DIRECTORY,
}

/**
 * A path the scanner found but cannot safely turn into a media item yet.
 * This is a rebuildable device-side index; a user decision about it is stored in the
 * portable `.gallery/state/inbox.json` and only mirrored into [disposition].
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
    /** Mirror of the portable Inbox decision; null means it is still waiting. */
    val disposition: InboxDisposition? = null,
) {
    val id: String get() = "$libraryId:$relativePath"

    /** True while this path still awaits a decision in Inbox. */
    val pending: Boolean get() = disposition == null
}
