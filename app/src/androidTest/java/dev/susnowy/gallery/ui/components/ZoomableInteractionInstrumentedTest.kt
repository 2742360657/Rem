package dev.susnowy.gallery.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
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

    /**
     * The comic reader rests at "width filled" instead of "whole frame visible". These are the
     * gestures that decide whether continuous reading still scrolls and whether a zoomed page
     * can be dragged out of its slice.
     */
    private fun widthFilledContent(state: ZoomState, visibleHeight: Float) {
        rule.setContent {
            state.placement = ZoomPlacement.WIDTH
            state.visibleHeight = visibleHeight
            Zoomable(
                state = state,
                placement = ZoomPlacement.WIDTH,
                modifier = Modifier
                    .size(500.dp)
                    .testTag("page"),
            ) { modifier ->
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .height(4000.dp)
                        .background(Color.Red)
                        .onSizeChanged { size ->
                            state.intrinsic = Size(size.width.toFloat(), size.height.toFloat())
                        },
                )
            }
        }
    }

    @Test
    fun widthFilledPageDoesNotPanBeforeZooming() {
        val state = ZoomState()
        widthFilledContent(state, visibleHeight = 0f)

        rule.onNodeWithTag("page").performTouchInput {
            swipe(start = Offset(250f, 900f), end = Offset(250f, 100f), durationMillis = 250)
        }

        rule.runOnIdle {
            // While at rest the vertical drag belongs to the surrounding list.
            assertEquals(0f, state.transform.offsetX, 0.01f)
            assertEquals(1f, state.transform.scale, 0.01f)
            assertTrue("静止状态不应产生垂直位移", !state.transform.isZoomed)
        }
    }

    @Test
    fun doubleTapZoomsAWidthFilledPageAndBack() {
        val state = ZoomState()
        widthFilledContent(state, visibleHeight = 0f)

        rule.onNodeWithTag("page").performTouchInput { doubleClick(Offset(250f, 400f)) }
        rule.runOnIdle {
            assertTrue("双击应放大漫画页", state.isZoomed)
            assertEquals(ZoomMath.DOUBLE_TAP_SCALE, state.transform.scale, 0.01f)
        }

        rule.onNodeWithTag("page").performTouchInput { doubleClick(Offset(250f, 400f)) }
        rule.runOnIdle {
            assertTrue("再次双击应还原", !state.isZoomed)
            assertEquals(0f, state.transform.offsetX, 0.01f)
        }
    }

    @Test
    fun aZoomedPageCannotBeDraggedPastTheVisibleSlice() {
        val state = ZoomState()
        // Only a slice of the page is on screen, which is what bounds the pan.
        widthFilledContent(state, visibleHeight = 800f)

        rule.onNodeWithTag("page").performTouchInput { doubleClick(Offset(250f, 400f)) }
        rule.runOnIdle { assertTrue(state.isZoomed) }

        rule.onNodeWithTag("page").performTouchInput {
            swipe(start = Offset(250f, 200f), end = Offset(250f, 1600f), durationMillis = 250)
        }

        rule.runOnIdle {
            val scaledHeight = 4000f * state.transform.scale
            val limit = (scaledHeight - 800f) / 2f
            assertTrue(
                "垂直位移必须在可见切片范围内，实际 ${state.transform.offsetY}，上限 $limit",
                state.transform.offsetY in -limit - 0.5f..limit + 0.5f,
            )
        }
    }

    @Test
    fun widthMeasurementSurvivesAnUnrelatedRecomposition() {
        val state = ZoomState()
        val revision = mutableStateOf(0)
        rule.setContent {
            revision.value
            Zoomable(
                state = state,
                placement = ZoomPlacement.WIDTH,
                modifier = Modifier
                    .size(500.dp)
                    .testTag("page"),
            ) { modifier ->
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .height(900.dp)
                        .onSizeChanged { size ->
                            state.intrinsic = Size(size.width.toFloat(), size.height.toFloat())
                        },
                )
            }
        }

        var measured = Size.Zero
        rule.runOnIdle {
            measured = state.intrinsic
            assertTrue(measured.width > 0f && measured.height > 0f)
            revision.value++
        }
        rule.runOnIdle {
            assertEquals(measured, state.intrinsic)
        }
    }

    @Test
    fun tapAndLongPressEachDispatchOnce() {
        val state = ZoomState()
        var taps = 0
        var longPresses = 0
        rule.setContent {
            Zoomable(
                state = state,
                intrinsicSize = Size(1000f, 1000f),
                modifier = Modifier
                    .size(500.dp)
                    .testTag("viewer"),
                onTap = { taps++ },
                onLongPress = { longPresses++ },
            ) { modifier -> Box(modifier) }
        }

        rule.onNodeWithTag("viewer").performTouchInput { click() }
        // A registered double-tap handler intentionally delays single-tap dispatch until the
        // second-tap window closes. Do not start the long press inside that window.
        rule.waitUntil(2_000) { taps == 1 }
        rule.onNodeWithTag("viewer").performTouchInput { longClick() }
        rule.waitUntil(2_000) { longPresses == 1 }
        rule.runOnIdle {
            assertEquals(1, taps)
            assertEquals(1, longPresses)
        }
    }
}
