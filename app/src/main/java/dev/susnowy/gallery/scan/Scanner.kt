package dev.susnowy.gallery.scan

import android.content.Context
import dev.susnowy.gallery.logging.RemLog
import dev.susnowy.gallery.media.MediaProbe
import dev.susnowy.gallery.model.ALBUM
import dev.susnowy.gallery.model.COLLECTION
import dev.susnowy.gallery.model.Entry
import dev.susnowy.gallery.model.MediaType
import dev.susnowy.gallery.model.splitProjectFolder
import dev.susnowy.gallery.storage.Child
import dev.susnowy.gallery.storage.LibraryTree

/** What one pass over a Library produced. */
data class ScanResult(
    val entries: List<Entry>,
    /** Relative paths that do not follow the file rules. Reported, never corrected. */
    val violations: List<String>,
    /** True when every entry came from the cache, so nothing had to be re-read. */
    val reusedCache: Boolean,
)

/**
 * Walks the two browsable directories and produces the entry list.
 *
 * The walk is deliberately shallow: `相册/` contributes its direct files and `画集/` contributes
 * one level of project folders. Nothing below that is read, so a nested folder is reported as a
 * rule violation instead of being silently indexed. `待分类/` is never touched at all.
 *
 * Cost is dominated by reading EXIF and video headers, so a cached entry whose size and
 * modification time still match is reused without opening the file. That is what keeps the
 * background refresh after start-up off the critical path.
 */
class Scanner(private val context: Context, private val tree: LibraryTree) {

    fun scan(cached: Map<String, Entry>): ScanResult {
        val startedAt = System.currentTimeMillis()
        RemLog.info(
            SCOPE,
            "开始扫描 root='${tree.rootDocumentId()}' 缓存条目=${cached.size}",
        )
        val result = Pass(cached).run()
        RemLog.info(
            SCOPE,
            "扫描结束 条目=${result.entries.size} 复用=${if (result.reusedCache) "全部" else "部分"} " +
                "违规=${result.violations.size} 用时=${System.currentTimeMillis() - startedAt}ms",
        )
        result.violations.take(MAX_LOGGED_VIOLATIONS).forEach { RemLog.warn(SCOPE, "违规：$it") }
        if (result.violations.size > MAX_LOGGED_VIOLATIONS) {
            RemLog.warn(SCOPE, "另有 ${result.violations.size - MAX_LOGGED_VIOLATIONS} 条违规未逐条记录")
        }
        return result
    }

    private inner class Pass(private val cached: Map<String, Entry>) {
        private val violations = mutableListOf<String>()
        private var reused = 0
        private var total = 0

        fun run(): ScanResult {
            val entries = buildList {
                val albumChildren = tree.list(ALBUM)
                RemLog.info(SCOPE, "'$ALBUM' 直属项 ${albumChildren.size}：" + albumChildren.names())
                addAll(readFiles(albumChildren, "相册下不允许建立子文件夹"))

                val collectionChildren = tree.list(COLLECTION)
                RemLog.info(SCOPE, "'$COLLECTION' 直属项 ${collectionChildren.size}：" + collectionChildren.names())
                for (project in collectionChildren) {
                    if (!project.isDirectory) {
                        violations += "${project.path}：画集第一层只能是项目文件夹"
                        continue
                    }
                    if (splitProjectFolder(project.name) == null) {
                        violations += "${project.path}：项目文件夹必须命名为「作者名称-项目名称」"
                    }
                    val files = tree.list(project.path)
                    RemLog.debug(SCOPE, "项目 '${project.path}' 内含 ${files.size} 项：" + files.names())
                    addAll(readFiles(files, "项目文件夹内不允许建立子文件夹"))
                }
            }
            return ScanResult(
                entries = entries,
                violations = violations.toList(),
                reusedCache = reused == total,
            )
        }

        private fun List<Child>.names(): String = joinToString("、") { it.name }.take(LOG_NAME_LIMIT)

        /** Turns one directory listing into entries, recording everything that cannot be one. */
        private fun readFiles(children: List<Child>, nestedMessage: String): List<Entry> =
            children.mapNotNull { child ->
                // Dot entries are Rem's own state and markers. They are not user content, and the
                // rules only speak about media, so they are skipped without being reported.
                if (child.name.startsWith('.')) return@mapNotNull null
                if (child.isDirectory) {
                    violations += "${child.path}：$nestedMessage"
                    return@mapNotNull null
                }
                val type = child.mediaType
                if (type == null) {
                    violations += "${child.path}：不支持的媒体格式"
                    return@mapNotNull null
                }
                // Only collection files carry a sequence number, so the rule is applied where it means something.
                if (child.path.startsWith("$COLLECTION/") && !SEQUENCE.matches(child.name)) {
                    violations += "${child.path}：文件名必须是四位数字序号，如 0001.jpg"
                }
                total++
                entryFor(child, type)
            }

        private fun entryFor(child: Child, type: MediaType): Entry {
            cachedEntry(child)?.let {
                reused++
                return it
            }
            val metadata = MediaProbe.read(context, child.uri, type)
            return Entry(
                path = child.path,
                size = child.size,
                modified = child.modified,
                captured = metadata.captured,
                place = metadata.place,
            )
        }

        /**
         * A cached reading is trusted only when both size and modification time match. Size alone
         * would miss an edit that kept the length, and a timestamp alone would miss a replacement
         * that restored one.
         */
        private fun cachedEntry(child: Child): Entry? =
            cached[child.path]?.takeIf { it.size == child.size && it.modified == child.modified }
    }

    private companion object {
        const val SCOPE = "Scan"
        const val MAX_LOGGED_VIOLATIONS = 50
        const val LOG_NAME_LIMIT = 300

        val SEQUENCE = Regex("^\\d{4}\\.[^.]+$")
    }
}
