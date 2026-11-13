package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.memory.*
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
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

    override suspend fun create(actor: Principal, entry: NewMemory, origin: MemoryOrigin, sessionId: String?): MemoryEntry = database.query {
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
        val id = UUID.randomUUID().toString()
        val now = nowMillis()
        // The session an execution credential belongs to, kept as provenance.
        val session = sessionId ?: actor.tokenId.takeIf { actor.serviceScopes != null }?.let { token ->
            CloudInferenceCredentials.select(CloudInferenceCredentials.sessionId).where { CloudInferenceCredentials.tokenId eq token }.singleOrNull()?.get(CloudInferenceCredentials.sessionId)
        }
        Memories.insert {
            it[Memories.id] = id; it[teamId] = actor.teamId; it[scope] = entry.scope.wire; it[ownerId] = owner
            it[title] = entry.title; it[summary] = entry.summary; it[content] = entry.content; it[refs] = Json.encodeToString(refList, entry.refs)
            it[status] = MemoryStatus.ACTIVE.wire; it[Memories.origin] = origin.wire; it[authorId] = actor.member.id; it[Memories.sessionId] = session
            it[expiresAt] = entry.expiresAt; it[version] = 1; it[createdAt] = now; it[updatedAt] = now; it[readCount] = 0
        }
        MemoryTags.batchInsert(entry.tags, shouldReturnGeneratedValues = false) { tag ->
            this[MemoryTags.memoryId] = id; this[MemoryTags.tag] = tag; this[MemoryTags.teamId] = actor.teamId; this[MemoryTags.scope] = entry.scope.wire; this[MemoryTags.ownerId] = owner
        }
        audit(actor.teamId, actor.member.id, "memory.created", id, credential = actor.tokenId.takeIf { actor.serviceScopes != null }, details = """{"scope":"${entry.scope.wire}","origin":"${origin.wire}"}""")
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
