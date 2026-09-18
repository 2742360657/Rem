package dev.susnowy.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.ui.components.BatchMetadataDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BatchMetadataInteractionTest {
    @get:Rule val rule = createComposeRule()
    private val item = MediaItem("w", "lib", "book", "", MediaKind.IMAGE_SET,
        sourceKind = SourceKind.DIRECTORY, displayTitle = "Book", tags = listOf("old"))

    @Test fun explicitClearKeepsOpeningBaselineEvenAfterCurrentItemsChange() {
        val items = mutableStateOf(listOf(item))
        var saved: Pair<List<MediaItem>, BatchMetadataEdit>? = null
        rule.setContent { MaterialTheme { BatchMetadataDialog(items.value, {}, { baseline, edit -> saved = baseline to edit }) } }
        rule.onNodeWithText("应用到所选").assertIsNotEnabled()
        rule.onNodeWithText("Tag：保持").performClick()
        rule.onNodeWithText("清空").performClick()
        rule.onNodeWithText("清空会删除所选字段的全部值，并记录为人工决定。").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { items.value = listOf(item.copy(tags = listOf("concurrent"))) }
        rule.onNodeWithText("应用到所选").performClick()
        rule.runOnIdle {
            assertEquals(item, saved!!.first.single())
            assertEquals(BatchListMode.CLEAR, saved!!.second.tags.mode)
            assertEquals(BatchListMode.KEEP, saved!!.second.authors.mode)
        }
    }

    @Test fun replacementRequiresValuesAndDoesNotMeanClear() {
        var saved: BatchMetadataEdit? = null
        rule.setContent { MaterialTheme { BatchMetadataDialog(listOf(item), {}, { _, edit -> saved = edit }) } }
        rule.onNodeWithText("作者：保持").performClick()
        rule.onNodeWithText("替换").performClick()
        rule.onNodeWithText("应用到所选").assertIsNotEnabled()
        rule.onNodeWithText("作者（逗号分隔）").performTextInput("A,B")
        rule.onNodeWithText("应用到所选").performClick()
        rule.runOnIdle {
            assertEquals(listOf("A", "B"), saved!!.authors.normalized)
            assertEquals(BatchListMode.REPLACE, saved!!.authors.mode)
        }
    }
}
