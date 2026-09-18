package dev.susnowy.gallery.data

import android.content.Context
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import dev.susnowy.gallery.library.PortableLibraryManager
import dev.susnowy.gallery.storage.DocumentTreeStorage
import dev.susnowy.gallery.storage.TestDocumentsProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class LibraryAttachValidationTest {
    @Test fun invalidPortableCatalogFailsBeforeRegisteringLibrary() = runBlocking {
        // This provider is owned by the target app; model a picker grant for DocumentFile's
        // permission preflight while retaining real Provider queries/streams underneath.
        val context = object : android.content.ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getApplicationContext(): Context = this
            override fun checkCallingOrSelfUriPermission(uri: android.net.Uri, modeFlags: Int): Int =
                if (uri.authority == TestDocumentsProvider.AUTHORITY) android.content.pm.PackageManager.PERMISSION_GRANTED
                else super.checkCallingOrSelfUriPermission(uri, modeFlags)
        }
        val root = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
        context.contentResolver.call(root, TestDocumentsProvider.METHOD_RESET, null, Bundle())
        val storage = DocumentTreeStorage(context, root)
        val identity = PortableLibraryManager(storage).initialize("Invalid catalog fixture")
        val validCatalog = dev.susnowy.gallery.metadata.PortableMetadataStore(storage).loadCatalog(identity.libraryId)
        val original = kotlinx.serialization.json.Json.encodeToString(validCatalog).toByteArray()
        val catalog = storage.find(".gallery/items/catalog.json") ?: storage.createFile(".gallery/items/catalog.json", "application/json")
        storage.openOutput(catalog).use { it.write("{broken".toByteArray()) }
        val database = GalleryDatabase(context)
        try {
            val result = runCatching { GalleryRepository(context).attach(root) }
            assertTrue("Corrupt portable metadata must be reported", result.isFailure)
            assertTrue("Unexpected failure: ${result.exceptionOrNull()}", result.exceptionOrNull()?.message.orEmpty().contains("catalog"))
            assertNull(database.library(identity.libraryId))
            assertEquals("{broken", storage.openInput(catalog).bufferedReader().use { it.readText() })
            storage.openOutput(catalog).use { it.write(original) }
            assertEquals(identity.libraryId, GalleryRepository(context).attach(root).libraryId)
            assertNotNull(database.library(identity.libraryId))
        } finally { database.removeLibrary(identity.libraryId); database.close() }
    }
}
