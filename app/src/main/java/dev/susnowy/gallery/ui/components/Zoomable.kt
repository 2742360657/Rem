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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * Zoom/pan state for one viewer or one page of a reader.
 *
 * The rendered [transform] is always the clamped one, so content can never be dragged off
 * screen and resting content cannot be panned at all. Tell it the intrinsic content size and
 * how the content should rest ([placement]); while the geometry is not known yet the window is
 * used and clamping stays conservative.
 */
@Stable
class ZoomState {
    /** What the user asked for; [transform] may be clamped further by the current geometry. */
    var requested by mutableStateOf(ZoomTransform.Fit)
        internal set

    var placement: ZoomPlacement = ZoomPlacement.FIT
        internal set

    internal var windowSize by mutableStateOf(Size.Zero)
    internal var intrinsic by mutableStateOf(Size.Zero)

    /**
     * How much of one page is inside the reading window right now.
     *
     * Continuous comic reading puts every page in a scrolling list, so this is what stops a pan
     * from dragging a long page into the blank space above or below the visible slice. Zero
     * means "not known" and the geometry falls back to overflow clamping.
     */
    internal var visibleHeight by mutableStateOf(0f)

    /** The rendered transform: the request, clamped to the geometry that is known now. */
    val transform: ZoomTransform
        get() {
            val content = contentSize()
            return ZoomMath.clamp(
                transform = requested,
                windowWidth = windowSize.width,
                windowHeight = windowSize.height,
                contentWidth = content.width,
                contentHeight = content.height,
                resting = restingTransform(content),
            )
        }

    val isZoomed: Boolean
        get() {
            // Resting is not always scale == 1: a page laid out narrower than the window rests at
            // a scale above 1 so it still fills the width, and that must not count as a zoom.
            val content = contentSize()
            val atRest = ZoomMath.baseScale(
                placement,
                windowSize.width,
                windowSize.height,
                content.width,
                content.height,
            )
            return transform.scale > atRest * ZoomTransform.FIT_TOLERANCE
        }

    fun reset() {
        requested = ZoomTransform.Fit
    }

    /** Where this content rests when it is not zoomed: fitted, or width-filled from the top. */
    private fun restingTransform(content: Size): ZoomTransform = ZoomMath.baseTransform(
        placement = placement,
        windowWidth = windowSize.width,
        windowHeight = windowSize.height,
        contentWidth = content.width,
        contentHeight = content.height,
    )

    /**
     * Content size in pixels: the measured natural size in [ZoomPlacement.WIDTH], the fitted box
     * in [ZoomPlacement.FIT]. Unknown intrinsic size falls back to the window so clamping stays
     * conservative before the first frame of content is measured.
     */
    fun contentSize(): Size {
        if (intrinsic.width > 0f && intrinsic.height > 0f) {
            if (placement == ZoomPlacement.WIDTH) return intrinsic
            val (width, height) = ZoomMath.fittedSize(
                windowWidth = windowSize.width,
                windowHeight = windowSize.height,
                intrinsicWidth = intrinsic.width,
                intrinsicHeight = intrinsic.height,
            )
            return Size(width, height)
        }
        return windowSize.takeIf { it.width > 0f && it.height > 0f } ?: Size.Zero
    }

    internal fun applyGesture(zoom: Float, pan: Offset, centroid: Offset) {
        val content = contentSize()
        val resting = restingTransform(content)
        var next = requested
        if (zoom != 1f && zoom > 0f) {
            next = ZoomMath.zoomAround(
                transform = transform,
                focusX = centroid.x,
                focusY = centroid.y,
                factor = zoom,
                windowWidth = windowSize.width,
                windowHeight = windowSize.height,
                contentWidth = content.width,
                contentHeight = content.height,
                resting = resting,
            )
        }
        if (pan != Offset.Zero) {
            next = if (placement == ZoomPlacement.WIDTH) {
                val vertical = ZoomMath.panWithinWindow(
                    transform = next,
                    dy = pan.y,
                    windowHeight = windowSize.height,
                    contentHeight = content.height,
                    visibleHeight = visibleHeight,
                )
                ZoomMath.panBy(
                    transform = vertical,
                    dx = pan.x,
                    dy = 0f,
                    windowWidth = windowSize.width,
                    windowHeight = windowSize.height,
                    contentWidth = content.width,
                    contentHeight = content.height,
                    resting = resting,
                )
            } else {
                ZoomMath.panBy(
                    transform = next,
                    dx = pan.x,
                    dy = pan.y,
                    windowWidth = windowSize.width,
                    windowHeight = windowSize.height,
                    contentWidth = content.width,
                    contentHeight = content.height,
                    resting = resting,
                )
            }
        }
        requested = next
    }

    internal fun applyDoubleTap(position: Offset) {
        val content = contentSize()
        requested = ZoomMath.doubleTapTarget(
            transform = transform,
            focusX = position.x,
            focusY = position.y,
            windowWidth = windowSize.width,
            windowHeight = windowSize.height,
            contentWidth = content.width,
            contentHeight = content.height,
            zoomed = isZoomed,
            resting = restingTransform(content),
        )
    }
}

@Composable
fun rememberZoomState(placement: ZoomPlacement = ZoomPlacement.FIT): ZoomState {
    val state = remember { ZoomState() }
    state.placement = placement
    return state
}

/**
 * A black-backed zoomable surface.
 *
 * Single-finger gestures are only taken over once the content is zoomed or a second finger is
 * down; while at rest, vertical drags stay with the surrounding list so continuous reading keeps
 * working. Zoom is anchored at the gesture centroid, and everything is clamped by [ZoomMath].
 *
 * In [ZoomPlacement.FIT] the content box is laid out at the fitted size, which is what makes
 * clamping exact. In [ZoomPlacement.WIDTH] the content keeps its own size and the resting
 * transform scales it to the window width, so a comic page fills the screen edge to edge.
 */
@Composable
fun Zoomable(
    state: ZoomState,
    modifier: Modifier = Modifier,
    intrinsicSize: Size? = null,
    placement: ZoomPlacement = ZoomPlacement.FIT,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onDoubleTap: ((Offset) -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    state.placement = placement
    // WIDTH content reports its measured aspect ratio from inside [content]. Do not erase that
    // measurement on every recomposition (for example while its visible slice changes during a
    // list scroll); onSizeChanged does not fire again when the size itself stayed the same.
    if (intrinsicSize != null) state.intrinsic = intrinsicSize

    val density = LocalDensity.current
    val contentModifier = when {
        placement == ZoomPlacement.WIDTH -> Modifier.fillMaxWidth()
        state.windowSize == Size.Zero -> Modifier.fillMaxSize()
        else -> {
            val fitted = state.contentSize()
            if (fitted.width <= 0f || fitted.height <= 0f) {
                Modifier.fillMaxSize()
            } else {
                Modifier.size(
                    width = with(density) { fitted.width.toDp() },
                    height = with(density) { fitted.height.toDp() },
                )
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .onSizeChanged { state.windowSize = Size(it.width.toFloat(), it.height.toFloat()) }
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
                    onLongPress = { onLongPress?.invoke() },
                    onDoubleTap = { position ->
                        state.applyDoubleTap(position)
                        onDoubleTap?.invoke(position)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = state.transform.scale
                    scaleY = state.transform.scale
                    translationX = state.transform.offsetX
                    translationY = state.transform.offsetY
                }
                .then(contentModifier),
        ) {
            content(contentModifier)
        }
    }
}
