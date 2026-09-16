package dev.susnowy.gallery.storage

import dev.susnowy.gallery.library.LibraryDocument

/**
 * A resolved SAF document: the provider's own answer for one path, kept so the path is
 * never walked from the tree root twice. Every field is a provider column, so the
 * scanner can classify a file without asking the provider again.
 */
internal data class StorageNode(
    val relativePath: String,
    val name: String,
    val documentId: String,
    val uri: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0,
    val mimeType: String? = null,
) {
    val isFile: Boolean get() = !isDirectory

    fun toStorageEntry() = StorageEntry(
        relativePath = relativePath,
        uri = uri,
        name = name,
        mimeType = mimeType,
        isDirectory = isDirectory,
        size = size,
        lastModified = lastModified,
    )

    fun toLibraryDocument(path: String): LibraryDocument = LibraryDocument(
        key = path,
        name = name,
        isDirectory = isDirectory,
        locator = uri,
    )
}

/**
 * Bounded memoization of provider answers, addressed two ways: by relative path for
 * document resolution, and by parent document id for directory listings.
 *
 * The two indexes are kept together because a child lookup needs both — resolving
 * `A/B` requires the listing of `A` and the node for `A`. Entries are evicted in
 * least-recently-used order so a large Library cannot grow the cache without bound
 * while a repeated scan of the same directories still hits.
 *
 * Not thread-safe by itself: [DocumentTreeStorage] serializes access with its own lock.
 */
internal class SafDocumentCache(
    private val pathLimit: Int = DEFAULT_PATH_LIMIT,
    private val directoryLimit: Int = DEFAULT_DIRECTORY_LIMIT,
) {
    private val paths = object : LinkedHashMap<String, StorageNode>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StorageNode>?) =
            size > pathLimit
    }

    private val directories = object : LinkedHashMap<String, Map<String, StorageNode>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Map<String, StorageNode>>?,
        ) = size > directoryLimit
    }

    fun path(relativePath: String): StorageNode? = paths[relativePath]

    fun put(node: StorageNode) {
        paths[node.relativePath] = node
    }

    fun children(parentDocumentId: String): Map<String, StorageNode>? =
        directories[parentDocumentId]

    fun putChildren(parentDocumentId: String, children: Map<String, StorageNode>) {
        directories[parentDocumentId] = children
    }

    /** Keeps an already-recorded parent listing coherent after this process creates a child. */
    fun putChild(parentDocumentId: String, child: StorageNode) {
        val current = directories[parentDocumentId] ?: return
        directories[parentDocumentId] = current + (child.name to child)
    }

    fun forgetChildren(parentDocumentId: String?) {
        if (parentDocumentId != null) directories.remove(parentDocumentId)
    }

    /** Cache-only child lookup; null means the parent listing was never recorded. */
    fun child(parentPath: String, name: String): StorageNode? {
        val parent = paths[parentPath] ?: return null
        return directories[parent.documentId]?.get(name)
    }

    /**
     * Forgets the listing of [relativePath] itself, the path entry for it, and every
     * path recorded beneath it.
     *
     * Used after a mutation: that directory's listing no longer matches the provider,
     * and any cached descendant may have moved. Scoped to the affected subtree so a
     * bulk operation — hundreds of renames during page ordering — invalidates only the
     * directory it touched instead of discarding the whole cache.
     */
    fun forgetSubtree(relativePath: String) {
        if (relativePath.isEmpty()) {
            clear()
            return
        }
        val prefix = "$relativePath/"
        val removedDocumentIds = paths
            .filterKeys { key -> key == relativePath || key.startsWith(prefix) }
            .values
            .mapTo(mutableSetOf(), StorageNode::documentId)
        paths.keys.removeAll { key -> key == relativePath || key.startsWith(prefix) }
        removedDocumentIds.forEach(directories::remove)
    }

    fun clear() {
        paths.clear()
        directories.clear()
    }

    val pathCount: Int get() = paths.size
    val directoryCount: Int get() = directories.size

    companion object {
        const val DEFAULT_PATH_LIMIT = 8_192
        const val DEFAULT_DIRECTORY_LIMIT = 512
    }
}
