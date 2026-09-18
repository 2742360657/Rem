package dev.susnowy.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.ui.components.RelationRemovalDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RelationRemovalInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val item = MediaItem("w", "lib", "book", "", MediaKind.IMAGE_SET,
        sourceKind = SourceKind.DIRECTORY, displayTitle = "Book")
    private val series = MediaSeries("s", "lib", "Series", members = listOf(MediaSeriesMember("w")),
        revision = 3)

    @Test fun confirmationBindsOpeningRevisionAndMemberScope() {
        val current = mutableStateOf(series)
        var saved: RelationRemoval? = null
        rule.setContent { MaterialTheme { RelationRemovalDialog(listOf(item), emptyList(), listOf(current.value), {}, { saved = it }) } }
        rule.onNodeWithText("Series").performClick()
        rule.runOnIdle { current.value = series.copy(revision = 4) }
        rule.onNodeWithText("移出").performClick()
        rule.runOnIdle {
            assertEquals(3L, saved!!.revision)
            assertEquals(setOf("w"), saved!!.workIds)
            assertEquals("lib", saved!!.libraryId)
        }
    }

    @Test fun changingLibraryClearsPendingConfirmation() {
        val selection = mutableStateOf(listOf(item))
        rule.setContent { MaterialTheme { RelationRemovalDialog(selection.value, emptyList(), listOf(series), {}, {}) } }
        rule.onNodeWithText("Series").performClick()
        rule.onNodeWithText("移出").assertExists()
        rule.runOnIdle { selection.value = listOf(item.copy(libraryId = "other")) }
        rule.onNodeWithText("移出").assertDoesNotExist()
        rule.onNodeWithText("所选作品没有可移出的分组或系列").assertIsDisplayed()
    }
}
