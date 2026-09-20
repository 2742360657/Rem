package dev.susnowy.gallery.storage

import android.content.Context
import android.net.Uri
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.Index
import dev.susnowy.gallery.model.SortMode
import dev.susnowy.gallery.model.ViewMode
import dev.susnowy.gallery.model.toStored
import kotlinx.serialization.json.Json

/**
 * Rem's own state for the attached Library: which folder is attached, the scan cache, and the
 * rules document handed to whoever organises the files.
 *
 * Only `.gallery/` is ever written. Media files are read-only to Rem.
 */
class LibraryStore(private val context: Context) {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val json = Json {
        prettyPrint = true
        // The rules document asks that `index.json` be left alone, but an editor that adds a
        // field must not make Rem throw away a usable cache.
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** The attached tree, or `null` when the user has not chosen a Library yet. */
    val attachedTreeUri: Uri?
        get() = preferences.getString(KEY_TREE_URI, null)?.let(Uri::parse)

    fun attach(treeUri: Uri) {
        LibraryTree.persistPermission(context, treeUri)
        preferences.edit().putString(KEY_TREE_URI, treeUri.toString()).apply()
        RemLog.info(SCOPE, "接入 Library treeUri=$treeUri")
    }

    fun detach() {
        RemLog.info(SCOPE, "断开 Library treeUri=${preferences.getString(KEY_TREE_URI, null)}")
        preferences.edit().remove(KEY_TREE_URI).apply()
    }

    /**
     * The collection's order, remembered across restarts.
     *
     * Stored on the device rather than in the Library: it is how one person likes to read their
     * collection, not a property of the files, and the Library must stay free of Rem's UI state.
     */
    var sortMode: SortMode
        get() = SortMode.entries.firstOrNull { it.name == preferences.getString(KEY_SORT_MODE, null) }
            ?: SortMode.SEQUENCE
        set(value) {
            preferences.edit().putString(KEY_SORT_MODE, value.name).apply()
        }

    /** Grid or list. A reading preference, so it lives on the device and survives restarts. */
    var viewMode: ViewMode
        get() = ViewMode.entries.firstOrNull { it.name == preferences.getString(KEY_VIEW_MODE, null) }
            ?: ViewMode.GRID
        set(value) {
            preferences.edit().putString(KEY_VIEW_MODE, value.name).apply()
        }

    fun tree(): LibraryTree? = attachedTreeUri?.let { LibraryTree(context, it) }

    /** The cached index, or `null` when absent, unreadable, or written by a newer format. */
    fun readIndex(tree: LibraryTree): Index? {
        val text = tree.readInternal(INDEX_FILE) ?: return null
        val index = runCatching { json.decodeFromString<Index>(text) }.getOrNull()
        if (index == null) {
            RemLog.warn(SCOPE, "$INDEX_FILE 无法解析，丢弃缓存（${text.length}B）")
            return null
        }
        if (index.version != Index.VERSION) {
            RemLog.warn(SCOPE, "$INDEX_FILE 版本 ${index.version} != ${Index.VERSION}，丢弃缓存")
            return null
        }
        RemLog.info(SCOPE, "载入缓存 ${index.entries.size} 条")
        return index
    }

    /**
     * Deletes the cache file, so the next pass has nothing to reuse.
     *
     * Used by the explicit rebuild: when files were moved outside Rem the cache can describe a
     * Library that no longer exists, and the honest fix is to start over rather than to guess which
     * entries are still good.
     */
    fun dropIndex(tree: LibraryTree): Boolean {
        val removed = runCatching { tree.deleteInternal(INDEX_FILE) }.getOrDefault(false)
        RemLog.info(SCOPE, "删除 $INDEX_FILE：$removed")
        return removed
    }

    /** Writes the index cache. A failure costs one rescan, so callers may ignore the result. */
    fun writeIndex(tree: LibraryTree, index: Index): Boolean =
        tree.writeInternal(INDEX_FILE, json.encodeToString(Index.serializer(), index))

    /**
     * Removes copies of Rem's own files that a provider renamed with an appended extension.
     *
     * Earlier builds asked for `text/plain` and `text/markdown`, which providers turned into
     * `index.json.txt` and `RULES.md.txt`. Those copies are dead weight and, worse, they hid the
     * fact that the cache was never being read. Clearing them once per launch keeps a Library free
     * of them without a migration step.
     */
    fun cleanUpMangledFiles(tree: LibraryTree) {
        listOf(INDEX_FILE, RULES_FILE).forEach(tree::cleanUpMangledNames)
    }

    /**
     * Rewrites `RULES.md` with the shipped rules.
     *
     * The document is overwritten on every attach so a Library always carries the current
     * spec; an out-of-date copy is worse than useless to whoever organises the files.
     */
    fun writeRules(tree: LibraryTree): Boolean =
        runCatching { context.assets.open(RULES_ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }
            .getOrNull()
            ?.let { tree.writeInternal(RULES_FILE, it) }
            ?: false

    companion object {
        const val INDEX_FILE = "index.json"
        const val RULES_FILE = "RULES.md"
        const val RULES_ASSET = "RULES.md"

        private const val SCOPE = "Store"
        private const val PREFERENCES = "rem.library"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_VIEW_MODE = "view_mode"
    }
}

/** Freezes a scan's result into the cache that [LibraryStore.writeIndex] writes. */
fun indexOf(
    entries: List<Entry>,
    folders: List<String>,
    violations: List<String>,
    /** Paths whose metadata has already been read. Everything else is still waiting for the pass. */
    metadataDone: Set<String> = emptySet(),
): Index =
    Index(
        // Stated explicitly: the data class default is the *legacy* version, because that is what
        // a file predating the version key decodes to. A cache Rem writes must claim the current one.
        version = Index.VERSION,
        entries = entries.map { it.toStored(metadataRead = it.path in metadataDone) },
        folders = folders.sorted(),
        violations = violations,
    )
