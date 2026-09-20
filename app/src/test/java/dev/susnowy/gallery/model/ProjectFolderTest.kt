package dev.susnowy.gallery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rules name the folder once, so the split has to be decided by the first hyphen and nothing
 * else. These cases are the ones that decide how a real Library is displayed.
 */
class ProjectFolderTest {

    @Test
    fun `splits on the first hyphen`() {
        assertEquals("山田" to "夏日", splitProjectFolder("山田-夏日"))
    }

    @Test
    fun `keeps later hyphens inside the project name`() {
        assertEquals("A" to "B-C", splitProjectFolder("A-B-C"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("作者" to "项目", splitProjectFolder(" 作者 - 项目 "))
    }

    @Test
    fun `rejects a name without a hyphen`() {
        assertNull(splitProjectFolder("无连字符"))
    }

    @Test
    fun `rejects a name with nothing before the hyphen`() {
        assertNull(splitProjectFolder("-项目"))
    }

    @Test
    fun `rejects a name with nothing after the hyphen`() {
        assertNull(splitProjectFolder("作者-"))
    }
}
