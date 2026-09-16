package dev.susnowy.gallery.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SafDocumentCacheTest {
    private fun doc(path: String, documentId: String, directory: Boolean = false) = StorageNode(
        relativePath = path,
        name = path.substringAfterLast('/'),
        documentId = documentId,
        uri = "content://test/$documentId",
        isDirectory = directory,
    )

    @Test
    fun childLookupUsesTheRecordedParentListing() {
        val cache = SafDocumentCache()
        cache.put(doc("", "root", directory = true))
        cache.putChildren("root", mapOf("a.jpg" to doc("a.jpg", "doc-a")))
        cache.put(doc("a.jpg", "doc-a"))

        assertEquals("doc-a", cache.child("", "a.jpg")?.documentId)
        assertEquals("doc-a", cache.path("a.jpg")?.documentId)
    }

    @Test
    fun childLookupWithoutAParentListingStaysUnknown() {
        val cache = SafDocumentCache()
        cache.put(doc("", "root", directory = true))

        // Never listed: callers must treat this as a cache miss and query the provider.
        assertNull(cache.child("", "a.jpg"))
    }

    @Test
    fun childLookupOfAnUncachedParentStaysUnknown() {
        val cache = SafDocumentCache()
        cache.putChildren("root", mapOf("a.jpg" to doc("a.jpg", "doc-a")))

        assertNull(cache.child("", "a.jpg"))
    }

    @Test
    fun pathIndexEvictsLeastRecentlyUsedEntries() {
        val cache = SafDocumentCache(pathLimit = 2, directoryLimit = 4)
        cache.put(doc("a", "doc-a"))
        cache.put(doc("b", "doc-b"))
        cache.path("a") // Touch "a" so "b" becomes the least recently used entry.
        cache.put(doc("c", "doc-c"))

        assertEquals(2, cache.pathCount)
        assertEquals("doc-a", cache.path("a")?.documentId)
        assertNull(cache.path("b"))
        assertEquals("doc-c", cache.path("c")?.documentId)
    }

    @Test
    fun directoryIndexIsBoundedSeparately() {
        val cache = SafDocumentCache(pathLimit = 8, directoryLimit = 1)
        cache.putChildren("root", mapOf("a" to doc("a", "doc-a")))
        cache.putChildren("other", mapOf("b" to doc("b", "doc-b")))

        assertEquals(1, cache.directoryCount)
        assertNull(cache.children("root"))
        assertEquals(1, cache.children("other")?.size)
    }

    @Test
    fun clearDropsBothIndexes() {
        val cache = SafDocumentCache()
        cache.put(doc("", "root", directory = true))
        cache.putChildren("root", mapOf("a" to doc("a", "doc-a")))

        cache.clear()

        assertEquals(0, cache.pathCount)
        assertEquals(0, cache.directoryCount)
    }

    @Test
    fun forgetSubtreeRemovesThePathAndItsDescendantsOnly() {
        val cache = SafDocumentCache()
        cache.put(doc("", "root", directory = true))
        cache.put(doc("dir", "doc-dir", directory = true))
        cache.put(doc("dir/page.jpg", "doc-page"))
        cache.put(doc("other.jpg", "doc-other"))
        cache.putChildren("root", mapOf("dir" to doc("dir", "doc-dir", directory = true)))
        cache.putChildren("doc-dir", mapOf("page.jpg" to doc("dir/page.jpg", "doc-page")))

        cache.forgetSubtree("dir")

        assertNull(cache.path("dir"))
        assertNull(cache.path("dir/page.jpg"))
        assertNull(cache.children("doc-dir"))
        // Unrelated entries survive, so a bulk rename does not discard the whole cache.
        assertEquals("doc-other", cache.path("other.jpg")?.documentId)
        assertEquals("root", cache.path("")?.documentId)
    }

    @Test
    fun forgetSubtreeCanTargetTheWholeTree() {
        val cache = SafDocumentCache()
        cache.put(doc("", "root", directory = true))
        cache.put(doc("a.jpg", "doc-a"))
        cache.putChildren("root", mapOf("a.jpg" to doc("a.jpg", "doc-a")))

        cache.forgetSubtree("")

        assertEquals(0, cache.pathCount)
        assertEquals(0, cache.directoryCount)
    }

    @Test
    fun addingAChildUpdatesAnAlreadyRecordedParentListing() {
        val cache = SafDocumentCache()
        cache.put(doc("", "root", directory = true))
        cache.putChildren("root", emptyMap())
        val created = doc(".gallery", "doc-gallery", directory = true)

        cache.put(created)
        cache.putChild("root", created)

        assertEquals("doc-gallery", cache.child("", ".gallery")?.documentId)
        assertEquals("doc-gallery", cache.path(".gallery")?.documentId)
    }

    @Test
    fun forgettingAParentListingKeepsResolvedAncestorPaths() {
        val cache = SafDocumentCache()
        cache.put(doc("", "root", directory = true))
        cache.put(doc("dir", "doc-dir", directory = true))
        cache.put(doc("other", "doc-other", directory = true))
        cache.putChildren("root", mapOf("dir" to doc("dir", "doc-dir", directory = true)))

        cache.forgetSubtree("dir")
        cache.forgetChildren("root")

        assertEquals("root", cache.path("")?.documentId)
        assertEquals("doc-other", cache.path("other")?.documentId)
        assertNull(cache.children("root"))
    }
}

class RelativePathNormalizationTest {
    @Test
    fun acceptsPlainRelativePaths() {
        assertEquals("a/b/c.jpg", "a/b/c.jpg".normalizeRelativePath())
        assertEquals("a/b", "/a/b/".normalizeRelativePath())
        assertEquals("a/b", "a\\b".normalizeRelativePath())
    }

    @Test
    fun rejectsTraversalSegments() {
        assertThrows(IllegalArgumentException::class.java) {
            "../outside".normalizeRelativePath()
        }
        assertThrows(IllegalArgumentException::class.java) {
            "a/../../outside".normalizeRelativePath()
        }
        assertThrows(IllegalArgumentException::class.java) {
            "a/./b".normalizeRelativePath()
        }
    }

    @Test
    fun rejectsVolumeQualifiedPaths() {
        // A Windows drive letter or a SAF volume id must never reach the provider.
        assertThrows(IllegalArgumentException::class.java) {
            "E:/Rem-lib/a.jpg".normalizeRelativePath()
        }
        assertThrows(IllegalArgumentException::class.java) {
            "primary:DCIM/a.jpg".normalizeRelativePath()
        }
    }

    @Test
    fun emptyAndRootPathsStayEmpty() {
        assertEquals("", "".normalizeRelativePath())
        assertEquals("", "/".normalizeRelativePath())
    }
}
