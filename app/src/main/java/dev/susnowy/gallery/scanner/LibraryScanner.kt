package dev.susnowy.gallery.scanner

import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import dev.susnowy.gallery.library.LibraryDocument
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.model.DiscoveryReason
import dev.susnowy.gallery.metadata.ComicInfoReader
import dev.susnowy.gallery.metadata.DownloadedSourceRecognizer
import dev.susnowy.gallery.metadata.FieldSource
import dev.susnowy.gallery.metadata.FilenameMetadataParser
import dev.susnowy.gallery.metadata.RecognizedMetadata
import dev.susnowy.gallery.metadata.mergeRecognizedMetadata
import dev.susnowy.gallery.metadata.withFieldSource
import dev.susnowy.gallery.storage.DocumentTreeStorage
import dev.susnowy.gallery.storage.StorageEntry
import java.util.Locale
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

data class ScanDiscoveryCandidate(
    val relativePath: String,
    val uri: String? = null,
    val isDirectory: Boolean,
    val mimeType: String? = null,
    val size: Long = 0,
    val modifiedAt: Long = 0,
    val reason: DiscoveryReason,
)

data class ScanResult(
    val candidates: List<ScanCandidate>,
    val ambiguousDirectories: List<String>,
    val discoveries: List<ScanDiscoveryCandidate> = emptyList(),
    val warnings: List<String>,
    val unreadableDirectories: List<String> = emptyList(),
    /** Files whose content was actually opened because no usable prior record existed. */
    val contentReads: Int = 0,
    val contentReadsSkipped: Int = 0,
) {
    /** A failed subtree was not evidence that its previously indexed items disappeared. */
    fun protectsPreviouslyIndexed(path: String): Boolean = unreadableDirectories.any { directory ->
        directory.isEmpty() || path == directory || path.startsWith("$directory/")
    }
}

data class ScanProgress(
    val directoriesRead: Int,
    val entriesRead: Int,
    val candidatesFound: Int,
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
    val pageCount: Int? = null,
)

/** Prior results keyed by relative path; empty for a first scan or a full rescan. */
typealias ScanSnapshot = Map<String, ScannedFile>

/**
 * Whether the previous scan's answers may be reused for a file that looks unchanged.
 *
 * Both the size and the timestamp must match exactly. Callers additionally require the
 * particular value they want to reuse (for example a hash or archive page count).
 */
internal fun ScannedFile.canBeReused(size: Long, modifiedAt: Long): Boolean =
    this.size == size && this.modifiedAt == modifiedAt

internal fun ScannedFile.reusableArchivePageCount(size: Long, modifiedAt: Long): Int? =
    pageCount?.takeIf { canBeReused(size, modifiedAt) }

private class ScanStatistics {
    private val readPaths = mutableSetOf<String>()
    private val reusedPaths = mutableSetOf<String>()
    var directoriesRead: Int = 0
    var entriesRead: Int = 0

    val contentReads: Int get() = readPaths.size
    val contentReadsSkipped: Int get() = reusedPaths.size

    fun contentRead(path: String) {
        readPaths += path
        reusedPaths -= path
    }

    fun contentReused(path: String) {
        if (path !in readPaths) reusedPaths += path
    }

    fun progress(candidatesFound: Int) = ScanProgress(
        directoriesRead = directoriesRead,
        entriesRead = entriesRead,
        candidatesFound = candidatesFound,
    )
}

