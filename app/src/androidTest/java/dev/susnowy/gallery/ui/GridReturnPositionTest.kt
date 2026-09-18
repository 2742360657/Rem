package dev.susnowy.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.ui.components.MediaGrid
import org.junit.Rule
import org.junit.Test

class GridReturnPositionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun jumpSurvivesDetailReturnAndRestorationWithInitiallyEmptyItems() {
        val vm = GalleryViewModel(ApplicationProvider.getApplicationContext<GalleryApplication>(), SavedStateHandle())
        val fixture = (1..150).map { n -> MediaItem("w$n", "fixture", "$n.jpg", "", MediaKind.IMAGE,
            sourceKind = SourceKind.FILE, displayTitle = "Item $n") }
        val items = mutableStateOf(fixture)
        val detail = mutableStateOf(false)
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MaterialTheme {
            val holder = rememberSaveableStateHolder()
            if (detail.value) Text("Detail fixture") else holder.SaveableStateProvider("grid") {
                MediaGrid(items.value, vm, { detail.value = true })
            }
        } }
        rule.onNodeWithText("1 / 150 · 定位").performClick()
        rule.onNodeWithText("位置（1–150）").performTextReplacement("100")
        rule.onNodeWithText("跳转").performClick()
        rule.onNodeWithText("Item 100").assertIsDisplayed()
        // The first visible item is the start of the grid row containing the target.
        val anchor = rule.onNodeWithText(" / 150 · 定位", substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single().text
        rule.onNodeWithText("Item 100").performClick()
        rule.onNodeWithText("Detail fixture").assertIsDisplayed()
        rule.runOnIdle { detail.value = false }
        rule.onNodeWithText(anchor).assertIsDisplayed()
        rule.runOnIdle { items.value = emptyList() }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { items.value = fixture }
        rule.onNodeWithText(anchor).assertIsDisplayed()
    }
}
