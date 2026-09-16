package dev.susnowy.gallery.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicInfoReaderTest {
    @Test
    fun archiveInspectionCountsPagesWhenOptionalComicInfoIsMalformed() {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("ComicInfo.xml"))
                zip.write("<ComicInfo><Title>broken".encodeToByteArray())
                zip.closeEntry()
                listOf("001.jpg", "nested/002.webp", "notes.txt").forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }
        }.toByteArray()

        val result = ComicInfoReader().inspectArchive(ByteArrayInputStream(bytes)) { name ->
            name.endsWith(".jpg") || name.endsWith(".webp")
        }

        assertEquals(2, result.pageCount)
        assertNull(result.metadata)
    }
}
