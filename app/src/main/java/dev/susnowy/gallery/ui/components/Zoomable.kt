package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged

/**
 * Zoom/pan state for one viewer.
 *
 * The transform is always clamped, so the content can never be dragged off screen and a fitted
 * image cannot be panned at all. Tell it the intrinsic content size (when known); otherwise the
 * viewport is used and clamping stays conservative.
 */
@Stable
class ZoomState {
    var transform by mutableStateOf(ZoomTransform.Fit)
        internal set

    internal var viewport by mutableStateOf(Size.Zero)
    internal var intrinsic by mutableStateOf(Size.Zero)

    val isZoomed: Boolean get() = transform.isZoomed

    /** Content size when fitted into the current viewport. */
    internal fun fittedSize(): Size {
        val (width, height) = ZoomMath.fittedSize(
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            intrinsicWidth = intrinsic.width,
            intrinsicHeight = intrinsic.height,
        )
        return Size(width, height)
    }

    fun reset() {
        transform = ZoomTransform.Fit
    }

    internal fun applyGesture(zoom: Float, pan: Offset, centroid: Offset) {
        val fitted = fittedSize()
        var next = transform
        if (zoom != 1f && zoom > 0f) {
            next = ZoomMath.zoomAround(
                transform = next,
                focusX = centroid.x,
                focusY = centroid.y,
                factor = zoom,
                viewportWidth = viewport.width,
                viewportHeight = viewport.height,
                contentWidth = fitted.width,
                contentHeight = fitted.height,
            )
        }
        if (pan != Offset.Zero) {
            next = ZoomMath.panBy(
                transform = next,
                dx = pan.x,
                dy = pan.y,
                viewportWidth = viewport.width,
                viewportHeight = viewport.height,
                contentWidth = fitted.width,
                contentHeight = fitted.height,
            )
        }
        transform = next
    }

    internal fun applyDoubleTap(position: Offset) {
        val fitted = fittedSize()
        transform = ZoomMath.doubleTapTarget(
            transform = transform,
            focusX = position.x,
            focusY = position.y,
            viewportWidth = viewport.width,
            viewportHeight = viewport.height,
            contentWidth = fitted.width,
            contentHeight = fitted.height,
        )
    }
}

@Composable
fun rememberZoomState(): ZoomState = remember { ZoomState() }

/**
 * A black-backed zoomable surface.
 *
 * Single-finger gestures are only taken over once the content is zoomed or a second finger is
 * down; while fitted, vertical drags stay with the surrounding list so continuous reading keeps
 * working. Zoom is anchored at the gesture centroid, and everything is clamped by [ZoomMath].
 */
@Composable
fun Zoomable(
    state: ZoomState,
    modifier: Modifier = Modifier,
    intrinsicSize: Size? = null,
    onTap: (() -> Unit)? = null,
    onDoubleTap: ((Offset) -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    state.intrinsic = intrinsicSize ?: Size.Zero
    val fitted = state.fittedSize()

    val density = LocalDensity.current
    val sizedModifier = if (state.viewport == Size.Zero || fitted.width <= 0f || fitted.height <= 0f) {
        Modifier.fillMaxSize()
    } else {
        Modifier.size(
            width = with(density) { fitted.width.toDp() },
            height = with(density) { fitted.height.toDp() },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { state.viewport = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val takeOver = pressed >= 2 || state.isZoomed
                        if (takeOver) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            state.applyGesture(zoom, pan, centroid)
                            event.changes.forEach { change ->
                                if (change.positionChanged()) change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap?.invoke() },
                    onDoubleTap = { position ->
                        state.applyDoubleTap(position)
                        onDoubleTap?.invoke(position)
                    },
                )
            },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = state.transform.scale
                    scaleY = state.transform.scale
                    translationX = state.transform.offsetX
                    translationY = state.transform.offsetY
                }
                .then(sizedModifier),
        ) {
            content(Modifier.fillMaxSize())
        }
    }
}
