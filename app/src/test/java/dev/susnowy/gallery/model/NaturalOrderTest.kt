package dev.susnowy.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collection's name order. A plain string sort puts `10` before `2`, and an earlier version of
 * the comparator declared every pair of digit-free names equal — so a level of Chinese folder names
 * silently kept whatever order the index happened to hand over.
 */
class NaturalOrderTest {

    private fun sorted(vararg names: String): List<String> = names.sortedWith(NATURAL_ORDER)

    @Test
    fun `digit runs compare as numbers`() {
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), sorted("10.jpg", "2.jpg", "1.jpg"))
        assertEquals(listOf("0001.jpg", "0009.jpg", "0010.jpg"), sorted("0010.jpg", "0009.jpg", "0001.jpg"))
    }

    @Test
    fun `zero padding does not change the order`() {
        assertEquals(listOf("1.jpg", "02.jpg", "003.jpg"), sorted("003.jpg", "02.jpg", "1.jpg"))
    }

    @Test
    fun `names without digits still order by text`() {
        // The regression: this used to come back in the order it was given, because both names
        // compared equal once the loop ran out.
        // 底 U+5E95 < 面 U+9762, so codepoint order puts 封底 first; the point is that the two
        // are not equal, which is what the broken comparator claimed.
        assertEquals(listOf("封底.jpg", "封面.jpg"), sorted("封面.jpg", "封底.jpg"))
        assertEquals(listOf("apple", "banana", "cherry"), sorted("cherry", "apple", "banana"))
        assertTrue(NATURAL_ORDER.compare("封面.jpg", "封底.jpg") != 0)
    }

    @Test
    fun `case is ignored so upper and lower names group together`() {
        assertEquals(listOf("alpha", "Beta", "gamma"), sorted("gamma", "Beta", "alpha"))
    }

    @Test
    fun `a prefix sorts before the longer name`() {
        assertEquals(listOf("img", "img2.jpg"), sorted("img2.jpg", "img"))
    }

    @Test
    fun `mixed names compare position by position`() {
        assertEquals(
            listOf("a1.jpg", "a2.jpg", "a10.jpg", "b1.jpg"),
            sorted("b1.jpg", "a10.jpg", "a2.jpg", "a1.jpg"),
        )
    }

    @Test
    fun `the comparator is consistent, so sorting is stable for equal names`() {
        assertEquals(0, NATURAL_ORDER.compare("same.jpg", "same.jpg"))
        assertEquals(sorted("b", "a", "b"), sorted("b", "b", "a"))
    }
}
