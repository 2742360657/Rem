package dev.susnowy.gallery.compare

import dev.susnowy.gallery.media.PageEntry
import dev.susnowy.gallery.media.SourceManifest
import java.util.Locale

/** How two pages were matched. */
enum class PageMatchKind {
    /** Same bytes on both sides (only possible when both manifests were hashed). */
    IDENTICAL,

    /** Same page name but the content differs. */
    SAME_NAME_DIFFERENT_CONTENT,

    /** Same page name and same size, without content hashes: a duplicate candidate. */
    SIZE_MATCH_UNVERIFIED,
}

data class PageMatch(
    val leftIndex: Int,
    val rightIndex: Int,
    val kind: PageMatchKind,
)

/**
 * Result of comparing two sources page by page.
 *
 * The report is evidence, not a verdict: it never deletes or rewrites anything, and a
 * `SIZE_MATCH_UNVERIFIED` page is explicitly not called a duplicate.
 */
data class EditionComparisonReport(
    val left: SourceManifest,
    val right: SourceManifest,
    val matches: List<PageMatch>,
    val leftOnly: List<Int>,
    val rightOnly: List<Int>,
    val deep: Boolean,
) {
    val identicalCount: Int get() = matches.count { it.kind == PageMatchKind.IDENTICAL }
    val conflictingCount: Int get() = matches.count { it.kind == PageMatchKind.SAME_NAME_DIFFERENT_CONTENT }
    val unverifiedCount: Int get() = matches.count { it.kind == PageMatchKind.SIZE_MATCH_UNVERIFIED }

    /** Pages that are identical or same-name-same-size, i.e. candidates for de-duplication. */
    val duplicateCandidates: Int get() = identicalCount + unverifiedCount

    val summary: String
        get() = buildString {
            append("${left.label} ${left.pageCount} 页 / ${right.label} ${right.pageCount} 页：")
            if (deep) {
                append("完全重复 $identicalCount 页")
            } else {
                append("疑似重复 $duplicateCandidates 页（未读内容）")
            }
            if (conflictingCount > 0) append("、同名但内容不同 $conflictingCount 页")
            append("、仅左侧 ${leftOnly.size} 页、仅右侧 ${rightOnly.size} 页")
        }
}

/**
 * Page-level comparison of two manifests.
 *
 * Matching runs in two deterministic passes: content hashes first (only possible for hashed
 * manifests), then page names. Nothing here reads bytes; the manifests already carry what was
 * read once by [dev.susnowy.gallery.media.PageManifestService].
 */
object PageComparison {
    fun compare(left: SourceManifest, right: SourceManifest): EditionComparisonReport {
        val matchedLeft = mutableSetOf<Int>()
        val matchedRight = mutableSetOf<Int>()
        val matches = mutableListOf<PageMatch>()

        matchByHash(left, right, matchedLeft, matchedRight, matches)
        matchByName(left, right, matchedLeft, matchedRight, matches)

        val leftOnly = left.pages.indices.filterNot(matchedLeft::contains)
        val rightOnly = right.pages.indices.filterNot(matchedRight::contains)
        return EditionComparisonReport(
            left = left,
            right = right,
            matches = matches.sortedBy(PageMatch::leftIndex),
            leftOnly = leftOnly,
            rightOnly = rightOnly,
            deep = left.hashed && right.hashed,
        )
    }

    private fun matchByHash(
        left: SourceManifest,
        right: SourceManifest,
        matchedLeft: MutableSet<Int>,
        matchedRight: MutableSet<Int>,
        matches: MutableList<PageMatch>,
    ) {
        if (!left.hashed || !right.hashed) return
        val rightByHash = right.pages.withIndex()
            .mapNotNull { (index, page) -> page.sha256?.let { it to index } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size == 1 }
            .mapValues { (_, indexes) -> indexes.single() }
        left.pages.forEachIndexed { leftIndex, page ->
            val hash = page.sha256 ?: return@forEachIndexed
            val rightIndex = rightByHash[hash] ?: return@forEachIndexed
            if (rightIndex in matchedRight) return@forEachIndexed
            matchedLeft += leftIndex
            matchedRight += rightIndex
            matches += PageMatch(leftIndex, rightIndex, PageMatchKind.IDENTICAL)
        }
    }

