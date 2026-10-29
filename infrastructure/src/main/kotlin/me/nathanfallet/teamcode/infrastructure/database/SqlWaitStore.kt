package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.waits.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Waits in shared persistence. Answering and cancelling are one conditional update on `status = open`, so of two
 * replicas answering at once exactly one changes the row; creation locks its session's row, so the cap of open waits
 * holds across replicas. A session's open permission request is read from the session's own columns, in the same
 * transaction as the stored waits, never copied (docs/waits.md).
 */
class SqlWaitStore(private val database: Database) : WaitStore {
    override suspend fun now(): Long = database.query { nowMillis() }
    override suspend fun revalidate(actor: Principal) = database.query { requireCurrent(this, actor) }
    override suspend fun member(teamId: String, memberId: String): Member? = database.query {
        Members.selectAll().where { (Members.id eq memberId) and (Members.teamId eq teamId) }.singleOrNull()
            ?.let { Member(it[Members.id], it[Members.username], readMemberRole(it), it[Members.disabled], it[Members.mustChangePassword]) }
    }
    override suspend fun executionSession(tokenId: String): String? = database.query {
        CloudInferenceCredentials.select(CloudInferenceCredentials.sessionId).where { CloudInferenceCredentials.tokenId eq tokenId }.singleOrNull()?.get(CloudInferenceCredentials.sessionId)
    }
    override suspend fun session(teamId: String, sessionId: String): WaitSession? = database.query {
        CloudSessions.select(CloudSessions.id, CloudSessions.teamId, CloudSessions.ownerId, CloudSessions.name)
            .where { (CloudSessions.id eq sessionId) and (CloudSessions.teamId eq teamId) }.singleOrNull()
            ?.let { WaitSession(it[CloudSessions.id], it[CloudSessions.teamId], it[CloudSessions.ownerId], it[CloudSessions.name]) }
    }

    override suspend fun create(actor: Principal, wait: Wait, idempotencyKey: String?, limit: Int): WaitChange = database.query {
        requireCurrent(this, actor)
        // The session's row serializes its creations, so two replicas cannot pass the cap together.
        CloudSessions.select(CloudSessions.id).where { (CloudSessions.id eq wait.sessionId!!) and (CloudSessions.teamId eq wait.teamId) }.forUpdate().singleOrNull()
            ?: throw TeamFailure("not_found", 404, "Session not found.")
        if (idempotencyKey != null) {
            Waits.select(Waits.id).where { (Waits.createdById eq actor.member.id) and (Waits.idempotencyKey eq idempotencyKey) }.singleOrNull()
                ?.let { return@query WaitChange(read(wait.teamId, it[Waits.id])!!, false) }
        }
        if (Waits.select(Waits.id).where { (Waits.sessionId eq wait.sessionId!!) and (Waits.status eq WaitStatus.OPEN.wire) }.limit(limit).count() >= limit)
            throw TeamFailure("wait_limit", 409, "A session holds at most $limit open waits. Answer or cancel one first.")
        Waits.insert {
            it[id] = wait.id; it[teamId] = wait.teamId; it[sessionId] = wait.sessionId!!; it[kind] = wait.kind.wire; it[resolver] = wait.resolver.wire
            it[status] = WaitStatus.OPEN.wire; it[assigneeId] = wait.assigneeId; it[createdById] = wait.createdById; it[title] = wait.title; it[body] = wait.body
            it[refs] = WaitJson.refs(wait.refs); it[fieldDefs] = WaitJson.fields(wait.fields); it[actions] = WaitJson.actions(wait.actions)
            it[Waits.idempotencyKey] = idempotencyKey; it[createdAt] = wait.createdAt
        }
        audit(actor.teamId, actor.member.id, "wait.created", wait.id, null, actor.tokenId, related = wait.assigneeId.takeIf { it != actor.member.id })
        val stored = read(wait.teamId, wait.id)!!
        // Notifications (push, mail) are a separate piece of work; this is the place to start them.
        publish(TeamEventType.WAIT_CREATED, stored, stored.createdAt)
        WaitChange(stored, true)
    }

