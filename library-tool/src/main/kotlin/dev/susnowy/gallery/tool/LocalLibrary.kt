package dev.susnowy.gallery.tool

import dev.susnowy.gallery.model.*
import dev.susnowy.gallery.portable.*
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.util.UUID
import kotlinx.serialization.json.*

/** Host metadata access only. Never enumerate or open any Asset/media path. */
class LocalLibrary(directory: Path) {
    val root: Path = directory.toRealPath()
    private val json = AgentCatalogEdits.json
    private val documents = listOf(
        ".gallery/library.json", ".gallery/items/catalog.json", ".gallery/state/state.json",
        ".gallery/state/inbox.json", ".gallery/schema/v4.json", "GALLERY_LIBRARY.md",
    )

    data class Snapshot(val texts: Map<String, String?>, val library: PortableLibrary, val catalog: PortableCatalog)

    fun snapshot(): Snapshot {
        val texts = documents.associateWith { name ->
            val file = safe(name)
            val recovery = file.resolveSibling(".${file.fileName}.rem-backup")
            require(!Files.exists(recovery, NOFOLLOW_LINKS)) { "发现恢复槽：先由 Rem 恢复，或使用 recover-catalog" }
            if (Files.exists(file, NOFOLLOW_LINKS)) Files.readString(file) else null
        }
        return validateTexts(texts)
    }

    private fun validateTexts(texts: Map<String, String?>): Snapshot {
        val identity = requireNotNull(texts[documents[0]]) { "缺少 library.json" }
        requireSchema(identity)
        val library = json.decodeFromString<PortableLibrary>(identity)
        require(library.format == GALLERY_FORMAT) { "未知 Library 格式" }
        UUID.fromString(library.libraryId)
        val catalogText = requireNotNull(texts[CATALOG]) { "缺少 catalog.json；先在 Rem 中接受内容" }
        requireSchema(catalogText)
        val catalog = json.decodeFromString<PortableCatalog>(catalogText)
        require(catalog.libraryId == library.libraryId) { "Catalog 身份不匹配" }
        PortableValidation.validate(catalog)
        val workIds = catalog.works.mapTo(mutableSetOf()) { it.id }
        texts[".gallery/state/state.json"]?.let { text ->
            requireSchema(text)
            val state = json.decodeFromString<PortableState>(text)
            require(state.libraryId == library.libraryId) { "State 身份不匹配" }
            PortableValidation.validate(state)
            require(state.progress.all { it.itemId in workIds } && state.trash.all { it.itemId in workIds }) { "State 引用缺失 Work" }
        }
        texts[".gallery/state/inbox.json"]?.let { text ->
            requireSchema(text)
            val inbox = json.decodeFromString<PortableInbox>(text)
            require(inbox.libraryId == library.libraryId) { "Inbox 身份不匹配" }
            PortableValidation.validate(inbox)
            require(inbox.decisions.all { it.workId == null || it.workId in workIds }) { "Inbox 引用缺失 Work" }
            require(inbox.decisions.none { it.disposition == InboxDisposition.HANDLED && it.target != InboxTarget.DISCOVERY }) { "handled 只能用于 discovery" }
        }
        texts[".gallery/schema/v4.json"]?.let(::requireSchema)
        return Snapshot(texts, library, catalog)
    }

    fun preview(plan: AgentEditPlan): PreparedAgentEdit = snapshot().let {
        AgentCatalogEdits.prepare(it.texts.getValue(CATALOG)!!, plan)
    }

    /** The caller has exclusive use of this Library: Android must be disconnected/stopped. */
    fun apply(plan: AgentEditPlan, backupDirectory: Path, afterBackupMove: () -> Unit = {}): AgentEditReport = locked {
        refuseTransactions()
        val before = snapshot()
        val prepared = AgentCatalogEdits.prepare(before.texts.getValue(CATALOG)!!, plan)
        if (prepared.report.changed.isEmpty()) return@locked prepared.report
        backup(before, backupDirectory)
        val target = safe(CATALOG)
        val recovery = safe(".gallery/items/.catalog.json.rem-backup")
        val stage = safe(".gallery/items/.${UUID.randomUUID()}.catalog.json.tmp")
        writeNew(stage, prepared.catalog)
        try {
            // Include identity/state/Inbox in the check, even though only catalog is modified.
            require(snapshot().texts == before.texts) { "备份期间 Library 已变化，拒绝提交" }
            Files.move(target, recovery, ATOMIC_MOVE)
            afterBackupMove()
            Files.move(stage, target, ATOMIC_MOVE)
            check(Files.readString(target) == prepared.catalog) { "提交后校验失败；保留备份供恢复" }
            // A cleanup failure does not turn a completed write into a failed write.
            runCatching { Files.delete(recovery) }
            prepared.report
        } finally {
            Files.deleteIfExists(stage)
        }
    }

