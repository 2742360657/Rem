package dev.susnowy.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.ui.screens.SeriesEditor
import dev.susnowy.gallery.ui.screens.TrashScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReadinessInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun openingMixedDirectoryVideoKeepsOuterSelection() {
        val saved = SavedStateHandle(mapOf("selected_item_id" to "folder"))
        val vm = GalleryViewModel(ApplicationProvider.getApplicationContext<GalleryApplication>(), saved)
        val folder = media("folder").copy(relativePath = "mixed", sourceKind = SourceKind.DIRECTORY, trashed = false)
        val video = media("video").copy(relativePath = "mixed/video.mp4", kind = MediaKind.VIDEO,
            sourceKind = SourceKind.FILE, trashed = false, displayTitle = "Fixture video")
        rule.setContent { MaterialTheme {
            dev.susnowy.gallery.ui.screens.MediaDetail(item = folder, libraryWorks = listOf(folder, video),
                viewModel = vm, onBack = {})
        } }
        rule.onNodeWithText("Fixture video").performClick()
        rule.onNodeWithText("媒体不在本机").assertIsDisplayed()
        rule.runOnIdle { assertEquals("folder", saved.get<String>("selected_item_id")) }
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("浏览全部图片").assertIsDisplayed()
    }

    private fun viewModel() = GalleryViewModel(
        ApplicationProvider.getApplicationContext<GalleryApplication>(), SavedStateHandle(),
    )

    private fun media(id: String, library: String = "A") = MediaItem(
        id = id, libraryId = library, relativePath = "$id.cbz", uri = "",
        kind = MediaKind.IMAGE_SET, sourceKind = SourceKind.ARCHIVE, displayTitle = id,
        trashed = true, deletedAt = 1,
    )

    @Test fun trashScopeIncludesOtherLibrariesOnlyWhenRequestedAndSwitchClearsConfirmation() {
        val vm = viewModel()
        val a = media("Alpha")
        val b = media("Beta", "B")
        val state = mutableStateOf(GalleryUiState(activeLibraryId = "A", allMedia = listOf(a, b)))
        rule.setContent { MaterialTheme { TrashScreen(state.value, vm) } }
        rule.onNodeWithText("Alpha").assertExists()
        rule.onNodeWithText("Beta").assertDoesNotExist()
        rule.onNodeWithContentDescription("永久删除").performClick()
        rule.onNodeWithText("永久删除？").assertExists()
        rule.runOnIdle { state.value = state.value.copy(activeLibraryId = "B") }
        rule.onNodeWithText("永久删除？").assertDoesNotExist()
        rule.onNodeWithText("Beta").assertExists()
        rule.onNodeWithText("全部库").performClick()
        rule.onNodeWithText("Alpha").assertExists()
        rule.onNodeWithText("Beta").assertExists()
    }

    @Test fun seriesDraftSurvivesRestorationAndBackDoesNotSaveSilently() {
        val vm = viewModel()
        val work = media("Chapter").copy(trashed = false)
        val series = MediaSeries("series", "A", "Test series", members = listOf(MediaSeriesMember(work.id)))
        var exited = 0
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            MaterialTheme { SeriesEditor(series, listOf(work), vm) { exited++ } }
        }
        rule.onNodeWithContentDescription("移出系列").performClick()
        rule.onNodeWithText("顺序或成员已修改").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("顺序或成员已修改").assertExists()
        rule.onNodeWithContentDescription("返回系列书架").performClick()
        rule.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, exited) }
        rule.onNodeWithText("继续编辑").performClick()
        rule.onNodeWithContentDescription("返回系列书架").performClick()
        rule.onNodeWithText("放弃修改").performClick()
        rule.runOnIdle { assertEquals(1, exited) }
    }
}