    override suspend fun find(teamId: String, id: String): Wait? = database.query { read(teamId, id) ?: sessionWait(teamId, id) }

    override suspend fun list(teamId: String, query: WaitQuery): WaitPage = database.query {
        val cursor = query.before?.let { id ->
            Waits.select(Waits.createdAt).where { (Waits.id eq id) and (Waits.teamId eq teamId) }.singleOrNull()?.get(Waits.createdAt)
                ?: throw TeamFailure("invalid_cursor", 400, "Unknown wait cursor.")
        }
        var condition: Op<Boolean> = Waits.teamId eq teamId
        query.assignee?.let { condition = condition and (Waits.assigneeId eq it) }
        query.sessionId?.let { condition = condition and (Waits.sessionId eq it) }
        query.status?.let { condition = condition and (Waits.status eq it.wire) }
        if (cursor != null) condition = condition and ((Waits.createdAt less cursor) or ((Waits.createdAt eq cursor) and (Waits.id less query.before!!)))
        val rows = Waits.selectAll().where { condition }.orderBy(Waits.createdAt to SortOrder.DESC, Waits.id to SortOrder.DESC).limit(query.limit + 1).toList()
        val names = names(rows.take(query.limit).map { it[Waits.assigneeId] }.toSet(), rows.take(query.limit).map { it[Waits.sessionId] }.toSet())
        val page = rows.take(query.limit).map { wait(it, names) }
        // Permission requests are only ever open, and listed once, ahead of the stored waits.
        val sessions = if (query.sessionId == null && query.before == null && (query.status == null || query.status == WaitStatus.OPEN)) sessionWaits(teamId, query.assignee) else emptyList()
        WaitPage(sessions + page, if (rows.size > query.limit) page.last().id else null)
    }

    override suspend fun answer(actor: Principal, id: String, answer: WaitAnswer): WaitChange = database.query {
        requireCurrent(this, actor)
        val changed = Waits.update({ (Waits.id eq id) and (Waits.teamId eq actor.teamId) and (Waits.status eq WaitStatus.OPEN.wire) }) {
            it[status] = WaitStatus.RESOLVED.wire; it[answerAction] = answer.action; it[answerValues] = JsonObject(answer.values).toString()
            it[answerAuthorId] = answer.authorId; it[resolvedAt] = answer.answeredAt
        } > 0
        val wait = read(actor.teamId, id) ?: throw TeamFailure("not_found", 404, "Wait not found.")
        if (changed) {
            audit(actor.teamId, actor.member.id, "wait.answered", id, null, actor.tokenId, related = wait.assigneeId.takeIf { it != actor.member.id })
            publish(TeamEventType.WAIT_RESOLVED, wait, answer.answeredAt) { put("action", answer.action) }
        }
        WaitChange(wait, changed)
    }

    override suspend fun cancel(actor: Principal, id: String, at: Long): WaitChange = database.query {
        requireCurrent(this, actor)
        val changed = Waits.update({ (Waits.id eq id) and (Waits.teamId eq actor.teamId) and (Waits.status eq WaitStatus.OPEN.wire) }) {
            it[status] = WaitStatus.CANCELLED.wire; it[resolvedAt] = at
        } > 0
        val wait = read(actor.teamId, id) ?: throw TeamFailure("not_found", 404, "Wait not found.")
        if (changed) {
            audit(actor.teamId, actor.member.id, "wait.cancelled", id, null, actor.tokenId, related = wait.assigneeId.takeIf { it != actor.member.id })
            publish(TeamEventType.WAIT_CANCELLED, wait, at)
        }
        WaitChange(wait, changed)
    }

