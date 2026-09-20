package dev.susnowy.gallery.logging

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Rem's own log file, so a problem can be diagnosed after the fact.
 *
 * `logcat` is not enough. A SAF failure surfaces as a snackbar the user has already dismissed by
 * the time anyone looks, MIUI's log buffers are noisy and get cleared, and the events that matter
 * (which tree was attached, what the scan found, which file failed to open) are exactly the ones
 * no framework log records.
 *
 * The file lives in `cacheDir/logs/`, which is readable with `adb pull` on a debug build and is
 * never scanned by Rem itself — a log inside the Library would be indexed as an unsupported file.
 *
 * Writes are synchronous and flushed. A crash loses everything still buffered, and a crash is
 * precisely when the last line matters most, so a synchronous append is worth its cost here.
 */
object RemLog {

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")

    private const val DIRECTORY = "logs"
    private const val FILE_NAME = "rem.log"
    private const val TAG = "Rem"

    /** A session starts by trimming an oversized file, so only one rotation ever happens. */
    private const val MAX_BYTES = 512L * 1024L

    @Volatile
    private var file: File? = null

    private val lock = Any()

    fun initialize(context: Context) {
        val directory = File(context.cacheDir, DIRECTORY)
        val target = File(directory, FILE_NAME)
        synchronized(lock) {
            file = runCatching {
                directory.mkdirs()
                if (target.length() > MAX_BYTES) {
                    // Keep the tail: the most recent session is the one being diagnosed.
                    target.writeText(target.readText(Charsets.UTF_8).takeLast((MAX_BYTES / 2).toInt()))
                }
                target
            }.getOrNull()
        }
        if (file == null) Log.w(TAG, "日志文件不可用，仅输出到 logcat")
        info("RemLog", "日志开始 version=${versionName(context)} pid=${android.os.Process.myPid()}")
    }

    fun debug(scope: String, message: String) = write("D", scope, message, null)

    fun info(scope: String, message: String) = write("I", scope, message, null)

    fun warn(scope: String, message: String, error: Throwable? = null) = write("W", scope, message, error)

    fun error(scope: String, message: String, error: Throwable? = null) = write("E", scope, message, error)

    /** Records the outcome of one step, with its duration. */
    fun timed(scope: String, message: String, startedAt: Long) =
        info(scope, "$message 用时 ${System.currentTimeMillis() - startedAt}ms")

    /** The log directory, for sharing or pulling. */
    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    fun text(): String =
        synchronized(lock) { file?.takeIf(File::isFile)?.readText(Charsets.UTF_8) }.orEmpty()

    fun clear() {
        synchronized(lock) { file?.writeText("", Charsets.UTF_8) }
        info("RemLog", "日志已清空")
    }

    private fun write(level: String, scope: String, message: String, error: Throwable?) {
        Log.println(level.priority(), TAG, "[$scope] $message")
        error?.let { Log.println(Log.ERROR, TAG, Log.getStackTraceString(it)) }
        val line = buildString {
            append(TIME.format(Instant.now().atZone(ZoneId.systemDefault())))
            append(' ').append(level).append(' ')
            append('[').append(Thread.currentThread().name).append("] ")
            append(scope).append(": ").append(message)
            error?.let { append('\n').append(it.stackTraceToString().trimEnd()) }
            append('\n')
        }
        synchronized(lock) {
            val target = file ?: return
            runCatching { target.appendText(line, Charsets.UTF_8) }
        }
    }

    private fun String.priority(): Int = when (this) {
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        else -> Log.ERROR
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("?")
}
