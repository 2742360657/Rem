package dev.susnowy.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.importer.*
import dev.susnowy.gallery.ui.screens.SystemGalleryScreen
import org.junit.Rule
import org.junit.Test

class SystemGalleryPositionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun filtersKeepSelectionAndIndependentPositionsThroughLoadingAndRecreation() {
        val entries = (1..150).map {
            val type = if (it % 2 == 0) SystemMediaType.VIDEO else SystemMediaType.IMAGE
            SystemMediaEntry("file:///missing/fixture-$it", "Media $it",
                if (type == SystemMediaType.VIDEO) "video/mp4" else "image/jpeg",
                type, 1, (151 - it).toLong(), "Camera/", "Camera")
        }
        val state = mutableStateOf(GalleryUiState(systemMedia = entries, systemMediaAccess = SystemMediaAccess.FULL))
        val vm = GalleryViewModel(ApplicationProvider.getApplicationContext<GalleryApplication>(), SavedStateHandle())
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MaterialTheme { SystemGalleryScreen(state.value, vm, {}, {}, {}) } }
        rule.onNodeWithText("Media 1").performClick()
        rule.onNodeWithText("视频", useUnmergedTree = true).performClick()
        rule.onNodeWithText("全选当前 75 项").assertIsDisplayed()
        rule.onNodeWithText("导入 1 项").assertIsDisplayed()
        rule.onNodeWithText("Media 2").performClick()
        rule.onNodeWithText("1 / 75 · 定位").performClick()
        rule.onNodeWithText("位置（1–75）").performTextReplacement("40")
        rule.onNodeWithText("跳转").performClick()
        rule.onNodeWithText("Media 80").assertIsDisplayed()
        val position = rule.onAllNodes(hasText(" / 75 · 定位", substring = true))
            .fetchSemanticsNodes().single().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        rule.onNodeWithText("照片", useUnmergedTree = true).performClick()
        rule.onNodeWithText("Media 1").assertIsDisplayed()
        rule.onNodeWithText("导入 2 项").assertIsDisplayed()
        rule.onNodeWithText("视频", useUnmergedTree = true).performClick()
        rule.onNodeWithText(position).assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(systemMedia = emptyList(), systemMediaLoading = true) }
        rule.onNodeWithText("导入 2 项").assertIsNotEnabled()
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { state.value = state.value.copy(systemMedia = entries, systemMediaLoading = false) }
        rule.onNodeWithText(position).assertIsDisplayed()
        rule.onNodeWithText("导入 2 项").assertIsEnabled()
        rule.onNodeWithText("Media 80").assertIsDisplayed()
    }

    @Test fun sourceAndTypeFiltersIntersectAndCompletedRefreshPrunesUnavailableSelection() {
        val entries = (1..150).map {
            val type = if (it % 2 == 0) SystemMediaType.VIDEO else SystemMediaType.IMAGE
            SystemMediaEntry("file:///missing/fixture-$it", "Media $it", "image/jpeg",
                type, 1, (151 - it).toLong(), if (it <= 100) "Camera/" else "Download/", "")
        }
        val state = mutableStateOf(GalleryUiState(systemMedia = entries, systemMediaAccess = SystemMediaAccess.PARTIAL))
        val vm = GalleryViewModel(ApplicationProvider.getApplicationContext<GalleryApplication>(), SavedStateHandle())
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MaterialTheme { SystemGalleryScreen(state.value, vm, {}, {}, {}) } }
        val chip = SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected)
        rule.onNode(hasText("Camera/") and chip).performClick()
        rule.onNodeWithText("视频", useUnmergedTree = true).performClick()
        rule.onNodeWithText("全选当前 50 项").performClick()
        rule.onNodeWithText("导入 50 项").assertIsDisplayed()
        rule.onNode(hasText("Download/") and chip).performClick()
        rule.onNodeWithText("全选当前 25 项").assertIsDisplayed()
        rule.onNodeWithText("Media 102").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("全选当前 25 项").assertIsDisplayed()
        rule.onNodeWithText("导入 50 项").assertIsDisplayed()
        rule.runOnIdle { state.value = state.value.copy(systemMedia = entries.drop(100)) }
        rule.onNodeWithText("导入 0 项").assertIsNotEnabled()
    }
}