    /** The feed event of a change, last in the transaction (see [publishTeamEvent]); it belongs to the assignee and carries the session's kind for the feed's `kind` filter. */
    private fun JdbcTransaction.publish(type: TeamEventType, wait: Wait, at: Long, extra: JsonObjectBuilder.() -> Unit = {}) {
        val sessionKind = CloudSessions.select(CloudSessions.kind).where { CloudSessions.id eq wait.sessionId!! }.single()[CloudSessions.kind]
        publishTeamEvent(wait.teamId, type, wait.id, wait.assigneeId, buildJsonObject {
            put("waitId", wait.id); put("sessionId", wait.sessionId); put("waitKind", wait.kind.wire); put("assigneeId", wait.assigneeId); put("kind", sessionKind); extra()
        }, at)
    }

    /**
     * The members' names and the sessions' names of a page, each in one statement: no join in the paged query, whose
     * index order the planner would otherwise give up for a scan of the sessions.
     */
    private class Names(val members: Map<String, String>, val sessions: Map<String, String?>)
    private fun JdbcTransaction.names(memberIds: Set<String>, sessionIds: Set<String>) = Names(
        if (memberIds.isEmpty()) emptyMap() else Members.select(Members.id, Members.username).where { Members.id inList memberIds }.associate { it[Members.id] to it[Members.username] },
        if (sessionIds.isEmpty()) emptyMap() else CloudSessions.select(CloudSessions.id, CloudSessions.name).where { CloudSessions.id inList sessionIds }.associate { it[CloudSessions.id] to it[CloudSessions.name] },
    )

    private fun JdbcTransaction.read(teamId: String, id: String): Wait? =
        Waits.selectAll().where { (Waits.id eq id) and (Waits.teamId eq teamId) }.singleOrNull()?.let { row -> wait(row, names(setOf(row[Waits.assigneeId]), setOf(row[Waits.sessionId]))) }

    private fun wait(row: ResultRow, names: Names): Wait {
        val answered = row[Waits.answerAction]
        return Wait(
            row[Waits.id], row[Waits.teamId], WaitSource.WAIT, WaitKind.parse(row[Waits.kind]) ?: WaitKind.HUMAN_REQUEST,
            WaitResolver.parse(row[Waits.resolver]) ?: WaitResolver.HUMAN, WaitStatus.parse(row[Waits.status]) ?: WaitStatus.OPEN,
            row[Waits.sessionId], names.sessions[row[Waits.sessionId]], null, row[Waits.assigneeId], names.members[row[Waits.assigneeId]], row[Waits.createdById],
            row[Waits.title], row[Waits.body], WaitJson.refs(row[Waits.refs]), WaitJson.fields(row[Waits.fieldDefs]), WaitJson.actions(row[Waits.actions]),
            answered?.let { WaitAnswer(it, WaitJson.values(row[Waits.answerValues]), row[Waits.answerAuthorId] ?: "", row[Waits.resolvedAt] ?: 0) },
            row[Waits.createdAt], row[Waits.resolvedAt],
        )
    }

    /** The open permission request or question of sessions: [assignee]'s, or the team's when null. At most [MAX_SESSION_WAITS], newest first. */
    private fun JdbcTransaction.sessionWaits(teamId: String, assignee: String?): List<Wait> {
        var condition: Op<Boolean> = (CloudSessions.teamId eq teamId) and CloudSessions.awaitingSince.isNotNull()
        assignee?.let { condition = condition and (CloudSessions.ownerId eq it) }
        // `awaiting_reason` is written with `awaiting_since`, so the index on the latter is enough to select.
        val rows = CloudSessions.selectAll().where { condition }.orderBy(CloudSessions.awaitingSince to SortOrder.DESC).limit(MAX_SESSION_WAITS).toList()
        if (rows.isEmpty()) return emptyList()
        val owners = Members.select(Members.id, Members.username).where { Members.id inList rows.map { it[CloudSessions.ownerId] }.toSet() }.associate { it[Members.id] to it[Members.username] }
        return rows.mapNotNull { sessionWait(it, owners) }
    }

