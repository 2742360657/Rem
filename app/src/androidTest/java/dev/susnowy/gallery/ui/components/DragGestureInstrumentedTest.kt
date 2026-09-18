package dev.susnowy.gallery.ui.components


import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DragGestureInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test(timeout = 30000)
    fun dragSurvivesMultipleSwapsAndActivatesEdgeScroll() {
        val order = mutableStateOf((0 until 40).toList())
        lateinit var observedDrag: DragReorderState
        lateinit var observedList: LazyListState
        rule.setContent {
            MaterialTheme {
                val drag = rememberDragReorderState(REORDER_ROW_HEIGHT)
                observedDrag = drag
                drag.itemCount = order.value.size
                val list = rememberLazyListState()
                observedList = list
                LazyColumn(state = list, modifier = Modifier.height(400.dp).testTag("list")) {
                    itemsIndexed(order.value, key = { _, id -> id }) { index, id ->
                        ReorderableRow(index, drag, { from, to ->
                            order.value = order.value.toMutableList().apply { add(to, removeAt(from)) }
                        }, {}, { Text("Row $id") }, {}, {}, {}, list)
                    }
                }
            }
        }
        val handle = rule.onAllNodesWithContentDescription("长按拖动排序", useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val list = rule.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot
        val start = handle.center - list.topLeft
        val end = Offset(start.x, list.height - 8)
        rule.onNodeWithTag("list").performTouchInput {
            down(start)
            advanceEventTime(1500)
            repeat(12) { step ->
                moveTo(start + (end - start) * ((step + 1) / 12f), delayMillis = 32)
            }
            repeat(45) { moveTo(end, delayMillis = 32) }
            up()
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue("pointer=${observedDrag.pointerY} order=${order.value}", order.value.indexOf(0) >= 4)
            assertTrue("The edge must scroll the list", observedList.firstVisibleItemIndex > 0 ||
                observedList.firstVisibleItemScrollOffset > 0)
        }
    }
}