class LibraryScanner {
    private val comicInfo = ComicInfoReader()
    suspend fun scan(
        storage: DocumentTreeStorage,
        prior: ScanSnapshot = emptyMap(),
        onProgress: (ScanProgress) -> Unit = {},
    ): ScanResult = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ScanCandidate>()
        val ambiguous = mutableListOf<String>()
        val discoveries = mutableListOf<ScanDiscoveryCandidate>()
        val warnings = mutableListOf<String>()
        val unreadableDirectories = mutableListOf<String>()
        val statistics = ScanStatistics()
        scanDirectory(
            storage,
            "",
            candidates,
            ambiguous,
            discoveries,
            warnings,
            unreadableDirectories,
            prior,
            statistics,
            onProgress,
        )
        onProgress(statistics.progress(candidates.size))
        RemLog.info(
            TAG,
            "扫描完成：候选=${candidates.size}，其他待判断=${discoveries.size}，" +
                "警告=${warnings.size}，读取内容=${statistics.contentReads}，" +
                "复用未变化文件=${statistics.contentReadsSkipped}",
        )
        ScanResult(
            candidates = candidates.sortedWith(compareBy<ScanCandidate> { it.kind.ordinal }
                .thenComparator { left, right ->
                    MediaClassifier.naturalCompare(left.relativePath, right.relativePath)
                }),
            ambiguousDirectories = ambiguous,
            discoveries = discoveries.sortedWith { left, right ->
                MediaClassifier.naturalCompare(left.relativePath, right.relativePath)
            },
            warnings = warnings,
            unreadableDirectories = unreadableDirectories,
            contentReads = statistics.contentReads,
            contentReadsSkipped = statistics.contentReadsSkipped,
        )
    }

    private suspend fun scanDirectory(
        storage: DocumentTreeStorage,
        path: String,
        output: MutableList<ScanCandidate>,
        ambiguous: MutableList<String>,
        discoveries: MutableList<ScanDiscoveryCandidate>,
        warnings: MutableList<String>,
        unreadableDirectories: MutableList<String>,
        prior: ScanSnapshot,
        statistics: ScanStatistics,
        onProgress: (ScanProgress) -> Unit,
    ) {
        coroutineContext.ensureActive()
        val entries = runCatching { storage.list(path) }.getOrElse { error ->
            RemLog.warn(TAG, "无法读取 ${path.ifEmpty { "Library 根目录" }}", error)
            warnings += "无法读取 ${path.ifEmpty { "Library 根目录" }}：${error.message.orEmpty()}"
            unreadableDirectories += path
            return
        }.filterNot { path.isEmpty() && it.isDirectory && DiscoveryPolicy.ignoreRootDirectory(it.name) }
        statistics.directoriesRead++
        statistics.entriesRead += entries.size
        if (statistics.directoriesRead == 1 || statistics.directoriesRead % PROGRESS_DIRECTORY_INTERVAL == 0) {
            onProgress(statistics.progress(output.size))
        }

        val directories = entries.filter(StorageEntry::isDirectory)
        val files = entries.filterNot(StorageEntry::isDirectory)
        val rootDirectory = path.pathSegments().firstOrNull()
        val inPhotos = rootDirectory.equals("Photos", ignoreCase = true)
        val inImages = rootDirectory.equals("Images", ignoreCase = true)
        val inVideos = rootDirectory.equals("Videos", ignoreCase = true)
        val images = files.filter { MediaClassifier.isImage(it.name, it.mimeType) }
        val videos = files.filter { MediaClassifier.isVideo(it.name, it.mimeType) }
        val archives = files.filter { MediaClassifier.isImageArchive(it.name) }
        val recognizedPaths = (images + videos + archives).mapTo(mutableSetOf(), StorageEntry::relativePath)
        files.asSequence()
            .filterNot { it.relativePath in recognizedPaths || DiscoveryPolicy.ignoreFile(it.name) }
            .forEach { entry ->
                discoveries += ScanDiscoveryCandidate(
                    relativePath = entry.relativePath,
                    uri = entry.uri,
                    isDirectory = false,
                    mimeType = entry.mimeType,
                    size = entry.size,
                    modifiedAt = entry.lastModified,
                    reason = DiscoveryReason.UNSUPPORTED_FILE,
                )
            }
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
                val totalSize = files.sumOf(StorageEntry::size)
                val modifiedAt = maxOf(
                    directory.lastModified,
                    files.maxOfOrNull(StorageEntry::lastModified) ?: 0,
                )
                val unchanged = prior[path]?.canBeReused(totalSize, modifiedAt) == true
                val parentName = path.substringBeforeLast('/', "").substringAfterLast('/').takeIf(String::isNotBlank)
                val inferredMetadata = mergeRecognizedMetadata(
                    DownloadedSourceRecognizer.fromDirectory(path, files.map(StorageEntry::name))
                        ?.withFieldSource(FieldSource.FILENAME),
                    FilenameMetadataParser.parse(path.substringAfterLast('/'), parentName)
                        .withFieldSource(FieldSource.FILENAME),
                ) ?: FilenameMetadataParser.parse(path.substringAfterLast('/'), parentName)
                    .withFieldSource(FieldSource.FILENAME)
                val comicInfoEntry = files.firstOrNull {
                    it.name.equals("ComicInfo.xml", ignoreCase = true)
                }
                val directoryMetadata = if (unchanged) {
                    comicInfoEntry?.let { statistics.contentReused(it.relativePath) }
                    null
                } else {
                    comicInfoEntry?.let { statistics.contentRead(it.relativePath) }
                    val localComicInfo = runCatching { comicInfo.fromDirectory(storage, path) }
                        .getOrElse { error ->
                            val source = comicInfoEntry?.relativePath ?: path
                            warnings += "ComicInfo $source 无法读取：${error.message.orEmpty()}"
                            RemLog.warn(TAG, "ComicInfo 无法读取：$source", error)
                            null
                        }?.withFieldSource(FieldSource.COMIC_INFO)
                    mergeRecognizedMetadata(
                        localComicInfo,
                        inferredMetadata,
                    ) ?: inferredMetadata
                }
                output += ScanCandidate(
                    relativePath = path,
                    uri = directory.uri,
                    kind = MediaKind.IMAGE_SET,
                    domain = MediaDomain.WORKS,
                    sourceKind = SourceKind.DIRECTORY,
                    suggestedTitle = path.substringAfterLast('/'),
                    mimeType = null,
                    size = totalSize,
                    modifiedAt = modifiedAt,
                    pageCount = images.size,
                    coverPath = sortedPages.firstOrNull()?.relativePath,
                    contentHash = directoryFingerprint(files),
                    recognizedMetadata = directoryMetadata,
                )
                // Downloaded image sets often contain one or more bonus videos. Keep the
                // directory as one readable image set, but never make those video files vanish.
                videos.forEach { video ->
                    val parsed = FilenameMetadataParser.parseVideo(video.name, path.substringAfterLast('/'))
                        .withFieldSource(FieldSource.FILENAME)
                    output += video.toCandidate(
                        kind = MediaKind.VIDEO,
                        domain = MediaDomain.WORKS,
                        sourceKind = SourceKind.FILE,
                        contentHash = contentHash(storage, video, prior, statistics),
                        recognizedMetadata = parsed.copy(
                            authors = parsed.authors.ifEmpty { (directoryMetadata ?: inferredMetadata).authors },
                            tags = parsed.tags.ifEmpty { (directoryMetadata ?: inferredMetadata).tags },
                            series = parsed.series ?: (directoryMetadata ?: inferredMetadata).series,
                        ),
                    )
                }
            }
        } else {
            if (path.isNotBlank() && !inPhotos && !inImages && !inVideos &&
                images.size >= MIN_IMAGE_SET_PAGES && directories.isNotEmpty()
            ) {
                ambiguous += path
                val directory = storage.entry(path)
                discoveries += ScanDiscoveryCandidate(
                    relativePath = path,
                    uri = directory?.uri,
                    isDirectory = true,
                    modifiedAt = directory?.lastModified ?: 0,
                    reason = DiscoveryReason.AMBIGUOUS_DIRECTORY,
                )
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
                        DownloadedSourceRecognizer.fromFile(image.relativePath)
                            ?.withFieldSource(FieldSource.FILENAME),
                        FilenameMetadataParser.parse(
                            image.name,
                            image.relativePath.substringBeforeLast('/', "").substringAfterLast('/')
                                .takeIf(String::isNotBlank),
                        ).withFieldSource(FieldSource.FILENAME),
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
                    recognizedMetadata = if (inPhotos) null else FilenameMetadataParser
                        .parseVideo(video.name, parentName)
                        .withFieldSource(FieldSource.FILENAME),
                )
            }
        }

        archives.forEach { archive ->
            val reusedPageCount = prior[archive.relativePath]
                ?.reusableArchivePageCount(archive.size, archive.lastModified)
            val inspection = if (reusedPageCount == null) {
                statistics.contentRead(archive.relativePath)
                runCatching {
                    comicInfo.inspectArchive(
                        storage = storage,
                        archivePath = archive.relativePath,
                        locator = archive.uri,
                        isPage = { name -> MediaClassifier.isImage(name, null) },
                    )
                }.getOrElse { error ->
                    warnings += "压缩包 ${archive.relativePath} 无法读取：${error.message.orEmpty()}"
                    ComicInfoReader.ArchiveInspection(0, null)
                }
            } else {
                statistics.contentReused(archive.relativePath)
                null
            }
            output += archive.toCandidate(
                kind = MediaKind.IMAGE_SET,
                domain = MediaDomain.WORKS,
                sourceKind = SourceKind.ARCHIVE,
                pageCount = reusedPageCount ?: inspection?.pageCount ?: 0,
                contentHash = contentHash(storage, archive, prior, statistics),
                // Existing local/portable metadata remains authoritative when the archive
                // is unchanged, so reopening it only to parse ComicInfo would be redundant.
                recognizedMetadata = if (inspection == null) null else mergeRecognizedMetadata(
                    inspection.metadata?.withFieldSource(FieldSource.COMIC_INFO),
                    DownloadedSourceRecognizer.fromFile(archive.relativePath)
                        ?.withFieldSource(FieldSource.FILENAME),
                    FilenameMetadataParser.parse(
                        archive.name,
                        archive.relativePath.substringBeforeLast('/', "").substringAfterLast('/').takeIf(String::isNotBlank),
                    ).withFieldSource(FieldSource.FILENAME),
                ),
            )
        }

        directories.forEach { directory ->
            scanDirectory(
                storage,
                directory.relativePath,
                output,
                ambiguous,
                discoveries,
                warnings,
                unreadableDirectories,
                prior,
                statistics,
                onProgress,
            )
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
            statistics.contentReused(entry.relativePath)
            return recorded.contentHash
        }
        statistics.contentRead(entry.relativePath)
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
            statistics.contentReused(entry.relativePath)
            return CapturedMetadata(recorded.capturedAt, recorded.latitude, recorded.longitude)
        }
        statistics.contentRead(entry.relativePath)
        return readCapturedMetadata(storage, entry)
    }

    internal fun directoryFingerprint(files: List<StorageEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedWith { left, right -> MediaClassifier.naturalCompare(left.name, right.name) }
            .forEach { entry ->
                digest.update(entry.name.lowercase(Locale.ROOT).encodeToByteArray())
                digest.update(0.toByte())
                digest.update(entry.size.toString().encodeToByteArray())
                digest.update(0.toByte())
                digest.update(entry.lastModified.toString().encodeToByteArray())
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
        private const val PROGRESS_DIRECTORY_INTERVAL = 10
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
