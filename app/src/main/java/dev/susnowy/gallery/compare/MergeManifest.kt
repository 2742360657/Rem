package dev.susnowy.gallery.compare

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Program-managed record of a virtual merge, written to `.gallery/imports/`.
 *
 * The merged Edition itself carries the page plan; this manifest keeps the evidence: which
 * sources were compared, with what depth, and what the report said. It never replaces the
 * sources and it is not a user decision, so an Agent may read it and must not treat it as
 * authority over the catalog.
 */
@Serializable
data class MergeManifest(
    val kind: String = KIND,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("library_id") val libraryId: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edition_id") val editionId: String,
    @SerialName("target_work_id") val targetWorkId: String,
    val label: String,
    val sources: List<MergeSource>,
    val result: MergeResult,
) {
    companion object {
        const val KIND = "virtual_merge"
    }
}

@Serializable
data class MergeSource(
    @SerialName("work_id") val workId: String,
    @SerialName("asset_path") val assetPath: String,
    val label: String,
    val pages: Int,
    val hashed: Boolean,
    @SerialName("bytes_read") val bytesRead: Long,
    @SerialName("duration_ms") val durationMs: Long,
)

@Serializable
data class MergeResult(
    val pages: Int,
    val identical: Int,
    @SerialName("left_only") val leftOnly: Int,
    @SerialName("right_only") val rightOnly: Int,
    val conflicting: Int,
    val unverified: Int,
    val deep: Boolean,
)
