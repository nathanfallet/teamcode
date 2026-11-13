package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.memory.*
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.WaitStatus
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The memory in shared persistence (docs/memory.md). Visibility is in every query: a team entry for every member of the team,
 * a personal entry for its owner only, so no route, administrator or key of another member can reach it. A list is at most
 * three reads in one transaction (one per scope, then the tags of the rows returned), never one per row, and never reads
 * the content, which only [read] loads.
 */
class SqlMemoryStore(private val database: Database) : MemoryStore {
    override suspend fun index(actor: Principal, scope: MemoryScope?, tags: List<String>, after: MemoryCursor?, limit: Int): MemoryPage = database.query {
        requireCurrent(this, actor)
        val now = nowMillis()
        val scopes = scope?.let { listOf(it) } ?: MemoryScope.entries
        val rows = scopes.flatMap { s ->
            Memories.select(Memories.id, Memories.scope, Memories.title, Memories.summary, Memories.updatedAt)
                .where { visible(actor, s, now) and cursor(after) and tagged(actor, s, tags) }
                .orderBy(Memories.updatedAt to SortOrder.DESC, Memories.id to SortOrder.DESC).limit(limit + 1).toList()
        }.sortedWith(compareByDescending<ResultRow> { it[Memories.updatedAt] }.thenByDescending { it[Memories.id] })
        val page = rows.take(limit)
        val entries = entries(page)
        MemoryPage(entries, if (rows.size > limit) page.last().let { MemoryCursor(it[Memories.updatedAt], it[Memories.id]) } else null)
    }

    override suspend fun search(actor: Principal, terms: List<String>, tags: List<String>, limit: Int): List<MemoryIndexEntry> = database.query {
        requireCurrent(this, actor)
        val now = nowMillis()
        val matching = terms.map { term ->
            val pattern = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            (Memories.title like pattern) or (Memories.summary like pattern) or
                (Memories.id inSubQuery MemoryTags.select(MemoryTags.memoryId).where { (MemoryTags.teamId eq actor.teamId) and (MemoryTags.tag like pattern) })
        }.reduce { a, b -> a and b }
        val rows = MemoryScope.entries.flatMap { s ->
            Memories.select(Memories.id, Memories.scope, Memories.title, Memories.summary, Memories.updatedAt)
                .where { visible(actor, s, now) and matching and tagged(actor, s, tags) }
                .orderBy(Memories.updatedAt to SortOrder.DESC, Memories.id to SortOrder.DESC).limit(limit).toList()
        }.sortedWith(compareByDescending<ResultRow> { it[Memories.updatedAt] }.thenByDescending { it[Memories.id] }).take(limit)
        entries(rows)
    }

    override suspend fun read(actor: Principal, id: String): MemoryEntry = database.query {
        requireCurrent(this, actor)
        val now = nowMillis()
        val row = Memories.selectAll().where { (Memories.id eq id) and (Memories.teamId eq actor.teamId) }.singleOrNull()
            ?.takeIf { readable(actor, it, now) } ?: throw notFound()
        // An atomic increment, so two readers of a team entry neither wait for each other nor lose a count.
        Memories.update({ Memories.id eq id }) { it[lastReadAt] = now; it[readCount] = readCount + 1 }
        entry(row, tagsOf(listOf(id))[id].orEmpty(), lastReadAt = now, readCount = row[Memories.readCount] + 1)
    }

