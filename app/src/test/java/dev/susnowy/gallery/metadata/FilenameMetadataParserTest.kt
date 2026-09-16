package dev.susnowy.gallery.metadata

import org.junit.Assert.assertEquals
import org.junit.Test

class FilenameMetadataParserTest {
    @Test
    fun parsesAuthorPrefix() {
        val metadata = FilenameMetadataParser.parse("[Artist] A Book.cbz")
        assertEquals("A Book", metadata.title)
        assertEquals(listOf("Artist"), metadata.authors)
    }

    @Test
    fun parsesSeriesNumber() {
        val metadata = FilenameMetadataParser.parse("Series - Ch 12.5 - Finale")
        assertEquals("Series", metadata.series)
        assertEquals(12.5, metadata.sortIndex)
        assertEquals("Finale", metadata.title)
    }

    @Test
    fun preservesSimpleTitles() {
        assertEquals("My Work", FilenameMetadataParser.parse("My Work").title)
    }

    @Test
    fun parsesNumberedCreatorCollectionFromDownloadedFolder() {
        val metadata = FilenameMetadataParser.parse(
            "花柒Hana – NO.040 萤火虫动漫嘉年华 猫猫蕾姆[107P-1V-889.7M]",
            "218.花柒Hana",
        )
        assertEquals("萤火虫动漫嘉年华 猫猫蕾姆", metadata.title)
        assertEquals(listOf("花柒Hana"), metadata.authors)
        assertEquals(40.0, metadata.sortIndex)
    }

    @Test
    fun stripsDownloadFactsFromStandaloneSet() {
        val metadata = FilenameMetadataParser.parse("9358-狐玖玖 柴郡睡衣 [32P-264.23MB]")
        assertEquals("狐玖玖 柴郡睡衣", metadata.title)
    }

    @Test
    fun parsesAnimeSeasonAndEpisode() {
        val metadata = FilenameMetadataParser.parseVideo(
            "[SubsPlease] Example Show - S02E03 - A Title (1080p) [ABCDEF12].mkv",
        )
        assertEquals("Example Show", metadata.series)
        assertEquals(2, metadata.season)
        assertEquals(3.0, metadata.episode)
        assertEquals("A Title", metadata.title)
    }

    @Test
    fun usesSeriesFolderForBareEpisodeName() {
        val metadata = FilenameMetadataParser.parseVideo("03 - A Title.mkv", "Example Show")
        assertEquals("Example Show", metadata.series)
        assertEquals(3.0, metadata.episode)
        assertEquals("A Title", metadata.title)
    }

    @Test
    fun usesParentSeriesForChapterFolder() {
        val metadata = FilenameMetadataParser.parse("Chapter 12 - Finale", "Example Manga")
        assertEquals("Example Manga", metadata.series)
        assertEquals(12.0, metadata.sortIndex)
        assertEquals("Finale", metadata.title)
    }
}
