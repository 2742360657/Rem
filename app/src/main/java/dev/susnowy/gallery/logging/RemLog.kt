package dev.susnowy.gallery.logging

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import dev.susnowy.gallery.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * The device-side record of what Rem did, kept so a problem reported from a phone can be
 * investigated without a cable attached.
 *
 * Logcat is not enough for that: its ring buffer is wiped by a reboot and by ordinary
 * system chatter, it is unreadable without a host, and a release build's stack traces are
 * obfuscated. Records therefore go to `filesDir/logs/`, which needs no permission, works
 * while a removable Library is unplugged, and is cleared when the app is uninstalled.
 *
 * Records never go into the Library itself. `.gallery` is the user's portable truth and
 * must stay writable-only-when-mounted, so a diagnostic written there would vanish
 * exactly when something goes wrong with the volume.
 *
 * Writes are handed to a single background thread, so a logging call never blocks the
 * caller and never interleaves two records. Level filtering follows the build type:
 * debug builds record everything, release builds record warnings and above.
 */
object RemLog {
    const val TAG = "Rem"

    private const val DIRECTORY = "logs"
    private const val CRASH_FILE = "last-crash.log"
    private const val HEADER_FILE = LogRetention.HEADER_FILE

    private const val MAX_MESSAGE_CHARS = 4_000

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rem-log").apply { isDaemon = true }
    }
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)
    private val fileStamp = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
    private val initialized = AtomicReference<File?>(null)

    /** Wires up crash capture and rolls old records out. Safe to call more than once. */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        val directory = File(appContext.filesDir, DIRECTORY).apply { mkdirs() }
        if (!initialized.compareAndSet(null, directory)) return
        installCrashHandler(appContext, directory)
        executor.execute {
            LogRetention.prune(directory)
            writeHeader(appContext, directory)
        }
    }

    fun debug(tag: String, message: String) = record(Level.DEBUG, tag, message, null)

    fun info(tag: String, message: String) = record(Level.INFO, tag, message, null)

    fun warn(tag: String, message: String, error: Throwable? = null) =
        record(Level.WARN, tag, message, error)

    fun error(tag: String, message: String, error: Throwable? = null) =
        record(Level.ERROR, tag, message, error)

    /** Records an operation that failed, keeping its cause so the report stays actionable. */
    fun failure(tag: String, message: String, error: Throwable) =
        record(Level.ERROR, tag, message, error)

    /**
     * Recent records, newest last, capped at [LogRetention.TAIL_LINES] so the viewer can render a large
     * Library's log without loading all of it.
     */
    fun tail(context: Context): String {
        val directory = directory(context)
        val session = sessionFile(directory)
        val crash = File(directory, CRASH_FILE)
        return buildString {
            // The previous session's records still describe what happened before a restart.
            sessionFiles(directory).filter { it != session }.takeLast(1).forEach { previous ->
                appendLine("—— 上一次运行（${previous.name}）——")
                append(tailOf(previous, LogRetention.TAIL_LINES / 2))
            }
            appendLine("—— 本次运行 ——")
            append(tailOf(session, LogRetention.TAIL_LINES))
            if (crash.isFile) {
                appendLine()
                appendLine("—— 上次崩溃 ——")
                append(tailOf(crash, LogRetention.TAIL_LINES / 2))
            }
        }
    }

    /** The files a bug report should contain, newest first, ready to be shared. */
    fun reportFiles(context: Context): List<File> {
        val directory = directory(context)
        return buildList {
            add(sessionFile(directory))
            File(directory, CRASH_FILE).takeIf(File::isFile)?.let(::add)
            File(directory, HEADER_FILE).takeIf(File::isFile)?.let(::add)
        }.filter(File::isFile)
    }

    /**
     * Shares the current records through the system share sheet. The files stay in the
     * app's private storage and are exposed read-only through a content URI, so nothing
     * needs storage permission and the user picks the destination.
     */
    fun share(context: Context) {
        val files = reportFiles(context)
        if (files.isEmpty()) return
        executor.execute {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "text/plain"
                putParcelableArrayListExtra(
                    Intent.EXTRA_STREAM,
                    ArrayList(
                        files.map { file ->
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                        },
                    ),
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "导出 Rem 诊断日志")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    }

    fun clear(context: Context) {
        val directory = directory(context)
        executor.execute {
            sessionFiles(directory).forEach { runCatching { it.delete() } }
            runCatching { File(directory, CRASH_FILE).delete() }
        }
    }

    private enum class Level { DEBUG, INFO, WARN, ERROR }

    private fun record(level: Level, tag: String, message: String, error: Throwable?) {
        if (!isRecorded(level)) return
        // Logcat stays authoritative for live debugging; the file is the durable copy.
        when (level) {
            Level.DEBUG -> Log.d(tag, message, error)
            Level.INFO -> Log.i(tag, message, error)
            Level.WARN -> Log.w(tag, message, error)
            Level.ERROR -> Log.e(tag, message, error)
        }
        val line = buildString {
            append(formatter.format(Date()))
            append(' ').append(level.name.first())
            append(' ').append(tag)
            append(" | ").append(scrub(message).take(MAX_MESSAGE_CHARS))
            error?.let { append(" | ").append(describe(it)) }
            append('\n')
        }
        val directory = initialized.get() ?: return
        executor.execute { runCatching { appendTo(sessionFile(directory), line) } }
    }

    private fun isRecorded(level: Level): Boolean =
        BuildConfig.DEBUG || level == Level.WARN || level == Level.ERROR

    /** One line per failure, keeping the first few frames so a report stays readable. */
    private fun describe(error: Throwable): String {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val relevant = trace.lineSequence()
            .filter { line -> line.contains("dev.susnowy.gallery") || !line.trimStart().startsWith("at ") }
            .take(8)
            .joinToString(" <- ")
            .replace('\n', ' ')
        return "${error.javaClass.name}: ${scrub(error.message.orEmpty())}".let { headline ->
            if (relevant.isBlank()) headline else "$headline [$relevant]"
        }
    }

    private fun installCrashHandler(context: Context, directory: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = buildString {
                    appendLine("时间: ${formatter.format(Date())}")
                    appendLine("线程: ${thread.name}")
                    appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine()
                    append(scrub(StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()))
                }
                appendTo(sessionFile(directory), "崩溃 | $report")
                // A dedicated copy survives the next launch's log rotation.
                File(directory, CRASH_FILE).writeText(report)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeHeader(context: Context, directory: File) {
        val header = buildString {
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("开始: ${formatter.format(Date())}")
        }
        runCatching { File(directory, HEADER_FILE).writeText(header) }
        appendTo(sessionFile(directory), "启动 | ${header.replace('\n', ' ')}")
    }

    private fun directory(context: Context) =
        initialized.get() ?: File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    private fun sessionFile(directory: File) =
        File(
            directory,
            "${LogRetention.SESSION_PREFIX}${fileStamp.format(Date())}${LogRetention.SESSION_SUFFIX}",
        )

    private fun sessionFiles(directory: File): List<File> = LogRetention.sessions(directory)

    private fun appendTo(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.appendText(text)
    }

    private fun tailOf(file: File, lines: Int): String = LogRetention.tail(file, lines)

    /**
     * Keeps a report safe to hand over. The rule from the portable Library guide is that
     * account material never leaves the device, and a tree URI carries the volume id of
     * the user's disk, so both are reduced to what is needed to understand the failure.
     */
    internal fun scrub(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace(CONTENT_URI, "content://…")
            .replace(WINDOWS_PATH, "…")
    }

    private val CONTENT_URI = Regex("""content://[^\s"']*""")
    private val WINDOWS_PATH = Regex("""[A-Za-z]:[\\/][^\s"']*""")
}
