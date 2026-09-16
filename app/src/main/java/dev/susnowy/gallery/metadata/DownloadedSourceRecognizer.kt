package dev.susnowy.gallery.metadata

/**
 * Recognizes stable identifiers that survive custom downloader naming rules.
 * It deliberately does not perform network requests: providers can use these
 * source tags later without guessing from a human-edited title.
 */
object DownloadedSourceRecognizer {
    private val ehViewerDirectory = Regex("^(\\d{4,})-(.+)$")
    private val pixivPage = Regex("(?<!\\d)(\\d{5,})_p(\\d+)(?!\\d)", RegexOption.IGNORE_CASE)
    private val pixivUgoira = Regex("(?<!\\d)(\\d{5,})_ugoira(?:\\d+x\\d+)?", RegexOption.IGNORE_CASE)
    private val jmName = Regex(
        "(?:^|[\\[（( _.-])JM\\s*(\\d{3,})(?:\\]|）|\\)|[ _.-]|$)",
        RegexOption.IGNORE_CASE,
    )
    private val numericDirectory = Regex("^\\d{3,}$")
    private val jmRoots = setOf("jm", "jmcomic", "禁漫", "禁漫天堂")
    private val pixivRoots = setOf("pixiv", "pixivutil", "pixivutil2")

    fun fromDirectory(relativePath: String, fileNames: Collection<String>): RecognizedMetadata? {
        val segments = relativePath.pathSegments()
        if (segments.isEmpty()) return null
        val directoryName = segments.last()

        val hasEhViewerMarker = fileNames.any { it.equals(".ehviewer", ignoreCase = true) }
        val underEhViewerRoot = segments.any { it.equals("EhViewer", ignoreCase = true) }
        ehViewerDirectory.matchEntire(directoryName)?.takeIf { hasEhViewerMarker || underEhViewerRoot }
            ?.let { match ->
                val gid = match.groupValues[1]
                return RecognizedMetadata(
                    title = match.groupValues[2].trim().ifBlank { "E-Hentai $gid" },
                    tags = listOf("source:ehviewer", "eh:gid:$gid"),
                )
            }

        jmReference(segments)?.let { reference ->
            val leafTitle = directoryName.takeUnless(numericDirectory::matches)
                ?.removeJmPrefix(reference.albumId)
                ?.takeIf(String::isNotBlank)
            return RecognizedMetadata(
                title = leafTitle ?: if (reference.photoId == null) {
                    "JM${reference.albumId}"
                } else {
                    "JM${reference.albumId} · ${reference.photoId}"
                },
                sortIndex = reference.photoId?.toDoubleOrNull(),
                tags = buildList {
                    add("source:jm")
                    add("jm:album:${reference.albumId}")
                    reference.photoId?.let { add("jm:photo:$it") }
                },
            )
        }

        val pixivMatches = fileNames.flatMap(::pixivReferences).distinctBy(PixivReference::workId)
        if (pixivMatches.size == 1) {
            val reference = pixivMatches.single()
            val meaningfulDirectory = directoryName
                .takeUnless { it.equals(reference.workId, ignoreCase = true) }
                ?.takeUnless { name -> pixivRoots.any { name.equals(it, ignoreCase = true) } }
                ?.takeIf { !numericDirectory.matches(it) }
            return RecognizedMetadata(
                title = meaningfulDirectory ?: "Pixiv ${reference.workId}",
                tags = buildList {
                    add("source:pixiv")
                    add("pixiv:id:${reference.workId}")
                    if (reference.ugoira) add("format:ugoira")
                },
                sourceUrl = "https://www.pixiv.net/artworks/${reference.workId}",
            )
        }

        return null
    }

    fun containsMultiplePixivWorks(fileNames: Collection<String>): Boolean =
        fileNames.flatMap(::pixivReferences).map(PixivReference::workId).distinct().size > 1

    fun fromFile(relativePath: String): RecognizedMetadata? {
        val name = relativePath.substringAfterLast('/')
        pixivReferences(name).firstOrNull()?.let { reference ->
            return RecognizedMetadata(
                title = if (reference.ugoira) {
                    "Pixiv ${reference.workId} · 动图"
                } else {
                    "Pixiv ${reference.workId} · ${reference.pageIndex?.plus(1) ?: 1}"
                },
                sortIndex = reference.pageIndex?.toDouble(),
                tags = buildList {
                    add("source:pixiv")
                    add("pixiv:id:${reference.workId}")
                    if (reference.ugoira) add("format:ugoira")
                },
                sourceUrl = "https://www.pixiv.net/artworks/${reference.workId}",
            )
        }
        jmName.find(name.substringBeforeLast('.', name))?.let { match ->
            val id = match.groupValues[1]
            return RecognizedMetadata(
                title = name.substringBeforeLast('.', name).removeJmPrefix(id).ifBlank { "JM$id" },
                tags = listOf("source:jm", "jm:album:$id"),
            )
        }
        return null
    }

    private fun pixivReferences(name: String): List<PixivReference> = buildList {
        pixivPage.findAll(name).forEach { match ->
            add(
                PixivReference(
                    workId = match.groupValues[1],
                    pageIndex = match.groupValues[2].toIntOrNull(),
                    ugoira = false,
                ),
            )
        }
        pixivUgoira.findAll(name).forEach { match ->
            add(PixivReference(workId = match.groupValues[1], pageIndex = null, ugoira = true))
        }
    }

    private fun jmReference(segments: List<String>): JmReference? {
        val rootIndex = segments.indexOfFirst { it.lowercase() in jmRoots }
        if (rootIndex >= 0) {
            val ids = segments.drop(rootIndex + 1).filter(numericDirectory::matches)
            if (ids.isNotEmpty()) return JmReference(ids.first(), ids.getOrNull(1))
        }
        jmName.find(segments.last())?.let { return JmReference(it.groupValues[1], null) }
        return null
    }

    private fun String.removeJmPrefix(id: String): String = replace(
        Regex("^\\s*(?:\\[?JM\\s*)?$id(?:\\]|）|\\)|[ _.-]+)?", RegexOption.IGNORE_CASE),
        "",
    ).trim()

    private fun String.pathSegments(): List<String> = replace('\\', '/').trim('/').split('/')
        .filter(String::isNotBlank)

    private data class JmReference(val albumId: String, val photoId: String?)
    private data class PixivReference(val workId: String, val pageIndex: Int?, val ugoira: Boolean)
}

fun mergeRecognizedMetadata(vararg values: RecognizedMetadata?): RecognizedMetadata? {
    val available = values.filterNotNull()
    if (available.isEmpty()) return null
    fun <T> firstValue(selector: (RecognizedMetadata) -> T?): T? = available.firstNotNullOfOrNull(selector)
    fun firstList(selector: (RecognizedMetadata) -> List<String>): List<String> =
        available.firstNotNullOfOrNull { selector(it).takeIf(List<String>::isNotEmpty) }.orEmpty()
    return RecognizedMetadata(
        title = firstValue(RecognizedMetadata::title),
        series = firstValue(RecognizedMetadata::series),
        sortIndex = firstValue(RecognizedMetadata::sortIndex),
        volume = firstValue(RecognizedMetadata::volume),
        season = firstValue(RecognizedMetadata::season),
        episode = firstValue(RecognizedMetadata::episode),
        authors = firstList(RecognizedMetadata::authors),
        tags = available.flatMap(RecognizedMetadata::tags).distinct(),
        language = firstValue(RecognizedMetadata::language),
        sourceUrl = firstValue(RecognizedMetadata::sourceUrl),
    )
}
