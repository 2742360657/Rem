package dev.susnowy.gallery.compare

import dev.susnowy.gallery.media.PageEntry
import dev.susnowy.gallery.media.SourceManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageComparisonTest {
    private fun manifest(
        label: String,
        hashed: Boolean,
        vararg pages: Triple<String, Long, String?>,
    ) = SourceManifest(
        sourceId = label,
        libraryId = "library-id",
        label = label,
        pages = pages.map { (name, size, hash) ->
            PageEntry(
                containerPath = "$label/container",
                entryPath = null,
                name = name,
                sizeBytes = size,
                sha256 = hash.takeIf { hashed },
            )
        },
        hashed = hashed,
        bytesRead = pages.sumOf { it.second },
        durationMs = 1,
    )

    @Test
    fun deepComparisonMatchesByContentEvenWhenNamesDiffer() {
        val left = manifest(
            "A",
            hashed = true,
            Triple("001.jpg", 100, "a"),
            Triple("002.jpg", 100, "b"),
        )
        val right = manifest(
            "B",
            hashed = true,
            Triple("scan_001.jpg", 100, "a"),
            Triple("scan_002.jpg", 120, "c"),
        )

        val report = PageComparison.compare(left, right)

        assertEquals(1, report.identicalCount)
        assertEquals(listOf(1), report.leftOnly)
        assertEquals(listOf(1), report.rightOnly)
        assertTrue(report.deep)
    }

    @Test
    fun quickComparisonNeverClaimsADuplicate() {
        val left = manifest("A", hashed = false, Triple("001.jpg", 100, null))
        val right = manifest("B", hashed = false, Triple("001.jpg", 100, null))

        val report = PageComparison.compare(left, right)

        assertEquals(0, report.identicalCount)
        assertEquals(1, report.unverifiedCount)
        assertEquals(1, report.duplicateCandidates)
        assertFalse(report.deep)
        assertTrue(report.summary.contains("未读内容"))
    }

    @Test
    fun sameNameDifferentSizeIsReportedAsConflict() {
        val left = manifest("A", hashed = false, Triple("001.jpg", 100, null))
        val right = manifest("B", hashed = false, Triple("001.jpg", 180, null))

        val report = PageComparison.compare(left, right)

        assertEquals(1, report.conflictingCount)
        assertEquals(0, report.duplicateCandidates)
        assertTrue(report.summary.contains("同名但内容不同"))
    }

    @Test
    fun hashedComparisonReportsReEncodedPagesAsConflicts() {
        val left = manifest("A", hashed = true, Triple("001.jpg", 100, "abc"))
        val right = manifest("B", hashed = true, Triple("001.jpg", 100, "def"))

        val report = PageComparison.compare(left, right)

        assertEquals(0, report.identicalCount)
        assertEquals(1, report.conflictingCount)
        assertEquals(0, report.unverifiedCount)
    }

    @Test
    fun directoryPagesAreMatchedByBaseName() {
        val left = SourceManifest(
            sourceId = "A",
            libraryId = "library-id",
            label = "A",
            pages = listOf(PageEntry("A", null, "001.jpg", 10, "x")),
            hashed = true,
            bytesRead = 10,
            durationMs = 1,
        )
        val right = SourceManifest(
            sourceId = "B",
            libraryId = "library-id",
            label = "B",
            pages = listOf(PageEntry("B.cbz", "chapter/001.JPG", "001.JPG", 10, "x")),
            hashed = true,
            bytesRead = 10,
            durationMs = 1,
        )

        val report = PageComparison.compare(left, right)

        assertEquals(1, report.identicalCount)
        assertEquals(0, report.leftOnly.size)
        assertEquals(0, report.rightOnly.size)
    }

    @Test
    fun duplicateNamesAreMatchedInOrder() {
        val left = manifest(
            "A",
            hashed = false,
            Triple("001.jpg", 10, null),
            Triple("001.jpg", 10, null),
        )
        val right = manifest(
            "B",
            hashed = false,
            Triple("001.jpg", 10, null),
            Triple("001.jpg", 10, null),
            Triple("001.jpg", 10, null),
        )

        val report = PageComparison.compare(left, right)

        assertEquals(2, report.matches.size)
        assertEquals(1, report.rightOnly.size)
        assertEquals(listOf(0 to 0, 1 to 1), report.matches.map { it.leftIndex to it.rightIndex })
    }
}

class MergePlanTest {
    private fun page(name: String, size: Long = 10, hash: String? = null) = Triple(name, size, hash)

    private fun manifest(label: String, vararg pages: Triple<String, Long, String?>): SourceManifest {
        val hashed = pages.all { it.third != null }
        return SourceManifest(
            sourceId = label,
            libraryId = "library-id",
            label = label,
            pages = pages.map { (name, size, hash) ->
                PageEntry("$label/container", null, name, size, hash.takeIf { hashed })
            },
            hashed = hashed,
            bytesRead = 0,
            durationMs = 0,
        )
    }

    @Test
    fun keepsLeftOrderAndFillsInRightOnlyPages() {
        val left = manifest("A", page("1", hash = "a"), page("2", hash = "b"), page("4", hash = "d"))
        val right = manifest("B", page("1", hash = "a"), page("3", hash = "c"), page("4", hash = "d"))

        val plan = MergePlan.build(PageComparison.compare(left, right))

        assertEquals(listOf("1", "2", "3", "4"), plan.map { it.name })
        assertEquals(listOf(false, false, true, false), plan.map { it.fromRight })
    }

    @Test
    fun rightOnlyPagesBeforeTheFirstMatchComeFirst() {
        val left = manifest("A", page("5", hash = "e"))
        val right = manifest("B", page("1", hash = "a"), page("2", hash = "b"), page("5", hash = "e"))

        val plan = MergePlan.build(PageComparison.compare(left, right))

        assertEquals(listOf("1", "2", "5"), plan.map { it.name })
    }

    @Test
    fun leftOnlyPagesStayInPlace() {
        val left = manifest("A", page("1", hash = "a"), page("2", hash = "b"), page("3", hash = "c"))
        val right = manifest("B", page("1", hash = "a"))

        val plan = MergePlan.build(PageComparison.compare(left, right))

        assertEquals(listOf("1", "2", "3"), plan.map { it.name })
        assertTrue(plan.none { it.fromRight })
    }
}
