package dev.susnowy.gallery.media

import android.os.Looper
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class EditionPageResolverTest {
    @Test fun mainThreadCallerListsEachParentOffMainAndPreservesPageOrder() = runBlocking {
        val calls = mutableListOf<String>()
        val plan = listOf(ImagePage("root", relativePath = "cover.jpg"),
            ImagePage("second", relativePath = "pages/2.jpg"),
            ImagePage("first", relativePath = "pages/1.jpg"),
            ImagePage("zip", archiveEntry = "3.jpg", relativePath = "book.cbz"))
        val result = withContext(Dispatchers.Main) {
            resolveEditionPages(plan) { parent ->
                assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
                calls += parent
                mapOf("cover.jpg" to "content://fixture/cover", "pages/1.jpg" to "content://fixture/1", "pages/2.jpg" to "content://fixture/2")
            }
        }
        assertEquals(listOf("", "pages"), calls)
        assertEquals(plan.map { it.name }, result.map { it.name })
        assertEquals("content://fixture/cover", result[0].uri)
        assertEquals("content://fixture/2", result[1].uri)
        assertEquals(plan.last(), result.last())
    }

    @Test fun providerFailureIsNotSilentlyConvertedToMissingPages() = runBlocking {
        val result = runCatching { resolveEditionPages(listOf(ImagePage("x", relativePath = "x.jpg"))) {
            throw SecurityException("permission lost")
        } }
        assertTrue(result.exceptionOrNull() is SecurityException)
    }
}
