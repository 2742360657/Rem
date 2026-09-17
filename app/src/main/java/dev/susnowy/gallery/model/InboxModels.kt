package dev.susnowy.gallery.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the user decided about a path or Work that the scanner surfaced.
 *
 * A disposition is a portable user statement, not a device cache: it must survive a
 * rebuild of the local index, another phone, and an external organizing Agent.
 */
@Serializable
enum class InboxDisposition {
    /** The scanner's suggestion was accepted; the Work belongs to the normal views. */
    @SerialName("accepted") ACCEPTED,

    /** The user classified the Work from Inbox; the Work keeps the authoritative domain. */
    @SerialName("classified") CLASSIFIED,

    /** The user does not want Rem to surface this path again; the media stays untouched. */
    @SerialName("ignored") IGNORED,

    /** A discovered path the user considers dealt with outside Rem. */
    @SerialName("handled") HANDLED,
}

/** Which side of Inbox a decision was made about. */
@Serializable
enum class InboxTarget {
    @SerialName("media") MEDIA,
    @SerialName("discovery") DISCOVERY,
}

/**
 * One portable Inbox decision.
 *
 * `relative_path` is always recorded because discovery entries have no Work. When the
 * target is a Work, `work_id` is recorded as well so a later rename or Organizer move
 * still resolves to the same decision.
 */
@Serializable
data class PortableInboxDecision(
    @SerialName("relative_path") val relativePath: String,
    val target: InboxTarget,
    @SerialName("work_id") val workId: String? = null,
    val disposition: InboxDisposition,
    /** Domain chosen while classifying; the Work keeps the authoritative value. */
    val domain: MediaDomain? = null,
    /** Scanner evidence at decision time, for example `UNSUPPORTED_FILE`. */
    val reason: String? = null,
    val note: String? = null,
    /** `manual`, or `agent:<id>` when an external organizer wrote the decision. */
    val by: String = MANUAL_DECISION_SOURCE,
    @SerialName("decided_at") val decidedAt: String,
) {
    /** Stable identity of the decided target inside one Library. */
    val key: String get() = workId ?: relativePath

    companion object {
        const val MANUAL_DECISION_SOURCE = "manual"
    }
}

/** Portable `.gallery/state/inbox.json`: the user's Inbox decisions for one Library. */
@Serializable
data class PortableInbox(
    @SerialName("schema_version") val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    @SerialName("library_id") val libraryId: String,
    val revision: Long = 0,
    @SerialName("updated_at") val updatedAt: String,
    val decisions: List<PortableInboxDecision> = emptyList(),
) {
    /** A decision for a Work, matched by Work id first and by path second. */
    fun forWork(workId: String, relativePath: String): PortableInboxDecision? =
        decisions.firstOrNull { it.target == InboxTarget.MEDIA && it.workId == workId }
            ?: decisions.firstOrNull {
                it.target == InboxTarget.MEDIA && it.workId == null && it.relativePath == relativePath
            }

    /** A decision for a discovered path that has no Work. */
    fun forDiscovery(relativePath: String): PortableInboxDecision? =
        decisions.firstOrNull {
            it.target == InboxTarget.DISCOVERY && it.relativePath == relativePath
        }

    val ignored: List<PortableInboxDecision>
        get() = decisions.filter { it.disposition == InboxDisposition.IGNORED }

    fun keySet(): Set<String> = decisions.mapTo(mutableSetOf(), PortableInboxDecision::key)
}

/** True when the user asked Rem to keep this Work out of the normal library views. */
val MediaItem.mutedByInboxDecision: Boolean
    get() = inboxDisposition == InboxDisposition.IGNORED

/** True when this Work belongs in the normal views: decided, not ignored, not trashed. */
val MediaItem.visibleInLibrary: Boolean
    get() = !trashed && !inInbox && !mutedByInboxDecision
