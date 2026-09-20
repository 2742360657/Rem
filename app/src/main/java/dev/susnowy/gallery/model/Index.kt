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
    /**
     * Every directory under the browsable roots, relative to the Library root, sorted.
     *
     * Stored rather than derived from [entries] because a folder holding no media — or holding only
     * deeper folders — still exists and must stay browsable. Entry paths alone cannot tell an empty
     * folder from one that was never read.
     */
    val folders: List<String> = emptyList(),
    /** Relative paths the last scan could not file under the rules. Reported, never corrected. */
    val violations: List<String> = emptyList(),
    /**
     * First-level 画集 projects whose whole subtree the last listing pass walked.
     *
     * Directory listing is the one part of a scan that cannot be shortened: no provider reports a
     * folder's modification time, so finding out whether anything changed means asking for every
     * folder's contents again. On a real Library that is tens of thousands of files across hundreds
     * of folders, and on a removable volume it takes minutes — long enough that the process is
     * often killed first. Without this the next pass starts that walk from the top again and the
     * work is lost, which is what "it rescans everything from scratch" looked like.
     *
     * Only projects the pass actually finished are listed, and the list is rewritten from the walk
     * each time rather than accumulated, so a project removed from the volume cannot linger as a
     * phantom that would make the walk skip a project that is really there.
     */
    val doneProjects: List<String> = emptyList(),
) {
    companion object {
        /**
         * 5 — the listing walk records which 画集 projects it has already finished.
         *
         * 4 — entries record whether their metadata has been read at all.
         *
         * A listing pass writes entries without opening any file, so a Library is browsable in
         * seconds instead of after reading tens of thousands of files. Without this flag a cache
         * written by that pass is indistinguishable from one whose files simply carry no capture
         * time, and the catch-up pass would either re-read everything forever or never run.
         */
        const val VERSION = 5

        /** What a file written before the version key was always encoded decodes to. */
        const val LEGACY_VERSION = 1
    }
}

/**
 * [Entry] as it appears on disk.
 *
 * [metadataRead] separates "this file carries no capture time" from "nobody has looked yet". The
 * listing pass sets it to false for everything it lists; the catch-up pass opens those files and
 * sets it to true — including when the reading finds nothing, which is a result in itself.
 */
@Serializable
data class StoredEntry(
    val path: String,
    val size: Long,
    val modified: Long,
    val captured: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val metadataRead: Boolean = false,
)

fun Entry.toStored(metadataRead: Boolean): StoredEntry = StoredEntry(
    path = path,
    size = size,
    modified = modified,
    captured = captured,
    latitude = place?.latitude,
    longitude = place?.longitude,
    metadataRead = metadataRead,
)

fun StoredEntry.toEntry(): Entry = Entry(
    path = path,
    size = size,
    modified = modified,
    captured = captured,
    place = if (latitude != null && longitude != null) Place(latitude, longitude) else null,
)
