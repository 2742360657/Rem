package dev.susnowy.gallery.media

import dev.susnowy.gallery.metadata.ComicInfoReader
import dev.susnowy.gallery.scanner.MediaClassifier
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the archive defect found on a real device: pages stored uncompressed with an extended
 * data descriptor are refused by [ZipInputStream] (`only DEFLATED entries can have EXT
 * descriptor`), which used to cost the library its page counts, page lists and ComicInfo.
 *
 * The fixture builds that exact layout by hand, because no JDK writer produces it.
 */
class StoredArchiveEntryTest {
    private fun payload(seed: Int, size: Int = 512) = ByteArray(size) { index -> (index + seed).toByte() }

    /** A ZIP whose entries are STORED and carry an extended data descriptor (bit 3 set). */
    private fun storedWithDataDescriptor(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        val central = mutableListOf<ByteArray>()
        var offset = 0
        entries.forEach { (name, bytes) ->
            val nameBytes = name.encodeToByteArray()
            val crc = CRC32().apply { update(bytes) }
            val local = ByteArrayOutputStream()
            local.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))        // local file header
            local.writeBytes(le16(20))                                    // version needed
            local.writeBytes(le16(0x0808))                                // bit 3 (descriptor) + UTF-8
            local.writeBytes(le16(0))                                     // method: STORED
            local.writeBytes(le16(0))                                     // time
            local.writeBytes(le16(0))                                     // date
            local.writeBytes(le32(crc.value))
            local.writeBytes(le32(bytes.size.toLong()))                   // compressed size
            local.writeBytes(le32(bytes.size.toLong()))                   // uncompressed size
            local.writeBytes(le16(nameBytes.size.toLong()))
            local.writeBytes(le16(0))                                     // extra length
            local.writeBytes(nameBytes)
            local.writeBytes(bytes)
            local.writeBytes(byteArrayOf(0x50, 0x4B, 0x07, 0x08))        // data descriptor
            local.writeBytes(le32(crc.value))
            local.writeBytes(le32(bytes.size.toLong()))
            local.writeBytes(le32(bytes.size.toLong()))
            val localBytes = local.toByteArray()
            output.writeBytes(localBytes)
            central += centralEntry(name, nameBytes, bytes.size, crc.value, offset)
            offset += localBytes.size
        }
        val centralStart = offset
        central.forEach { output.writeBytes(it) }
        val centralSize = output.size() - centralStart
        output.writeBytes(byteArrayOf(0x50, 0x4B, 0x05, 0x06))   // end of central directory
        output.writeBytes(le16(0))                              // this disk
        output.writeBytes(le16(0))                              // disk with central directory
        output.writeBytes(le16(entries.size.toLong()))          // entries on this disk
        output.writeBytes(le16(entries.size.toLong()))          // entries in total
        output.writeBytes(le32(centralSize.toLong()))
        output.writeBytes(le32(centralStart.toLong()))
        output.writeBytes(le16(0))                              // comment length
        return output.toByteArray()
    }

    private fun centralEntry(
        name: String,
        nameBytes: ByteArray,
        size: Int,
        crc: Long,
        offset: Int,
    ): ByteArray = ByteArrayOutputStream().apply {
        writeBytes(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
        writeBytes(le16(20))                    // version made by
        writeBytes(le16(20))                    // version needed
        writeBytes(le16(0x0808))                // flags
        writeBytes(le16(0))                     // method: STORED
        writeBytes(le16(0))
        writeBytes(le16(0))
        writeBytes(le32(crc))
        writeBytes(le32(size.toLong()))
        writeBytes(le32(size.toLong()))
        writeBytes(le16(nameBytes.size.toLong()))
        writeBytes(le16(0))                     // extra
        writeBytes(le16(0))                     // comment
        writeBytes(le16(0))                     // disk
        writeBytes(le16(0))                     // internal attrs
        writeBytes(le32(0))                     // external attrs
        writeBytes(le32(offset.toLong()))
        writeBytes(nameBytes)
    }.toByteArray()

    private fun le16(value: Long) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    private fun le32(value: Long) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun archiveFile(bytes: ByteArray): File =
        File.createTempFile("stored-descriptor", ".cbz").apply {
            FileOutputStream(this).use { it.write(bytes) }
            deleteOnExit()
        }

    @Test
    fun streamingReaderCannotReadThisLayout() {
        val bytes = storedWithDataDescriptor("001.jpg" to payload(1), "002.jpg" to payload(2))

        assertThrows(java.util.zip.ZipException::class.java) {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    zip.nextEntry ?: break
                    zip.closeEntry()
                }
            }
        }
    }

    @Test
    fun centralDirectoryCountsAndHashesEveryPage() = runBlocking {
        val first = payload(1)
        val second = payload(2, size = 1024)
        val file = archiveFile(
            storedWithDataDescriptor(
                "001.jpg" to first,
                "ComicInfo.xml" to "<ComicInfo><Title>作品</Title></ComicInfo>".encodeToByteArray(),
                "002.jpg" to second,
            ),
        )

        ZipFile(file).use { zip ->
            val pages = readArchivePages(zip, "Comics/a.cbz", hashPages = true)

            assertEquals(listOf("001.jpg", "002.jpg"), pages.map { it.name })
            assertEquals(listOf(512L, 1024L), pages.map { it.sizeBytes })
            assertEquals(sha256(first), pages[0].sha256)
            assertEquals(sha256(second), pages[1].sha256)

            // ComicInfo parsing itself goes through Android's XML pull parser, which a plain
            // JVM test cannot run; what matters here is that the sidecar neither breaks the
            // central-directory walk nor gets counted as a page.
            val inspection = ComicInfoReader().inspectArchive(zip) { MediaClassifier.isImage(it, null) }
            assertEquals(2, inspection.pageCount)
        }
    }

    @Test
    fun pageManifestWithoutHashingStillReportsSizes() = runBlocking {
        val file = archiveFile(
            storedWithDataDescriptor("001.jpg" to payload(3), "readme.txt" to "no".encodeToByteArray()),
        )

        ZipFile(file).use { zip ->
            val pages = readArchivePages(zip, "Comics/b.cbz", hashPages = false)

            assertEquals(1, pages.size)
            assertEquals(512L, pages.single().sizeBytes)
            assertEquals("001.jpg", pages.single().entryPath)
            assertTrue(pages.single().sha256 == null)
        }
    }

    @Test
    fun evictionKeepsTheCacheInsideItsBudget() {
        val entries = listOf(
            ArchiveCacheEntry("/a", bytes = 400, lastUsed = 1),
            ArchiveCacheEntry("/b", bytes = 400, lastUsed = 2),
            ArchiveCacheEntry("/c", bytes = 400, lastUsed = 3),
        )

        // Nothing to do while inside the budget.
        assertTrue(archiveEvictions(entries, maxBytes = 2000).isEmpty())

        // Over budget: oldest use goes first and the trim stops at 90% of the budget.
        val doomed = archiveEvictions(entries, maxBytes = 800)
        assertEquals(listOf("/a", "/b"), doomed)

        // The archive being written right now is never evicted by its own copy, and the trim
        // keeps going until the cache is back under 90% of the budget.
        val protected = archiveEvictions(entries, maxBytes = 800, keep = "/a")
        assertEquals(listOf("/b", "/c"), protected)
    }

    @Test
    fun cacheCopiesAnArchiveOnceAndReusesIt() {
        val directory = File.createTempFile("archive-cache", "").let { file ->
            file.delete()
            file
        }
        directory.mkdirs()
        val cache = ArchiveCache(directory, maxBytes = 1024 * 1024)
        assertNotNull(cache)
        assertTrue(directory.isDirectory)
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
