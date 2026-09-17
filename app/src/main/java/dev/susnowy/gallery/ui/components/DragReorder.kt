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
    var dragOffset by mutableFloatStateOf(0f)
        internal set
    var rowHeightPx by mutableFloatStateOf(0f)
    var itemCount by mutableIntStateOf(0)

    /** Pixels the list has been scrolled by the drag itself, so the row can follow it. */
    var scrollOffset by mutableFloatStateOf(0f)
        internal set

    /** The finger's position in the list viewport, which is what the auto-scroll follows. */
    var pointerY by mutableFloatStateOf(0f)
        internal set

    val dragging: Boolean get() = draggingIndex != null

    /** Visual displacement of the dragged row from its laid-out position. */
    val rowOffset: Float get() = dragOffset + scrollOffset

    internal fun start(index: Int, pointerInViewport: Float) {
        draggingIndex = index
        dragOffset = 0f
        scrollOffset = 0f
        pointerY = pointerInViewport
    }

    /** Auto-scroll moved the list under the finger, so the dragged row moves with it. */
    internal fun scrolledBy(delta: Float) {
        scrollOffset += delta
    }

    fun drag(deltaY: Float, onMove: (from: Int, to: Int) -> Unit) {
        val index = draggingIndex ?: return
        dragOffset += deltaY
        pointerY += deltaY
        val result = reorderStep(index, dragOffset, rowHeightPx, itemCount)
        result.moves.forEach { (from, to) -> onMove(from, to) }
        draggingIndex = result.index
        dragOffset = result.offset
    }

    fun finish() {
        draggingIndex = null
        dragOffset = 0f
        scrollOffset = 0f
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
 * [pointerInViewport] converts a position inside the handle into list-viewport coordinates, which
 * is what the auto-scroll needs to know whether the finger is held near an edge of the list.
 */
fun Modifier.dragReorderHandle(
    state: DragReorderState,
    index: Int,
    onMove: (from: Int, to: Int) -> Unit,
    pointerInViewport: (Float) -> Float,
): Modifier = pointerInput(index) {
    detectDragGesturesAfterLongPress(
        onDragStart = { offset -> state.start(index, pointerInViewport(offset.y)) },
        onDragEnd = { state.finish() },
        onDragCancel = { state.finish() },
        onDrag = { change, dragAmount ->
            change.consume()
            state.pointerY = pointerInViewport(change.position.y)
            state.drag(dragAmount.y, onMove)
        },
    )
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
                    val consumed = listState.scrollBy(dragAutoScrollDelta(speed, elapsed))
                    if (consumed != 0f) state.scrolledBy(consumed)
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
                translationY = if (dragging) state.rowOffset else 0f
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
                    pointerInViewport = { localY -> rowTop + handleOffsetInRow + localY },
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
