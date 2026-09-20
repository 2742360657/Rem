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

    /** The folder that directly contains this file, or `null` for a file at a browsable root. */
    val parentFolder: String?
        get() = path.substringBeforeLast('/', "").ifEmpty { null }

    /** The `作者名称-项目名称` folder this file sits in, or `null` for an album file. */
    val projectFolder: String?
        get() = path.takeIf { it.startsWith("$COLLECTION/") }
            ?.split('/')
            ?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }

    /**
     * The number this file carries for the「序号」sort, or `null` for an album file.
     *
     * Names are free now, so any run of digits counts — `01.jpg`, `12.png` and `zz001_002.jpg` all
     * have a position — and a name with no digits at all sorts last. Album files are excluded
     * because the album has a single fixed order of its own (capture time).
     */
    val sequence: Long?
        get() = if (projectFolder == null) null else sequenceOf(fileName)

    /**
     * What the scrollbar shows while the list is being dragged: the time and place for an album
     * file, the four-digit number for a collection file.
     */
    val positionLabel: String
        get() = sequence?.let { "%04d".format(it) } ?: TIME.format(
            Instant.ofEpochMilli(orderTime).atZone(ZoneId.systemDefault()),
        )
}

/**
 * One folder inside the browsable tree.
 *
 * Kept as a path rather than an object: its name is the last segment, its parent is everything
 * before it, and nothing else about it needs storing. Folders appear in the list even when they
 * hold no media, because an empty folder the user made is still part of their library.
 */
data class Folder(val path: String) {
    val name: String get() = path.substringAfterLast('/')

    /** The folder that contains this one, or `null` when this is a first-level folder. */
    val parent: String? get() = path.substringBeforeLast('/', "").ifEmpty { null }

    /**
     * Every folder between the collection root and this one, outermost first.
     *
     * `画集` itself is left out: it is the section's name, not a step the user took, and the tab
     * bar already says where they are.
     */
    fun ancestorsWithinCollection(): List<String> {
        val segments = path.split('/')
        return (2 until segments.size).map { segments.take(it).joinToString("/") }
    }
}

/** How the collection is ordered. The album has one fixed order and does not use this. */
enum class SortMode(val title: String) {
    SEQUENCE("序号"),
    NAME("名称"),
    CAPTURED("拍摄时间"),
    MODIFIED("修改时间"),
    SIZE("文件大小"),
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

/**
 * The number a file name carries for the「序号」sort, or `null` when it carries none.
 *
 * Any run of digits counts, not just a leading zero-padded one, because names are free now:
 * `01.jpg`, `12.png` and `zz001_002.jpg` all have a position, and `cover.jpg` simply has none and
 * sorts last. The first run wins, which is the one a reader's eye lands on.
 */
private val NUMBER_RUN = Regex("\\d+")

fun sequenceOf(fileName: String): Long? =
    NUMBER_RUN.find(fileName.substringBeforeLast('.', fileName))?.value?.toLongOrNull()

/**
 * Natural order: digit runs compare as numbers, everything else compares as text.
 *
 * Plain string order puts `10.jpg` before `2.jpg`, which is wrong for every folder of numbered
 * images. Digits are therefore split out and compared numerically at the same position.
 */
val NATURAL_ORDER: Comparator<String> = Comparator { left, right ->
    var i = 0
    var j = 0
    while (i < left.length && j < right.length) {
        val leftDigit = left[i].isDigit()
        val rightDigit = right[j].isDigit()
        if (leftDigit && rightDigit) {
            var endLeft = i
            var endRight = j
            while (endLeft < left.length && left[endLeft].isDigit()) endLeft++
            while (endRight < right.length && right[endRight].isDigit()) endRight++
            val leftNumber = left.substring(i, endLeft).trimStart('0')
            val rightNumber = right.substring(j, endRight).trimStart('0')
            val byLength = leftNumber.length.compareTo(rightNumber.length)
            val result = if (byLength != 0) byLength else leftNumber.compareTo(rightNumber)
            if (result != 0) return@Comparator result
            i = endLeft
            j = endRight
        } else {
            val result = left[i].lowercaseChar().compareTo(right[j].lowercaseChar())
            if (result != 0) return@Comparator result
            i++
            j++
        }
    }
    (left.length - i).compareTo(right.length - j)
}

/** Scrollbar rows are one line wide, so minutes are the finest useful unit. */
private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Splits `作者名称-项目名称` on its first hyphen; an unhyphenated name has no author. */
fun splitProjectFolder(folder: String): Pair<String, String>? {
    val separator = folder.indexOf('-')
    if (separator <= 0 || separator == folder.lastIndex) return null
    return folder.substring(0, separator).trim() to folder.substring(separator + 1).trim()
}
