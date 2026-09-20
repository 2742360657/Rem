package dev.susnowy.gallery.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything Rem can display. Only these extensions are read out of a Library. */
enum class MediaType { IMAGE, VIDEO }

/** An early GPS or location reading taken from the media's own metadata. */
data class Place(val latitude: Double, val longitude: Double) {
    /** Decimal degrees, five places — the precision the old viewer reported. */
    val label: String get() = "%.5f, %.5f".format(latitude, longitude)
}

/**
 * One playable or viewable file inside the Library.
 *
 * [path] is relative to the Library root and always starts with `相册/` or `画集/`, so the
 * entry carries its own filing and needs no separate category column in the index.
 *
 * [captured] is the media's own timestamp; when it is absent the file's modification time
 * decides the order, which is why [orderTime] rather than [captured] is what callers sort on.
 */
data class Entry(
    val path: String,
    val size: Long,
    val modified: Long,
    val captured: Long? = null,
    val place: Place? = null,
) {
    val mediaType: MediaType? = MediaTypes.of(path)
    val orderTime: Long get() = captured ?: modified
    val fileName: String get() = path.substringAfterLast('/')
    val displayName: String get() = fileName.substringBeforeLast('.', fileName)

    /** The `作者名称-项目名称` folder this file sits in, or `null` for an album file. */
    val projectFolder: String?
        get() = path.takeIf { it.startsWith("$COLLECTION/") }
            ?.split('/')
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }

    /**
     * The `0001` prefix of a collection file, or `null` when the name does not start with exactly
     * four digits. Applied to the name without its extension on purpose: `0001.jpg.bak` is a
     * malformed name that should still report item 1 rather than sort to the end.
     */
    val sequence: Int?
        get() = if (projectFolder == null) null
        else SEQUENCE.find(fileName.substringBeforeLast('.', fileName))?.value?.toIntOrNull()

    /**
     * What the scrollbar shows while the list is being dragged: the time and place for an album
     * file, the four-digit number for a collection file.
     */
    val positionLabel: String
        get() = sequence?.let { "%04d".format(it) } ?: TIME.format(
            Instant.ofEpochMilli(orderTime).atZone(ZoneId.systemDefault()),
        )
}

/** A `作者名称-项目名称` folder, split once so scrolling never re-parses the name. */
data class Project(
    val folder: String,
    val author: String,
    val name: String,
    val entries: List<Entry>,
) {
    /** The folder name minus its numbers and extension — what a reader actually looks for. */
    val imageCount: Int get() = entries.count { it.mediaType == MediaType.IMAGE }
    val videoCount: Int get() = entries.count { it.mediaType == MediaType.VIDEO }

    /** Files whose names do not follow the `0001` rule; they are listed after the rest. */
    val offRuleCount: Int get() = entries.count { it.sequence == null }
}

/** The Library's top level, as declared by the product rules. */
enum class Section { ALBUM, COLLECTION }

const val ALBUM = "相册"
const val COLLECTION = "画集"
const val INBOX = "待分类"
const val INTERNAL = ".gallery"

object MediaTypes {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic", "heif")
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "ts", "wmv", "flv")

    fun of(path: String): MediaType? = when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        in IMAGE_EXTENSIONS -> MediaType.IMAGE
        in VIDEO_EXTENSIONS -> MediaType.VIDEO
        else -> null
    }
}

/** A collection file's leading `0001`. `\D` keeps `00012.jpg` from reading as item 1. */
private val SEQUENCE = Regex("^\\d{4}(?=\\D|$)")

/** Scrollbar rows are one line wide, so minutes are the finest useful unit. */
private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Splits `作者名称-项目名称` on its first hyphen; an unhyphenated name has no author. */
fun splitProjectFolder(folder: String): Pair<String, String>? {
    val separator = folder.indexOf('-')
    if (separator <= 0 || separator == folder.lastIndex) return null
    return folder.substring(0, separator).trim() to folder.substring(separator + 1).trim()
}
