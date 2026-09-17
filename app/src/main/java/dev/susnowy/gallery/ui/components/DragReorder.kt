package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
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

    fun start(index: Int) {
        draggingIndex = index
        dragOffset = 0f
    }

    fun drag(deltaY: Float, onMove: (from: Int, to: Int) -> Unit) {
        val index = draggingIndex ?: return
        val result = reorderStep(index, dragOffset + deltaY, rowHeightPx, itemCount)
        result.moves.forEach { (from, to) -> onMove(from, to) }
        draggingIndex = result.index
        dragOffset = result.offset
    }

    fun finish() {
        draggingIndex = null
        dragOffset = 0f
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

@Composable
fun rememberDragReorderState(rowHeight: Dp): DragReorderState {
    val state = remember { DragReorderState() }
    val density = LocalDensity.current
    state.rowHeightPx = with(density) { rowHeight.toPx() }
    return state
}

/**
 * Attaches the drag gesture to a handle (never to the whole row, so a list can still scroll).
 */
fun Modifier.dragReorderHandle(
    state: DragReorderState,
    index: Int,
    onMove: (from: Int, to: Int) -> Unit,
): Modifier = pointerInput(index) {
    detectDragGesturesAfterLongPress(
        onDragStart = { state.start(index) },
        onDragEnd = { state.finish() },
        onDragCancel = { state.finish() },
        onDrag = { change, dragAmount ->
            change.consume()
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
) {
    val dragging = state.draggingIndex == index
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(REORDER_ROW_HEIGHT)
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer { translationY = if (dragging) state.dragOffset else 0f }
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
                .dragReorderHandle(state, index, onMove),
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
