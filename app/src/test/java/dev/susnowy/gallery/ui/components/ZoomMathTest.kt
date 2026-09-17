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
    private val viewportWidth = 1000f
    private val viewportHeight = 2000f
    // A wide image: fitted height 1000, so it letterboxes vertically inside the viewport.
    private val contentWidth = 1000f
    private val contentHeight = 1000f

    private fun clamp(scale: Float, x: Float, y: Float) = ZoomMath.clamp(
        ZoomTransform(scale, x, y),
        viewportWidth,
        viewportHeight,
        contentWidth,
        contentHeight,
    )

    @Test
    fun fittedContentCannotBePannedAtAll() {
        val moved = ZoomMath.panBy(
            ZoomTransform.Fit,
            dx = 400f,
            dy = -400f,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )

        assertEquals(ZoomTransform.Fit, moved)
        assertFalse(moved.isZoomed)
    }

    @Test
    fun panningStopsAtTheContentEdge() {
        // At 2x the content is 2000x2000 inside a 1000x2000 viewport: it may move 500px
        // horizontally and 0px vertically.
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
        val focusY = 900f
        // Content point under the focus before zooming.
        val contentX = (focusX - viewportWidth / 2f - start.offsetX) / start.scale
        val contentY = (focusY - viewportHeight / 2f - start.offsetY) / start.scale

        val zoomed = ZoomMath.zoomAround(
            transform = start,
            focusX = focusX,
            focusY = focusY,
            factor = 1.5f,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )

        assertEquals(3f, zoomed.scale, 0.001f)
        // The same content point must still sit under the focus (unless clamping intervened,
        // which this focus/zoom combination does not trigger).
        assertEquals(focusX, viewportWidth / 2f + zoomed.offsetX + contentX * zoomed.scale, 0.5f)
        assertEquals(focusY, viewportHeight / 2f + zoomed.offsetY + contentY * zoomed.scale, 0.5f)
    }

    @Test
    fun scaleIsClampedToTheAllowedRange() {
        val tooFar = ZoomMath.zoomAround(
            ZoomTransform.Fit,
            focusX = 500f,
            focusY = 1000f,
            factor = 100f,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
        assertEquals(ZoomMath.MAX_SCALE, tooFar.scale, 0.001f)

        val tooSmall = ZoomMath.zoomAround(
            ZoomTransform(scale = 2f),
            focusX = 500f,
            focusY = 1000f,
            factor = 0.01f,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
        assertEquals(ZoomMath.MIN_SCALE, tooSmall.scale, 0.001f)
        // Zooming back out to fit recentres, so no stray offset is left behind.
        assertEquals(0f, tooSmall.offsetX, 0.001f)
        assertEquals(0f, tooSmall.offsetY, 0.001f)
    }

    @Test
    fun doubleTapZoomsInThenBackToFit() {
        val zoomed = ZoomMath.doubleTapTarget(
            ZoomTransform.Fit,
            focusX = 250f,
            focusY = 1500f,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )

        assertEquals(ZoomMath.DOUBLE_TAP_SCALE, zoomed.scale, 0.001f)
        assertTrue(zoomed.isZoomed)
        // The tapped point is what stays in place.
        val contentX = (250f - viewportWidth / 2f) / 1f
        assertEquals(250f, viewportWidth / 2f + zoomed.offsetX + contentX * zoomed.scale, 0.5f)

        val back = ZoomMath.doubleTapTarget(
            zoomed,
            focusX = 250f,
            focusY = 1500f,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
        )
        assertEquals(ZoomTransform.Fit, back)
    }

    @Test
    fun fittedSizePreservesAspectRatio() {
        // A tall image fits by height, leaving horizontal letterboxing.
        val (width, height) = ZoomMath.fittedSize(
            viewportWidth = 1000f,
            viewportHeight = 2000f,
            intrinsicWidth = 500f,
            intrinsicHeight = 2000f,
        )
        assertEquals(500f, width, 0.01f)
        assertEquals(2000f, height, 0.01f)

        // Unknown intrinsic size falls back to the viewport so clamping still behaves.
        val (fallbackWidth, fallbackHeight) = ZoomMath.fittedSize(1000f, 2000f, 0f, 0f)
        assertEquals(1000f, fallbackWidth, 0.01f)
        assertEquals(2000f, fallbackHeight, 0.01f)
    }
}
