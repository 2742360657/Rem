package dev.susnowy.gallery.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.request.ImageRequest
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.ui.screens.DirectComicPage
import dev.susnowy.gallery.ui.screens.DecodedComicPage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComicPageRecoveryTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<GalleryApplication>()
    private val file = File(app.cacheDir, "comic-retry-${UUID.randomUUID()}.png")
    @After fun clean() { file.delete() }

    @Test fun directPageRetriesSameRequestAfterFileBecomesAvailable() {
        val request = ImageRequest.Builder(app).data(file).build()
        rule.setContent { MaterialTheme { DirectComicPage(request, "第 1 页") } }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        rule.onNodeWithText("重试").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("第 1 页").assertIsDisplayed()
        rule.onNodeWithText("重试").assertDoesNotExist()
    }

    @Test fun archiveRetryClearsErrorWhileLoadingAndCanSucceed() {
        var attempts = 0
        val next = CompletableDeferred<Bitmap?>()
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        rule.setContent { MaterialTheme { DecodedComicPage("page", "loaded") {
            attempts++
            if (attempts == 1) throw java.io.IOException("temporary")
            next.await()
        } } }
        rule.onNodeWithText("重试").performClick()
        rule.onNodeWithText("此页无法解码").assertDoesNotExist()
        rule.onNodeWithText("重试").assertDoesNotExist()
        rule.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        rule.runOnIdle { assertEquals(2, attempts); next.complete(bitmap) }
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("loaded").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription("loaded").assertIsDisplayed()
    }

    @Test fun changingPageClearsOldBitmapAndDisposalCancelsPendingRead() {
        val identity = mutableStateOf("first")
        val open = mutableStateOf(true)
        var cancelled = false
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        rule.setContent { MaterialTheme {
            if (open.value) DecodedComicPage(identity.value, "decoded") {
                if (identity.value == "first") bitmap
                else try { awaitCancellation() } finally { cancelled = true }
            }
        } }
        rule.onNodeWithContentDescription("decoded").assertIsDisplayed()
        rule.runOnIdle { identity.value = "second" }
        rule.onNodeWithContentDescription("decoded").assertDoesNotExist()
        rule.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        rule.runOnIdle { open.value = false }
        rule.runOnIdle { assertTrue(cancelled) }
        rule.onNodeWithText("此页无法解码").assertDoesNotExist()
    }
}
