package dev.susnowy.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.ui.screens.GroupShelf
import org.junit.Rule
import org.junit.Test

class GroupShelfPositionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun jumpCountsGroupsNotHeadersAndRestoresAcrossEmptySnapshot() {
        val saved = (1..150).map { MediaGroup("g$it", "fixture", "Group $it") }
        val folder = MediaItem("folder", "fixture", "mixed", "", MediaKind.IMAGE_SET,
            sourceKind = SourceKind.DIRECTORY, displayTitle = "Derived folder", missingMedia = true)
        val video = folder.copy(id = "video", relativePath = "mixed/video.mp4", kind = MediaKind.VIDEO,
            sourceKind = SourceKind.FILE)
        val groups = mutableStateOf(saved)
        val media = mutableStateOf(listOf(folder, video))
        val vm = GalleryViewModel(ApplicationProvider.getApplicationContext<GalleryApplication>(), SavedStateHandle())
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MaterialTheme { GroupShelf(media.value, groups.value, vm) } }
        rule.onNodeWithText("1 / 151 · 定位").performClick()
        rule.onNodeWithText("位置（1–151）").performTextReplacement("100")
        rule.onNodeWithText("跳转").performClick()
        rule.onNodeWithText("Group 100").assertIsDisplayed()
        rule.onNodeWithText("100 / 151 · 定位").assertIsDisplayed()
        rule.runOnIdle { groups.value = emptyList(); media.value = emptyList() }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { groups.value = saved; media.value = listOf(folder, video) }
        rule.onNodeWithText("100 / 151 · 定位").assertIsDisplayed()
        rule.onNodeWithText("100 / 151 · 定位").performClick()
        rule.onNodeWithText("末项").performClick()
        rule.onNodeWithText("跳转").performClick()
        rule.onNodeWithText("Derived folder").assertIsDisplayed()
        rule.onNodeWithText("保存为 Group").assertIsDisplayed()
    }
}
