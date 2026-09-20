package dev.susnowy.gallery.model

import kotlinx.serialization.Serializable

/**
 * The rebuildable cache under `.gallery/index.json`.
 *
 * This is a cache and nothing else: [LibraryStore][dev.susnowy.gallery.storage.LibraryStore]
 * can regenerate it from the Library at any time, and deleting the file costs one scan. It is
 * deliberately not a source of truth, so nothing here needs a migration path — a version bump
 * just means the next scan writes a new file.
 */
@Serializable
data class Index(
    val version: Int = VERSION,
    val entries: List<StoredEntry> = emptyList(),
    /** Relative paths the last scan could not file under the rules. Reported, never corrected. */
    val violations: List<String> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

/** [Entry] as it appears on disk. Optional fields are omitted so the file stays readable. */
@Serializable
data class StoredEntry(
    val path: String,
    val size: Long,
    val modified: Long,
    val captured: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

fun Entry.toStored(): StoredEntry = StoredEntry(
    path = path,
    size = size,
    modified = modified,
    captured = captured,
    latitude = place?.latitude,
    longitude = place?.longitude,
)

fun StoredEntry.toEntry(): Entry = Entry(
    path = path,
    size = size,
    modified = modified,
    captured = captured,
    place = if (latitude != null && longitude != null) Place(latitude, longitude) else null,
)
