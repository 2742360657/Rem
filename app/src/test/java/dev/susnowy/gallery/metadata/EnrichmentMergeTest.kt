package dev.susnowy.gallery.metadata

import dev.susnowy.gallery.model.MediaDomain
import dev.susnowy.gallery.model.MediaItem
import dev.susnowy.gallery.model.MediaKind
import dev.susnowy.gallery.model.SeriesRef
import dev.susnowy.gallery.model.SourceKind
import dev.susnowy.gallery.scanner.ScanEnrichment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The regression this locks: media bytes are read outside the portable write mutex, so a
 * completed enrichment batch used to write the `MediaItem` snapshot taken *before* that read.
 * If the user edited the same Work while the batch was running, the batch silently rolled the
 * device projection back to the pre-edit value.
 *
 * The fix merges field by field into the row read at commit time, so both the user's edit and
 * the freshly read content survive.
 */
class EnrichmentMergeTest {
    private fun row(
        id: String = "work",
        relativePath: String = "downloads/work.cbz",
        title: String = "旧标题",
        authors: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        series: SeriesRef? = null,
        contentHash: String? = null,
        pageCount: Int? = null,
        fieldSources: Map<String, String> = emptyMap(),
    ) = MediaItem(
        id = id,
        libraryId = "library",
        relativePath = relativePath,
        uri = "content://$id",
        kind = MediaKind.IMAGE_SET,
        domain = MediaDomain.WORKS,
        sourceKind = SourceKind.ARCHIVE,
        displayTitle = title,
        authors = authors,
        tags = tags,
        series = series,
        contentHash = contentHash,
        pageCount = pageCount,
        fieldSources = fieldSources,
    )

    private fun recognition(
        relativePath: String = "downloads/work.cbz",
        title: String? = null,
        authors: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        language: String? = null,
        series: String? = null,
        chapter: Double? = null,
        contentHash: String? = null,
        pageCount: Int? = null,
    ) = ScanEnrichment(
        relativePath = relativePath,
        contentHash = contentHash,
        pageCount = pageCount,
        recognizedMetadata = RecognizedMetadata(
            title = title,
            series = series,
            chapter = chapter,
            authors = authors,
            tags = tags,
            language = language,
            fieldSources = buildMap {
                if (title != null) put(MetadataField.DISPLAY_TITLE, FieldSource.COMIC_INFO)
                if (authors.isNotEmpty()) put(MetadataField.AUTHORS, FieldSource.COMIC_INFO)
                if (tags.isNotEmpty() || language != null) put(MetadataField.TAGS, FieldSource.COMIC_INFO)
                if (series != null || chapter != null) put(MetadataField.SERIES, FieldSource.FILENAME)
            },
        ),
    )

    @Test
    fun anEditMadeDuringEnrichmentIsNotRolledBack() {
        val planned = row(title = "旧标题", contentHash = null, pageCount = null)
        // The user renamed the Work while its bytes were being read.
        val current = planned.copy(
            displayTitle = "用户改的标题",
            fieldSources = mapOf(MetadataField.DISPLAY_TITLE to FieldSource.MANUAL),
        )

        val merged = current.mergeEnrichment(planned, recognition(contentHash = "hash", pageCount = 12))

        assertEquals("用户改的标题", merged.displayTitle)
        assertEquals(FieldSource.MANUAL, merged.fieldSources[MetadataField.DISPLAY_TITLE])
        assertEquals("hash", merged.contentHash)
        assertEquals(12, merged.pageCount)
    }

    @Test
    fun automaticFieldsAreFilledFromTheFreshRecognition() {
        val planned = row()
        val current = planned.copy(pageCount = 5)

        val merged = current.mergeEnrichment(
            planned,
            recognition(title = "识别标题", authors = listOf("作者"), contentHash = "hash", pageCount = 12),
        )

        assertEquals("识别标题", merged.displayTitle)
        assertEquals(listOf("作者"), merged.authors)
        assertEquals("hash", merged.contentHash)
        assertEquals(12, merged.pageCount)
        assertEquals(FieldSource.COMIC_INFO, merged.fieldSources[MetadataField.DISPLAY_TITLE])
        assertEquals(FieldSource.COMIC_INFO, merged.fieldSources[MetadataField.AUTHORS])
    }

