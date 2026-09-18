package dev.susnowy.gallery.media

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.storage.DocumentTreeStorage
import dev.susnowy.gallery.storage.TestDocumentsProvider
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ArchiveBitmapIdentityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cacheRoot = File(context.cacheDir, "archive-identity-${UUID.randomUUID()}")
    private val service = MediaContentService(ArchiveCache(cacheRoot))
    private lateinit var root: DocumentTreeStorage

    @Before fun setup() {
        context.contentResolver.call(Uri.parse("content://${TestDocumentsProvider.AUTHORITY}"),
            TestDocumentsProvider.METHOD_RESET, null, Bundle.EMPTY)
        root = DocumentTreeStorage(context, DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID))
    }
    @After fun clean() { cacheRoot.deleteRecursively() }

    private fun library(name: String): DocumentTreeStorage {
        root.ensureDirectory(name)
        val id = DocumentsContract.getDocumentId(Uri.parse(requireNotNull(root.entry(name)).uri))
        return DocumentTreeStorage(context, DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, id))
    }

    private fun write(storage: DocumentTreeStorage, color: Int, padding: Int = 0) {
        val document = storage.find("book.cbz") ?: storage.createFile("book.cbz", "application/zip")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        storage.openOutput(document).use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("001.png"))
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
                zip.closeEntry()
                if (padding > 0) {
                    zip.putNextEntry(ZipEntry("padding.txt"))
                    zip.write(ByteArray(padding) { it.toByte() })
                    zip.closeEntry()
                }
            }
        }
        bitmap.recycle()
    }

    private fun work(library: String, size: Long = 1) = MediaItem("work", library, "book.cbz", "",
        MediaKind.IMAGE_SET, sourceKind = SourceKind.ARCHIVE, displayTitle = "Fixture", size = size, modifiedAt = 1)

    @Test fun samePathAndTimestampInDifferentLibrariesCannotReusePage() = runBlocking {
        val first = library("first")
        val second = library("second")
        write(first, Color.RED)
        write(second, Color.BLUE)
        var dimensions: ComicPageDimensions? = null
        val red = requireNotNull(service.decodeArchivePage(work("first"), "001.png", first, 100, 100,
            onDimensions = { dimensions = it }))
        assertEquals(ComicPageDimensions(16, 16), dimensions)
        assertEquals(Color.RED, red.getPixel(0, 0))
        assertSame(red, service.decodeArchivePage(work("first"), "001.png", first, 100, 100))
        assertEquals(Color.BLUE, requireNotNull(service.decodeArchivePage(work("second"), "001.png", second, 100, 100)).getPixel(0, 0))
    }

    @Test fun changedArchiveSizeInvalidatesPageEvenWithUnchangedTimestamp() = runBlocking {
        val storage = library("version")
        write(storage, Color.RED)
        val initial = work("version", requireNotNull(storage.entry("book.cbz")).size)
        assertEquals(Color.RED, requireNotNull(service.decodeArchivePage(initial, "001.png", storage, 100, 100)).getPixel(0, 0))
        write(storage, Color.BLUE, 512)
        val changed = initial.copy(size = requireNotNull(storage.entry("book.cbz")).size)
        assertNotEquals(initial.size, changed.size)
        assertEquals(Color.BLUE, requireNotNull(service.decodeArchivePage(changed, "001.png", storage, 100, 100)).getPixel(0, 0))
    }

    @Test fun mergedEditionUsesContainerVersionRatherThanWorkVersion() = runBlocking {
        val storage = library("merged")
        val merged = work("merged").copy(relativePath = "virtual-edition")
        write(storage, Color.RED)
        assertEquals(Color.RED, requireNotNull(service.decodeArchivePage(merged, "001.png", storage, 100, 100,
            archivePath = "book.cbz")).getPixel(0, 0))
        write(storage, Color.BLUE, 512)
        assertEquals(Color.BLUE, requireNotNull(service.decodeArchivePage(merged, "001.png", storage, 100, 100,
            archivePath = "book.cbz")).getPixel(0, 0))
    }
}
