package dev.susnowy.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.ui.components.TargetOption
import dev.susnowy.gallery.ui.components.TargetPickerDialog
import dev.susnowy.gallery.ui.components.WorkPickerDialog
import dev.susnowy.gallery.ui.screens.SeriesChapterList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LongListPositionTest {
    @get:Rule val rule = createComposeRule()
    private fun works() = (1..150).map { n -> MediaItem("w$n", "fixture", "$n.cbz", "",
        MediaKind.IMAGE_SET, sourceKind = SourceKind.ARCHIVE, displayTitle = "Chapter $n", missingMedia = true) }

    private fun jump(position: String) {
        rule.onNodeWithText("· 定位", substring = true).performClick()
        rule.onNodeWithText("位置（1–150）").performTextReplacement(position)
        rule.onNodeWithText("跳转").performClick()
    }

    @Test fun chapterJumpAndReturnKeepChapterAnchor() {
        val works = works()
        val detail = mutableStateOf(false)
        val vm = GalleryViewModel(ApplicationProvider.getApplicationContext<GalleryApplication>(), SavedStateHandle())
        rule.setContent { MaterialTheme {
            val holder = rememberSaveableStateHolder()
            if (detail.value) Text("Reading fixture") else holder.SaveableStateProvider("chapters") {
                SeriesChapterList("Chapters", works, null, vm, {}, { _, _ -> detail.value = true }, {})
            }
        } }
        jump("100")
        rule.onNodeWithText("Chapter 100").assertIsDisplayed().performClick()
        rule.onNodeWithText("Reading fixture").assertIsDisplayed()
        rule.runOnIdle { detail.value = false }
        rule.onNodeWithText("100 / 150 · 定位").assertIsDisplayed()
        rule.onNodeWithText("Chapter 100").assertIsDisplayed()
    }

    @Test fun workPickerJumpKeepsSelectionWhenFiltering() {
        var selected = emptyList<String>()
        rule.setContent { MaterialTheme { WorkPickerDialog(works(), {}, { selected = it }) } }
        jump("100")
        rule.onNodeWithText("Chapter 100").assertIsDisplayed().performClick()
        rule.onNodeWithText("搜索标题、路径或作者").performTextReplacement("Chapter 150")
        rule.onNode(hasText("Chapter 150") and !hasSetTextAction()).assertIsDisplayed().performClick()
        rule.onNodeWithText("选择 2 项").performClick()
        rule.runOnIdle { assertEquals(listOf("w100", "w150"), selected) }
    }

    @Test fun targetPickerJumpChoosesTheDisplayedDestination() {
        var selected: String? = null
        val options = (1..150).map { TargetOption("g$it", "Group $it") }
        rule.setContent { MaterialTheme { TargetPickerDialog("Choose group", options, {}, { selected = it.id }) } }
        jump("120")
        rule.onNodeWithText("Group 120").assertIsDisplayed().performClick()
        rule.runOnIdle { assertEquals("g120", selected) }
    }
}
