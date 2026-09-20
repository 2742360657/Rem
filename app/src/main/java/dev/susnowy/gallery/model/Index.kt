package dev.susnowy.gallery.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The rebuildable cache under `.gallery/index.json`.
 *
 * This is a cache and nothing else: [LibraryStore][dev.susnowy.gallery.storage.LibraryStore]
 * can regenerate it from the Library at any time, and deleting the file costs one scan. It is
 * deliberately not a source of truth, so nothing here needs a migration path — a version bump
 * just means the next scan writes a new file.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Index(
    /**
     * The build that produced this cache, encoded unconditionally.
     *
     * It used to be dropped by `encodeDefaults = false`, and a field that is never written cannot
     * be compared: decoding filled in the current default, so the version check in
     * [LibraryStore][dev.susnowy.gallery.storage.LibraryStore] matched every file ever produced and
     * no cache was ever invalidated.
     *
     * The default is deliberately the *previous* version rather than [VERSION]. Files written
     * before this field was always encoded carry no key at all, so decoding one has to land on a
     * value that gets the cache thrown away — not on the value that makes it look current.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = LEGACY_VERSION,
    val entries: List<StoredEntry> = emptyList(),
    /** Relative paths the last scan could not file under the rules. Reported, never corrected. */
    val violations: List<String> = emptyList(),
) {
    companion object {
        /**
         * 2 — video capture times became readable, so a cache written by version 1 can hold a
         * `captured` of `null` for a video that does carry one. Entries are only re-read when a
         * file's size or modification time moves, so without a bump an existing Library would keep
         * sorting its videos by copy date indefinitely.
         */
        const val VERSION = 2

        /** What a file written before the version key was always encoded decodes to. */
        const val LEGACY_VERSION = 1
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