    @Test
    fun aManualTitleSurvivesEnrichment() {
        val planned = row(title = "人工标题")
        val current = planned.copy(
            fieldSources = mapOf(MetadataField.DISPLAY_TITLE to FieldSource.MANUAL),
        )

        val merged = current.mergeEnrichment(planned, recognition(title = "自动标题"))

        assertEquals("人工标题", merged.displayTitle)
        assertEquals(FieldSource.MANUAL, merged.fieldSources[MetadataField.DISPLAY_TITLE])
    }

    @Test
    fun manualTagsAreNeverAppendedTo() {
        val planned = row(tags = listOf("manual:tag"))
        val current = planned.copy(
            tags = listOf("manual:tag"),
            fieldSources = mapOf(MetadataField.TAGS to FieldSource.MANUAL),
        )

        val merged = current.mergeEnrichment(planned, recognition(tags = listOf("自动"), language = "zh"))

        assertEquals(listOf("manual:tag"), merged.tags)
    }

    @Test
    fun automaticTagRecognitionAppendsWithoutDuplicates() {
        val planned = row(tags = listOf("已有"))
        val current = planned.copy(tags = listOf("已有"))

        val merged = current.mergeEnrichment(
            planned,
            recognition(tags = listOf("已有", "新增"), language = "zh"),
        )

        assertEquals(listOf("已有", "新增", "language:zh"), merged.tags)
    }

    @Test
    fun recognizedSeriesReplacesAnAutomaticAssignmentButNeverAManualOne() {
        val automatic = row(series = SeriesRef(id = "old", title = "旧系列"))
            .copy(fieldSources = mapOf(MetadataField.SERIES to FieldSource.FILENAME))
        val fromRecognition = automatic.mergeEnrichment(
            automatic,
            recognition(series = "新系列", chapter = 3.0),
        )
        assertEquals("新系列", fromRecognition.series?.title)
        assertEquals(3.0, fromRecognition.series?.chapter ?: 0.0, 0.001)

        val manual = automatic.copy(
            series = null,
            fieldSources = mapOf(MetadataField.SERIES to FieldSource.MANUAL),
        )
        val kept = manual.mergeEnrichment(manual, recognition(series = "不应生效"))
        assertNull(kept.series)
    }

    @Test
    fun theSeriesIdIsStableAcrossDevicesAndScans() {
        val planned = row()
        val first = planned.mergeEnrichment(planned, recognition(series = "系列 A", chapter = 1.0))
        val second = planned.mergeEnrichment(planned, recognition(series = "系列 A", chapter = 1.0))

        assertEquals(first.series?.id, second.series?.id)
        assertEquals(
            java.util.UUID.nameUUIDFromBytes("library:系列 A".encodeToByteArray()).toString(),
            first.series?.id,
        )
    }

    @Test
    fun aMismatchedRowIsNotEnriched() {
        val planned = row()
        val other = planned.copy(relativePath = "other/work.cbz")

        val failure = runCatching { other.mergeEnrichment(planned, recognition()) }

        assertTrue("路径不一致时必须拒绝合并", failure.isFailure)
    }

    @Test
    fun aUserEditKeepsFieldsTheScannerRefreshedMeanwhile() {
        val planned = row(title = "旧标题", contentHash = null, pageCount = null)
        // The scanner finished first: the row now carries fresh automatic values.
        val current = planned.copy(contentHash = "hash", pageCount = 12, capturedAt = 1_700_000_000_000)
        val edited = planned.copy(displayTitle = "新标题")

        val merged = edited.mergeEdit(planned, current)

        assertEquals("新标题", merged.displayTitle)
        assertEquals(FieldSource.MANUAL, merged.fieldSources[MetadataField.DISPLAY_TITLE])
        assertEquals("hash", merged.contentHash)
        assertEquals(12, merged.pageCount)
        assertEquals(1_700_000_000_000, merged.capturedAt ?: 0L)
    }

