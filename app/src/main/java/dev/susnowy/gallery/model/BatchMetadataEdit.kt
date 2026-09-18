package dev.susnowy.gallery.model

enum class BatchListMode { KEEP, APPEND, REPLACE, CLEAR }

data class BatchListEdit(val mode: BatchListMode = BatchListMode.KEEP, val values: List<String> = emptyList()) {
    val normalized: List<String> get() = values.map(String::trim).filter(String::isNotEmpty).distinct()
    val active: Boolean get() = mode == BatchListMode.CLEAR ||
        ((mode == BatchListMode.APPEND || mode == BatchListMode.REPLACE) && normalized.isNotEmpty())

    fun apply(current: List<String>): List<String> = when (mode) {
        BatchListMode.KEEP -> current
        BatchListMode.APPEND -> (current + normalized).distinct()
        BatchListMode.REPLACE -> normalized
        BatchListMode.CLEAR -> emptyList()
    }
}

data class BatchMetadataEdit(
    val authors: BatchListEdit = BatchListEdit(),
    val tags: BatchListEdit = BatchListEdit(),
    val collections: BatchListEdit = BatchListEdit(),
    val domain: MediaDomain? = null,
) {
    val active: Boolean get() = authors.active || tags.active || collections.active || domain != null

    /** A conflict skips the Work as a whole. Unrelated later edits remain intact. */
    fun merge(baseline: MediaItem, current: MediaItem): BatchMetadataMerge {
        require(baseline.id == current.id && baseline.libraryId == current.libraryId)
        val conflicts = mutableListOf<String>()
        val locks = current.fieldSources.toMutableMap()
        fun list(field: String, action: BatchListEdit, before: List<String>, now: List<String>): List<String> {
            if (!action.active) return now
            if (before != now || baseline.fieldSources[field] != current.fieldSources[field]) {
                conflicts += field
                return now
            }
            // Explicit clear of an already empty field still establishes an intentional manual lock.
            locks[field] = "manual"
            return action.apply(now)
        }
        val nextAuthors = list("authors", authors, baseline.authors, current.authors)
        val nextTags = list("tags", tags, baseline.tags, current.tags)
        val nextCollections = list("collections", collections, baseline.collections, current.collections)
        if (domain != null) {
            if (baseline.domain != current.domain || baseline.fieldSources["domain"] != current.fieldSources["domain"]) {
                conflicts += "domain"
            } else locks["domain"] = "manual"
        }
        return if (conflicts.isNotEmpty()) BatchMetadataMerge(current, conflicts) else BatchMetadataMerge(
            current.copy(authors = nextAuthors, tags = nextTags, collections = nextCollections,
                domain = domain ?: current.domain, fieldSources = locks),
        )
    }
}

data class BatchMetadataMerge(val item: MediaItem, val conflicts: List<String> = emptyList())
data class BatchMetadataIssue(val libraryId: String, val workId: String, val title: String, val reason: String)
data class BatchMetadataReport(val updated: Int = 0, val unchanged: Int = 0, val issues: List<BatchMetadataIssue> = emptyList())