    override suspend fun create(actor: Principal, entry: NewMemory, origin: MemoryOrigin, sessionId: String?, status: MemoryStatus): MemoryEntry = database.query {
        requireCurrent(this, actor)
        val personal = entry.scope == MemoryScope.PERSONAL
        val owner = if (personal) actor.member.id else ""
        // Counted on the covering index; a team count is team-wide, so team writes take the team lock to keep the limit exact.
        if (!personal) lockTeam(actor.teamId)
        val held = Memories.select(Memories.id).where {
            (Memories.teamId eq actor.teamId) and (Memories.scope eq entry.scope.wire) and (Memories.ownerId eq owner) and
                (Memories.status inList listOf(MemoryStatus.ACTIVE.wire, MemoryStatus.PROPOSED.wire))
        }.count()
        if (held >= (if (personal) MemoryRules.PERSONAL_LIMIT else MemoryRules.TEAM_LIMIT))
            throw TeamFailure("memory_limit", 409, "This memory holds ${if (personal) MemoryRules.PERSONAL_LIMIT else MemoryRules.TEAM_LIMIT} entries. Forget an old one first.")
        if (status == MemoryStatus.PROPOSED) {
            // Per owner: the proposals of all the agents of one member, so one runaway agent cannot fill the team's room.
            val waiting = Memories.select(Memories.id).where { (Memories.teamId eq actor.teamId) and (Memories.scope eq MemoryScope.TEAM.wire) and (Memories.ownerId eq "") and (Memories.status eq MemoryStatus.PROPOSED.wire) and (Memories.authorId eq actor.member.id) }.count()
            if (waiting >= MemoryRules.PROPOSALS_LIMIT) throw TeamFailure("memory_proposals_limit", 409, "${MemoryRules.PROPOSALS_LIMIT} proposals of yours are waiting for an answer. Answer or wait for them first.")
        }
        val id = UUID.randomUUID().toString()
        val now = nowMillis()
        // The session an execution credential belongs to, kept as provenance.
        val session = sessionId ?: actor.tokenId.takeIf { actor.serviceScopes != null }?.let { token ->
            CloudInferenceCredentials.select(CloudInferenceCredentials.sessionId).where { CloudInferenceCredentials.tokenId eq token }.singleOrNull()?.get(CloudInferenceCredentials.sessionId)
        }
        Memories.insert {
            it[Memories.id] = id; it[teamId] = actor.teamId; it[scope] = entry.scope.wire; it[ownerId] = owner
            it[title] = entry.title; it[summary] = entry.summary; it[content] = entry.content; it[refs] = Json.encodeToString(refList, entry.refs)
            it[Memories.status] = status.wire; it[Memories.origin] = origin.wire; it[authorId] = actor.member.id; it[Memories.sessionId] = session
            it[expiresAt] = entry.expiresAt; it[version] = 1; it[createdAt] = now; it[updatedAt] = now; it[readCount] = 0
        }
        MemoryTags.batchInsert(entry.tags, shouldReturnGeneratedValues = false) { tag ->
            this[MemoryTags.memoryId] = id; this[MemoryTags.tag] = tag; this[MemoryTags.teamId] = actor.teamId; this[MemoryTags.scope] = entry.scope.wire; this[MemoryTags.ownerId] = owner
        }
        audit(actor.teamId, actor.member.id, if (status == MemoryStatus.PROPOSED) "memory.proposed" else "memory.created", id, credential = actor.tokenId.takeIf { actor.serviceScopes != null }, details = """{"scope":"${entry.scope.wire}","origin":"${origin.wire}"}""")
        entry(Memories.selectAll().where { Memories.id eq id }.single(), entry.tags)
    }

    override suspend fun update(actor: Principal, id: String, update: MemoryUpdate): MemoryEntry = database.query {
        requireCurrent(this, actor)
        val now = nowMillis()
        val row = Memories.selectAll().where { (Memories.id eq id) and (Memories.teamId eq actor.teamId) }.forUpdate().singleOrNull()
            ?.takeIf { readable(actor, it, now) } ?: throw notFound()
        requireEditable(actor, row)
        if (row[Memories.version] != update.expectedVersion) throw TeamFailure("memory_conflict", 409, "This entry changed since you read it. Read it again and retry.")
        Memories.update({ Memories.id eq id }) {
            it[title] = update.title; it[summary] = update.summary; it[content] = update.content; it[refs] = Json.encodeToString(refList, update.refs)
            it[expiresAt] = update.expiresAt; it[version] = row[Memories.version] + 1; it[updatedAt] = now
        }
        MemoryTags.deleteWhere { memoryId eq id }
        MemoryTags.batchInsert(update.tags, shouldReturnGeneratedValues = false) { tag ->
            this[MemoryTags.memoryId] = id; this[MemoryTags.tag] = tag; this[MemoryTags.teamId] = actor.teamId; this[MemoryTags.scope] = row[Memories.scope]; this[MemoryTags.ownerId] = row[Memories.ownerId]
        }
        audit(actor.teamId, actor.member.id, "memory.updated", id, credential = actor.tokenId.takeIf { actor.serviceScopes != null })
        entry(Memories.selectAll().where { Memories.id eq id }.single(), update.tags)
    }

