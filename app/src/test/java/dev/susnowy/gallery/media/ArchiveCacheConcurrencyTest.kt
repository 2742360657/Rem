package dev.susnowy.gallery.media

import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.library.LibraryDocumentAccess
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveCacheConcurrencyTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun foregroundPreemptsColdPreviewCopyAndPreviewRestartsCleanly() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val zip = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { output ->
                output.putNextEntry(ZipEntry("page.txt"))
                output.write("page".toByteArray())
                output.closeEntry()
            }
        }.toByteArray()
        val access = object : LibraryDocumentAccess {
            override fun find(relativePath: String): LibraryDocument? = null
            override fun ensureDirectory(relativePath: String): LibraryDocument = error("read only")
            override fun createFile(relativePath: String, mimeType: String): LibraryDocument = error("read only")
            override fun openInput(document: LibraryDocument): InputStream {
                if (document.key == "preview.zip" && reads.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return ByteArrayInputStream(zip)
            }
            override fun openOutput(document: LibraryDocument, truncate: Boolean): OutputStream = error("read only")
            override fun rename(document: LibraryDocument, displayName: String): Boolean = error("read only")
            override fun delete(document: LibraryDocument): Boolean = error("read only")
        }
        val root = directory.newFolder()
        val cache = ArchiveCache(root)
        val priority = MediaReadPriority()
        val preview = async(Dispatchers.IO) {
            priority.background {
                cache.open("lib", "preview.zip", zip.size.toLong(), 1, access)!!.use {
                    assertNotNull(it.getEntry("page.txt"))
                }
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            priority.foreground {
                // A synchronous Provider call must return before cancellation can release the copy lock.
                release.countDown()
                withTimeout(2_000) {
                    cache.open("lib", "reader.zip", zip.size.toLong(), 1, access)!!.use {
                        assertNotNull(it.getEntry("page.txt"))
                    }
                }
                assertEquals(1, reads.get())
            }
            withTimeout(2_000) { preview.await() }
            assertEquals(2, reads.get())
            assertFalse(root.listFiles().orEmpty().any { it.extension == "part" })
            assertEquals(2, cache.stats().files)
        } finally {
            release.countDown()
            preview.cancelAndJoin()
        }
    }

    @Test fun slowCopyDoesNotBlockCachedReadOrStats() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val zip = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { output ->
                output.putNextEntry(ZipEntry("page.txt"))
                output.write("page".toByteArray())
                output.closeEntry()
            }
        }.toByteArray()
        val access = object : LibraryDocumentAccess {
            override fun find(relativePath: String): LibraryDocument? = null
            override fun ensureDirectory(relativePath: String): LibraryDocument = error("read only")
            override fun createFile(relativePath: String, mimeType: String): LibraryDocument = error("read only")
            override fun openInput(document: LibraryDocument): InputStream {
                if (document.key == "slow.zip") {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return ByteArrayInputStream(zip)
            }
            override fun openOutput(document: LibraryDocument, truncate: Boolean): OutputStream = error("read only")
            override fun rename(document: LibraryDocument, displayName: String): Boolean = error("read only")
            override fun delete(document: LibraryDocument): Boolean = error("read only")
        }
        val cache = ArchiveCache(directory.newFolder())
        cache.open("lib", "warm.zip", zip.size.toLong(), 1, access)!!.close()
        val copying = async(Dispatchers.IO) { cache.open("lib", "slow.zip", zip.size.toLong(), 1, access) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) {
                cache.open("lib", "warm.zip", zip.size.toLong(), 1, access)!!.use { file ->
                    assertNotNull(file.getEntry("page.txt"))
                }
                assertEquals(1, cache.stats().files)
            }
        } finally {
            release.countDown()
            copying.await()?.close()
        }
    }
}
