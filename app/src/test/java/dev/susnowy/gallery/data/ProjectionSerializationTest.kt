package dev.susnowy.gallery.data

import dev.susnowy.gallery.model.GroupMemberRole
import dev.susnowy.gallery.model.GroupType
import dev.susnowy.gallery.model.MediaGroup
import dev.susnowy.gallery.model.MediaGroupMember
import dev.susnowy.gallery.model.MediaSeries
import dev.susnowy.gallery.model.MediaSeriesMember
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The device index stores projected Groups and Series as JSON columns with exactly this
 * configuration. Without it the write throws only at runtime — which is how a saved Group
 * once reached `catalog.json` but never appeared in the UI.
 */
class ProjectionSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun groupMembersRoundTripThroughTheIndexFormat() {
        val members = listOf(
            MediaGroupMember("work-1", GroupMemberRole.ITEM, 0.0),
            MediaGroupMember("work-2", GroupMemberRole.VIDEO, 1.0),
        )

        val stored = json.encodeToString(members)
        val restored = json.decodeFromString<List<MediaGroupMember>>(stored)

        assertEquals(members, restored)
    }

    @Test
    fun seriesMembersRoundTripThroughTheIndexFormat() {
        val members = listOf(
            MediaSeriesMember("work-1", sortIndex = 0.0, chapter = 138.0),
            MediaSeriesMember("work-2", sortIndex = 1.0, season = 1, episode = 2.0),
        )

        val stored = json.encodeToString(members)
        val restored = json.decodeFromString<List<MediaSeriesMember>>(stored)

        assertEquals(members, restored)
    }

    @Test
    fun wholeProjectionRowsAreSerializable() {
        val group = MediaGroup(
            id = "group-1",
            libraryId = "library-id",
            title = "写真集A",
            type = GroupType.MEDIA_SET,
            members = listOf(MediaGroupMember("work-1")),
            coverWorkId = "work-1",
        )
        val series = MediaSeries(
            id = "series-1",
            libraryId = "library-id",
            title = "爱神巧克力进行时",
            members = listOf(MediaSeriesMember("work-1", chapter = 138.0)),
        )

        assertEquals(group, json.decodeFromString<MediaGroup>(json.encodeToString(group)))
        assertEquals(series, json.decodeFromString<MediaSeries>(json.encodeToString(series)))
    }
}
