package dev.susnowy.gallery.model

data class RelationRemoval(
    val libraryId: String,
    val relationId: String,
    val title: String,
    val isSeries: Boolean,
    val revision: Long,
    val workIds: Set<String>,
)
