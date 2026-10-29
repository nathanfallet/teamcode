package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.workflows.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Workflow definitions in shared persistence. A save or a delete first locks the team's settings row, which serializes
 * them across replicas: the call-loop check, the name and the cap are evaluated on what no other save can change
 * meanwhile (docs/workflows.md). Reads take no lock.
 */
class SqlWorkflowStore(private val database: Database) : WorkflowStore {
    override suspend fun revalidate(actor: Principal) = database.query { requireCurrent(this, actor) }
    override suspend fun member(teamId: String, memberId: String): Member? = database.query {
        Members.selectAll().where { (Members.id eq memberId) and (Members.teamId eq teamId) }.singleOrNull()
            ?.let { Member(it[Members.id], it[Members.username], readMemberRole(it), it[Members.disabled], it[Members.mustChangePassword]) }
    }

    override suspend fun editors(teamId: String): WorkflowEditors = database.query { readEditors(teamId) }

    private fun JdbcTransaction.readEditors(teamId: String): WorkflowEditors =
        WorkflowTeamSettings.selectAll().where { WorkflowTeamSettings.teamId eq teamId }.singleOrNull()?.let {
            WorkflowEditors(EditorMode.parse(it[WorkflowTeamSettings.editorMode]) ?: EditorMode.ADMINS, WorkflowJson.ids(it[WorkflowTeamSettings.editorIds]))
        } ?: WorkflowEditors()

    override suspend fun saveEditors(actor: Principal, editors: WorkflowEditors) {
        ensureSettings(actor.teamId)
        database.query {
            requireCurrent(this, actor)
            lock(actor.teamId)
            WorkflowTeamSettings.update({ WorkflowTeamSettings.teamId eq actor.teamId }) {
                it[editorMode] = editors.mode.wire; it[editorIds] = WorkflowJson.ids(editors.memberIds)
            }
            audit(actor.teamId, actor.member.id, "workflow.editors.changed", actor.teamId, null, actor.tokenId, details = editors.mode.wire)
        }
    }

    override suspend fun find(teamId: String, id: String): Workflow? = database.query {
        Workflows.selectAll().where { (Workflows.id eq id) and (Workflows.teamId eq teamId) }.singleOrNull()?.let(::workflow)
    }

    override suspend fun list(teamId: String, after: String?, limit: Int): WorkflowPage = database.query {
        var condition: Op<Boolean> = Workflows.teamId eq teamId
        after?.let { condition = condition and (Workflows.id greater it) }
        val rows = Workflows.selectAll().where { condition }.orderBy(Workflows.id to SortOrder.ASC).limit(limit + 1).toList()
        val page = rows.take(limit).map(::workflow)
        WorkflowPage(page, if (rows.size > limit) page.last().id else null)
    }

    override suspend fun save(
        actor: Principal, id: String?, definition: WorkflowDefinition, expectedVersion: Int?, limit: Int, check: (Map<String, WorkflowDefinition>) -> Unit,
    ): Workflow {
        ensureSettings(actor.teamId)
        return database.query {
            requireCurrent(this, actor)
            lock(actor.teamId)
            val others = Workflows.select(Workflows.id, Workflows.definition).where { Workflows.teamId eq actor.teamId }.limit(limit + 1)
                .associate { it[Workflows.id] to WorkflowJson.definition(it[Workflows.definition]) }
            check(others.filterKeys { it != id })
            val now = nowMillis()
            val json = WorkflowJson.definition(definition)
            if (id == null) {
                if (others.size >= limit) throw TeamFailure("workflow_limit", 409, "A team keeps at most $limit workflows. Delete one first.")
                nameFree(actor.teamId, definition.name, null)
                val created = UUID.randomUUID().toString()
                Workflows.insert {
                    it[Workflows.id] = created; it[teamId] = actor.teamId; it[name] = definition.name; it[version] = 1; it[Workflows.definition] = json
                    it[createdAt] = now; it[updatedAt] = now; it[updatedById] = actor.member.id
                }
                audit(actor.teamId, actor.member.id, "workflow.created", created, null, actor.tokenId)
                return@query workflow(Workflows.selectAll().where { Workflows.id eq created }.single())
            }
            nameFree(actor.teamId, definition.name, id)
            val changed = Workflows.update({
                (Workflows.id eq id) and (Workflows.teamId eq actor.teamId) and (if (expectedVersion != null) Workflows.version eq expectedVersion else Op.TRUE)
            }) {
                it[name] = definition.name; it[Workflows.definition] = json; it[updatedAt] = now; it[updatedById] = actor.member.id
                it[version] = Workflows.version + 1
            } > 0
            if (!changed) {
                if (Workflows.select(Workflows.id).where { (Workflows.id eq id) and (Workflows.teamId eq actor.teamId) }.empty()) throw TeamFailure("not_found", 404, "Workflow not found.")
                throw TeamFailure("workflow_conflict", 409, "Someone saved this workflow meanwhile. Reload it and apply your change again.")
            }
            audit(actor.teamId, actor.member.id, "workflow.updated", id, null, actor.tokenId)
            workflow(Workflows.selectAll().where { Workflows.id eq id }.single())
        }
    }