    @Test
    fun anUnchangedFieldKeepsTheAutomaticProvenanceOfTheCurrentRow() {
        val planned = row(
            fieldSources = mapOf(
                MetadataField.DISPLAY_TITLE to FieldSource.MANUAL,
                MetadataField.AUTHORS to FieldSource.FILENAME,
            ),
        )
        val current = planned.copy(pageCount = 7)
        val edited = planned.copy(authors = listOf("用户作者"))

        val merged = edited.mergeEdit(planned, current)

        assertEquals(listOf("用户作者"), merged.authors)
        assertEquals(FieldSource.MANUAL, merged.fieldSources[MetadataField.AUTHORS])
        assertEquals(FieldSource.MANUAL, merged.fieldSources[MetadataField.DISPLAY_TITLE])
        assertEquals(7, merged.pageCount)
    }

    @Test
    fun aStaleEditorCannotDropManualProvenanceWrittenAfterItOpened() {
        val planned = row(
            title = "旧标题",
            fieldSources = mapOf(
                MetadataField.DISPLAY_TITLE to FieldSource.FILENAME,
                MetadataField.AUTHORS to FieldSource.FILENAME,
            ),
        )
        // Another user action commits an author while this editor is still open.
        val current = planned.copy(
            authors = listOf("并发写入的作者"),
            fieldSources = planned.fieldSources + (MetadataField.AUTHORS to FieldSource.MANUAL),
        )
        val edited = planned.copy(displayTitle = "编辑器里的新标题")

        val merged = edited.mergeEdit(planned, current)

        assertEquals("编辑器里的新标题", merged.displayTitle)
        assertEquals(listOf("并发写入的作者"), merged.authors)
        assertEquals(FieldSource.MANUAL, merged.fieldSources[MetadataField.DISPLAY_TITLE])
        assertEquals(
            "当前行的人工来源锁不能被旧编辑器快照清掉",
            FieldSource.MANUAL,
            merged.fieldSources[MetadataField.AUTHORS],
        )
    }

    @Test
    fun aStaleEditorKeepsAutomaticProvenanceThatArrivedWithEnrichment() {
        val planned = row(title = "旧标题")
        val current = planned.copy(
            authors = listOf("ComicInfo 作者"),
            fieldSources = mapOf(MetadataField.AUTHORS to FieldSource.COMIC_INFO),
        )
        val edited = planned.copy(displayTitle = "新标题")

        val merged = edited.mergeEdit(planned, current)

        assertEquals(listOf("ComicInfo 作者"), merged.authors)
        assertEquals(FieldSource.COMIC_INFO, merged.fieldSources[MetadataField.AUTHORS])
    }

    @Test
    fun aRebuiltSeriesReferenceDoesNotBecomeAManualDecision() {
        val planned = row(series = SeriesRef(id = "stable-id", title = "系列 A", chapter = 4.0))
        // The editor rebuilds the assignment from the Library; only the derived id differs.
        val edited = planned.copy(series = SeriesRef(id = "other-id", title = "系列 A", chapter = 4.0))

        val merged = edited.mergeEdit(planned, planned)

        assertEquals("stable-id", merged.series?.id)
        assertTrue(merged.fieldSources[MetadataField.SERIES] == null)
    }

    @Test
    fun aChangedSeriesPositionIsStillAManualDecision() {
        val planned = row(series = SeriesRef(id = "stable-id", title = "系列 A", chapter = 4.0))
        val edited = planned.copy(series = SeriesRef(id = "stable-id", title = "系列 A", chapter = 5.0))

        val merged = edited.mergeEdit(planned, planned)

        assertEquals(5.0, merged.series?.chapter ?: 0.0, 0.001)
        assertEquals(FieldSource.MANUAL, merged.fieldSources[MetadataField.SERIES])
    }
}
