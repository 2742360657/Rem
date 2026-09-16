package dev.susnowy.gallery.scanner

import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.metadata.ComicInfoReader
import dev.susnowy.gallery.metadata.DownloadedSourceRecognizer
import dev.susnowy.gallery.metadata.FilenameMetadataParser
import dev.susnowy.gallery.metadata.RecognizedMetadata
import dev.susnowy.gallery.metadata.mergeRecognizedMetadata
import dev.susnowy.gallery.storage.DocumentTreeStorage
import dev.susnowy.gallery.storage.StorageEntry
import java.util.Locale
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ScanCandidate(
    val relativePath: String,
    val uri: String,
    val kind: MediaKind,
    val domain: MediaDomain,
    val sourceKind: SourceKind,
    val suggestedTitle: String,
    val mimeType: String?,
    val size: Long,
    val modifiedAt: Long,
    val capturedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val contentHash: String? = null,
    val pageCount: Int? = null,
    val coverPath: String? = null,
    val secondaryPath: String? = null,
    val recognizedMetadata: RecognizedMetadata? = null,
)

data class ScanResult(
    val candidates: List<ScanCandidate>,
    val ambiguousDirectories: List<String>,
    val warnings: List<String>,
    /** Files whose content was actually opened because no usable prior record existed. */
    val contentReads: Int = 0,
    val contentReadsSkipped: Int = 0,
)

/**
 * What the previous scan already established about one file, so an unchanged file does
 * not have to be opened again. Content hashing and metadata extraction are the only
 * operations that read bytes off the volume, and on a removable Library those bytes are
 * the expensive part.
 */
