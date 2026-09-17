package dev.susnowy.gallery.model

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Device-side projection of one portable Group.
 *
 * The portable document is `catalog.json`; this type only exists so the UI can render and
 * edit a group without loading the whole catalog.
 */
@Serializable
data class MediaGroup(
    val id: String,
    val libraryId: String,
    val title: String,
    val type: GroupType = GroupType.MEDIA_SET,
    val ordered: Boolean = true,
    val members: List<MediaGroupMember> = emptyList(),
    val coverWorkId: String? = null,
    val revision: Long = 1,
) {
    val memberIds: List<String> get() = members.map(MediaGroupMember::workId)

    fun membersInOrder(): List<MediaGroupMember> = members.sortedWith(
        compareBy<MediaGroupMember> { it.sortIndex ?: Double.MAX_VALUE }
            .thenBy(MediaGroupMember::workId),
    )

    fun roleOf(workId: String): GroupMemberRole =
        members.firstOrNull { it.workId == workId }?.role ?: GroupMemberRole.ITEM
}

@Serializable
data class MediaGroupMember(
    val workId: String,
    val role: GroupMemberRole = GroupMemberRole.ITEM,
    val sortIndex: Double? = null,
)

/** Default member role for a Work, used when a member is added from the library. */
fun roleForKind(kind: MediaKind): GroupMemberRole = when (kind) {
    MediaKind.VIDEO -> GroupMemberRole.VIDEO
    MediaKind.IMAGE -> GroupMemberRole.IMAGE
    else -> GroupMemberRole.ITEM
}

/** Stable Group id for a derived mixed folder, so saving it twice cannot duplicate it. */
fun derivedGroupId(libraryId: String, primaryWorkId: String): String =
    UUID.nameUUIDFromBytes("group:$libraryId:$primaryWorkId".encodeToByteArray()).toString()

fun PortableGroup.toMediaGroup(libraryId: String): MediaGroup = MediaGroup(
    id = id,
    libraryId = libraryId,
    title = title,
    type = type,
    ordered = ordered,
    members = members.map { MediaGroupMember(it.workId, it.role, it.sortIndex) },
    coverWorkId = coverWorkId,
    revision = revision,
)

/** Portable form of a group edit; [MediaGroup.revision] carries the expected revision. */
fun MediaGroup.toPortableGroup(order: List<String> = memberIds): PortableGroup = PortableGroup(
    id = id,
    title = title,
    type = type,
    ordered = ordered,
    members = order.mapIndexed { index, workId ->
        PortableGroupMember(
            workId = workId,
            role = roleOf(workId),
            sortIndex = index.toDouble(),
        )
    },
    coverWorkId = coverWorkId,
    revision = revision,
    updatedAt = java.time.Instant.now().toString(),
)
