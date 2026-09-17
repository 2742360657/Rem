package dev.susnowy.gallery.model

/**
 * Device-side projection of one portable Series.
 *
 * `catalog.json` stays the source of truth; this type lets the UI list, reorder and rename a
 * series without loading the whole catalog.
 */
data class MediaSeries(
    val id: String,
    val libraryId: String,
    val title: String,
    val aliases: List<String> = emptyList(),
    val members: List<MediaSeriesMember> = emptyList(),
    val revision: Long = 1,
) {
    val memberIds: List<String> get() = members.map(MediaSeriesMember::workId)

    /** Members in reading order: explicit manual order first, then season/episode or volume/chapter. */
    fun membersInOrder(): List<MediaSeriesMember> = members.sortedWith(seriesMemberOrder)

    /** Human-readable position summary, or null when the member carries no numbering. */
    fun positionLabel(workId: String): String? {
        val member = members.firstOrNull { it.workId == workId } ?: return null
        return buildString {
            member.season?.let { append("第 $it 季") }
            member.episode?.let { if (isNotEmpty()) append(" · "); append("第 ${it.trimmed()} 集") }
            member.volume?.let { if (isNotEmpty()) append(" · "); append("第 ${it.trimmed()} 卷") }
            member.chapter?.let { if (isNotEmpty()) append(" · "); append("第 ${it.trimmed()} 章") }
        }.takeIf(String::isNotEmpty)
    }
}

data class MediaSeriesMember(
    val workId: String,
    val sortIndex: Double? = null,
    val season: Int? = null,
    val episode: Double? = null,
    val volume: Double? = null,
    val chapter: Double? = null,
) {
    val numbered: Boolean
        get() = season != null || episode != null || volume != null || chapter != null
}

private val seriesMemberOrder: Comparator<MediaSeriesMember> =
    compareBy<MediaSeriesMember> { it.sortIndex ?: Double.MAX_VALUE }
        .thenBy { it.season ?: Int.MAX_VALUE }
        .thenBy { it.episode ?: Double.MAX_VALUE }
        .thenBy { it.volume ?: Double.MAX_VALUE }
        .thenBy { it.chapter ?: Double.MAX_VALUE }
        .thenBy(MediaSeriesMember::workId)

fun PortableSeries.toMediaSeries(libraryId: String): MediaSeries = MediaSeries(
    id = id,
    libraryId = libraryId,
    title = title,
    aliases = aliases,
    members = members.map {
        MediaSeriesMember(
            workId = it.workId,
            sortIndex = it.sortIndex,
            season = it.season,
            episode = it.episode,
            volume = it.volume,
            chapter = it.chapter,
        )
    },
    revision = revision,
)

/**
 * Portable form of a series edit.
 *
 * [order] is the reading order the user arranged; list position becomes `sort_index`, which
 * is what every device reads back. Numbering is preserved unless [clearPositions] is set.
 */
fun MediaSeries.toPortableSeries(
    order: List<String> = memberIds,
    clearPositions: Boolean = false,
): PortableSeries = PortableSeries(
    id = id,
    title = title,
    aliases = aliases,
    members = order.mapIndexed { index, workId ->
        val existing = members.firstOrNull { it.workId == workId }
        PortableSeriesMember(
            workId = workId,
            sortIndex = index.toDouble(),
            season = if (clearPositions) null else existing?.season,
            episode = if (clearPositions) null else existing?.episode,
            volume = if (clearPositions) null else existing?.volume,
            chapter = if (clearPositions) null else existing?.chapter,
        )
    },
    fieldSources = emptyMap(),
    revision = revision,
    updatedAt = java.time.Instant.now().toString(),
)

private fun Double.trimmed(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()