    override suspend fun delete(actor: Principal, id: String): Workflow {
        ensureSettings(actor.teamId)
        return database.query {
            requireCurrent(this, actor)
            lock(actor.teamId)
            val row = Workflows.selectAll().where { (Workflows.id eq id) and (Workflows.teamId eq actor.teamId) }.singleOrNull() ?: throw TeamFailure("not_found", 404, "Workflow not found.")
            val users = Workflows.select(Workflows.name, Workflows.definition).where { (Workflows.teamId eq actor.teamId) and (Workflows.id neq id) }
                .filter { r -> WorkflowJson.definition(r[Workflows.definition]).nodes.any { it.workflowId == id } }
            if (users.isNotEmpty()) throw TeamFailure("workflow_in_use", 409, "Workflow ${users.first()[Workflows.name]} calls this one. Change it first.")
            Workflows.deleteWhere { (Workflows.id eq id) and (teamId eq actor.teamId) }
            audit(actor.teamId, actor.member.id, "workflow.deleted", id, null, actor.tokenId)
            workflow(row)
        }
    }

    /**
     * The settings row exists before anything locks it, created in a transaction of its own: two replicas inserting it
     * inside the locking transaction would each hold the gap the other waits for, and one would be a deadlock victim.
     */
    private suspend fun ensureSettings(teamId: String) = database.query {
        WorkflowTeamSettings.insertIgnore { it[WorkflowTeamSettings.teamId] = teamId; it[editorMode] = EditorMode.ADMINS.wire; it[editorIds] = "[]" }
    }

    /** The team's lock: its settings row (see [ensureSettings]), held until the transaction ends. */
    private fun JdbcTransaction.lock(teamId: String) {
        WorkflowTeamSettings.select(WorkflowTeamSettings.teamId).where { WorkflowTeamSettings.teamId eq teamId }.forUpdate().single()
    }

    private fun JdbcTransaction.nameFree(teamId: String, name: String, self: String?) {
        var condition: Op<Boolean> = (Workflows.teamId eq teamId) and (Workflows.name eq name)
        self?.let { condition = condition and (Workflows.id neq it) }
        if (!Workflows.select(Workflows.id).where { condition }.limit(1).empty()) throw TeamFailure("workflow_name_taken", 409, "Another workflow is already named $name.")
    }

    private fun workflow(row: ResultRow) = Workflow(
        row[Workflows.id], row[Workflows.teamId], row[Workflows.version], WorkflowJson.definition(row[Workflows.definition]),
        row[Workflows.createdAt], row[Workflows.updatedAt], row[Workflows.updatedById],
    )
}

