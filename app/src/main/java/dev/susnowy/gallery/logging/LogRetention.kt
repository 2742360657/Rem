package dev.susnowy.gallery.logging

import java.io.File

/**
 * Rolling-window policy for the diagnostic log directory, kept apart from [RemLog] so the
 * bounds can be tested: a crash loop must not be able to fill the device, and a stuck
 * clock must not be able to keep stale records forever.
 */
internal object LogRetention {
    const val SESSION_PREFIX = "session-"
    const val SESSION_SUFFIX = ".log"

    /**
     * Not a rolling session, even though it shares the prefix. Counting it against
     * [MAX_FILES] would evict a real session early, and it would eventually be deleted as
     * the oldest entry, taking the only record of where the log came from.
     */
    const val HEADER_FILE = "session-header.log"

    const val MAX_FILES = 10
    const val RETENTION_MILLIS = 14L * 24 * 60 * 60 * 1000
    const val TAIL_LINES = 400

    /** Oldest first, so `dropLast(MAX_FILES)` leaves the newest sessions. */
    fun sessions(directory: File): List<File> =
        directory.listFiles { file: File ->
            file.name.startsWith(SESSION_PREFIX) && file.name != HEADER_FILE
        }
            ?.sortedBy(File::getName)
            .orEmpty()

    /** Deletes sessions past the age window and beyond the file cap. Returns what remains. */
    fun prune(directory: File, now: Long = System.currentTimeMillis()): List<File> {
        val threshold = now - RETENTION_MILLIS
        sessions(directory).filter { it.lastModified() < threshold }.forEach { file ->
            runCatching { file.delete() }
        }
        sessions(directory).dropLast(MAX_FILES).forEach { file ->
            runCatching { file.delete() }
        }
        return sessions(directory)
    }

    /** The last [lines] lines of a file, reading it as a stream rather than loading it all. */
    fun tail(file: File, lines: Int = TAIL_LINES): String {
        if (!file.isFile) return ""
        val kept = ArrayDeque<String>(lines)
        runCatching {
            file.useLines { sequence ->
                sequence.forEach { line ->
                    if (kept.size == lines) kept.removeFirst()
                    kept.addLast(line)
                }
            }
        }
        return kept.joinToString("\n", postfix = "\n")
    }
}
