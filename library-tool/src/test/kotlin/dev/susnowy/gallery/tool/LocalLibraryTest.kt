package dev.susnowy.gallery.tool

import dev.susnowy.gallery.portable.AgentCatalogEdits
import dev.susnowy.gallery.portable.AgentEditPlan
import dev.susnowy.gallery.portable.AgentWorkEdit
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val id = "d421f1ce-59e7-4f9f-85a0-250a586cdca5"
    private val time = "2026-09-18T00:00:00Z"

    private fun fixture(): Path {
        val root = temporary.newFolder().toPath()
        Files.createDirectories(root.resolve(".gallery/items"))
        Files.createDirectories(root.resolve(".gallery/state"))
        Files.writeString(root.resolve(".gallery/library.json"), """{"format":"gallery-library","schema_version":4,"library_id":"$id","name":"test","created_at":"$time","updated_at":"$time"}""")
        Files.writeString(root.resolve(LocalLibrary.CATALOG), """{
          "schema_version":4,"library_id":"$id","revision":7,"updated_at":"$time","extension":{"keep":true},
          "assets":[{"id":"a","relative_path":"Missing/book.cbz","media_type":"image_set","source":"archive","revision":1,"updated_at":"$time"}],
          "works":[{"id":"w","type":"image_set","domain":"works","display_title":"locked","authors":[],"tags":["source:jm"],"collections":[],"favorite":false,"revision":3,"updated_at":"$time","field_sources":{"display_title":"manual","authors":"manual"},"extension":"keep"}],
          "editions":[{"id":"e","work_id":"w","assets":[{"asset_id":"a"}],"revision":1,"updated_at":"$time"}],"groups":[],"series":[]
        }""")
        Files.writeString(root.resolve(".gallery/state/state.json"), """{"schema_version":4,"library_id":"$id","revision":2,"updated_at":"$time","progress":[{"work_id":"w","page":5,"last_opened_at":"$time"}],"trash":[]}""")
        Files.writeString(root.resolve(".gallery/state/inbox.json"), """{"schema_version":4,"library_id":"$id","revision":1,"updated_at":"$time","decisions":[{"relative_path":"Missing/book.cbz","target":"media","work_id":"w","disposition":"ignored","by":"manual","decided_at":"$time"}]}""")
        Files.writeString(root.resolve("GALLERY_LIBRARY.md"), "用户规则，不能覆盖")
        return root
    }

    private fun plan(root: Path, fields: String = """{"display_title":"replace","authors":["new"],"collections":["new"]}"""): AgentEditPlan = AgentEditPlan(
        id, AgentCatalogEdits.sha256(Files.readAllBytes(root.resolve(LocalLibrary.CATALOG))), "test",
        listOf(AgentWorkEdit("w", Json.parseToJsonElement(fields).jsonObject)),
    )

    @Test fun preservesManualEmptyFieldsUnknownDataAndOtherDocuments() {
        val root = fixture()
        val tool = LocalLibrary(root)
        val before = tool.snapshot()
        val backup = temporary.newFolder().toPath()
        val report = tool.apply(plan(root), backup)
        assertEquals(listOf("collections"), report.changed["w"])
        assertEquals(listOf("display_title", "authors"), report.skipped_manual["w"])
        val after = tool.snapshot()
        val work = after.catalog.works.single()
        assertEquals("locked", work.displayTitle)
        assertTrue(work.authors.isEmpty())
        assertEquals(listOf("new"), work.collections)
        assertEquals("provider:test", work.fieldSources["collections"])
        assertEquals(4L, work.revision)
        assertEquals(8L, after.catalog.revision)
        assertEquals(before.texts - LocalLibrary.CATALOG, after.texts - LocalLibrary.CATALOG)
        val tree = Json.parseToJsonElement(after.texts.getValue(LocalLibrary.CATALOG)!!).jsonObject
        assertTrue(tree.getValue("extension").jsonObject.getValue("keep").jsonPrimitive.boolean)
        assertEquals("keep", tree.getValue("works").jsonArray.single().jsonObject.getValue("extension").jsonPrimitive.content)
        Files.walk(backup).use { files ->
            val saved = files.filter { it.fileName.toString() == "catalog.json" }.findFirst().get()
            assertEquals(before.texts[LocalLibrary.CATALOG], Files.readString(saved))
        }
        assertFalse(Files.exists(root.resolve("Missing")))
    }

    @Test fun previewDoesNotWriteAndOldPlansAreRejected() {
        val root = fixture()
        val tool = LocalLibrary(root)
        val before = tool.snapshot()
        val plan = plan(root)
        tool.preview(plan)
        assertEquals(before.texts, tool.snapshot().texts)
        Files.writeString(root.resolve(LocalLibrary.CATALOG), before.texts.getValue(LocalLibrary.CATALOG)!! + "\n")
        val changed = tool.snapshot().texts
        assertThrows(IllegalArgumentException::class.java) { tool.apply(plan, temporary.newFolder().toPath()) }
        assertEquals(changed, tool.snapshot().texts)
    }

    @Test fun noOpDoesNotRewriteOrBackup() {
        val root = fixture()
        val tool = LocalLibrary(root)
        val before = tool.snapshot().texts
        val backup = temporary.newFolder().toPath()
        val result = tool.apply(plan(root, """{"display_title":"ignored"}"""), backup)
        assertTrue(result.changed.isEmpty())
        assertEquals(before, tool.snapshot().texts)
        Files.list(backup).use { assertEquals(0L, it.count()) }
    }

    @Test fun rejectsWrongTypesUnsafePathsProtectedTagsAndReferences() {
        val root = fixture()
        val tool = LocalLibrary(root)
        val before = tool.snapshot().texts
        listOf(
            """{"favorite":"yes"}""", """{"cover_path":"../secret"}""",
            """{"preferred_edition_id":"absent"}""", """{"tags":[]}""",
            """{"id":"replacement"}""",
        ).forEach { fields ->
            assertThrows(Exception::class.java) { tool.preview(plan(root, fields)) }
            assertEquals(before, tool.snapshot().texts)
        }
    }

    @Test fun rejectsFutureSchemaInEveryDocument() {
        listOf(".gallery/library.json", LocalLibrary.CATALOG, ".gallery/state/state.json", ".gallery/state/inbox.json").forEach { document ->
            val root = fixture()
            val plan = plan(root)
            val path = root.resolve(document)
            val original = Files.readString(path).replace("\"schema_version\":4", "\"schema_version\":99")
            Files.writeString(path, original)
            assertThrows(IllegalArgumentException::class.java) { LocalLibrary(root).apply(plan, temporary.newFolder().toPath()) }
            assertEquals(original, Files.readString(path))
        }
    }

    @Test fun interruptedCommitRestoresStableBackupAndCanRetry() {
        val root = fixture()
        val tool = LocalLibrary(root)
        val before = tool.snapshot().texts
        val plan = plan(root)
        assertThrows(IllegalStateException::class.java) {
            tool.apply(plan, temporary.newFolder().toPath()) { error("simulated process stop") }
        }
        assertFalse(Files.exists(root.resolve(LocalLibrary.CATALOG)))
        assertThrows(IllegalArgumentException::class.java) { tool.snapshot() }
        tool.recoverCatalog()
        assertEquals(before, tool.snapshot().texts)
        tool.apply(plan, temporary.newFolder().toPath())
        assertEquals(listOf("new"), tool.snapshot().catalog.works.single().collections)
    }

    @Test fun backupMustBeOutsideLibraryAndTransactionsBlockWrites() {
        val root = fixture()
        val tool = LocalLibrary(root)
        val before = tool.snapshot().texts
        assertThrows(IllegalArgumentException::class.java) { tool.apply(plan(root), root) }
        Files.createDirectories(root.resolve(".gallery/transactions"))
        Files.writeString(root.resolve(".gallery/transactions/active.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) { tool.apply(plan(root), temporary.newFolder().toPath()) }
        assertEquals(before, tool.snapshot().texts)
    }

    @Test fun separateGuideExportPreservesUserGuideAndRefusesOverwrite() {
        val root = fixture()
        val tool = LocalLibrary(root)
        val output = root.resolve("GALLERY_LIBRARY.next.md")
        tool.exportGuide(output)
        assertTrue(Files.readString(output).contains("expected_catalog_sha256"))
        assertEquals("用户规则，不能覆盖", Files.readString(root.resolve("GALLERY_LIBRARY.md")))
        assertThrows(Exception::class.java) { tool.exportGuide(output) }
        assertThrows(IllegalArgumentException::class.java) { tool.exportGuide(root.resolve("GALLERY_LIBRARY.md")) }
    }

    @Test fun rejectedPlanKeysAreNotSilentlyIgnored() {
        assertThrows(Exception::class.java) {
            AgentCatalogEdits.parsePlan("""{"library_id":"$id","expected_catalog_sha256":"x","agent_id":"test","edits":[],"unexpected":true}""")
        }
    }

    @Test fun recoveryMustNotModifyLibraryWithNewerState() {
        val root = fixture()
        val tool = LocalLibrary(root)
        assertThrows(IllegalStateException::class.java) {
            tool.apply(plan(root), temporary.newFolder().toPath()) { error("stop") }
        }
        val state = root.resolve(".gallery/state/state.json")
        Files.writeString(state, Files.readString(state).replace("\"schema_version\":4", "\"schema_version\":99"))
        assertThrows(IllegalArgumentException::class.java) { tool.recoverCatalog() }
        assertFalse(Files.exists(root.resolve(LocalLibrary.CATALOG)))
        assertTrue(Files.exists(root.resolve(".gallery/items/.catalog.json.rem-backup")))
    }

    @Test fun recoveryPrefersValidatedLiveRevision() {
        val root = fixture()
        val tool = LocalLibrary(root)
        val old = Files.readString(root.resolve(LocalLibrary.CATALOG))
        tool.apply(plan(root), temporary.newFolder().toPath())
        val live = Files.readString(root.resolve(LocalLibrary.CATALOG))
        Files.writeString(root.resolve(".gallery/items/.catalog.json.rem-backup"), old)
        tool.recoverCatalog()
        assertEquals(live, Files.readString(root.resolve(LocalLibrary.CATALOG)))
        assertEquals(listOf("new"), tool.snapshot().catalog.works.single().collections)
    }

    @Test fun rejectsGroupCoverOutsideItsMembersBeforeAnyWrite() {
        val root = fixture()
        val catalog = root.resolve(LocalLibrary.CATALOG)
        val invalid = Files.readString(catalog).replace(
            "\"groups\":[]",
            """"groups":[{"id":"g","title":"Empty group","members":[],"cover_work_id":"w","revision":1,"updated_at":"$time"}]""",
        )
        Files.writeString(catalog, invalid)
        val backup = temporary.newFolder().toPath()
        assertThrows(IllegalArgumentException::class.java) { LocalLibrary(root).apply(plan(root), backup) }
        assertEquals(invalid, Files.readString(catalog))
        Files.list(backup).use { assertEquals(0L, it.count()) }
        Files.writeString(catalog, invalid.replace("\"members\":[]", """"members":[{"work_id":"w"}]"""))
        assertEquals("w", LocalLibrary(root).snapshot().catalog.groups.single().coverWorkId)
    }
}
