package dev.susnowy.gallery.media

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.exifinterface.media.ExifInterface
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.MediaType
import dev.susnowy.gallery.model.Place
import java.io.FileDescriptor
import java.io.IOException
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

    /**
     * Reads a video's own container metadata.
     *
     * `MediaMetadataRetriever.setDataSource(FileDescriptor)` looks like the direct path and is not
     * one: AOSP runs the descriptor through `FileUtils.convertToModernFd`, which asks `MediaStore`
     * for the original media fd — a second trip through MediaProvider for every single video. On a
     * USB volume with a few hundred videos that is measurable: an ANR trace from the real device
     * showed MediaProvider at 42% of a CPU that was busy enough to starve the UI thread.
     *
     * A `MediaDataSource` skips that: the retriever calls back into `readAt`, which `pread`s the
     * descriptor directly. It is used only when the descriptor is a seekable regular file, because
     * `pread` is meaningless on a pipe, and every failure falls back to the old path — a video whose
     * timestamp cannot be read would silently sort by file modification time instead.
     */
    private fun readVideo(context: Context, uri: Uri): Metadata = runCatching {
        val startedAt = System.nanoTime()
        val retriever = MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                // The descriptor stays open until the metadata has been read: with a MediaDataSource
                // the retriever keeps reading through it, unlike the FileDescriptor path where the
                // documentation allows closing straight after setDataSource returns.
                val regularFile = descriptor.regularFileSize()
                val direct = regularFile != null && setDirectDataSource(retriever, descriptor, regularFile)
                if (!direct) {
                    fallbacks.incrementAndGet()
                    retriever.setDataSource(descriptor.fileDescriptor)
                }
                Metadata(
                    captured = extractCaptured(retriever, uri, direct),
                    place = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                        ?.let(::parseIso6709),
                )
            } ?: Metadata.NONE
        } finally {
            runCatching { retriever.release() }
            record(System.nanoTime() - startedAt)
        }
    }.getOrDefault(Metadata.NONE)

    /** True when the retriever accepted the descriptor as a directly readable data source. */
    private fun setDirectDataSource(
        retriever: MediaMetadataRetriever,
        descriptor: ParcelFileDescriptor,
        size: Long,
    ) = runCatching {
        retriever.setDataSource(PreadDataSource(descriptor, size))
        if (directAccepted.compareAndSet(false, true)) {
            RemLog.info(SCOPE, "视频元数据改为直接读取（跳过 MediaStore），首个文件 ${size}B")
        }
        true
    }.onFailure {
        RemLog.warn(SCOPE, "直接读取失败，回退到系统路径：${it.message}")
    }.getOrDefault(false)

    private fun extractCaptured(retriever: MediaMetadataRetriever, uri: Uri, direct: Boolean): Long? {
        val raw = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
        val captured = raw?.let(::parseVideoTimestamp)
        if (raw != null && captured == null) {
            RemLog.warn(SCOPE, "无法解析视频拍摄时间 '$raw' uri=$uri")
        }
        if (direct && captured != null) directHits.incrementAndGet()
        return captured
    }

    /**
     * A descriptor the retriever reads through itself.
     *
     * `readAt` is what the native parser calls; it must fill what it can and never throw for a
     * short read at the end of the file, which is how a parser detects EOF.
     */
    private class PreadDataSource(
        private val descriptor: ParcelFileDescriptor,
        private val size: Long,
    ) : MediaDataSource() {
        private val fd: FileDescriptor = descriptor.fileDescriptor

        @Volatile
        private var closed = false

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (closed || position >= this.size) return -1
            val wanted = minOf(size.toLong(), this.size - position).toInt()
            if (wanted <= 0) return -1
            return try {
                Os.pread(fd, buffer, offset, wanted, position)
            } catch (_: IOException) {
                -1
            } catch (_: android.system.ErrnoException) {
                -1
            }
        }

        override fun getSize(): Long = size

        override fun close() {
            closed = true
        }
    }

    /**
     * The file size when the descriptor is a seekable regular file, or `null` otherwise.
     *
     * `pread` only means anything on a regular file. A provider is allowed to hand back a pipe or a
     * socket instead — a cloud `DocumentsProvider` streaming a download would — and reading one at
     * a position is either meaningless or an error, so those go down the system path instead.
     */
    private fun ParcelFileDescriptor.regularFileSize(): Long? = runCatching {
        val stat = Os.fstat(fileDescriptor)
        if (OsConstants.S_ISREG(stat.st_mode)) stat.st_size else null
    }.getOrNull()

    /**
     * Records how long video metadata took and which path served it.
     *
     * The A/B evidence for the direct path, kept because it is the only way to tell whether the
     * shortcut is actually earning its place on a given device. A slow read is logged individually;
     * the summary is logged every [SUMMARY_EVERY] reads.
     */
    private fun record(nanos: Long) {
        val millis = nanos / 1_000_000
        totalNanos.addAndGet(nanos)
        val count = reads.incrementAndGet()
        synchronized(slowest) {
            if (millis > slowest[0]) slowest[0] = millis
        }
        if (millis >= SLOW_READ_MS) {
            RemLog.warn(SCOPE, "视频元数据读取较慢 ${millis}ms")
        }
        if (count % SUMMARY_EVERY == 0) {
            RemLog.info(
                SCOPE,
                "视频元数据：$count 次，平均 ${totalNanos.get() / count / 1_000_000}ms，" +
                    "最慢 ${slowest[0]}ms，走直读 ${directHits.get()} 次，回退 ${fallbacks.get()} 次",
            )
        }
    }

    /** `±DD.DDDD±DDD.DDDD/` as mp4 writes it, optionally with an altitude after the slash. */
    private fun parseIso6709(value: String): Place? {
        val match = ISO_6709.find(value) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        return Place(latitude, longitude)
    }

    private val ISO_6709 = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)")

    private val directAccepted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reads = java.util.concurrent.atomic.AtomicInteger()
    private val directHits = java.util.concurrent.atomic.AtomicInteger()
    private val fallbacks = java.util.concurrent.atomic.AtomicInteger()
    private val totalNanos = java.util.concurrent.atomic.AtomicLong()
    private val slowest = longArrayOf(0)

    private const val SCOPE = "Media"
    private const val SUMMARY_EVERY = 50
    private const val SLOW_READ_MS = 200L
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
