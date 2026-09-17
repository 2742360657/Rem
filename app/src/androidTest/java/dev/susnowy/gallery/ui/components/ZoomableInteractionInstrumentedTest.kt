package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real gesture verification for the zoom container.
 *
 * The adb `input` command cannot produce a double tap or a pinch (each call starts a process,
 * so two taps are never close enough in time), which is exactly why these run as Compose
 * gesture tests instead of being eyeballed.
 */
class ZoomableInteractionInstrumentedTest {
    @get:Rule
    val rule = createComposeRule()

    private fun content(state: ZoomState) {
        rule.setContent {
            Zoomable(
                state = state,
                intrinsicSize = Size(1000f, 1000f),
                modifier = Modifier
                    .size(500.dp)
                    .testTag("viewer"),
            ) { modifier ->
                Box(modifier.background(Color.Red))
            }
        }
    }

    @Test
    fun doubleTapZoomsInAndBackOut() {
        val state = ZoomState()
        content(state)

        rule.onNodeWithTag("viewer").performTouchInput { doubleClick(Offset(120f, 120f)) }
        rule.runOnIdle {
            assertTrue("双击应放大", state.isZoomed)
            assertEquals(ZoomMath.DOUBLE_TAP_SCALE, state.transform.scale, 0.01f)
        }

        rule.onNodeWithTag("viewer").performTouchInput { doubleClick(Offset(120f, 120f)) }
        rule.runOnIdle {
            assertTrue("再次双击应还原", !state.isZoomed)
            assertEquals(0f, state.transform.offsetX, 0.01f)
            assertEquals(0f, state.transform.offsetY, 0.01f)
        }
    }

    @Test
    fun fittedContentDoesNotPan() {
        val state = ZoomState()
        content(state)

        rule.onNodeWithTag("viewer").performTouchInput {
            swipe(start = Offset(150f, 150f), end = Offset(420f, 380f), durationMillis = 200)
        }

        rule.runOnIdle {
            // While fitted the gesture must stay with the surrounding list, so nothing moves.
            assertEquals(0f, state.transform.offsetX, 0.01f)
            assertEquals(0f, state.transform.offsetY, 0.01f)
            assertEquals(1f, state.transform.scale, 0.01f)
        }
    }

    @Test
    fun pinchZoomsAroundTheFingersAndStaysClamped() {
        val state = ZoomState()
        content(state)

        rule.onNodeWithTag("viewer").performTouchInput {
            pinch(
                start0 = Offset(200f, 200f),
                end0 = Offset(120f, 120f),
                start1 = Offset(300f, 300f),
                end1 = Offset(380f, 380f),
                durationMillis = 250,
            )
        }

        rule.runOnIdle {
            assertTrue("双指张开应放大，实际 ${state.transform.scale}", state.transform.scale > 1f)
            assertTrue(state.transform.scale <= ZoomMath.MAX_SCALE + 0.01f)
        }
    }

    @Test
    fun zoomedContentPansButCannotLeaveTheViewport() {
        val state = ZoomState()
        content(state)

        rule.onNodeWithTag("viewer").performTouchInput { doubleClick(Offset(250f, 250f)) }
        rule.runOnIdle { assertTrue(state.isZoomed) }

        rule.onNodeWithTag("viewer").performTouchInput {
            swipe(start = Offset(250f, 250f), end = Offset(20f, 20f), durationMillis = 250)
        }

        rule.runOnIdle {
            val limitX = (1000f * state.transform.scale - 500f) / 2f
            val limitY = (1000f * state.transform.scale - 500f) / 2f
            assertTrue(
                "偏移应被钳制在 ±$limitX 内，实际 ${state.transform.offsetX}",
                kotlin.math.abs(state.transform.offsetX) <= limitX + 0.5f,
            )
            assertTrue(kotlin.math.abs(state.transform.offsetY) <= limitY + 0.5f)
        }
    }
}
