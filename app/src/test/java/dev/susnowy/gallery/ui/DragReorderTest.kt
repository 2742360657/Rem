package dev.susnowy.gallery.ui

import dev.susnowy.gallery.ui.components.DRAG_AUTOSCROLL_PX_PER_SECOND
import dev.susnowy.gallery.ui.components.DragReorderState
import dev.susnowy.gallery.ui.components.dragAutoScrollDelta
import dev.susnowy.gallery.ui.components.dragAutoScrollSpeed
import dev.susnowy.gallery.ui.components.reorderStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DragReorderTest {
    private fun moves(index: Int, offset: Float, rowHeight: Float = 100f, count: Int = 5) =
        reorderStep(index, offset, rowHeight, count)

    @Test
    fun movingDownOneRowSwapsWithTheNextItem() {
        val result = moves(index = 1, offset = 110f)

        assertEquals(listOf(1 to 2), result.moves)
        assertEquals(2, result.index)
        assertEquals(10f, result.offset, 0.001f)
    }

    @Test
    fun movingUpOneRowSwapsWithThePreviousItem() {
        val result = moves(index = 3, offset = -130f)

        assertEquals(listOf(3 to 2), result.moves)
        assertEquals(2, result.index)
        assertEquals(-30f, result.offset, 0.001f)
    }

    @Test
    fun aFastDragMovesSeveralPositions() {
        val result = moves(index = 0, offset = 340f)

        assertEquals(listOf(0 to 1, 1 to 2, 2 to 3), result.moves)
        assertEquals(3, result.index)
        assertEquals(40f, result.offset, 0.001f)
    }

    @Test
    fun theEdgesStopMovingWithoutLosingTheOffset() {
        val atStart = moves(index = 0, offset = -400f)
        assertTrue(atStart.moves.isEmpty())
        assertEquals(0, atStart.index)
        assertEquals(-400f, atStart.offset, 0.001f)

        val atEnd = moves(index = 4, offset = 400f)
        assertTrue(atEnd.moves.isEmpty())
        assertEquals(4, atEnd.index)
        assertEquals(400f, atEnd.offset, 0.001f)
    }

    @Test
    fun aRowHeightIsRequiredBeforeAnythingMoves() {
        val result = moves(index = 2, offset = 500f, rowHeight = 0f)

        assertTrue(result.moves.isEmpty())
        assertEquals(2, result.index)
    }

    @Test
    fun aSingleItemListNeverMoves() {
        val result = moves(index = 0, offset = 500f, count = 1)

        assertTrue(result.moves.isEmpty())
        assertEquals(0, result.index)
    }

    // --- Auto-scroll while dragging through a long member list --------------------------

    @Test
    fun holdingADraggedRowNearTheBottomScrollsTowardsLaterItems() {
        // The edge zone is 160px here, so 100px from the bottom is already at full speed.
        val speed = dragAutoScrollSpeed(pointerY = 1_900f, viewportHeight = 2_000f)

        assertTrue("靠近底边必须向下滚动，实际 $speed", speed > 0f)
        assertEquals(450f, speed, 0.001f)
        assertEquals(
            DRAG_AUTOSCROLL_PX_PER_SECOND,
            dragAutoScrollSpeed(pointerY = 2_000f, viewportHeight = 2_000f),
            0.001f,
        )
    }

    @Test
    fun holdingADraggedRowNearTheTopScrollsTowardsEarlierItems() {
        val speed = dragAutoScrollSpeed(pointerY = 10f, viewportHeight = 2_000f)

        assertTrue("靠近顶边必须向上滚动，实际 $speed", speed < 0f)
        assertEquals(-1_125f, speed, 0.001f)
        assertEquals(
            -DRAG_AUTOSCROLL_PX_PER_SECOND,
            dragAutoScrollSpeed(pointerY = 0f, viewportHeight = 2_000f),
            0.001f,
        )
    }

    @Test
    fun theMiddleOfTheListDoesNotScrollOnItsOwn() {
        assertEquals(0f, dragAutoScrollSpeed(pointerY = 1_000f, viewportHeight = 2_000f), 0.001f)
    }

    @Test
    fun theScrollSpeedRampsUpTowardsTheEdge() {
        val near = dragAutoScrollSpeed(pointerY = 1_950f, viewportHeight = 2_000f)
        val closer = dragAutoScrollSpeed(pointerY = 1_990f, viewportHeight = 2_000f)

        assertTrue("越靠近边缘越快：$near → $closer", closer > near)
        assertTrue(closer <= DRAG_AUTOSCROLL_PX_PER_SECOND)
    }

    @Test
    fun anEmptyViewportNeverScrolls() {
        assertEquals(0f, dragAutoScrollSpeed(pointerY = 10f, viewportHeight = 0f), 0.001f)
    }

    @Test
    fun aShortListKeepsABothSidedEdgeZone() {
        // A 100px viewport gets a 25px edge zone, so its middle still holds still.
        assertEquals(0f, dragAutoScrollSpeed(pointerY = 50f, viewportHeight = 100f), 0.001f)
        assertEquals(-DRAG_AUTOSCROLL_PX_PER_SECOND, dragAutoScrollSpeed(0f, 100f), 0.001f)
        assertEquals(DRAG_AUTOSCROLL_PX_PER_SECOND, dragAutoScrollSpeed(100f, 100f), 0.001f)
    }

    @Test
    fun theFrameDeltaIsProportionalToTheElapsedTimeAndNeverJumps() {
        // 10 ms of a 1200 px/s scroll is 12 px.
        assertEquals(12f, dragAutoScrollDelta(1_200f, elapsedMillis = 10), 0.001f)
        assertEquals(0f, dragAutoScrollDelta(1_200f, elapsedMillis = 0), 0.001f)
        // A stalled or jumping clock must not scroll the list by a whole screen in one frame.
        assertEquals(76.8f, dragAutoScrollDelta(1_200f, elapsedMillis = 5_000), 0.001f)
    }

    @Test
    fun autoScrollActuallyReordersRowsPassingUnderTheFinger() {
        val state = DragReorderState().apply {
            rowHeightPx = 100f
            itemCount = 5
            start(index = 1, pointerInViewport = 490f)
        }
        val moves = mutableListOf<Pair<Int, Int>>()

        state.scrolledBy(230f) { from, to -> moves += from to to }

        assertEquals(listOf(1 to 2, 2 to 3), moves)
        assertEquals(3, state.draggingIndex)
        assertEquals(30f, state.rowOffset, 0.001f)
    }
}
