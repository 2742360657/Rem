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
}
