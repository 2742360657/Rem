package dev.susnowy.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GroupModelsTest {
    private fun item(id: String, kind: MediaKind) = MediaItem(
        id = id,
        libraryId = "library-id",
        relativePath = "Comics/$id",
        uri = "content://$id",
        kind = kind,
        sourceKind = SourceKind.DIRECTORY,
        displayTitle = id,
    )

    @Test
    fun orderBecomesSortIndex() {
        val group = MediaGroup(
            id = "group-1",
            libraryId = "library-id",
            title = "写真集",
            members = listOf(
                MediaGroupMember("work-b", GroupMemberRole.ITEM),
                MediaGroupMember("work-a", GroupMemberRole.ITEM),
            ),
        )

        val portable = group.toPortableGroup(order = listOf("work-a", "work-b"))

        assertEquals(listOf("work-a", "work-b"), portable.members.map(PortableGroupMember::workId))
        assertEquals(listOf(0.0, 1.0), portable.members.map(PortableGroupMember::sortIndex))
    }

    @Test
    fun membersWithoutOrderFallBackToWorkId() {
        val group = MediaGroup(
            id = "group-1",
            libraryId = "library-id",
            title = "写真集",
            members = listOf(
                MediaGroupMember("work-b"),
                MediaGroupMember("work-a"),
            ),
        )

        assertEquals(listOf("work-a", "work-b"), group.membersInOrder().map(MediaGroupMember::workId))
        assertEquals(GroupMemberRole.ITEM, group.roleOf("unknown"))
        assertEquals(GroupMemberRole.ITEM, group.roleOf("work-a"))
    }

    @Test
    fun derivedGroupIdIsStablePerWork() {
        val first = derivedGroupId("library-id", "work-1")

        assertEquals(first, derivedGroupId("library-id", "work-1"))
        assertNotEquals(first, derivedGroupId("library-id", "work-2"))
        assertNotEquals(first, derivedGroupId("other-library", "work-1"))
    }

    @Test
    fun memberRolesFollowTheMediaKind() {
        assertEquals(GroupMemberRole.VIDEO, roleForKind(item("v", MediaKind.VIDEO).kind))
        assertEquals(GroupMemberRole.IMAGE, roleForKind(item("i", MediaKind.IMAGE).kind))
        assertEquals(GroupMemberRole.ITEM, roleForKind(item("s", MediaKind.IMAGE_SET).kind))
    }
}
