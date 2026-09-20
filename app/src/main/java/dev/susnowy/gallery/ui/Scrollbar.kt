package dev.susnowy.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What the scrollbar shows beside the thumb while it is being dragged.
 *
 * [secondary] is the place for an album file and empty for a collection file, so the bubble keeps
 * the same two-line shape everywhere instead of changing height as the list scrolls.
 */
data class ScrollLabel(val primary: String, val secondary: String = "")

/**
 * The right-edge drag slider the rules require on long lists.
 *
 * It reads the lazy layout's own scroll state, so it stays correct when rows are recycled, when
 * the grid changes column count on rotation, and when a filter shortens the list. Tapping or
 * dragging the track jumps to that fraction of the list and shows what sits under the thumb,
 * which is the whole point of the control: on a ten-thousand-file album, aiming at a date is the
 * only usable way to reach it.
 *
 * The track and the thumb are drawn in pixels because the thumb's travel depends on the measured
 * height, which is not known until layout.
 */
@Composable
fun Scrollbar(
    fraction: Float,
    onJump: (Float) -> Unit,
    labelAt: (Float) -> ScrollLabel,
    modifier: Modifier = Modifier,
) {
    var track by remember { mutableStateOf(IntSize.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(ScrollLabel("")) }
    var pressedY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    val insetPx = with(density) { VERTICAL_INSET.toPx() }
    val thumbWidthPx = with(density) { THUMB_WIDTH.toPx() }
    val usableHeight = (track.height - insetPx * 2).coerceAtLeast(0f)
    val thumbHeight = with(density) { THUMB_MIN_HEIGHT.toPx() }.coerceAtMost(usableHeight)
    val travel = (usableHeight - thumbHeight).coerceAtLeast(0f)
    val thumbTop = insetPx + travel * fraction.coerceIn(0f, 1f)

    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val thumbColor = MaterialTheme.colorScheme.primary

    /**
     * The label has to disappear after the finger leaves, not a fixed moment after it arrived.
     * Keying this on [dragging] alone started one timer at drag start, so a drag longer than
     * [LABEL_LINGER_MS] hid its own label while the finger was still moving. Keying on the label
     * too restarts the timer on every movement, which is the behaviour the control is for.
     */
    LaunchedEffect(dragging, label) {
        if (dragging) {
            delay(LABEL_LINGER_MS)
            dragging = false
        }
    }

    /** [y] is relative to the track's top edge. */
    fun reportAt(y: Float) {
        if (usableHeight <= 0f) return
        // Aim with the thumb's centre so the label describes what the thumb actually covers.
        val target = ((y - insetPx - thumbHeight / 2f) / travel).coerceIn(0f, 1f)
        label = labelAt(target)
        onJump(target)
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(TRACK_WIDTH)
            .onSizeChanged { track = it }
            .pointerInput(usableHeight, travel) {
                detectTapGestures { offset ->
                    dragging = true
                    reportAt(offset.y)
                }
            }
            .pointerInput(usableHeight, travel) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        pressedY = offset.y
                        reportAt(offset.y)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onDrag = { change, _ ->
                        pressedY += change.position.y - change.previousPosition.y
                        reportAt(pressedY)
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .drawBehind {
                    if (travel <= 0f) return@drawBehind
                    val centre = size.width / 2f
                    drawLine(
                        color = trackColor,
                        start = Offset(centre, insetPx),
                        end = Offset(centre, size.height - insetPx),
                        strokeWidth = LINE_WIDTH_PX,
                    )
                    drawRoundRect(
                        color = thumbColor,
                        topLeft = Offset(centre - thumbWidthPx / 2f, thumbTop),
                        size = Size(thumbWidthPx, thumbHeight),
                        cornerRadius = CornerRadius(thumbWidthPx / 2f),
                    )
                },
        )
        if (dragging && label.primary.isNotEmpty()) {
            Surface(
                // The track is only TRACK_WIDTH wide, and a Box measures its children against its
                // own constraints — so the bubble has to be allowed to exceed that width, or every
                // label is squeezed to its first character. `TopEnd` then grows it leftwards over
                // the list instead of off the screen edge.
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 8.dp)
                    .wrapContentWidth(unbounded = true),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                tonalElevation = 3.dp,
            ) {
                // Two stacked, horizontally centred lines: the date stays on top and the place
                // underneath, instead of one long string that runs off the edge of the screen.
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = label.primary,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                    if (label.secondary.isNotEmpty()) {
                        Text(
                            text = label.secondary,
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Maps a lazy grid's scroll position to the 0..1 fraction the scrollbar draws.
 *
 * The thumb tracks the first visible item rather than a pixel offset, so it does not drift when
 * rows of different heights are recycled.
 */
fun gridFraction(state: LazyGridState, itemCount: Int): Float {
    if (itemCount <= 1 || state.layoutInfo.visibleItemsInfo.isEmpty()) return 0f
    return (state.firstVisibleItemIndex.toFloat() / (itemCount - 1)).coerceIn(0f, 1f)
}

/** The list counterpart of [gridFraction], including the partially scrolled row. */
fun listFraction(state: LazyListState, itemCount: Int): Float {
    if (itemCount <= 1 || state.layoutInfo.visibleItemsInfo.isEmpty()) return 0f
    val rowHeight = state.layoutInfo.visibleItemsInfo.first().size
    if (rowHeight <= 0) return 0f
    val first = state.firstVisibleItemIndex.toFloat() +
        state.firstVisibleItemScrollOffset.toFloat() / rowHeight
    return (first / (itemCount - 1)).coerceIn(0f, 1f)
}

/** Scrolls a grid so the target fraction of the list is at the top. */
suspend fun jumpGrid(state: LazyGridState, itemCount: Int, fraction: Float) {
    if (itemCount <= 1) return
    state.scrollToItem(targetIndex(itemCount, fraction))
}

/** Scrolls a list so the target fraction of the list is at the top. */
suspend fun jumpList(state: LazyListState, itemCount: Int, fraction: Float) {
    if (itemCount <= 1) return
    state.scrollToItem(targetIndex(itemCount, fraction))
}

/** The item a fraction points at. Shared so the label and the jump always agree. */
fun targetIndex(itemCount: Int, fraction: Float): Int =
    (fraction.coerceIn(0f, 1f) * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)

/** The scrollbar's width, so list content can reserve room for it instead of sitting under it. */
internal val TRACK_WIDTH = 34.dp
private val THUMB_WIDTH = 8.dp
private val THUMB_MIN_HEIGHT = 56.dp
private val VERTICAL_INSET = 12.dp
private const val LINE_WIDTH_PX = 4f
private const val LABEL_LINGER_MS = 900L
