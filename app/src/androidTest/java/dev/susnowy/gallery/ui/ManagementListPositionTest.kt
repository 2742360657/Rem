package dev.susnowy.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.ui.screens.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ManagementListPositionTest {
    @get:Rule val rule = createComposeRule()
    private fun vm() = GalleryViewModel(ApplicationProvider.getApplicationContext<GalleryApplication>(), SavedStateHandle())
    private fun jump(position: String) {
        rule.onNodeWithText("· 定位", substring = true).performClick()
        rule.onNodeWithText("位置（1–150）").performTextReplacement(position)
        rule.onNodeWithText("跳转").performClick()
    }

    @Test fun inboxSectionsKeepIndependentPositionsAcrossSwitchAndRestoration() {
        val entries = (1..150).map { DiscoveredEntry("fixture", "Entry $it", isDirectory = false,
            reason = DiscoveryReason.UNSUPPORTED_FILE) }
        val content = InboxContent(pendingDiscoveries = entries,
            ignoredDiscoveries = entries.map { it.copy(disposition = InboxDisposition.IGNORED) },
            handledDiscoveries = entries.map { it.copy(disposition = InboxDisposition.HANDLED) })
        val vm = vm()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MaterialTheme { InboxScreen(content, vm) } }
        jump("100")
        rule.onNodeWithText("100 / 150 · 定位").assertIsDisplayed()
        rule.onNodeWithText("已忽略 150").performScrollTo().performClick()
        jump("50")
        rule.onNodeWithText("已处理 150").performScrollTo().performClick()
        jump("75")
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("75 / 150 · 定位").assertIsDisplayed()
        rule.onNodeWithText("其他待判断 150").performScrollTo().performClick()
        rule.onNodeWithText("100 / 150 · 定位").assertIsDisplayed()
        rule.onNodeWithText("已忽略 150").performScrollTo().performClick()
        rule.onNodeWithText("50 / 150 · 定位").assertIsDisplayed()
    }

    private fun works() = (1..150).map { MediaItem("w$it", "fixture", "$it.cbz", "",
        MediaKind.IMAGE_SET, sourceKind = SourceKind.ARCHIVE, displayTitle = "Work $it", missingMedia = true) }

    @Test fun ignoredMediaSelectionSurvivesRestoration() {
        val content = InboxContent(ignoredMedia = works().take(3))
        val vm = vm()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { MaterialTheme { InboxScreen(content, vm) } }
        rule.onNodeWithText("Work 1").performClick()
        rule.onNodeWithText("撤销 1").assertIsEnabled()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("撤销 1").assertIsEnabled()
    }

    @Test fun groupJumpDoesNotDirtyOrReorderDraft() {
        val works = works()
        val group = MediaGroup("group", "fixture", "Group", members = works.map { MediaGroupMember(it.id) })
        val vm = vm()
        var exited = 0
        rule.setContent { MaterialTheme { GroupDetail(group, works, vm) { exited++ } } }
        jump("100")
        rule.onNodeWithText("Work 100").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithText("放弃未保存的修改？").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, exited) }
    }

    @Test fun seriesJumpDoesNotDirtyOrReorderDraft() {
        val works = works()
        val series = MediaSeries("series", "fixture", "Series", members = works.map { MediaSeriesMember(it.id) })
        val vm = vm()
        var exited = 0
        rule.setContent { MaterialTheme { SeriesEditor(series, works, vm) { exited++ } } }
        jump("100")
        rule.onNodeWithText("Work 100").assertIsDisplayed()
        rule.onNodeWithContentDescription("返回系列书架").performClick()
        rule.onNodeWithText("放弃未保存的修改？").assertDoesNotExist()
        rule.runOnIdle { assertEquals(1, exited) }
    }
}
