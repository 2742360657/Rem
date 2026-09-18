package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

/** Position refers to the caller's visible, filtered list, never the unfiltered source. */
@Composable
fun ListPositionButton(
    state: LazyListState,
    count: Int,
    modifier: Modifier = Modifier,
    itemIndices: List<Int>? = null,
) {
    val scope = rememberCoroutineScope()
    var showJump by remember(count) { mutableStateOf(false) }
    val current by remember(state, count, itemIndices) {
        derivedStateOf {
            val index = itemIndices?.binarySearch(state.firstVisibleItemIndex)?.let {
                if (it >= 0) it else -it - 1
            } ?: state.firstVisibleItemIndex
            index.coerceIn(0, (count - 1).coerceAtLeast(0))
        }
    }
    if (count <= 1) return
    TextButton(onClick = { showJump = true }, modifier = modifier) {
        Text("${current + 1} / $count · 定位")
    }
    if (showJump) {
        PositionJumpDialog(count, current, "项", { showJump = false }) { index ->
            showJump = false
            scope.launch { state.scrollToItem(itemIndices?.get(index) ?: index) }
        }
    }
}