    override suspend fun delete(actor: Principal, id: String) = database.query {
        requireCurrent(this, actor)
        val now = nowMillis()
        val row = Memories.selectAll().where { (Memories.id eq id) and (Memories.teamId eq actor.teamId) }.forUpdate().singleOrNull()
            ?.takeIf { readable(actor, it, now) } ?: throw notFound()
        requireEditable(actor, row)
        Memories.deleteWhere { Memories.id eq id }
        audit(actor.teamId, actor.member.id, "memory.forgotten", id, credential = actor.tokenId.takeIf { actor.serviceScopes != null })
    }

    override suspend fun attachProposalWait(id: String, waitId: String) = database.query {
        Memories.update({ (Memories.id eq id) and (Memories.status eq MemoryStatus.PROPOSED.wire) }) { it[proposalWaitId] = waitId }
        Unit
    }

    override suspend fun proposals(actor: Principal, limit: Int): List<MemoryEntry> = database.query {
        requireCurrent(this, actor)
        val administrator = actor.member.role.isAdministrator
        val rows = Memories.selectAll().where {
            (Memories.teamId eq actor.teamId) and (Memories.scope eq MemoryScope.TEAM.wire) and (Memories.ownerId eq "") and (Memories.status eq MemoryStatus.PROPOSED.wire) and
                (if (administrator) Op.TRUE else Memories.authorId eq actor.member.id)
        }.orderBy(Memories.updatedAt to SortOrder.ASC).limit(limit).toList()
        val tags = tagsOf(rows.map { it[Memories.id] })
        rows.map { entry(it, tags[it[Memories.id]].orEmpty()) }
    }

    override suspend fun decide(actor: Principal, id: String, accept: Boolean): MemoryEntry = database.query {
        requireCurrent(this, actor)
        val row = Memories.selectAll().where { (Memories.id eq id) and (Memories.teamId eq actor.teamId) }.forUpdate().singleOrNull()
            ?.takeIf { it[Memories.status] == MemoryStatus.PROPOSED.wire && it[Memories.scope] == MemoryScope.TEAM.wire } ?: throw TeamFailure("memory_not_proposed", 409, "This entry is not a proposal waiting for an answer.")
        // Accepting changes what the team reads: an administrator's call. The author may withdraw their own.
        if (!actor.member.role.isAdministrator && (accept || row[Memories.authorId] != actor.member.id)) throw TeamFailure("forbidden", 403, "Only an administrator accepts a proposal.")
        val now = nowMillis()
        closeProposal(row, accept, now, actor.member.id, null)
        row[Memories.proposalWaitId]?.let { cancelWait(it, now) }
        entry(Memories.selectAll().where { Memories.id eq id }.single(), tagsOf(listOf(id))[id].orEmpty())
    }

