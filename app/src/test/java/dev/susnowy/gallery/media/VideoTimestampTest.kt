package dev.susnowy.gallery.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Video capture times arrive in two different ISO-8601 shapes and both must parse.
 *
 * Android's own MP4 extractor writes the compact form (`20240909T090909.000Z`), while most
 * containers carry the extended form. Before this was handled, every video fell back to its file
 * modification time, so a copied-in video sorted by when it was copied rather than when it was
 * filmed. These cases pin both shapes down, plus the ones that must stay unparsed.
 */
class VideoTimestampTest {

    /** 2024-09-09T09:09:09Z */
    private val expected = 1725872949000L

    @Test
    fun `extended iso 8601 with Z parses`() {
        assertEquals(expected, parseVideoTimestamp("2024-09-09T09:09:09Z"))
    }

    @Test
    fun `extended iso 8601 with fractional seconds parses`() {
        assertEquals(expected, parseVideoTimestamp("2024-09-09T09:09:09.000000Z"))
    }

    @Test
    fun `compact form written by android parses`() {
        assertEquals(expected, parseVideoTimestamp("20240909T090909.000Z"))
    }

    @Test
    fun `compact form without fractional seconds parses`() {
        assertEquals(expected, parseVideoTimestamp("20240909T090909Z"))
    }

    @Test
    fun `a numeric offset is honoured`() {
        // 09:09:09+02:00 is two hours earlier in UTC.
        assertEquals(expected - 7_200_000L, parseVideoTimestamp("2024-09-09T09:09:09+02:00"))
    }

    @Test
    fun `the 1904 mp4 epoch counts as no reading`() {
        // A file that never set creation_time reports the 1904 epoch. Trusting it would sort the
        // video behind every other file instead of falling back to its modification time.
        assertNull(parseVideoTimestamp("19040101T000000.000Z"))
        assertNull(parseVideoTimestamp("1904-01-01T00:00:00Z"))
    }

    @Test
    fun `nonsense stays unparsed rather than guessed`() {
        assertNull(parseVideoTimestamp(""))
        assertNull(parseVideoTimestamp("not a date"))
        assertNull(parseVideoTimestamp("0000-00-00T00:00:00Z"))
    }
}