    private fun matchByName(
        left: SourceManifest,
        right: SourceManifest,
        matchedLeft: MutableSet<Int>,
        matchedRight: MutableSet<Int>,
        matches: MutableList<PageMatch>,
    ) {
        val rightByName = right.pages.withIndex()
            .filterNot { (index, _) -> index in matchedRight }
            .groupBy { (_, page) -> page.name.normalizedPageName() }
        left.pages.forEachIndexed { leftIndex, leftPage ->
            if (leftIndex in matchedLeft) return@forEachIndexed
            val candidate = rightByName[leftPage.name.normalizedPageName()]
                ?.firstOrNull { (index, _) -> index !in matchedRight }
                ?: return@forEachIndexed
            val (rightIndex, rightPage) = candidate
            matchedLeft += leftIndex
            matchedRight += rightIndex
            matches += PageMatch(
                leftIndex = leftIndex,
                rightIndex = rightIndex,
                kind = classify(leftPage, rightPage),
            )
        }
    }

    private fun classify(left: PageEntry, right: PageEntry): PageMatchKind = when {
        left.sha256 != null && right.sha256 != null && left.sha256 == right.sha256 ->
            PageMatchKind.IDENTICAL
        left.sha256 != null && right.sha256 != null ->
            PageMatchKind.SAME_NAME_DIFFERENT_CONTENT
        left.sizeBytes == right.sizeBytes ->
            PageMatchKind.SIZE_MATCH_UNVERIFIED
        else -> PageMatchKind.SAME_NAME_DIFFERENT_CONTENT
    }

    private fun String.normalizedPageName(): String =
        substringAfterLast('/').trim().lowercase(Locale.ROOT)
}

/** One page of a virtual merged reading order. */
data class MergePlanPage(
    val containerPath: String,
    val entryPath: String?,
    val name: String,
    val fromRight: Boolean,
)

/**
 * Builds a virtual merged reading order without touching any file.
 *
 * The left source keeps its order. A page that only exists on the right is inserted
 * immediately before the next page the two sources share, because that is the position it
 * occupies in the right source's own reading order; pages with no shared page after them are
 * appended at the end. Pages that only exist on the left stay where they are.
 */
object MergePlan {
    fun build(report: EditionComparisonReport): List<MergePlanPage> {
        val left = report.left.pages
        val right = report.right.pages
        val matchedLeftByRight = report.matches.associate { it.rightIndex to it.leftIndex }
        val insertions = mutableMapOf<Int, MutableList<Int>>()
        var anchor = left.size
        for (rightIndex in report.right.pages.indices.reversed()) {
            val leftIndex = matchedLeftByRight[rightIndex]
            if (leftIndex != null) {
                anchor = leftIndex
            } else {
                insertions.getOrPut(anchor) { mutableListOf() }.add(0, rightIndex)
            }
        }
        val plan = mutableListOf<MergePlanPage>()
        left.forEachIndexed { leftIndex, page ->
            insertions[leftIndex]?.forEach { rightIndex ->
                plan += right[rightIndex].toPlanPage(fromRight = true)
            }
            plan += page.toPlanPage(fromRight = false)
        }
        insertions[left.size]?.forEach { rightIndex ->
            plan += right[rightIndex].toPlanPage(fromRight = true)
        }
        return plan
    }

    private fun PageEntry.toPlanPage(fromRight: Boolean) = MergePlanPage(
        containerPath = containerPath,
        entryPath = entryPath,
        name = name,
        fromRight = fromRight,
    )
}
