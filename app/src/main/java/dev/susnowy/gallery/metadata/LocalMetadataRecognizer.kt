package dev.susnowy.gallery.metadata

import android.util.Xml
import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.storage.DocumentTreeStorage
import java.io.InputStream
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

data class RecognizedMetadata(
    val title: String? = null,
    val series: String? = null,
    val sortIndex: Double? = null,
    val volume: Double? = null,
    val season: Int? = null,
    val episode: Double? = null,
    val authors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val language: String? = null,
    val sourceUrl: String? = null,
)

object FilenameMetadataParser {
    private val authorPrefix = Regex("""^\s*[\[（【(]([^\]）】)]+)[\]）】)]\s*(.+)$""")
    private val numberedTitle = Regex("^(.+?)\\s*[-–—]\\s*(?:ch(?:apter)?|vol(?:ume)?|ep(?:isode)?|#)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:[-–—]\\s*(.*))?$", RegexOption.IGNORE_CASE)
    private val numberedRelease = Regex("^(.+?)\\s*[-–—]\\s*(?:no\\.?\\s*)?(\\d{1,5})(?:\\s+|\\s*[-–—]\\s*)(.+)$", RegexOption.IGNORE_CASE)
    private val seasonEpisode = Regex("[Ss](\\d{1,2})[Ee](\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val episodeWithParent = Regex("^(?:EP?|Episode|第)?\\s*(\\d+(?:\\.\\d+)?)(?:\\s*集)?(?:\\s*[-–—]\\s*(.*))?$", RegexOption.IGNORE_CASE)
    private val chapterWithParent = Regex("^(?:ch(?:apter)?|vol(?:ume)?|第)\\.?\\s*(\\d+(?:\\.\\d+)?)(?:\\s*[话話章卷])?(?:\\s*[-–—]\\s*(.*))?$", RegexOption.IGNORE_CASE)
    private val releaseFacts = Regex("""\s*[\[【(（]\s*\d+\s*[PpVv](?:\s*[-+、,]\s*\d+\s*[PpVv])?(?:\s*[-–—]\s*[\d.]+\s*(?:[KMGTP]i?[Bb]?|[KMGTP]))?\s*[\]】)）]\s*$""")
    private val qualitySuffix = Regex("""(?:\s*[\[(【][^\])】]*(?:2160p|1080p|720p|HEVC|AVC|x26[45]|[0-9A-F]{8})[^\])】]*[\])】])+$""", RegexOption.IGNORE_CASE)
    private val leadingReleaseId = Regex("^\\d{3,}[._ -]+")
    private val trailingDate = Regex("""\s*[\[【(（]\d{4}(?:[.-]\d{1,2}){0,2}[\]】)）]\s*$""")

    fun parse(rawName: String, parentName: String? = null): RecognizedMetadata {
        val extension = rawName.substringAfterLast('.', "").lowercase()
        val name = if (extension in MEDIA_EXTENSIONS) rawName.substringBeforeLast('.') else rawName
        val trimmedName = name.replace(releaseFacts, "").trim()
        val parentAuthor = parentName?.let(::cleanParentName)
            ?.takeIf { it.isNotBlank() && it.lowercase() !in GENERIC_PARENTS }
        chapterWithParent.matchEntire(trimmedName)?.let { match ->
            val number = match.groupValues[1].toDoubleOrNull()
            return RecognizedMetadata(
                title = match.groupValues.getOrNull(2)?.trim().orEmpty().ifBlank { trimmedName },
                series = parentAuthor,
                sortIndex = number,
            )
        }
        val releaseMatch = numberedRelease.matchEntire(trimmedName)
        if (releaseMatch != null && releaseMatch.groupValues[0].contains(Regex("\\bNO\\.", RegexOption.IGNORE_CASE))) {
            val prefix = releaseMatch.groupValues[1].trim()
            val author = when {
                parentAuthor != null && prefix.contains(parentAuthor, ignoreCase = true) -> parentAuthor
                else -> prefix
            }
            return RecognizedMetadata(
                title = releaseMatch.groupValues[3].trim(),
                sortIndex = releaseMatch.groupValues[2].toDoubleOrNull(),
                authors = listOf(author),
            )
        }
        val authorMatch = authorPrefix.matchEntire(trimmedName)
        val author = authorMatch?.groupValues?.getOrNull(1)?.trim()
        val withoutAuthor = authorMatch?.groupValues?.getOrNull(2)?.trim() ?: trimmedName
        val normalizedName = withoutAuthor.replace(leadingReleaseId, "").trim()
        val seriesMatch = numberedTitle.matchEntire(normalizedName)
        return if (seriesMatch != null) {
            val series = seriesMatch.groupValues[1].trim()
            val number = seriesMatch.groupValues[2].toDoubleOrNull()
            val entry = seriesMatch.groupValues.getOrNull(3)?.trim().orEmpty()
            RecognizedMetadata(
                title = entry.ifEmpty { normalizedName },
                series = series,
                sortIndex = number,
                authors = listOfNotNull(author ?: matchingParentAuthor(parentAuthor, trimmedName)),
            )
        } else {
            RecognizedMetadata(
                title = normalizedName,
                authors = listOfNotNull(author ?: matchingParentAuthor(parentAuthor, trimmedName)),
            )
        }
    }

    fun parseVideo(rawName: String, parentName: String? = null): RecognizedMetadata {
        val extension = rawName.substringAfterLast('.', "").lowercase()
        val baseName = (if (extension in MEDIA_EXTENSIONS) rawName.substringBeforeLast('.') else rawName)
            .replace(qualitySuffix, "")
            .trim()
        seasonEpisode.find(baseName)?.let { match ->
            val series = baseName.substring(0, match.range.first)
                .removePrefixReleaseGroup().trim(' ', '-', '–', '—', '_', '.')
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toDoubleOrNull()
            val entry = baseName.substring(match.range.last + 1).trim(' ', '-', '–', '—', '_', '.')
            return RecognizedMetadata(
                title = entry.ifBlank { "第 ${match.groupValues[2]} 集" },
                series = series.ifBlank { parentName?.let(::cleanParentName) },
                sortIndex = episode,
                season = season,
                episode = episode,
            )
        }
        val parent = parentName?.let(::cleanParentName)
            ?.takeIf { it.isNotBlank() && it.lowercase() !in GENERIC_PARENTS }
        episodeWithParent.matchEntire(baseName)?.let { match ->
            val episode = match.groupValues[1].toDoubleOrNull()
            return RecognizedMetadata(
                title = match.groupValues.getOrNull(2)?.trim().orEmpty().ifBlank { "第 ${match.groupValues[1]} 集" },
                series = parent,
                sortIndex = episode,
                episode = episode,
            )
        }
        val parsed = parse(rawName, parentName)
        return if (parsed.series == null && parent != null && looksEpisodic(rawName)) parsed.copy(series = parent) else parsed
    }

    fun looksEpisodic(rawName: String): Boolean {
        val baseName = rawName.substringBeforeLast('.', rawName).replace(qualitySuffix, "").trim()
        return seasonEpisode.containsMatchIn(baseName) || episodeWithParent.matches(baseName) ||
            Regex("(?:^|[^A-Za-z])[Ee][Pp]?\\s*\\d+", RegexOption.IGNORE_CASE).containsMatchIn(baseName)
    }

    private fun matchingParentAuthor(parent: String?, child: String): String? =
        parent?.takeIf { child.startsWith(it, ignoreCase = true) }

    private fun cleanParentName(value: String): String = value
        .replace(leadingReleaseId, "")
        .replace(trailingDate, "")
        .trim()

    private fun String.removePrefixReleaseGroup(): String = replace(Regex("^\\[[^]]+]\\s*"), "")

    private val MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
        "zip", "cbz", "mp4", "mkv", "webm", "mov", "m4v", "avi",
    )
    private val GENERIC_PARENTS = setOf(
        "imagesets", "comics", "comic", "manga", "works", "anime", "animation",
        "videos", "movies", "series", "shows", "image", "images", "media",
        "漫画", "动漫", "动画", "番剧", "视频", "作品", "未分类",
    )
}

