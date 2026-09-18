package dev.susnowy.gallery.model

import org.junit.Assert.*
import org.junit.Test

class BatchMetadataEditTest {
    private val item = MediaItem(id = "w", libraryId = "lib", relativePath = "book", uri = "",
        kind = MediaKind.IMAGE_SET, sourceKind = SourceKind.DIRECTORY, displayTitle = "Book",
        authors = listOf("A"), tags = listOf("old"))

    @Test fun appendReplaceAndClearHaveDistinctMeaning() {
        val edit = BatchMetadataEdit(authors = BatchListEdit(BatchListMode.APPEND, listOf("A", " B ")),
            tags = BatchListEdit(BatchListMode.REPLACE, listOf("new")),
            collections = BatchListEdit(BatchListMode.CLEAR))
        val result = edit.merge(item, item)
        assertEquals(listOf("A", "B"), result.item.authors)
        assertEquals(listOf("new"), result.item.tags)
        assertTrue(result.item.collections.isEmpty())
        assertEquals("manual", result.item.fieldSources["collections"])
        assertTrue(result.conflicts.isEmpty())
    }

    @Test fun staleValueOrNewManualLockSkipsWholeWork() {
        val edit = BatchMetadataEdit(tags = BatchListEdit(BatchListMode.CLEAR), domain = MediaDomain.ALBUM)
        listOf(item.copy(tags = listOf("later")), item.copy(fieldSources = mapOf("tags" to "manual"))).forEach { current ->
            val result = edit.merge(item, current)
            assertEquals(listOf("tags"), result.conflicts)
            assertEquals(current, result.item)
        }
    }

    @Test fun unrelatedConcurrentEditsAndExistingManualDecisionsArePreserved() {
        val baseline = item.copy(fieldSources = mapOf("tags" to "manual"))
        val current = baseline.copy(displayTitle = "Later title", favorite = true, revision = 8)
        val result = BatchMetadataEdit(tags = BatchListEdit(BatchListMode.CLEAR)).merge(baseline, current)
        assertEquals("Later title", result.item.displayTitle)
        assertTrue(result.item.favorite)
        assertEquals(8L, result.item.revision)
        assertEquals("manual", result.item.fieldSources["tags"])
        assertTrue(result.item.tags.isEmpty())
    }

    @Test fun emptyReplaceDoesNotAccidentallyClearAndKeepDoesNotLock() {
        val edit = BatchMetadataEdit(tags = BatchListEdit(BatchListMode.REPLACE, listOf(" ")))
        assertFalse(edit.active)
        assertEquals(item, edit.merge(item, item).item)
    }
}
