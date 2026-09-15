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
    val authors: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val language: String? = null,
    val sourceUrl: String? = null,
)

object FilenameMetadataParser {
    private val authorPrefix = Regex("""^\s*[\[（【(]([^\]）】)]+)[\]）】)]\s*(.+)$""")
    private val numberedTitle = Regex("^(.+?)\\s*[-–—]\\s*(?:ch(?:apter)?|vol(?:ume)?|ep(?:isode)?|#)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:[-–—]\\s*(.*))?$", RegexOption.IGNORE_CASE)

    fun parse(rawName: String): RecognizedMetadata {
        val extension = rawName.substringAfterLast('.', "").lowercase()
        val name = if (extension in MEDIA_EXTENSIONS) rawName.substringBeforeLast('.') else rawName
        val trimmedName = name.trim()
        val authorMatch = authorPrefix.matchEntire(trimmedName)
        val author = authorMatch?.groupValues?.getOrNull(1)?.trim()
        val withoutAuthor = authorMatch?.groupValues?.getOrNull(2)?.trim() ?: trimmedName
        val seriesMatch = numberedTitle.matchEntire(withoutAuthor)
        return if (seriesMatch != null) {
            val series = seriesMatch.groupValues[1].trim()
            val number = seriesMatch.groupValues[2].toDoubleOrNull()
            val entry = seriesMatch.groupValues.getOrNull(3)?.trim().orEmpty()
            RecognizedMetadata(
                title = entry.ifEmpty { withoutAuthor },
                series = series,
                sortIndex = number,
                authors = listOfNotNull(author),
            )
        } else {
            RecognizedMetadata(title = withoutAuthor, authors = listOfNotNull(author))
        }
    }

    private val MEDIA_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif",
        "zip", "cbz", "mp4", "mkv", "webm", "mov", "m4v", "avi",
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