    override suspend fun settle(limit: Int): Int = database.query {
        val now = nowMillis()
        val rows = Memories.selectAll().where { Memories.status eq MemoryStatus.PROPOSED.wire }.orderBy(Memories.updatedAt to SortOrder.ASC).limit(limit).toList()
        val waits = rows.mapNotNull { it[Memories.proposalWaitId] }.let { ids ->
            if (ids.isEmpty()) emptyMap() else Waits.selectAll().where { Waits.id inList ids }.associateBy { it[Waits.id] }
        }
        val expired = now - MemoryRules.PROPOSAL_DAYS * DAY
        var changed = 0
        for (row in rows) {
            val wait = row[Memories.proposalWaitId]?.let { waits[it] }
            val open = wait?.get(Waits.status) == WaitStatus.OPEN.wire
            val accepted = wait?.get(Waits.status) == WaitStatus.RESOLVED.wire && wait[Waits.answerAction] == "accept"
            when {
                open && row[Memories.updatedAt] >= expired -> continue
                // A proposal whose wait is still being opened (attached just after the row) is left alone for a minute.
                wait == null && row[Memories.proposalWaitId] == null && row[Memories.updatedAt] >= now - 60_000 -> continue
                else -> {
                    val edits = if (accepted) edits(wait?.get(Waits.answerValues)) else emptyMap()
                    if (closeProposal(row, accepted, now, wait?.get(Waits.answerAuthorId), edits)) changed++
                    if (open) cancelWait(row[Memories.proposalWaitId]!!, now)
                }
            }
        }
        // Rejected proposals are kept for the audit, then deleted (their tags go with them).
        Memories.deleteWhere { (status eq MemoryStatus.REJECTED.wire) and (updatedAt less now - MemoryRules.REJECTED_DAYS * DAY) }
        changed
    }

    /** The owner's edited text fields of an accepted proposal; a field that is not a string is ignored. */
    private fun edits(raw: String?): Map<String, String> = try {
        (Json.parseToJsonElement(raw ?: "{}") as? kotlinx.serialization.json.JsonObject).orEmpty().mapNotNull { (key, value) ->
            (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString && key in setOf("title", "summary", "content") }?.let { key to it.content }
        }.toMap()
    } catch (_: Exception) { emptyMap() }

    /** Accepts or rejects a proposal still waiting, once (a concurrent replica loses on the status). Returns whether this call changed it. */
    private fun JdbcTransaction.closeProposal(row: ResultRow, accept: Boolean, now: Long, by: String?, edits: Map<String, String>?): Boolean {
        val title = edits?.get("title") ?: row[Memories.title]
        val summary = edits?.get("summary") ?: row[Memories.summary]
        val content = edits?.get("content") ?: row[Memories.content]
        // An edit that breaks a rule (too long, a credential) is not applied: the owner accepted the proposal as it was sent.
        val edited = accept && !edits.isNullOrEmpty() && try {
            val tags = tagsOf(listOf(row[Memories.id]))[row[Memories.id]].orEmpty()
            MemoryRules.validate(title, summary, content, tags, Json.decodeFromString(refList, row[Memories.refs]), null, now); true
        } catch (_: TeamFailure) { false }
        val updated = Memories.update({ (Memories.id eq row[Memories.id]) and (Memories.status eq MemoryStatus.PROPOSED.wire) }) {
            it[Memories.status] = if (accept) MemoryStatus.ACTIVE.wire else MemoryStatus.REJECTED.wire
            it[updatedAt] = now; it[version] = row[Memories.version] + 1
            if (edited) { it[Memories.title] = title; it[Memories.summary] = summary; it[Memories.content] = content }
        }
        if (updated == 0) return false
        audit(row[Memories.teamId], by ?: "system", if (accept) "memory.accepted" else "memory.rejected", row[Memories.id], details = if (accept) "{\"edited\":$edited}" else null)
        return true
    }

    private fun JdbcTransaction.cancelWait(waitId: String, now: Long) {
        Waits.update({ (Waits.id eq waitId) and (Waits.status eq WaitStatus.OPEN.wire) }) { it[status] = WaitStatus.CANCELLED.wire; it[resolvedAt] = now }
    }

    private companion object { const val DAY = 86_400_000L }

    private val refList = ListSerializer(String.serializer())
    private fun notFound() = TeamFailure("not_found", 404, "Memory entry not found.")

