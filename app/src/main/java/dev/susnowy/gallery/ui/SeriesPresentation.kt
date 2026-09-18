package dev.susnowy.gallery.ui

import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.SeriesRef

/** A presentation-only shelf derived from portable per-item series assignments. */
data class SeriesShelf(
    val key: String,
    val title: String,
    val items: List<MediaItem>,
    val isUnassigned: Boolean = false,
)

object SeriesPresentation {
    const val UNASSIGNED_KEY = "__unassigned__"

    fun shelves(items: List<MediaItem>): List<SeriesShelf> {
        val assigned = items.filter { !it.series?.title.isNullOrBlank() }
            // Schema v4 gives the relationship one owner and a stable Series id. Grouping by
            // title merges two legitimate same-named Series and makes the editor target an
            // arbitrary one, so presentation must keep the entity identity.
            .groupBy { "series:${requireNotNull(it.series).id}" }
            .map { (key, members) ->
                SeriesShelf(
                    key = key,
                    title = members.mapNotNull { it.series?.title?.trim() }
                        .minWithOrNull(String.CASE_INSENSITIVE_ORDER)
                        .orEmpty(),
                    items = orderEntries(members),
                )
            }
            .sortedWith(
                compareBy(String.CASE_INSENSITIVE_ORDER, SeriesShelf::title)
                    .thenBy(SeriesShelf::key),
            )
        val unassigned = items.filter { it.series?.title.isNullOrBlank() }
            .takeIf(List<MediaItem>::isNotEmpty)
            ?.let { members ->
                SeriesShelf(
                    key = UNASSIGNED_KEY,
                    title = "单篇 / 未归系列",
                    items = members.sortedWith(
                        compareBy(String.CASE_INSENSITIVE_ORDER, MediaItem::displayTitle)
                            .thenBy(MediaItem::relativePath),
                    ),
                    isUnassigned = true,
                )
            }
        return assigned + listOfNotNull(unassigned)
    }

    fun orderEntries(items: List<MediaItem>): List<MediaItem> = items.sortedWith(
        compareBy<MediaItem> { position(it.series).bucket }
            .thenBy { position(it.series).major }
            .thenBy { position(it.series).minor }
            .thenBy(String.CASE_INSENSITIVE_ORDER, MediaItem::displayTitle)
            .thenBy(MediaItem::relativePath),
    )

    private fun position(series: SeriesRef?): SeriesPosition = when {
        series?.sortIndex != null -> SeriesPosition(0, series.sortIndex, 0.0)
        series?.season != null || series?.episode != null -> SeriesPosition(
            1,
            series.season?.toDouble() ?: 0.0,
            series.episode ?: 0.0,
        )
        series?.volume != null || series?.chapter != null -> SeriesPosition(
            1,
            series.volume ?: 0.0,
            series.chapter ?: 0.0,
        )
        else -> SeriesPosition(2, 0.0, 0.0)
    }

    private data class SeriesPosition(val bucket: Int, val major: Double, val minor: Double)
}