data class ScannedFile(
    val relativePath: String,
    val size: Long,
    val modifiedAt: Long,
    val contentHash: String? = null,
    val capturedAt: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** Prior results keyed by relative path; empty for a first scan or a full rescan. */
typealias ScanSnapshot = Map<String, ScannedFile>

/**
 * Whether the previous scan's answers may be reused for a file that looks unchanged.
 *
 * Both the size and the timestamp must match exactly, and a recorded hash must exist:
 * a partial match always re-reads, because keeping a stale fingerprint would silently
 * mis-merge metadata across a Library after an edit.
 */
internal fun ScannedFile.canBeReused(size: Long, modifiedAt: Long): Boolean =
    this.size == size && this.modifiedAt == modifiedAt

private class ScanStatistics {
    var contentReads: Int = 0
    var contentReadsSkipped: Int = 0
}

class LibraryScanner {
    private val comicInfo = ComicInfoReader()
    suspend fun scan(
        storage: DocumentTreeStorage,
        prior: ScanSnapshot = emptyMap(),
    ): ScanResult = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ScanCandidate>()
        val ambiguous = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val statistics = ScanStatistics()
        scanDirectory(storage, "", candidates, ambiguous, warnings, prior, statistics)
        Log.i(
            TAG,
            "Scan finished: candidates=${candidates.size}, ambiguous=${ambiguous.size}, " +
                "warnings=${warnings.size}, contentReads=${statistics.contentReads}, " +
                "unchangedReused=${statistics.contentReadsSkipped}",
        )
        ScanResult(
            candidates = candidates.sortedWith(compareBy<ScanCandidate> { it.kind.ordinal }
                .thenComparator { left, right ->
                    MediaClassifier.naturalCompare(left.relativePath, right.relativePath)
                }),
            ambiguousDirectories = ambiguous,
            warnings = warnings,
            contentReads = statistics.contentReads,
            contentReadsSkipped = statistics.contentReadsSkipped,
        )
    }

    private suspend fun scanDirectory(
        storage: DocumentTreeStorage,
        path: String,
        output: MutableList<ScanCandidate>,
        ambiguous: MutableList<String>,
        warnings: MutableList<String>,
        prior: ScanSnapshot,
        statistics: ScanStatistics,
    ) {
        coroutineContext.ensureActive()
        val entries = runCatching { storage.list(path) }.getOrElse { error ->
            Log.e(TAG, "Unable to list ${path.ifEmpty { "<root>" }}", error)
            warnings += "无法读取 ${path.ifEmpty { "Library 根目录" }}：${error.message.orEmpty()}"
            return
        }.filterNot { path.isEmpty() && it.name == ".gallery" }

        val directories = entries.filter(StorageEntry::isDirectory)
        val files = entries.filterNot(StorageEntry::isDirectory)
        val rootDirectory = path.pathSegments().firstOrNull()
        val inPhotos = rootDirectory.equals("Photos", ignoreCase = true)
        val inImages = rootDirectory.equals("Images", ignoreCase = true)
        val inVideos = rootDirectory.equals("Videos", ignoreCase = true)
        val images = files.filter { MediaClassifier.isImage(it.name, it.mimeType) }
        val videos = files.filter { MediaClassifier.isVideo(it.name, it.mimeType) }
        val archives = files.filter { MediaClassifier.isImageArchive(it.name) }
        val isImageSetDirectory = shouldTreatDirectoryAsImageSet(
            path = path,
            imageCount = images.size,
            hasChildDirectories = directories.isNotEmpty(),
        ) && !DownloadedSourceRecognizer.containsMultiplePixivWorks(images.map(StorageEntry::name))
        if (isImageSetDirectory) {
            val sortedPages = images.sortedWith { left, right ->
                MediaClassifier.naturalCompare(left.name, right.name)
            }
            val directory = storage.entry(path)
            if (directory != null) {
                val parentName = path.substringBeforeLast('/', "").substringAfterLast('/').takeIf(String::isNotBlank)
                val directoryMetadata = mergeRecognizedMetadata(
                    comicInfo.fromDirectory(storage, path),
                    DownloadedSourceRecognizer.fromDirectory(path, files.map(StorageEntry::name)),
                    FilenameMetadataParser.parse(path.substringAfterLast('/'), parentName),
                ) ?: FilenameMetadataParser.parse(path.substringAfterLast('/'), parentName)
                output += ScanCandidate(
                    relativePath = path,
                    uri = directory.uri,
                    kind = MediaKind.IMAGE_SET,
                    domain = MediaDomain.WORKS,
                    sourceKind = SourceKind.DIRECTORY,
                    suggestedTitle = path.substringAfterLast('/'),
                    mimeType = null,
                    size = files.sumOf(StorageEntry::size),
                    modifiedAt = maxOf(directory.lastModified, images.maxOfOrNull(StorageEntry::lastModified) ?: 0),
                    pageCount = images.size,
                    coverPath = sortedPages.firstOrNull()?.relativePath,
                    contentHash = directoryFingerprint(files),
                    recognizedMetadata = directoryMetadata,
                )
                // Downloaded image sets often contain one or more bonus videos. Keep the
                // directory as one readable image set, but never make those video files vanish.
                videos.forEach { video ->
                    val parsed = FilenameMetadataParser.parseVideo(video.name, path.substringAfterLast('/'))
                    output += video.toCandidate(
                        kind = MediaKind.VIDEO,
                        domain = MediaDomain.WORKS,
                        sourceKind = SourceKind.FILE,
                        contentHash = contentHash(storage, video, prior, statistics),
                        recognizedMetadata = parsed.copy(
                            authors = parsed.authors.ifEmpty { directoryMetadata.authors },
                            tags = parsed.tags.ifEmpty { directoryMetadata.tags },
                            series = parsed.series ?: directoryMetadata.series,
                        ),
                    )
                }
            }
        } else {
            if (!inPhotos && !inImages && !inVideos && images.size >= MIN_IMAGE_SET_PAGES && directories.isNotEmpty()) {
                ambiguous += path
            }
            val pairedVideoPaths = mutableSetOf<String>()
            images.forEach { image ->
                val captured = if (inPhotos) capturedMetadata(storage, image, prior, statistics) else null
                val motion = if (inPhotos) videos.firstOrNull {
                    it.name.substringBeforeLast('.').equals(
                        image.name.substringBeforeLast('.'),
                        ignoreCase = true,
                    )
                } else null
                if (motion != null) pairedVideoPaths += motion.relativePath
                output += image.toCandidate(
                    kind = if (motion == null) {
                        if (inPhotos) MediaKind.PHOTO else MediaKind.IMAGE
                    } else MediaKind.LIVE_PHOTO,
                    domain = if (inPhotos) MediaDomain.ALBUM else MediaDomain.CLASSIFIED,
                    sourceKind = if (inPhotos) SourceKind.SYSTEM_IMPORT else SourceKind.FILE,
                    secondaryPath = motion?.relativePath,
                    capturedAt = captured?.capturedAt,
                    latitude = captured?.latitude,
                    longitude = captured?.longitude,
                    contentHash = contentHash(storage, image, prior, statistics),
                    sizeOverride = image.size + (motion?.size ?: 0),
                    recognizedMetadata = if (inPhotos) null else mergeRecognizedMetadata(
                        DownloadedSourceRecognizer.fromFile(image.relativePath),
                        FilenameMetadataParser.parse(
                            image.name,
                            image.relativePath.substringBeforeLast('/', "").substringAfterLast('/')
                                .takeIf(String::isNotBlank),
                        ),
                    ),
                )
            }
            videos.filterNot { it.relativePath in pairedVideoPaths }.forEach { video ->
                val parentName = video.relativePath.substringBeforeLast('/', "").substringAfterLast('/')
                    .takeIf(String::isNotBlank)
                val captured = if (inPhotos) capturedMetadata(storage, video, prior, statistics) else null
                output += video.toCandidate(
                    kind = if (inPhotos) MediaKind.PHOTO_VIDEO else MediaKind.VIDEO,
                    domain = when {
                        inPhotos -> MediaDomain.ALBUM
                        isWorkVideo(video.relativePath, video.name) -> MediaDomain.WORKS
                        else -> MediaDomain.CLASSIFIED
                    },
                    sourceKind = if (inPhotos) SourceKind.SYSTEM_IMPORT else SourceKind.FILE,
                    capturedAt = captured?.capturedAt,
                    latitude = captured?.latitude,
                    longitude = captured?.longitude,
                    contentHash = contentHash(storage, video, prior, statistics),
                    recognizedMetadata = if (inPhotos) null else FilenameMetadataParser.parseVideo(video.name, parentName),
                )
            }
        }

        archives.forEach { archive ->
            val count = runCatching { countArchivePages(storage, archive) }.getOrElse { error ->
                warnings += "压缩包 ${archive.relativePath} 无法读取：${error.message.orEmpty()}"
                0
            }
            output += archive.toCandidate(
                kind = MediaKind.IMAGE_SET,
                domain = MediaDomain.WORKS,
                sourceKind = SourceKind.ARCHIVE,
                pageCount = count,
                contentHash = contentHash(storage, archive, prior, statistics),
                recognizedMetadata = mergeRecognizedMetadata(
                    comicInfo.fromArchive(storage, archive.relativePath),
                    DownloadedSourceRecognizer.fromFile(archive.relativePath),
                    FilenameMetadataParser.parse(
                        archive.name,
                        archive.relativePath.substringBeforeLast('/', "").substringAfterLast('/').takeIf(String::isNotBlank),
                    ),
                ),
            )
        }

        directories.forEach { directory ->
            scanDirectory(storage, directory.relativePath, output, ambiguous, warnings, prior, statistics)
        }
    }

    /**
     * Reuses the recorded hash when the file clearly has not changed, and reports whether
     * the content had to be opened. A size or timestamp difference always forces a re-read,
     * so an edited file can never keep a stale fingerprint.
     */
    private fun contentHash(
        storage: DocumentTreeStorage,
        entry: StorageEntry,
        prior: ScanSnapshot,
        statistics: ScanStatistics,
    ): String? {
        if (entry.size <= 0 || entry.size > HASH_SIZE_LIMIT) return null
        val recorded = prior[entry.relativePath]
        if (recorded != null && recorded.canBeReused(entry.size, entry.lastModified) &&
            recorded.contentHash != null
        ) {
            statistics.contentReadsSkipped++
            return recorded.contentHash
        }
        statistics.contentReads++
        val document = LibraryDocument(entry.relativePath, entry.name, false, locator = entry.uri)
        val digest = MessageDigest.getInstance("SHA-256")
        storage.openInput(document).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun countArchivePages(storage: DocumentTreeStorage, archive: StorageEntry): Int {
        val document = LibraryDocument(archive.relativePath, archive.name, false, locator = archive.uri)
        return storage.openInput(document).buffered().use { stream ->
            ZipInputStream(stream).use { zip ->
                var count = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && MediaClassifier.isImage(entry.name, null)) count++
                    zip.closeEntry()
                }
                count
            }
        }
    }

    private fun StorageEntry.toCandidate(
        kind: MediaKind,
        domain: MediaDomain,
        sourceKind: SourceKind,
        pageCount: Int? = null,
        secondaryPath: String? = null,
        capturedAt: Long? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        recognizedMetadata: RecognizedMetadata? = null,
        contentHash: String? = null,
        sizeOverride: Long? = null,
    ) = ScanCandidate(
        relativePath = relativePath,
        uri = uri,
        kind = kind,
        domain = domain,
        sourceKind = sourceKind,
        suggestedTitle = name.substringBeforeLast('.', name),
        mimeType = mimeType,
        size = sizeOverride ?: size,
        modifiedAt = lastModified,
        capturedAt = capturedAt,
        latitude = latitude,
        longitude = longitude,
        pageCount = pageCount,
        secondaryPath = secondaryPath,
        recognizedMetadata = recognizedMetadata,
        contentHash = contentHash,
    )

    /**
     * Reuses the metadata the previous scan recorded when the file is unchanged. Reading
     * EXIF or a video container is a content read, and on a Photos Library that is one
     * read per item on every scan.
     */
    private fun capturedMetadata(
        storage: DocumentTreeStorage,
        entry: StorageEntry,
        prior: ScanSnapshot,
        statistics: ScanStatistics,
    ): CapturedMetadata? {
        val recorded = prior[entry.relativePath]
        if (recorded != null && recorded.canBeReused(entry.size, entry.lastModified)) {
            statistics.contentReadsSkipped++
            return CapturedMetadata(recorded.capturedAt, recorded.latitude, recorded.longitude)
        }
        statistics.contentReads++
        return readCapturedMetadata(storage, entry)
    }

    private fun directoryFingerprint(files: List<StorageEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedWith { left, right -> MediaClassifier.naturalCompare(left.name, right.name) }
            .forEach { entry ->
                digest.update(entry.name.lowercase(Locale.ROOT).encodeToByteArray())
                digest.update(0.toByte())
                digest.update(entry.size.toString().encodeToByteArray())
                digest.update(0.toByte())
            }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private data class CapturedMetadata(
        val capturedAt: Long? = null,
        val latitude: Double? = null,
        val longitude: Double? = null,
    )

    private fun readCapturedMetadata(storage: DocumentTreeStorage, entry: StorageEntry): CapturedMetadata? = when {
        MediaClassifier.isImage(entry.name, entry.mimeType) -> runCatching {
            val document = LibraryDocument(entry.relativePath, entry.name, false, locator = entry.uri)
            storage.openInput(document).use { input ->
                val exif = ExifInterface(input)
                val value = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                val capturedAt = value?.let { EXIF_DATE.parse(it, java.time.LocalDateTime::from) }
                    ?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli()
                val coordinates = exif.latLong
                CapturedMetadata(
                    capturedAt = capturedAt,
                    latitude = coordinates?.getOrNull(0),
                    longitude = coordinates?.getOrNull(1),
                )
            }
        }.getOrNull()
        MediaClassifier.isVideo(entry.name, entry.mimeType) -> runCatching {
            storage.openFileDescriptor(entry.relativePath)?.use { descriptor ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(descriptor.fileDescriptor)
                    val capturedAt = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                        ?.replace(Regex("\\.\\d+"), "")
                        ?.let { value -> runCatching { Instant.from(VIDEO_DATE.parse(value)).toEpochMilli() }.getOrNull() }
                    val coordinates = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                        ?.let(::parseIso6709)
                    CapturedMetadata(capturedAt, coordinates?.first, coordinates?.second)
                } finally {
                    retriever.release()
                }
            }
        }.getOrNull()
        else -> null
    }

    private fun parseIso6709(value: String): Pair<Double, Double>? {
        val match = Regex("^([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)").find(value) ?: return null
        val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
        val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
        return latitude to longitude
    }

    private fun String.pathSegments(): List<String> = split('/').filter(String::isNotBlank)

    private fun isWorkVideo(relativePath: String, name: String): Boolean {
        val root = relativePath.pathSegments().firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
        return root in WORK_VIDEO_ROOTS || FilenameMetadataParser.looksEpisodic(name)
    }

    companion object {
        private const val TAG = "GalleryScanner"
        const val MIN_IMAGE_SET_PAGES = 2
        private const val HASH_SIZE_LIMIT = 64L * 1024L * 1024L
        private val WORK_VIDEO_ROOTS = setOf(
            "anime", "animation", "animations", "works", "movies", "movie", "films", "film",
            "series", "shows", "tv", "动漫", "动画", "番剧", "影视", "电影", "剧集", "作品",
        )
        private val EXIF_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)
        private val VIDEO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX", Locale.ROOT)

        internal fun shouldTreatDirectoryAsImageSet(
            path: String,
            imageCount: Int,
            hasChildDirectories: Boolean,
        ): Boolean {
            if (path.isBlank() || imageCount < MIN_IMAGE_SET_PAGES || hasChildDirectories) return false
            val root = path.replace('\\', '/').trim('/').substringBefore('/')
            return root.lowercase(Locale.ROOT) !in setOf("photos", "images", "videos")
        }
    }
}
