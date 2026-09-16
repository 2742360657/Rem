package dev.susnowy.gallery.media

/**
 * Returns off-screen pages in the order the reader should warm them. The side
 * matching the current scroll direction receives a wider window while a small
 * back buffer keeps short reversals instant.
 */
internal fun comicPreloadOrder(
    firstVisible: Int,
    lastVisible: Int,
    pageCount: Int,
    scrollingForward: Boolean,
    ahead: Int = 5,
    behind: Int = 2,
): List<Int> {
    if (pageCount <= 0) return emptyList()
    val first = firstVisible.coerceIn(0, pageCount - 1)
    val last = lastVisible.coerceIn(first, pageCount - 1)
    val forward = ((last + 1)..minOf(last + ahead, pageCount - 1)).toList()
    val backward = (first - 1 downTo maxOf(first - ahead, 0)).toList()
    val shortForward = forward.take(behind)
    val shortBackward = backward.take(behind)
    return if (scrollingForward) forward + shortBackward else backward + shortForward
}
