package dev.susnowy.gallery.ui

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
}