class ComicInfoReader {
    fun fromDirectory(storage: DocumentTreeStorage, directoryPath: String): RecognizedMetadata? {
        val path = "$directoryPath/ComicInfo.xml"
        val document = storage.find(path) ?: return null
        return storage.openInput(document).use(::parse)
    }

    fun fromArchive(storage: DocumentTreeStorage, archivePath: String): RecognizedMetadata? {
        val document = LibraryDocument(archivePath, archivePath.substringAfterLast('/'), false)
        return storage.openInput(document).buffered().use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: return@use null
                    if (!entry.isDirectory && entry.name.substringAfterLast('/')
                            .equals("ComicInfo.xml", ignoreCase = true)
                    ) {
                        return@use parse(zip)
                    }
                    zip.closeEntry()
                }
                null
            }
        }
    }

    fun parse(input: InputStream): RecognizedMetadata? {
        val parser = Xml.newPullParser().apply {
            runCatching { setFeature("http://xmlpull.org/v1/doc/features.html#process-docdecl", false) }
            setInput(input, "UTF-8")
        }
        val values = mutableMapOf<String, MutableList<String>>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val key = parser.name
                if (key in SUPPORTED_FIELDS) {
                    val value = parser.nextText().trim()
                    if (value.isNotEmpty()) values.getOrPut(key) { mutableListOf() } += value
                }
            }
            event = parser.next()
        }
        if (values.isEmpty()) return null
        val authors = (values["Writer"].orEmpty() + values["Penciller"].orEmpty() +
            values["Inker"].orEmpty()).flatMap(::splitValues).distinct()
        val tags = (values["Genre"].orEmpty() + values["Tags"].orEmpty())
            .flatMap(::splitValues).distinct()
        return RecognizedMetadata(
            title = values["Title"]?.firstOrNull(),
            series = values["Series"]?.firstOrNull(),
            sortIndex = values["Number"]?.firstOrNull()?.toDoubleOrNull(),
            volume = values["Volume"]?.firstOrNull()?.toDoubleOrNull(),
            authors = authors,
            tags = tags,
            language = values["LanguageISO"]?.firstOrNull(),
            sourceUrl = values["Web"]?.firstOrNull(),
        )
    }

    private fun splitValues(value: String): List<String> =
        value.split(',', ';', '，', '；').map(String::trim).filter(String::isNotBlank)

    companion object {
        private val SUPPORTED_FIELDS = setOf(
            "Title", "Series", "Number", "Volume", "Writer", "Penciller", "Inker",
            "Genre", "Tags", "LanguageISO", "Web", "PageCount",
        )
    }
}

interface MetadataProvider {
    val id: String
    suspend fun search(query: String): List<MetadataCandidate>
    suspend fun fetch(candidate: MetadataCandidate): RecognizedMetadata
}

data class MetadataCandidate(
    val providerId: String,
    val remoteId: String,
    val title: String,
    val confidence: Double,
)
