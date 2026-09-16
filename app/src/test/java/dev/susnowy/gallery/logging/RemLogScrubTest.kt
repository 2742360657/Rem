package dev.susnowy.gallery.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Diagnostic records leave the device when the user exports them, so anything that
 * identifies the account or the physical disk must be reduced first. The portable Library
 * guide forbids writing account material into logs at all; this is the mechanism that
 * keeps that promise for the data that would otherwise leak in by accident.
 */
class RemLogScrubTest {
    @Test
    fun keepsLibraryRelativePathsReadable() {
        val message = "扫描失败：蠢沫沫[2026.08]/No.404/page_001.jpg"

        assertEquals(message, RemLog.scrub(message))
    }

    @Test
    fun removesContentUrisThatCarryTheVolumeIdentity() {
        val scrubbed = RemLog.scrub(
            "openInput content://com.android.externalstorage.documents/tree/1A2B-3C4D%3ARem-lib",
        )

        assertFalse("泄漏卷 ID：$scrubbed", scrubbed.contains("1A2B-3C4D"))
        assertFalse("泄漏路径：$scrubbed", scrubbed.contains("Rem-lib"))
        assertTrue(scrubbed.contains("openInput"))
    }

    @Test
    fun removesHostFilesystemPaths() {
        val scrubbed = RemLog.scrub("""无法读取 E:\Rem-lib\.gallery\library.json""")

        assertFalse(scrubbed.contains("Rem-lib"))
        assertFalse(scrubbed.contains("E:"))
    }

    @Test
    fun keepsTheRemainderOfTheMessageIntact() {
        val scrubbed = RemLog.scrub("""重命名失败：content://provider/doc%3A1 在 E:\lib\a.jpg 处中止""")

        assertTrue(scrubbed.startsWith("重命名失败："))
        assertTrue(scrubbed.endsWith("处中止"))
    }

    @Test
    fun leavesPlainTextUntouched() {
        assertEquals("", RemLog.scrub(""))
        assertEquals("扫描完成：候选=27835", RemLog.scrub("扫描完成：候选=27835"))
    }
}