    /** Same stable-slot rule as Android: live wins, otherwise restore the old revision. */
    fun recoverCatalog() = locked {
        refuseTransactions()
        val target = safe(CATALOG)
        val recovery = safe(".gallery/items/.catalog.json.rem-backup")
        require(Files.exists(recovery, NOFOLLOW_LINKS)) { "没有 catalog 恢复槽" }
        val selected = if (Files.exists(target, NOFOLLOW_LINKS)) target else recovery
        val texts = documents.associateWith { name ->
            val file = if (name == CATALOG) selected else safe(name)
            if (name != CATALOG) {
                require(!Files.exists(file.resolveSibling(".${file.fileName}.rem-backup"), NOFOLLOW_LINKS)) {
                    "其他文档存在恢复槽，请先由 Rem 恢复"
                }
            }
            if (Files.exists(file, NOFOLLOW_LINKS)) Files.readString(file) else null
        }
        validateTexts(texts)
        if (selected == recovery) Files.move(recovery, target, ATOMIC_MOVE)
        else Files.delete(recovery)
    }

    /** Export updated instructions separately; never overwrite the user's current guide. */
    fun exportGuide(destination: Path) {
        val current = snapshot()
        val output = destination.toAbsolutePath().normalize()
        require(output.fileName.toString() != "GALLERY_LIBRARY.md") { "请导出为新文件，保留现有库内说明" }
        Files.newBufferedWriter(output, Charsets.UTF_8, CREATE_NEW, WRITE).use {
            it.write("# ${current.library.name.replace('\n', ' ').replace('\r', ' ')}\n\n")
            it.write("Library ID: ${current.library.libraryId}\n\n")
            it.write(LibraryAgentInstructions.text())
        }
    }

    private fun backup(snapshot: Snapshot, destination: Path) {
        val parent = destination.toAbsolutePath().normalize()
        // Require an existing destination so symlinks and containment can be resolved first.
        val real = parent.toRealPath()
        require(!real.startsWith(root)) { "备份目录必须位于 Library 之外；真实库请使用另一介质" }
        val folder = Files.createDirectory(real.resolve("rem-agent-${UUID.randomUUID()}"))
        snapshot.texts.forEach { (name, text) ->
            if (text != null) {
                val file = folder.resolve(name)
                Files.createDirectories(file.parent)
                writeNew(file, text)
                check(Files.readString(file) == text) { "备份校验失败" }
            }
        }
    }

    private fun requireSchema(text: String) {
        require(json.parseToJsonElement(text).jsonObject["schema_version"]?.jsonPrimitive?.intOrNull == 4) {
            "仅支持显式 Schema v4；旧版先由 Rem 转换，新版拒绝写入"
        }
    }

    private fun refuseTransactions() {
        val directory = safe(".gallery/transactions")
        if (Files.isDirectory(directory, NOFOLLOW_LINKS)) {
            Files.list(directory).use { paths ->
                require(paths.noneMatch { it.fileName.toString().endsWith(".json") }) {
                    "存在物理事务记录；此版工具不能判定事务完成状态，拒绝编辑。请在 Rem 中处理，勿删除活动事务"
                }
            }
        }
    }

    private fun <T> locked(action: () -> T): T {
        // Keep host-only locks out of portable truth. OS releases the lock on process death.
        val lockDirectory = Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "rem-agent-locks"))
        val canonical = if (System.getProperty("os.name").startsWith("Windows")) root.toString().lowercase(java.util.Locale.ROOT) else root.toString()
        val lockPath = lockDirectory.resolve(AgentCatalogEdits.sha256(canonical.toByteArray(Charsets.UTF_8)))
        FileChannel.open(lockPath, CREATE, WRITE).use { channel ->
            val lock = checkNotNull(channel.tryLock()) { "另一个 Agent 工具正在写入" }
            lock.use { return action() }
        }
    }

    /** Reject symlinks/junctions escaping the selected root, including metadata ancestors. */
    private fun safe(relative: String): Path {
        val file = root.resolve(relative).normalize()
        require(file.startsWith(root))
        var cursor = root
        root.relativize(file).forEach { part ->
            cursor = cursor.resolve(part)
            if (Files.exists(cursor, NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor) && cursor.toRealPath().startsWith(root)) { "元数据路径含链接或越出 Library" }
            }
        }
        return file
    }

    private fun writeNew(file: Path, text: String) {
        FileChannel.open(file, CREATE_NEW, WRITE).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    companion object { const val CATALOG = ".gallery/items/catalog.json" }
}
