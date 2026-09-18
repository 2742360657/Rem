package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Long-press drag reordering for a fixed-height member list.
 *
 * The gesture only rearranges the local list the editor already owns, so nothing is written
 * until the user saves; the up/down buttons stay available as the precise path, and both
 * produce the same `sort_index` order.
 *
 * The arithmetic lives in [reorderStep] so it can be tested without a device.
 */
class DragReorderState internal constructor() {
    var draggingIndex by mutableStateOf<Int?>(null)
        internal set

    /**
     * How far the dragged row is displaced from its laid-out position. This is what the user sees,
     * after completed swaps have already moved its layout position.
     */
    var dragRowOffset by mutableFloatStateOf(0f)
        internal set

    /** Unconsumed distance; completed swaps already moved the row's layout position. */
    private var reorderOffset = 0f

    var rowHeightPx by mutableFloatStateOf(0f)
    var itemCount by mutableIntStateOf(0)

    /**
     * The finger's position in the list viewport, which is what the auto-scroll follows.
     *
     * It moves only when the finger moves: auto-scroll shifts the list, not the finger, and a
     * finger that keeps its screen position must keep its scroll speed.
     */
    var pointerY by mutableFloatStateOf(0f)
        internal set

    val dragging: Boolean get() = draggingIndex != null

    internal fun start(index: Int, pointerInViewport: Float) {
        draggingIndex = index
        dragRowOffset = 0f
        reorderOffset = 0f
        pointerY = pointerInViewport
    }

    fun drag(deltaY: Float, onMove: (from: Int, to: Int) -> Unit) {
        pointerY += deltaY
        applyDelta(deltaY, onMove)
    }

    /**
     * Auto-scroll is movement of the list underneath a stationary finger. It must feed the same
     * reorder arithmetic as a finger drag; a visual translation alone scrolls the dragged row out
     * of composition without ever changing the member order.
     */
    internal fun scrolledBy(delta: Float, onMove: (from: Int, to: Int) -> Unit) {
        applyDelta(delta, onMove)
    }

    private fun applyDelta(delta: Float, onMove: (from: Int, to: Int) -> Unit) {
        val index = draggingIndex ?: return
        val result = reorderStep(index, reorderOffset + delta, rowHeightPx, itemCount)
        result.moves.forEach { (from, to) -> onMove(from, to) }
        draggingIndex = result.index
        reorderOffset = result.offset
        dragRowOffset = result.offset
    }

    fun finish() {
        draggingIndex = null
        dragRowOffset = 0f
        reorderOffset = 0f
    }
}

/** Result of one drag update: which swaps to apply, where the finger is, what offset is left. */
data class ReorderStep(
    val moves: List<Pair<Int, Int>>,
    val index: Int,
    val offset: Float,
)

/**
 * Converts a vertical drag offset into a sequence of single-step swaps.
 *
 * Each completed row height swaps the dragged item with its neighbour and subtracts that row
 * height from the remaining offset, so a fast drag moves several positions and an edge simply
 * stops moving while the offset keeps being consumed.
 */
fun reorderStep(
    index: Int,
    offset: Float,
    rowHeight: Float,
    itemCount: Int,
): ReorderStep {
    if (rowHeight <= 0f || itemCount <= 1) return ReorderStep(emptyList(), index, offset)
    val moves = mutableListOf<Pair<Int, Int>>()
    var current = index.coerceIn(0, itemCount - 1)
    var remaining = offset
    while (remaining >= rowHeight && current < itemCount - 1) {
        moves += current to current + 1
        current += 1
        remaining -= rowHeight
    }
    while (remaining <= -rowHeight && current > 0) {
        moves += current to current - 1
        current -= 1
        remaining += rowHeight
    }
    return ReorderStep(moves, current, remaining)
}

/**
 * Pixels per second a list scrolls while a dragged row is held near an edge.
 *
 * Fast enough to cross a long member list without fighting the gesture, slow enough to stop on
 * the row the user wants.
 */
const val DRAG_AUTOSCROLL_PX_PER_SECOND = 1_200f

/**
 * How far from the list edge holding a dragged row starts scrolling, as a fraction of the list
 * height. A share of the viewport rather than a fixed distance keeps the ramp usable on a short
 * list and is clamped so a tall list does not scroll while the finger is still in its middle.
 */
const val DRAG_AUTOSCROLL_EDGE_FRACTION = 0.25f
const val DRAG_AUTOSCROLL_MAX_EDGE_PX = 160f

/**
 * Scroll speed for a dragged row at [pointerY] inside a list viewport of [viewportHeight].
 *
 * Zero in the middle of the list and in an empty viewport; positive scrolls towards later items
 * and the speed ramps up as the finger approaches the edge.
 */
