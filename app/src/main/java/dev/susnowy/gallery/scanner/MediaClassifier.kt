package dev.susnowy.gallery.scanner

import dev.susnowy.gallery.model.MediaKind
import java.util.Locale

object MediaClassifier {
    private val imageExtensions = setOf(
        "jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif", "bmp", "heic", "heif",
        "avif", "apng", "svg", "ico", "wbmp", "dng",
    )
    private val videoExtensions = setOf(
        "mp4", "mkv", "webm", "mov", "m4v", "avi", "3gp", "ts", "m2ts",
    )
    private val archiveExtensions = setOf("zip", "cbz")

    fun isImage(name: String, mimeType: String?): Boolean =
        mimeType?.startsWith("image/") == true || extension(name) in imageExtensions

    fun isVideo(name: String, mimeType: String?): Boolean =
        mimeType?.startsWith("video/") == true || extension(name) in videoExtensions

    fun isImageArchive(name: String): Boolean = extension(name) in archiveExtensions

    fun kindForFile(name: String, mimeType: String?, inPhotos: Boolean): MediaKind? = when {
        isImage(name, mimeType) -> if (inPhotos) MediaKind.PHOTO else MediaKind.IMAGE
        isVideo(name, mimeType) -> if (inPhotos) MediaKind.PHOTO_VIDEO else MediaKind.VIDEO
        isImageArchive(name) -> MediaKind.IMAGE_SET
        else -> null
    }

    fun naturalCompare(left: String, right: String): Int {
        val leftParts = tokenize(left)
        val rightParts = tokenize(right)
        for (index in 0 until minOf(leftParts.size, rightParts.size)) {
            val a = leftParts[index]
            val b = rightParts[index]
            val comparison = if (a.first().isDigit() && b.first().isDigit()) {
                val normalizedA = a.trimStart('0').ifEmpty { "0" }
                val normalizedB = b.trimStart('0').ifEmpty { "0" }
                normalizedA.length.compareTo(normalizedB.length)
                    .takeIf { it != 0 } ?: normalizedA.compareTo(normalizedB)
            } else {
                a.compareTo(b, ignoreCase = true)
            }
            if (comparison != 0) return comparison
        }
        return leftParts.size.compareTo(rightParts.size)
            .takeIf { it != 0 } ?: left.compareTo(right, ignoreCase = true)
    }

    private fun extension(name: String): String = name.substringAfterLast('.', "")
        .lowercase(Locale.ROOT)

    private fun tokenize(value: String): List<String> =
        Regex("\\d+|\\D+").findAll(value).map { it.value }.toList()
}
