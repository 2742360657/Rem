package dev.susnowy.gallery.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.MediaType
import dev.susnowy.gallery.model.Place
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What a media file says about itself. Everything here comes from the file, never inferred. */
data class Metadata(val captured: Long? = null, val place: Place? = null) {
    companion object {
        val NONE = Metadata()
    }
}

/**
 * Reads the capture time and location a file carries in its own metadata.
 *
 * The product rules allow only two sources for a capture time — the image's EXIF or the video's
 * own container metadata — and fall back to the file's modification time when neither exists.
 * Location is read the same way: an absent reading is displayed as nothing at all rather than
 * guessed from the file or folder name.
 *
 * Both readers open the document exactly once and swallow provider or codec failures, because a
 * file with unreadable metadata is still a file the user expects to see.
 */
object MediaProbe {

    private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    fun read(context: Context, uri: Uri, type: MediaType): Metadata = when (type) {
        MediaType.IMAGE -> readImage(context, uri)
        MediaType.VIDEO -> readVideo(context, uri)
    }

    private fun readImage(context: Context, uri: Uri): Metadata = runCatching {
        val exif = context.contentResolver.openInputStream(uri)?.use(::ExifInterface) ?: return Metadata.NONE
        // Original is when the shutter opened; plain DateTime is when the file was last written
        // by an editor, so it only stands in when nothing better exists.
        val stamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
        val captured = stamp?.let { value ->
            runCatching { LocalDateTime.parse(value, EXIF_DATE) }
                .getOrNull()
                ?.atZone(ZoneId.systemDefault())
                ?.toInstant()
                ?.toEpochMilli()
        }
        // `latLong` is null unless both coordinates are present and plausible.
        val coordinates = exif.latLong
        Metadata(
            captured = captured,
            place = coordinates?.let { Place(it[0], it[1]) },
        )
    }.getOrDefault(Metadata.NONE)

    private fun readVideo(context: Context, uri: Uri): Metadata = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                retriever.setDataSource(descriptor.fileDescriptor)
                val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                val captured = raw?.let(::parseVideoTimestamp)
                if (raw != null && captured == null) {
                    RemLog.warn(SCOPE, "无法解析视频拍摄时间 '$raw' uri=$uri")
                }
                val place = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                    ?.let(::parseIso6709)
                Metadata(captured = captured, place = place)
            } ?: Metadata.NONE
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrDefault(Metadata.NONE)

    /** `±DD.DDDD±DDD.DDDD/` as mp4 writes it, optionally with an altitude after the slash. */
    private fun parseIso6709(value: String): Place? {
        val match = ISO_6709.find(value) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        return Place(latitude, longitude)
    }

    private val ISO_6709 = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)")

    private const val SCOPE = "Media"
}

/**
 * Reads the capture time out of [MediaMetadataRetriever]'s date string.
 *
 * Two shapes reach here and both have to be accepted. A container usually carries extended
 * ISO-8601 (`2024-09-09T09:09:09Z`), but Android's own MP4 extractor writes the compact form
 * (`20240909T090909.000Z`). Accepting only the first one silently demoted every video to its file
 * modification time, which is how a video library ends up ordered by the date it was copied.
 *
 * An unparseable value returns `null`, which the caller treats as "no capture time" — never as a
 * guess, because the rules allow only what the file itself states.
 */
internal fun parseVideoTimestamp(value: String): Long? {
    // Fractional seconds are rejected by the strict parsers and nothing here needs them.
    val cleaned = value.trim().replace(FRACTIONAL_SECONDS, "").trim()
    val millis = instantOf(EXTENDED_TIMESTAMP, cleaned) ?: instantOf(COMPACT_TIMESTAMP, cleaned)
    // MP4 counts time from 1904, so a file that never set a date reports the 1904 epoch rather
    // than nothing. That is the container saying "unset", not a date: taking it literally would
    // file the video under 1904 and sort it behind everything. Media predating 1970 does not
    // exist, so anything at or before the Unix epoch is treated as no reading at all.
    return millis?.takeIf { it > 0L }
}

private fun instantOf(formatter: DateTimeFormatter, value: String): Long? =
    runCatching { Instant.from(formatter.parse(value)).toEpochMilli() }.getOrNull()

private val FRACTIONAL_SECONDS = Regex("\\.\\d+")

/** `2024-09-09T09:09:09Z`, with or without an offset. */
private val EXTENDED_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

/** `20240909T090909Z`, the compact form Android's MP4 extractor produces. */
private val COMPACT_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss[XX][X]")