fun dragAutoScrollSpeed(pointerY: Float, viewportHeight: Float): Float {
    if (viewportHeight <= 0f) return 0f
    val edge = minOf(DRAG_AUTOSCROLL_MAX_EDGE_PX, viewportHeight * DRAG_AUTOSCROLL_EDGE_FRACTION)
    val fromTop = pointerY
    val fromBottom = viewportHeight - pointerY
    return when {
        fromTop < edge -> -DRAG_AUTOSCROLL_PX_PER_SECOND * ((edge - fromTop) / edge).coerceIn(0f, 1f)
        fromBottom < edge -> DRAG_AUTOSCROLL_PX_PER_SECOND * ((edge - fromBottom) / edge).coerceIn(0f, 1f)
        else -> 0f
    }
}

/** Pixels of list scroll for one frame of auto-scroll at [speed]. */
fun dragAutoScrollDelta(speed: Float, elapsedMillis: Long): Float =
    speed * (elapsedMillis.coerceIn(0L, 64L) / 1_000f)

@Composable
fun rememberDragReorderState(rowHeight: Dp): DragReorderState {
    val state = remember { DragReorderState() }
    val density = LocalDensity.current
    state.rowHeightPx = with(density) { rowHeight.toPx() }
    return state
}

/**
 * Attaches the drag gesture to a handle (never to the whole row, so a list can still scroll).
 *
 * [rowViewportTop] reports where the row currently sits in the list viewport. It is read once, at
 * drag start, to place the finger in viewport coordinates. It must **not** be re-read while the
 * drag runs: auto-scroll moves the row under a stationary finger, so deriving the finger position
 * from the row's layout position would make the finger look like it is drifting back to the middle
 * of the list and the auto-scroll would slow itself down.
 */
@Composable
fun Modifier.dragReorderHandle(
    state: DragReorderState,
    index: Int,
    onMove: (from: Int, to: Int) -> Unit,
    rowViewportTop: () -> Float,
): Modifier {
    val currentIndex by rememberUpdatedState(index)
    val currentMove by rememberUpdatedState(onMove)
    val currentTop by rememberUpdatedState(rowViewportTop)
    return pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { offset -> state.start(currentIndex, currentTop() + offset.y) },
            onDragEnd = { state.finish() },
            onDragCancel = { state.finish() },
            onDrag = { change, dragAmount ->
                change.consume()
                state.drag(dragAmount.y, currentMove)
            },
        )
    }
}

/** Default row height for the editors; fixed so drag arithmetic stays predictable. */
val REORDER_ROW_HEIGHT: Dp = 76.dp

/**
 * One fixed-height member row with a drag handle, shared by the Group and Series editors.
 *
 * Fixed height is deliberate: the drag arithmetic swaps exactly one row per row height, so
 * the list cannot drift out of sync with the finger.
 *
 * A member list can be hundreds of rows long, so holding the dragged row near the top or bottom
 * edge scrolls the list; the scroll is applied to [listState] and the row follows it, which is
 * what makes a far-away position reachable without letting go.
 */
@Composable
fun ReorderableRow(
    index: Int,
    state: DragReorderState,
    onMove: (from: Int, to: Int) -> Unit,
    leading: @Composable () -> Unit,
    headline: @Composable () -> Unit,
    supporting: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    listState: LazyListState? = null,
) {
    val dragging = state.draggingIndex == index
    var rowTop by remember { mutableFloatStateOf(0f) }
    var handleOffsetInRow by remember { mutableFloatStateOf(0f) }

    if (dragging && listState != null) {
        LaunchedEffect(state, listState, index) {
            var previous = 0L
            while (state.draggingIndex == index) {
                val now = withFrameNanos { it }
                val elapsed = if (previous == 0L) 0L else (now - previous) / 1_000_000
                previous = now
                val info = listState.layoutInfo
                val speed = dragAutoScrollSpeed(
                    pointerY = state.pointerY,
                    viewportHeight = (info.viewportEndOffset - info.viewportStartOffset).toFloat(),
                )
                if (speed != 0f && elapsed > 0) {
                    // The distance the list actually consumed is what moves the member across
                    // rows; `scrollBy` returns 0 at the ends, so the order stops with the list.
                    val consumed = listState.scrollBy(dragAutoScrollDelta(speed, elapsed))
                    if (consumed != 0f) state.scrolledBy(consumed, onMove)
                }
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(REORDER_ROW_HEIGHT)
            .zIndex(if (dragging) 1f else 0f)
            .onGloballyPositioned { coordinates ->
                if (!dragging) rowTop = coordinates.positionInParent().y
            }
            .graphicsLayer {
                translationY = if (dragging) state.dragRowOffset else 0f
            }
            .background(
                if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
    ) {
        Icon(
            Icons.Rounded.DragHandle,
            contentDescription = "长按拖动排序",
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .onGloballyPositioned { coordinates ->
                    handleOffsetInRow = coordinates.positionInParent().y
                }
                .dragReorderHandle(
                    state = state,
                    index = index,
                    onMove = onMove,
                    rowViewportTop = { rowTop + handleOffsetInRow },
                ),
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { leading() }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            headline()
            supporting()
        }
        trailing()
    }
}