    /** Active, not expired, and in the caller's reach: the team's, or the caller's own personal entries. */
    private fun visible(actor: Principal, scope: MemoryScope, now: Long): Op<Boolean> =
        (Memories.teamId eq actor.teamId) and (Memories.scope eq scope.wire) and
            (Memories.ownerId eq (if (scope == MemoryScope.PERSONAL) actor.member.id else "")) and
            (Memories.status eq MemoryStatus.ACTIVE.wire) and (Memories.expiresAt.isNull() or (Memories.expiresAt greater now))

    private fun readable(actor: Principal, row: ResultRow, now: Long) =
        row[Memories.status] == MemoryStatus.ACTIVE.wire && (row[Memories.expiresAt]?.let { it > now } ?: true) &&
            (row[Memories.scope] == MemoryScope.TEAM.wire || row[Memories.ownerId] == actor.member.id)

    /** A personal entry is its owner's. A team entry is its author's or an interactive administrator's; agents never edit the team's. */
    private fun requireEditable(actor: Principal, row: ResultRow) {
        if (row[Memories.scope] == MemoryScope.PERSONAL.wire) return
        val human = actor.serviceScopes == null
        if (!human || (row[Memories.authorId] != actor.member.id && !actor.member.role.isAdministrator))
            throw TeamFailure("memory_team_forbidden", 403, "Only the author of a team entry or an administrator can change it.")
    }

    private fun cursor(after: MemoryCursor?): Op<Boolean> = if (after == null) Op.TRUE else
        (Memories.updatedAt less after.updatedAt) or ((Memories.updatedAt eq after.updatedAt) and (Memories.id less after.id))

    private fun tagged(actor: Principal, scope: MemoryScope, tags: List<String>): Op<Boolean> = if (tags.isEmpty()) Op.TRUE else
        Memories.id inSubQuery MemoryTags.select(MemoryTags.memoryId).where {
            (MemoryTags.teamId eq actor.teamId) and (MemoryTags.scope eq scope.wire) and
                (MemoryTags.ownerId eq (if (scope == MemoryScope.PERSONAL) actor.member.id else "")) and (MemoryTags.tag inList tags)
        }

    private fun tagsOf(ids: List<String>): Map<String, List<String>> = if (ids.isEmpty()) emptyMap() else
        MemoryTags.select(MemoryTags.memoryId, MemoryTags.tag).where { MemoryTags.memoryId inList ids }
            .orderBy(MemoryTags.tag to SortOrder.ASC).groupBy({ it[MemoryTags.memoryId] }, { it[MemoryTags.tag] })

    private fun entries(rows: List<ResultRow>): List<MemoryIndexEntry> {
        val tags = tagsOf(rows.map { it[Memories.id] })
        return rows.map { MemoryIndexEntry(it[Memories.id], MemoryScope.entries.single { s -> s.wire == it[Memories.scope] }, it[Memories.title], it[Memories.summary], tags[it[Memories.id]].orEmpty(), it[Memories.updatedAt]) }
    }

    private fun entry(row: ResultRow, tags: List<String>, lastReadAt: Long? = row[Memories.lastReadAt], readCount: Int = row[Memories.readCount]) = MemoryEntry(
        row[Memories.id], MemoryScope.entries.single { it.wire == row[Memories.scope] }, row[Memories.ownerId].ifEmpty { null }, row[Memories.title], row[Memories.summary],
        row[Memories.content], tags, Json.decodeFromString(refList, row[Memories.refs]), MemoryStatus.entries.single { it.wire == row[Memories.status] },
        MemoryOrigin.entries.single { it.wire == row[Memories.origin] }, row[Memories.authorId], row[Memories.sessionId], row[Memories.supersedesId], row[Memories.expiresAt],
        row[Memories.version], row[Memories.createdAt], row[Memories.updatedAt], lastReadAt, readCount,
    )
}
