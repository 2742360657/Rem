package dev.susnowy.gallery.media

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the archive path of the page manifest: one sequential pass must enumerate, size and
 * (when asked) hash every image entry without reading the archive again.
 */
class PageManifestArchiveTest {
    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun payload(seed: Int, size: Int = 1024) = ByteArray(size) { index -> (index + seed).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun readsEveryImageEntryOnceAndHashesIt() = runBlocking {
        val first = payload(1)
        val second = payload(2, size = 2048)
        val bytes = archive(
            "chapter/10.jpg" to first,
            "chapter/2.jpg" to second,
            "notes.txt" to "not a page".encodeToByteArray(),
            "chapter/" to byteArrayOf(),
        )
        val counter = PageManifestService.ByteCounter()

        val pages = readArchivePages("Comics/a.cbz", ByteArrayInputStream(bytes), true, counter)

        // Natural ordering, so page 2 comes before page 10.
        assertEquals(listOf("2.jpg", "10.jpg"), pages.map { it.name })
        assertEquals(listOf("chapter/2.jpg", "chapter/10.jpg"), pages.map { it.entryPath })
        assertEquals(listOf(2048L, 1024L), pages.map { it.sizeBytes })
        assertEquals(sha256(second), pages[0].sha256)
        assertEquals(sha256(first), pages[1].sha256)
        assertTrue(pages.all { it.containerPath == "Comics/a.cbz" })
        // The counter tracks page payload actually read (decompressed): one pass over the
        // archive content, never one read per page.
        assertEquals((first.size + second.size).toLong(), counter.bytes)
    }

    @Test
    fun withoutHashingItStillReportsSizesFromTheSamePass() = runBlocking {
        val bytes = archive("001.jpg" to payload(3), "002.jpg" to payload(4))
        val counter = PageManifestService.ByteCounter()
        var progressPages = 0
        var progressBytes = 0L

        val pages = readArchivePages("Comics/b.cbz", ByteArrayInputStream(bytes), false, counter) { count, read ->
            progressPages = count
            progressBytes = read
        }

        assertEquals(2, pages.size)
        assertEquals(1024L, pages[0].sizeBytes)
        assertNull(pages[0].sha256)
        assertEquals(2, progressPages)
        assertEquals(2048L, progressBytes)
        assertEquals(2048L, counter.bytes)
    }

    @Test
    fun nestedEntriesKeepTheirFullPathButAShortName() = runBlocking {
        val bytes = archive("a/b/c/007.png" to payload(5, size = 64))

        val pages = readArchivePages("Comics/c.cbz", ByteArrayInputStream(bytes), true)

        assertEquals("007.png", pages.single().name)
        assertEquals("a/b/c/007.png", pages.single().entryPath)
        assertEquals(64L, pages.single().sizeBytes)
    }
}
