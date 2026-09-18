package dev.susnowy.gallery.tool

import dev.susnowy.gallery.portable.AgentCatalogEdits
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    try {
        require(args.size >= 2) { USAGE }
        val library = LocalLibrary(Path.of(args[1]))
        val output = when (args[0]) {
            "validate" -> {
                require(args.size == 2) { USAGE }
                val current = library.snapshot()
                buildJsonObject {
                    put("valid", true)
                    put("library_id", current.library.libraryId)
                    put("works", current.catalog.works.size)
                    put("assets", current.catalog.assets.size)
                    put("catalog_sha256", AgentCatalogEdits.sha256(current.texts.getValue(LocalLibrary.CATALOG)!!.toByteArray(Charsets.UTF_8)))
                    put("media_checked", false)
                }.toString()
            }
            "preview", "apply" -> {
                require(if (args[0] == "preview") args.size == 3 else args.size == 5 && args[4] == "--exclusive") { USAGE }
                val plan = AgentCatalogEdits.parsePlan(Files.readString(Path.of(args[2])))
                val report = if (args[0] == "preview") library.preview(plan).report
                    else library.apply(plan, Path.of(args[3]))
                AgentCatalogEdits.json.encodeToString(report)
            }
            "recover-catalog" -> {
                require(args.size == 3 && args[2] == "--exclusive") { USAGE }
                library.recoverCatalog()
                "{\"recovered\":true}"
            }
            "export-guide" -> {
                require(args.size == 3) { USAGE }
                library.exportGuide(Path.of(args[2]))
                "{\"exported\":true,\"existing_guide_replaced\":false}"
            }
            else -> error(USAGE)
        }
        println(output)
    } catch (error: Exception) {
        System.err.println("Rem Library Tool: ${error.message ?: error.javaClass.simpleName}")
        exitProcess(2)
    }
}

private const val USAGE = """Usage (Java 17+):
  library-tool validate ROOT
  library-tool preview ROOT PLAN.json
  library-tool apply ROOT PLAN.json EXISTING_BACKUP_DIRECTORY --exclusive
  library-tool recover-catalog ROOT --exclusive
  library-tool export-guide ROOT NEW_GUIDE.md
--exclusive asserts Android and other writers are disconnected/stopped.
Only existing Work metadata is edited. No media I/O, recognition, migration, or physical operations.
"""
