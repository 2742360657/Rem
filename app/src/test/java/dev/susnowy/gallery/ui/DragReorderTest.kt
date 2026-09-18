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
    @Test
    fun visualPositionIncludesTheRowsNewLayoutPositionOnlyOnce() {
        val state = DragReorderState().apply {
            rowHeightPx = 100f
            itemCount = 10
            start(index = 0, pointerInViewport = 50f)
        }
        state.drag(250f) { _, _ -> }
        val laidOutTopAfterReorder = state.draggingIndex!! * state.rowHeightPx
        assertEquals(250f, laidOutTopAfterReorder + state.dragRowOffset, 0.001f)
    }
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
        // The visual offset keeps the row under the finger; the two completed swaps are already
        // expressed by its new position in the list, so only the 30px remainder is left over.
        assertEquals(30f, state.dragRowOffset, 0.001f)
    }

    /**
     * A held finger at the list edge must be able to cross many rows on auto-scroll alone: the
     * member order has to follow the distance the list actually scrolled, not the pixels the row
     * was nudged by.
     */
    @Test
    fun holdingTheEdgeCrossesManyRowsWithoutMovingTheFinger() {
        val state = DragReorderState().apply {
            rowHeightPx = 100f
            itemCount = 40
            start(index = 2, pointerInViewport = 1_900f)
        }
        val moves = mutableListOf<Pair<Int, Int>>()

        repeat(5) { state.scrolledBy(100f) { from, to -> moves += from to to } }

        assertEquals(listOf(2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7), moves)
        assertEquals(7, state.draggingIndex)
        // Five rows of new layout position consume the entire 500px displacement.
        assertEquals(0f, state.dragRowOffset, 0.001f)
    }

    @Test
    fun draggingWithTheFingerKeepsTheRowUnderTheFinger() {
        val state = DragReorderState().apply {
            rowHeightPx = 100f
            itemCount = 10
            start(index = 0, pointerInViewport = 500f)
        }
        val moves = mutableListOf<Pair<Int, Int>>()

        state.drag(250f) { from, to -> moves += from to to }

        assertEquals(listOf(0 to 1, 1 to 2), moves)
        assertEquals(2, state.draggingIndex)
        assertEquals(50f, state.dragRowOffset, 0.001f)
        assertEquals(750f, state.pointerY, 0.001f)
    }

    /** Auto-scroll moves the list, not the finger, so the edge speed must stay constant. */
    @Test
    fun autoScrollDoesNotDriftTheFingerTowardsTheMiddleOfTheList() {
        val state = DragReorderState().apply {
            rowHeightPx = 100f
            itemCount = 100
            start(index = 0, pointerInViewport = 1_980f)
        }

        repeat(10) { state.scrolledBy(60f) { _, _ -> } }

        assertEquals("自动滚动不是手指移动", 1_980f, state.pointerY, 0.001f)
        assertTrue(
            "仍然贴着底边，因此仍然保持滚动速度",
            dragAutoScrollSpeed(state.pointerY, 2_000f) > 0f,
        )
    }

    @Test
    fun aFingerDragStillMovesTheFingerPosition() {
        val state = DragReorderState().apply {
            rowHeightPx = 100f
            itemCount = 10
            start(index = 0, pointerInViewport = 1_000f)
        }

        state.drag(-120f) { _, _ -> }

        assertEquals(880f, state.pointerY, 0.001f)
    }
}
