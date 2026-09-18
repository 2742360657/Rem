package dev.susnowy.gallery.portable

import dev.susnowy.gallery.model.*
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable
data class AgentEditPlan(
    val library_id: String,
    val expected_catalog_sha256: String,
    val agent_id: String,
    val edits: List<AgentWorkEdit>,
)

@Serializable
data class AgentWorkEdit(val work_id: String, val set: JsonObject)

@Serializable
data class AgentEditReport(
    val changed: Map<String, List<String>>,
    val skipped_manual: Map<String, List<String>>,
)

data class PreparedAgentEdit(val catalog: String, val report: AgentEditReport)

/** Pure plan preparation. Unknown catalog fields survive because edits operate on the JSON tree. */
object AgentCatalogEdits {
    val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val planJson = Json { ignoreUnknownKeys = false }
    private val editable = setOf(
        "display_title", "original_title", "domain", "authors", "tags", "collections",
        "favorite", "cover_path", "preferred_edition_id",
    )

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun parsePlan(text: String): AgentEditPlan = planJson.decodeFromString(text)

    fun prepare(original: String, plan: AgentEditPlan, now: String = Instant.now().toString()): PreparedAgentEdit {
        require(plan.agent_id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))) { "agent_id 无效" }
        require(plan.expected_catalog_sha256 == sha256(original.toByteArray(Charsets.UTF_8))) {
            "catalog 已变化：重新读取并生成计划，不要覆盖并发决定"
        }
        Instant.parse(now)
        val tree = json.parseToJsonElement(original).jsonObject
        require(tree["schema_version"]?.jsonPrimitive?.intOrNull == 4) { "只编辑显式 Schema v4" }
        val catalog = json.decodeFromJsonElement<PortableCatalog>(tree)
        PortableValidation.validate(catalog)
        require(catalog.libraryId == plan.library_id) { "计划不属于当前 Library" }
        require(plan.edits.map { it.work_id }.distinct().size == plan.edits.size) { "计划包含重复 Work" }
        val works = catalog.works.associateBy { it.id }
        val changes = linkedMapOf<String, List<String>>()
        val skipped = linkedMapOf<String, List<String>>()
        val patches = plan.edits.associateBy { it.work_id }
        val rawWorks = tree.getValue("works").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
        plan.edits.forEach { edit ->
            require(edit.work_id in works) { "计划引用不存在的 Work: ${edit.work_id}" }
            require(edit.set.keys.all { it in editable }) { "仅允许编辑 Work 元数据字段" }
            // Validate requested types even if every requested field is manually locked.
            val source = rawWorks.getValue(edit.work_id).jsonObject
            json.decodeFromJsonElement<PortableWork>(JsonObject(source + edit.set))
        }
        val rewritten = tree.getValue("works").jsonArray.map { element ->
            val source = element.jsonObject
            val id = source.getValue("id").jsonPrimitive.content
            val edit = patches[id] ?: return@map element
            val work = works.getValue(id)
            val fields = source.toMutableMap()
            val provenance = (source["field_sources"]?.jsonObject ?: JsonObject(emptyMap())).toMutableMap()
            val touched = mutableListOf<String>()
            val locked = mutableListOf<String>()
            edit.set.forEach { (field, value) ->
                if (work.fieldSources[field] == "manual") {
                    locked += field
                } else if (source[field] != value) {
                    if (field == "tags") {
                        val stable = work.tags.filter(::isStableTag)
                        val newTags = value.jsonArray.map { it.jsonPrimitive.content }
                        require(newTags.containsAll(stable)) { "不能删除稳定来源 Tag" }
                    }
                    fields[field] = value
                    provenance[field] = JsonPrimitive("provider:${plan.agent_id}")
                    touched += field
                }
            }
            if (locked.isNotEmpty()) skipped[id] = locked
            if (touched.isEmpty()) return@map element
            changes[id] = touched
            fields["field_sources"] = JsonObject(provenance)
            fields["revision"] = JsonPrimitive(Math.addExact(work.revision, 1))
            fields["updated_at"] = JsonPrimitive(now)
            JsonObject(fields)
        }
        if (changes.isEmpty()) return PreparedAgentEdit(original, AgentEditReport(changes, skipped))
        val result = JsonObject(tree + mapOf(
            "works" to JsonArray(rewritten),
            "revision" to JsonPrimitive(Math.addExact(catalog.revision, 1)),
            "updated_at" to JsonPrimitive(now),
        ))
        PortableValidation.validate(json.decodeFromJsonElement<PortableCatalog>(result))
        return PreparedAgentEdit(json.encodeToString(result) + "\n", AgentEditReport(changes, skipped))
    }

    private fun isStableTag(tag: String) = listOf("source:", "jm:album:", "eh:gid:", "pixiv:id:").any(tag::startsWith)
}
