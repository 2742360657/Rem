package dev.susnowy.gallery.ui

import android.graphics.Bitmap
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.media.ImagePage
import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.ui.screens.ImageSetReader
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderPositionTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<GalleryApplication>()
    private val file = File(app.cacheDir, "reader-position-fixture.png")
    @After fun clean() { file.delete() }

    private fun pages(): List<ImagePage> {
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return (0 until 20).map { ImagePage("$it.png", file.toURI().toString(), relativePath = "$it.png") }
    }

    @Test fun initializedReaderPreservesExactListPositionInsteadOfReapplyingStartPage() {
        val pages = pages()
        val vm = GalleryViewModel(app, SavedStateHandle())
        val restoration = StateRestorationTester(rule)
        val jump = mutableStateOf<Pair<Int, Int>?>(null)
        lateinit var list: LazyListState
        var reported = -1
        restoration.setContent { MaterialTheme {
            list = rememberLazyListState(8, 37)
            ImageSetReader(work(), pages, vm, {}, { reported = it }, { _, _ -> }, {},
                listState = list, jumpRequest = jump.value, positionInitialized = true,
                onPositionInitialized = {}, onFinishChapter = {})
        } }
        rule.waitUntil(5_000) { reported >= 0 }
        rule.runOnIdle {
            assertEquals(8, list.firstVisibleItemIndex)
            assertEquals(37, list.firstVisibleItemScrollOffset)
            assertEquals(8, reported)
        }
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle {
            assertEquals(8, list.firstVisibleItemIndex)
            assertEquals(37, list.firstVisibleItemScrollOffset)
            jump.value = 8 to 1
        }
        rule.waitUntil(5_000) { list.firstVisibleItemScrollOffset == 0 }
        rule.runOnIdle { assertEquals(8, list.firstVisibleItemIndex) }
    }

    @Test fun jumpToShortLastPageSettlesWithoutCompletingOrAdvancing() {
        val pages = pages()
        val vm = GalleryViewModel(app, SavedStateHandle())
        val jump = mutableStateOf<Pair<Int, Int>?>(null)
        val initialized = mutableStateOf(false)
        lateinit var list: LazyListState
        var reported = -1
        var completed = false
        var advanced = false
        rule.setContent { MaterialTheme {
            list = rememberLazyListState()
            ImageSetReader(work(), pages, vm, {}, { reported = it }, { _, done -> completed = completed || done }, {},
                nextChapter = work().copy(id = "next"), autoAdvance = true,
                listState = list, jumpRequest = jump.value, positionInitialized = initialized.value,
                onPositionInitialized = { initialized.value = true }, onFinishChapter = {},
                onOpenNextChapter = { advanced = true })
        } }
        rule.waitUntil(5_000) { initialized.value && reported == 0 }
        rule.runOnIdle { jump.value = 19 to 1 }
        rule.waitUntil(5_000) { reported > 0 && reported == list.firstVisibleItemIndex }
        rule.runOnIdle {
            assertTrue("Visible indices: ${list.layoutInfo.visibleItemsInfo.map { it.index }}; reported=$reported",
                list.layoutInfo.visibleItemsInfo.any { it.index == 19 })
            assertFalse(completed)
            assertFalse(advanced)
        }
        // A later real forward gesture must still finish and hand over; a jump itself must not.
        rule.runOnIdle { jump.value = 12 to 2 }
        rule.waitUntil(5_000) { reported == 12 }
        repeat(8) {
            if (!advanced) rule.onRoot().performTouchInput { swipeUp(durationMillis = 400) }
        }
        rule.waitUntil(5_000) { completed && advanced }
        // Let the fling/overscroll animation drain under the Compose clock before Activity teardown.
        rule.mainClock.advanceTimeBy(2_000)
        rule.waitForIdle()
    }

    private fun work() = MediaItem("reader", "fixture", "pages", "", MediaKind.IMAGE_SET,
        sourceKind = SourceKind.DIRECTORY, displayTitle = "Reader fixture")
}
