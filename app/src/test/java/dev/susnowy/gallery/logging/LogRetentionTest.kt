package dev.susnowy.gallery.logging

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The log directory is the one part of the diagnostic system that grows on its own, so
 * its bounds are stated as tests: a crash loop must not fill the device, and a clock that
 * jumps backwards must not let stale sessions live forever.
 */
class LogRetentionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun session(name: String, modifiedAt: Long, content: String = "") =
        temporaryFolder.newFile(name).apply {
            writeText(content)
            setLastModified(modifiedAt)
        }

    @Test
    fun sessionsAreOrderedOldestFirstSoTheCapKeepsTheNewest() {
        val directory = temporaryFolder.root
        session("session-20260914.log", 1_000)
        session("session-20260916.log", 3_000)
        session("session-20260915.log", 2_000)
        temporaryFolder.newFile("last-crash.log")
        temporaryFolder.newFile("session-header.log")

        val sessions = LogRetention.sessions(directory).map(File::getName)

        assertEquals(
            listOf("session-20260914.log", "session-20260915.log", "session-20260916.log"),
            sessions,
        )
    }

    @Test
    fun fileCapDropsTheOldestSessionsOnly() {
        val directory = temporaryFolder.root
        val now = 1_700_000_000_000
        repeat(14) { index ->
            session("session-202609%02d.log".format(index + 1), now - index * 1_000)
        }

        val kept = LogRetention.prune(directory, now).map(File::getName)

        assertEquals(LogRetention.MAX_FILES, kept.size)
        // The newest ten are the keys 14 down to 5.
        assertTrue(kept.contains("session-20260914.log"))
        assertTrue(kept.contains("session-20260905.log"))
        assertTrue(!kept.contains("session-20260904.log"))
    }

    @Test
    fun ageWindowDropsStaleSessions() {
        val directory = temporaryFolder.root
        val now = 1_700_000_000_000
        session("session-old.log", now - LogRetention.RETENTION_MILLIS - 1)
        session("session-fresh.log", now - 1_000)

        val kept = LogRetention.prune(directory, now).map(File::getName)

        assertEquals(listOf("session-fresh.log"), kept)
    }

    @Test
    fun pruneIgnoresUnrelatedFiles() {
        val directory = temporaryFolder.root
        val now = 1_700_000_000_000
        session("session-20260916.log", now)
        val crash = session("last-crash.log", now)
        val header = session("session-header.log", now)

        LogRetention.prune(directory, now)

        assertTrue(crash.exists())
        assertTrue(header.exists())
    }

    @Test
    fun tailReturnsOnlyTheLastLines() {
        val file = session("session-20260916.log", 0, (1..50).joinToString("\n") { "line-$it" })

        val tail = LogRetention.tail(file, lines = 3)

        assertEquals("line-48\nline-49\nline-50\n", tail)
    }

    @Test
    fun tailOfAMissingFileIsEmpty() {
        assertEquals("", LogRetention.tail(File(temporaryFolder.root, "absent.log")))
    }
}
