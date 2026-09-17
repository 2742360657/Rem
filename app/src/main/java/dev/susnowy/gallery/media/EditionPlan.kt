package dev.susnowy.gallery.media

import dev.susnowy.gallery.model.PortableAsset
import dev.susnowy.gallery.model.PortableEditionAsset
import dev.susnowy.gallery.model.SourceKind

/**
 * Turns an Edition's ordered page plan into reader pages.
 *
 * A virtual merged Edition references pages across several Assets, so each page has to say
 * which file holds it: a page inside a directory becomes that file's path, a page inside an
 * archive keeps the archive as its container plus the entry, and a single image file is its
 * own page.
 *
 * Returns null when the plan cannot be read as a flat page list — a member that means "this
 * whole directory or archive" would have to be listed first, and the caller should fall back
 * to the normal single-source reader instead of pretending it is one page.
 */
fun editionPlanPages(
    members: List<PortableEditionAsset>,
    assetsById: Map<String, PortableAsset>,
): List<ImagePage>? {
    if (members.isEmpty()) return null
    val ordered = members.sortedWith(
        compareBy<PortableEditionAsset> { it.sortIndex ?: Double.MAX_VALUE }
            .thenBy(PortableEditionAsset::assetId),
    )
    val pages = ArrayList<ImagePage>(ordered.size)
    ordered.forEach { member ->
        val asset = assetsById[member.assetId] ?: return null
        val container = asset.relativePath.trimEnd('/')
        when (asset.source) {
            SourceKind.DIRECTORY -> {
                val entry = member.entryPath ?: return null
                pages += ImagePage(
                    name = entry.substringAfterLast('/'),
                    uri = null,
                    archiveEntry = null,
                    relativePath = "$container/$entry",
                )
            }
            SourceKind.ARCHIVE -> {
                val entry = member.entryPath ?: return null
                pages += ImagePage(
                    name = entry.substringAfterLast('/'),
                    uri = null,
                    archiveEntry = entry,
                    relativePath = container,
                )
            }
            SourceKind.FILE, SourceKind.SYSTEM_IMPORT -> {
                pages += ImagePage(
                    name = container.substringAfterLast('/'),
                    uri = null,
                    archiveEntry = null,
                    relativePath = container,
                )
            }
            else -> return null
        }
    }
    return pages.takeIf(List<ImagePage>::isNotEmpty)
}
