package dev.susnowy.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.susnowy.gallery.ui.components.PositionJumpDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PositionJumpInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun invalidPageCannotJumpAndLastPageUsesZeroBasedIndex() {
        var result = -1
        rule.setContent { MaterialTheme { PositionJumpDialog(120, 0, "页", {}, { result = it }) } }
        rule.onNodeWithText("位置（1–120）").performTextReplacement("121")
        rule.onNodeWithText("跳转").assertIsNotEnabled()
        rule.onNodeWithText("位置（1–120）").performTextReplacement("0")
        rule.onNodeWithText("跳转").assertIsNotEnabled()
        rule.onNodeWithText("末页").performClick()
        rule.onNodeWithText("跳转").performClick()
        rule.runOnIdle { assertEquals(119, result) }
    }

    @Test fun firstItemShortcutAndCancelAreExplicit() {
        var result = -1
        var cancelled = false
        rule.setContent { MaterialTheme { PositionJumpDialog(50, 24, "项", { cancelled = true }, { result = it }) } }
        rule.onNodeWithText("首项").performClick()
        rule.onNodeWithText("跳转").performClick()
        rule.runOnIdle { assertEquals(0, result) }
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertEquals(true, cancelled) }
    }
}