/** The JSON of a stored definition; unknown members are dropped on read, never trusted. */
internal object WorkflowJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun ids(value: Set<String>) = buildJsonArray { value.sorted().forEach(::add) }.toString()
    fun ids(text: String): Set<String> = (json.parseToJsonElement(text) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()

    fun definition(value: WorkflowDefinition): String = buildJsonObject {
        put("name", value.name); value.description?.let { put("description", it) }; put("start", value.start); put("sessionMode", value.sessionMode.wire)
        put("inputs", buildJsonArray {
            value.inputs.forEach { add(buildJsonObject { put("id", it.id); put("label", it.label); put("required", it.required); it.default?.let { d -> put("default", d) }; put("maxLength", it.maxLength) }) }
        })
        put("limits", buildJsonObject { put("maxNodes", value.limits.maxNodes); put("maxVisits", value.limits.maxVisits); put("maxDepth", value.limits.maxDepth) })
        put("nodes", buildJsonArray { value.nodes.forEach { add(node(it)) } })
    }.toString()

    private fun node(n: WorkflowNode) = buildJsonObject {
        put("id", n.id); put("kind", n.kind.wire); put("title", n.title); n.instruction?.let { put("instruction", it) }
        n.profileId?.let { put("profileId", it) }; n.agent?.let { put("agent", it) }; n.model?.let { put("model", it) }
        n.deliverable?.let { d -> put("deliverable", buildJsonObject { put("type", d.type.wire); d.label?.let { put("label", it) }; d.domain?.let { put("domain", it) }; d.schema?.let { put("schema", it) } }) }
        put("fields", json.parseToJsonElement(WaitJson.fields(n.fields))); put("actions", json.parseToJsonElement(WaitJson.actions(n.actions)))
        n.assignee?.let { a -> put("assignee", buildJsonObject { put("ownerOfRun", a.ownerOfRun); a.memberId?.let { put("memberId", it) } }) }
        n.workflowId?.let { put("workflowId", it) }
        put("arguments", buildJsonObject { n.arguments.forEach { (k, v) -> put(k, v) } })
        put("transitions", buildJsonArray { n.transitions.forEach { t -> add(buildJsonObject { put("on", t.on); t.to?.let { put("to", it) } }) } })
        put("maxVisits", n.maxVisits)
    }

    fun definition(text: String): WorkflowDefinition {
        val o = json.parseToJsonElement(text).jsonObject
        val limits = o["limits"] as? JsonObject
        return WorkflowDefinition(
            o.str("name") ?: "", o.str("description"), o.str("start") ?: "", o.str("sessionMode")?.let(SessionMode::parse) ?: SessionMode.SAME,
            o.objects("inputs").map { WorkflowInput(it.str("id") ?: "", it.str("label") ?: "", it.bool("required", true), it.str("default"), it.int("maxLength", 4_096)) },
            o.objects("nodes").map(::node),
            WorkflowLimits(limits.int("maxNodes", 50), limits.int("maxVisits", 100), limits.int("maxDepth", 3)),
        )
    }

    private fun node(n: JsonObject) = WorkflowNode(
        n.str("id") ?: "", n.str("kind")?.let(NodeKind::parse) ?: NodeKind.AGENT, n.str("title") ?: "", n.str("instruction"),
        n.str("profileId"), n.str("agent"), n.str("model"),
        (n["deliverable"] as? JsonObject)?.let { d -> Deliverable(d.str("type")?.let(DeliverableType::parse) ?: DeliverableType.TEXT, d.str("label"), d.str("domain"), d["schema"]) },
        WaitJson.fields((n["fields"] ?: JsonArray(emptyList())).toString()), WaitJson.actions((n["actions"] ?: JsonArray(emptyList())).toString()),
        (n["assignee"] as? JsonObject)?.let { NodeAssignee(it.bool("ownerOfRun", true), it.str("memberId")) },
        n.str("workflowId"), (n["arguments"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty(),
        n.objects("transitions").map { Transition(it.str("on") ?: "", it.str("to")) }, n.int("maxVisits", 3),
    )

    private fun JsonObject?.str(key: String) = (this?.get(key) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject?.bool(key: String, default: Boolean) = (this?.get(key) as? JsonPrimitive)?.booleanOrNull ?: default
    private fun JsonObject?.int(key: String, default: Int) = (this?.get(key) as? JsonPrimitive)?.intOrNull ?: default
    private fun JsonObject.objects(key: String) = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}