    private fun JdbcTransaction.sessionWait(teamId: String, id: String): Wait? =
        CloudSessions.selectAll().where { (CloudSessions.id eq id) and (CloudSessions.teamId eq teamId) and CloudSessions.awaitingReason.isNotNull() }.singleOrNull()?.let { row ->
            sessionWait(row, Members.select(Members.id, Members.username).where { Members.id eq row[CloudSessions.ownerId] }.associate { it[Members.id] to it[Members.username] })
        }

    private fun sessionWait(row: ResultRow, owners: Map<String, String>): Wait? {
        val reason = row[CloudSessions.awaitingReason] ?: return null
        val question = reason == "question"
        return Wait(
            row[CloudSessions.id], row[CloudSessions.teamId], WaitSource.SESSION, if (question) WaitKind.QUESTION else WaitKind.PERMISSION, WaitResolver.HUMAN,
            WaitStatus.OPEN, row[CloudSessions.id], row[CloudSessions.name], row[CloudSessions.awaitingRequestId], row[CloudSessions.ownerId], owners[row[CloudSessions.ownerId]],
            null, if (question) "The agent asks a question" else "The agent needs a permission", null, emptyList(), emptyList(), emptyList(), null,
            row[CloudSessions.awaitingSince] ?: 0, null,
        )
    }

    private companion object { const val MAX_SESSION_WAITS = 100 }
}

/** The JSON stored in the wait's columns; unknown members and values are dropped on read, never trusted. */
internal object WaitJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun refs(value: List<WaitRef>) = buildJsonArray {
        value.forEach { add(buildJsonObject { put("kind", it.kind); it.id?.let { v -> put("id", v) }; it.url?.let { v -> put("url", v) }; it.title?.let { v -> put("title", v) } }) }
    }.toString()
    fun refs(text: String): List<WaitRef> = array(text).map { WaitRef(it.str("kind") ?: "", it.str("id"), it.str("url"), it.str("title")) }

    fun actions(value: List<WaitAction>) = buildJsonArray {
        value.forEach { add(buildJsonObject {
            put("id", it.id); put("label", it.label); put("requires", buildJsonArray { it.requires.forEach(::add) }); it.style?.let { v -> put("style", v) }
        }) }
    }.toString()
    fun actions(text: String): List<WaitAction> = array(text).map {
        WaitAction(it.str("id") ?: "", it.str("label") ?: "", (it["requires"] as? JsonArray)?.mapNotNull { r -> (r as? JsonPrimitive)?.contentOrNull }.orEmpty(), it.str("style"))
    }

    fun fields(value: List<WaitField>) = buildJsonArray {
        value.forEach { f -> add(buildJsonObject {
            put("id", f.id); put("type", f.type.wire); put("label", f.label); f.help?.let { put("help", it) }; put("required", f.required)
            f.default?.let { put("default", it) }
            put("options", buildJsonArray { f.options.forEach { o -> add(buildJsonObject { put("value", o.value); put("label", o.label); o.description?.let { put("description", it) } }) } })
            put("multiple", f.multiple); put("allowOther", f.allowOther); f.maxLength?.let { put("maxLength", it) }
        }) }
    }.toString()
    fun fields(text: String): List<WaitField> = array(text).map { f ->
        WaitField(
            f.str("id") ?: "", f.str("type")?.let(FieldType::parse) ?: FieldType.TEXT, f.str("label") ?: "", f.str("help"), f["required"].bool(), f["default"],
            (f["options"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { WaitOption(it.str("value") ?: "", it.str("label") ?: "", it.str("description")) }.orEmpty(),
            f["multiple"].bool(), f["allowOther"].bool(), (f["maxLength"] as? JsonPrimitive)?.intOrNull,
        )
    }

    fun values(text: String?): Map<String, JsonElement> = text?.let { (json.parseToJsonElement(it) as? JsonObject)?.toMap() }.orEmpty()

    private fun array(text: String) = (json.parseToJsonElement(text) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonElement?.bool() = (this as? JsonPrimitive)?.booleanOrNull ?: false
}
