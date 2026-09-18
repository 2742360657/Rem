package dev.susnowy.gallery.storage

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import dev.susnowy.gallery.media.MediaReadPriority
import dev.susnowy.gallery.scanner.LibraryScanner
import dev.susnowy.gallery.scanner.ScanDepth
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScanPriorityTest {
    @Test fun inventoryWaitsBeforeProviderQueryAndCancellingWaitDoesNotReportUnreadableMedia() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = Uri.parse("content://${TestDocumentsProvider.AUTHORITY}")
        context.contentResolver.call(provider, TestDocumentsProvider.METHOD_RESET, null, Bundle.EMPTY)
        val tree = DocumentsContract.buildTreeDocumentUri(TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID)
        val gate = MediaReadPriority()
        var entered = CompletableDeferred<Unit>()
        val scanner = LibraryScanner {
            entered.complete(Unit)
            gate.awaitBackgroundTurn()
        }
        lateinit var scanning: Deferred<dev.susnowy.gallery.scanner.ScanResult>
        gate.foreground {
            scanning = async(Dispatchers.IO) { scanner.scan(DocumentTreeStorage(context, tree), depth = ScanDepth.INVENTORY) }
            withTimeout(5_000) { entered.await() }
            val queries = context.contentResolver.call(provider, TestDocumentsProvider.METHOD_CHILD_QUERY_COUNT,
                TestDocumentsProvider.ROOT_ID, Bundle.EMPTY)!!.getInt(TestDocumentsProvider.RESULT_COUNT)
            assertEquals(0, queries)
            assertFalse(scanning.isCompleted)
        }
        val result = withTimeout(5_000) { scanning.await() }
        assertTrue(result.unreadableDirectories.isEmpty())
        assertEquals(1, context.contentResolver.call(provider, TestDocumentsProvider.METHOD_CHILD_QUERY_COUNT,
            TestDocumentsProvider.ROOT_ID, Bundle.EMPTY)!!.getInt(TestDocumentsProvider.RESULT_COUNT))

        gate.foreground {
            entered = CompletableDeferred()
            val waiting = async(Dispatchers.IO) { scanner.scan(DocumentTreeStorage(context, tree), depth = ScanDepth.INVENTORY) }
            withTimeout(5_000) { entered.await() }
            waiting.cancelAndJoin()
            assertTrue(waiting.isCancelled)
        }
        assertEquals(1, context.contentResolver.call(provider, TestDocumentsProvider.METHOD_CHILD_QUERY_COUNT,
            TestDocumentsProvider.ROOT_ID, Bundle.EMPTY)!!.getInt(TestDocumentsProvider.RESULT_COUNT))
    }
}
