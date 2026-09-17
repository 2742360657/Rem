package dev.susnowy.gallery.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.storage.DocumentTreeStorage
import dev.susnowy.gallery.storage.TestDocumentsProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflinePreviewStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var storage: DocumentTreeStorage
    private lateinit var previewRoot: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.call(
            Uri.parse("content://${TestDocumentsProvider.AUTHORITY}"),
            TestDocumentsProvider.METHOD_RESET,
            null,
            Bundle.EMPTY,
        )
        val treeUri = DocumentsContract.buildTreeDocumentUri(
            TestDocumentsProvider.AUTHORITY,
            TestDocumentsProvider.ROOT_ID,
        )
        storage = DocumentTreeStorage(context, treeUri)
        previewRoot = File(context.cacheDir, "offline-preview-test-${UUID.randomUUID()}")
    }

    @After
    fun tearDown() {
        previewRoot.deleteRecursively()
    }

    @Test
    fun createsBoundedPreviewAndClearsOnlyDerivedFile() = runBlocking {
        val document = storage.createFile("Images/sample.png", "image/png")
        val source = Bitmap.createBitmap(1_200, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.MAGENTA)
        }
        storage.openOutput(document).use { output ->
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        source.recycle()
        val entry = requireNotNull(storage.entry("Images/sample.png"))
        val item = MediaItem(
            id = "preview-item",
            libraryId = "preview-library",
            relativePath = entry.relativePath,
            uri = entry.uri,
            kind = MediaKind.IMAGE,
            domain = MediaDomain.CLASSIFIED,
            sourceKind = SourceKind.FILE,
            displayTitle = "sample",
            size = entry.size,
            modifiedAt = entry.lastModified,
        )
        val store = OfflinePreviewStore(
            context = context,
            edgePixels = 128,
            rootDirectory = previewRoot,
            archives = ArchiveCache(File(context.cacheDir, "archive-cache-test")),
        )

        val preview = store.getOrCreate(item, storage)

        assertNotNull(preview)
        assertTrue(requireNotNull(preview).isFile)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(preview.absolutePath, bounds)
        assertEquals(128, maxOf(bounds.outWidth, bounds.outHeight))
        assertEquals(OfflinePreviewStats(files = 1, bytes = preview.length()), store.stats())
        assertEquals(preview, store.getOrCreate(item, storage))

        val removed = store.clear()
        assertEquals(1, removed.files)
        assertFalse(preview.exists())
        assertNotNull(storage.entry("Images/sample.png"))
    }
}
