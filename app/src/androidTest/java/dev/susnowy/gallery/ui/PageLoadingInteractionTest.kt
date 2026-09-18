package dev.susnowy.gallery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.susnowy.gallery.media.ImagePage
import dev.susnowy.gallery.ui.components.*
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PageLoadingInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun failedReadCanRetryWithoutLeavingTheReader() {
        var attempts = 0
        rule.setContent { MaterialTheme {
            val load = rememberPageLoad("fixture") {
                attempts++
                if (attempts == 1) throw SecurityException("权限失效")
                listOf(ImagePage("ready"))
            }
            if (load.result?.getOrNull()?.isNotEmpty() == true) Text("页面已就绪")
            else PageLoadingStatus(load, true, {})
        } }
        rule.onNodeWithText("读取失败：权限失效").assertIsDisplayed()
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("页面已就绪").assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, attempts) }
    }

    @Test fun cancellingOpenDisposesThePendingLoad() {
        val opened = mutableStateOf(true)
        var cancelled = false
        rule.setContent { MaterialTheme {
            if (opened.value) {
                val load = rememberPageLoad("slow") {
                    try { awaitCancellation() } finally { cancelled = true }
                }
                PageLoadingStatus(load, false, { opened.value = false })
            } else Text("已返回")
        } }
        rule.onNodeWithText("正在读取图片页列表").assertIsDisplayed()
        rule.onNodeWithText("取消打开").performClick()
        rule.onNodeWithText("已返回").assertIsDisplayed()
        rule.runOnIdle { assertTrue(cancelled) }
    }
}
