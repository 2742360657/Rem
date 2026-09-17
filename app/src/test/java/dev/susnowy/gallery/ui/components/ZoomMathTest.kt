package dev.susnowy.gallery.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the behaviour a viewer needs to feel right: no panning while fitted, no dragging the
 * content off screen, zoom that follows the fingers, and a double tap that toggles.
 */
class ZoomMathTest {
    private val windowWidth = 1000f
    private val windowHeight = 2000f
    // A wide image: fitted height 1000, so it letterboxes vertically inside the window.
    private val contentWidth = 1000f
    private val contentHeight = 1000f

    private fun clamp(scale: Float, x: Float, y: Float) = ZoomMath.clamp(
        ZoomTransform(scale, x, y),
        windowWidth,
        windowHeight,
        contentWidth,
        contentHeight,
    )

    @Test
    fun fittedContentCannotBePannedAtAll() {
        val moved = ZoomMath.panBy(
            ZoomTransform.Fit,
            dx = 400f,
            dy = -400f,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )

        assertEquals(ZoomTransform.Fit, moved)
        assertFalse(moved.isZoomed)
    }

    @Test
    fun panningStopsAtTheContentEdge() {
        // At 2x the content is 2000x2000 inside a 1000x2000 window: 500px of horizontal room
        // and none vertically, because it still does not overflow the window vertically.
        val moved = clamp(scale = 2f, x = 5000f, y = 5000f)

        assertEquals(500f, moved.offsetX, 0.001f)
        assertEquals(0f, moved.offsetY, 0.001f)

        val other = clamp(scale = 2f, x = -5000f, y = -5000f)
        assertEquals(-500f, other.offsetX, 0.001f)
        assertEquals(0f, other.offsetY, 0.001f)
    }

    @Test
    fun pinchKeepsTheContentPointUnderTheFingers() {
        val start = ZoomTransform(scale = 2f, offsetX = 100f, offsetY = 0f)
        val focusX = 700f
        val focusY = 1000f
        // Content point under the focus before zooming.
        val contentX = (focusX - windowWidth / 2f - start.offsetX) / start.scale
        val contentY = (focusY - windowHeight / 2f - start.offsetY) / start.scale

        val zoomed = ZoomMath.zoomAround(
            transform = start,
            focusX = focusX,
            focusY = focusY,
            factor = 1.5f,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )

        assertEquals(3f, zoomed.scale, 0.001f)
        // The same content point must still sit under the focus (unless clamping intervened,
        // which this focus/zoom combination does not trigger).
        assertEquals(focusX, windowWidth / 2f + zoomed.offsetX + contentX * zoomed.scale, 0.5f)
        assertEquals(focusY, windowHeight / 2f + zoomed.offsetY + contentY * zoomed.scale, 0.5f)
    }

