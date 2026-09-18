package dev.susnowy.gallery.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import coil3.ImageLoader
import coil3.intercept.Interceptor
import coil3.request.ImageRequest
import dev.susnowy.gallery.GalleryApplication
import dev.susnowy.gallery.ui.screens.DirectComicPage
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ComicPageLayoutTest {
    @get:Rule val rule = createComposeRule()
    private val app = ApplicationProvider.getApplicationContext<GalleryApplication>()
    private val file = File(app.cacheDir, "comic-layout-${UUID.randomUUID()}.png")
    private var loader: ImageLoader? = null
    @After fun clean() { loader?.shutdown(); file.delete() }

    @Test fun portraitKeepsHeightWhilePixelDecodeIsDelayed() = verifyDelayedLayout(80, 160)
    @Test fun landscapeKeepsHeightWhilePixelDecodeIsDelayed() = verifyDelayedLayout(160, 80)
    @Test fun longPageKeepsHeightWhilePixelDecodeIsDelayed() = verifyDelayedLayout(80, 400)

    private fun verifyDelayedLayout(sourceWidth: Int, sourceHeight: Int) {
        val bitmap = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val imageLoader = ImageLoader.Builder(app).components {
            add(Interceptor { chain -> started.complete(Unit); gate.await(); chain.proceed() })
        }.build().also { loader = it }
        val request = ImageRequest.Builder(app).data(file).build()
        var width = 0
        var height = 0
        rule.setContent { MaterialTheme {
            Column(Modifier.width(100.dp).onSizeChanged { width = it.width; height = it.height }) {
                DirectComicPage(request, "page", imageLoader)
            }
        } }
        rule.waitUntil(5_000) {
            started.isCompleted && width > 0 && kotlin.math.abs(height - width * sourceHeight / sourceWidth) <= 1
        }
        val before = height
        rule.onNode(hasProgressBarRangeInfo(androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()
        gate.complete(Unit)
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("page").fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { assertEquals(before, height) }
        rule.onNodeWithContentDescription("page").assertIsDisplayed()
    }
}