    @Test
    fun scaleIsClampedToTheAllowedRange() {
        val tooFar = ZoomMath.zoomAround(
            ZoomTransform.Fit,
            focusX = 500f,
            focusY = 1000f,
            factor = 100f,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
        assertEquals(ZoomMath.MAX_SCALE, tooFar.scale, 0.001f)

        val tooSmall = ZoomMath.zoomAround(
            ZoomTransform(scale = 2f),
            focusX = 500f,
            focusY = 1000f,
            factor = 0.01f,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
        assertEquals(ZoomMath.MIN_RENDERABLE_SCALE, tooSmall.scale, 0.001f)
        // Zooming back out to rest recentres, so no stray offset is left behind.
        assertEquals(0f, tooSmall.offsetX, 0.001f)
        assertEquals(0f, tooSmall.offsetY, 0.001f)
    }

    @Test
    fun doubleTapZoomsInThenBackToFit() {
        val zoomed = ZoomMath.doubleTapTarget(
            ZoomTransform.Fit,
            focusX = 250f,
            focusY = 1500f,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )

        assertEquals(ZoomMath.DOUBLE_TAP_SCALE, zoomed.scale, 0.001f)
        assertTrue(zoomed.isZoomed)
        // The tapped point is what stays in place.
        val contentX = (250f - windowWidth / 2f) / 1f
        assertEquals(250f, windowWidth / 2f + zoomed.offsetX + contentX * zoomed.scale, 0.5f)

        val back = ZoomMath.doubleTapTarget(
            zoomed,
            focusX = 250f,
            focusY = 1500f,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
        assertEquals(ZoomTransform.Fit, back)
    }

    @Test
    fun fittedSizePreservesAspectRatio() {
        // A tall image fits by height, leaving horizontal letterboxing.
        val (width, height) = ZoomMath.fittedSize(
            windowWidth = 1000f,
            windowHeight = 2000f,
            intrinsicWidth = 500f,
            intrinsicHeight = 2000f,
        )
        assertEquals(500f, width, 0.01f)
        assertEquals(2000f, height, 0.01f)

        // Unknown intrinsic size falls back to the window so clamping still behaves.
        val (fallbackWidth, fallbackHeight) = ZoomMath.fittedSize(1000f, 2000f, 0f, 0f)
        assertEquals(1000f, fallbackWidth, 0.01f)
        assertEquals(2000f, fallbackHeight, 0.01f)
    }

    // --- Width-filled placement: what a comic page needs -------------------------------

    @Test
    fun widthPlacementRestsSoTheContentFillsTheWindow() {
        // A page laid out narrower than the window is scaled up until it fills the width.
        val base = ZoomMath.baseTransform(
            placement = ZoomPlacement.WIDTH,
            windowWidth = 1000f,
            windowHeight = 2000f,
            contentWidth = 500f,
            contentHeight = 3000f,
        )

        assertEquals(2f, base.scale, 0.001f)
        assertEquals(0f, base.offsetX, 0.001f)
        // Content taller than the window opens at its top instead of its middle.
        assertEquals((2000f - 3000f * 2f) / 2f, base.offsetY, 0.001f)
        assertEquals(
            "静止比例就是该摆放方式的基准比例",
            ZoomMath.baseScale(ZoomPlacement.WIDTH, 1000f, 2000f, 500f, 3000f),
            base.scale,
            0.001f,
        )
    }

    @Test
    fun fittedContentStillRestsCentredAndUnzoomed() {
        val base = ZoomMath.baseTransform(
            placement = ZoomPlacement.FIT,
            windowWidth = 1000f,
            windowHeight = 2000f,
            contentWidth = 500f,
            contentHeight = 3000f,
        )

        assertEquals(ZoomTransform.Fit, base)
    }

    @Test
    fun widthPlacementContentShorterThanTheWindowIsCentred() {
        val base = ZoomMath.baseTransform(
            placement = ZoomPlacement.WIDTH,
            windowWidth = 1000f,
            windowHeight = 2000f,
            contentWidth = 1000f,
            contentHeight = 800f,
        )

        assertEquals(1f, base.scale, 0.001f)
        assertEquals(0f, base.offsetY, 0.001f)
    }

    @Test
    fun panningInsideALongPageStopsAtTheVisibleSliceEdges() {
        // A 4000px page shown one 2000px window at a time: at rest the top slice is visible.
        val page = 4000f
        val window = 2000f
        val rest = ZoomMath.baseTransform(
            placement = ZoomPlacement.WIDTH,
            windowWidth = 1000f,
            windowHeight = window,
            contentWidth = 1000f,
            contentHeight = page,
        )
        assertTrue("页面必须从顶部开始显示", rest.offsetY < 0f)

        // The whole page fits inside the visible slice window, so the pan range is the page
        // itself: the reader can reach both ends but never past them.
        val down = ZoomMath.panWithinWindow(rest, dy = 10_000f, windowHeight = window, contentHeight = page, visibleHeight = window)
        assertEquals((page - window) / 2f, down.offsetY, 0.001f)

        val up = ZoomMath.panWithinWindow(rest, dy = -10_000f, windowHeight = window, contentHeight = page, visibleHeight = window)
        assertEquals(-(page - window) / 2f, up.offsetY, 0.001f)
        assertEquals("回到顶端就是未放大的静止位置", rest.offsetY, up.offsetY, 0.001f)
    }

    @Test
    fun aShortVisibleSliceLeavesLessRoomToPan() {
        // Only 600px of the page are on screen: a pan may move the page by that slice, no more.
        val page = 4000f
        val window = 2000f
        val rest = ZoomMath.baseTransform(
            placement = ZoomPlacement.WIDTH,
            windowWidth = 1000f,
            windowHeight = window,
            contentWidth = 1000f,
            contentHeight = page,
        )

        val limit = (page - 600f) / 2f
        val down = ZoomMath.panWithinWindow(rest, dy = 10_000f, windowHeight = window, contentHeight = page, visibleHeight = 600f)
        val up = ZoomMath.panWithinWindow(rest, dy = -10_000f, windowHeight = window, contentHeight = page, visibleHeight = 600f)

        assertEquals(limit, down.offsetY, 0.001f)
        assertEquals(-limit, up.offsetY, 0.001f)
        assertTrue("可见切片越小，可移动范围越大", down.offsetY - up.offsetY > 0f)
    }

    @Test
    fun anUnknownVisibleSliceLeavesTheOffsetToOverflowClamping() {
        val transform = ZoomTransform(scale = 1f, offsetY = -50f)

        val moved = ZoomMath.panWithinWindow(
            transform,
            dy = 20f,
            windowHeight = 2000f,
            contentHeight = 4000f,
            visibleHeight = 0f,
        )

        assertEquals(-30f, moved.offsetY, 0.001f)
    }

    @Test
    fun pinchOutOfAWidthFilledPageRecentresAtTheRestingPlacement() {
        val page = 4000f
        val rest = ZoomMath.baseTransform(
            placement = ZoomPlacement.WIDTH,
            windowWidth = 1000f,
            windowHeight = 2000f,
            contentWidth = 1000f,
            contentHeight = page,
        )
        val zoomed = ZoomMath.zoomAround(
            transform = rest,
            focusX = 500f,
            focusY = 1500f,
            factor = 2f,
            windowWidth = 1000f,
            windowHeight = 2000f,
            contentWidth = 1000f,
            contentHeight = page,
            resting = rest,
        )
        assertTrue(zoomed.isZoomed)
        assertTrue("放大后允许上下移动", zoomed.offsetY != rest.offsetY)

        // Pinching closed below the resting scale must re-centre instead of leaving the page
        // stuck at whatever offset the zoomed state had.
        val backOut = ZoomMath.zoomAround(
            transform = zoomed,
            focusX = 500f,
            focusY = 1500f,
            factor = 0.3f,
            windowWidth = 1000f,
            windowHeight = 2000f,
            contentWidth = 1000f,
            contentHeight = page,
            resting = rest,
        )

        assertEquals(0.6f, backOut.scale, 0.001f)
        assertEquals("回到静止比例后页面重新从顶部开始", rest.offsetY, backOut.offsetY, 0.001f)
        assertEquals(0f, backOut.offsetX, 0.001f)
    }

    @Test
    fun aNarrowPageNeverShrinksBelowTheRenderableScale() {
        val clamped = ZoomMath.clamp(
            ZoomTransform(scale = 0.01f),
            windowWidth = 1000f,
            windowHeight = 2000f,
            contentWidth = 1000f,
            contentHeight = 2000f,
        )

        assertEquals(ZoomMath.MIN_RENDERABLE_SCALE, clamped.scale, 0.001f)
    }
}
