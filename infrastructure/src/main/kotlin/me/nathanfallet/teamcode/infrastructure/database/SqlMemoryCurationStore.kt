package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.builtins.serializer
import me.nathanfallet.teamcode.domain.memory.*
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * The memory's curation in shared persistence (docs/memory.md). A run touches one scope (the team's entries, or one member's personal ones)
 * per transaction and never mixes scopes: a personal entry is compared with the same member's entries only. It reads titles first and the
 * content of at most [MemoryRules.CURATION_GROUP_SIZE] entries of a group of equal titles, never calls a model, and deletes nothing it
 * decides about: an older entry becomes `superseded`, restorable until its retention ends.
 */
class SqlMemoryCurationStore(private val database: Database) : MemoryCurationStore {
    override suspend fun settings(actor: Principal): CurationSettings = database.query { requireCurrent(this, actor); settingsOf(actor.teamId) }

    override suspend fun setEnabled(actor: Principal, enabled: Boolean): CurationSettings = database.query {
        requireCurrent(this, actor)
        MemorySettings.insertIgnore { it[teamId] = actor.teamId }
        MemorySettings.update({ MemorySettings.teamId eq actor.teamId }) { it[curationEnabled] = enabled }
        audit(actor.teamId, actor.member.id, if (enabled) "memory.curation_enabled" else "memory.curation_disabled", actor.teamId)
        settingsOf(actor.teamId)
    }

    private fun settingsOf(team: String): CurationSettings = MemorySettings.selectAll().where { MemorySettings.teamId eq team }.singleOrNull()
        ?.let { CurationSettings(it[MemorySettings.curationEnabled], it[MemorySettings.lastRunAt], it[MemorySettings.lastRunId], it[MemorySettings.lastSuperseded], it[MemorySettings.lastQueued]) } ?: CurationSettings(false)

    /** The reviews a caller may act on: their own personal ones, and the team's for an administrator. */
    private fun reachable(actor: Principal): Op<Boolean> {
        val own = (MemoryReviews.scope eq MemoryScope.PERSONAL.wire) and (MemoryReviews.ownerId eq actor.member.id)
        return (MemoryReviews.teamId eq actor.teamId) and (if (actor.member.role.isAdministrator) own or (MemoryReviews.scope eq MemoryScope.TEAM.wire) else own)
    }

    override suspend fun reviews(actor: Principal, limit: Int): List<MemoryReview> = database.query {
        requireCurrent(this, actor)
        val rows = MemoryReviews.selectAll().where { reachable(actor) and (MemoryReviews.status eq OPEN) }.orderBy(MemoryReviews.createdAt to SortOrder.ASC).limit(limit).toList()
        views(rows)
    }

    private fun views(rows: List<ResultRow>): List<MemoryReview> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.flatMap { listOf(it[MemoryReviews.olderId], it[MemoryReviews.newerId]) }.distinct()
        val entries = Memories.select(Memories.id, Memories.title, Memories.origin).where { Memories.id inList ids }.associateBy { it[Memories.id] }
        return rows.mapNotNull { row ->
            val older = entries[row[MemoryReviews.olderId]] ?: return@mapNotNull null
            val newer = entries[row[MemoryReviews.newerId]] ?: return@mapNotNull null
            MemoryReview(row[MemoryReviews.id], scopeOf(row[MemoryReviews.scope]), row[MemoryReviews.ownerId].ifEmpty { null }, ReviewKind.entries.single { it.wire == row[MemoryReviews.kind] }, row[MemoryReviews.reason],
                older[Memories.id], older[Memories.title], originOf(older[Memories.origin]), newer[Memories.id], newer[Memories.title], originOf(newer[Memories.origin]), row[MemoryReviews.createdAt])
        }
    }

    override suspend fun resolve(actor: Principal, id: String, apply: Boolean): MemoryReview = database.query {
        requireCurrent(this, actor)
        val row = MemoryReviews.selectAll().where { (MemoryReviews.id eq id) and reachable(actor) }.forUpdate().singleOrNull() ?: throw TeamFailure("not_found", 404, "Review not found.")
        if (row[MemoryReviews.status] != OPEN) throw TeamFailure("memory_review_closed", 409, "This review was already answered.")
        val view = views(listOf(row)).singleOrNull()
        val now = nowMillis()
        if (apply) {
            val older = Memories.selectAll().where { (Memories.id eq row[MemoryReviews.olderId]) and (Memories.status eq MemoryStatus.ACTIVE.wire) }.forUpdate().singleOrNull()
            val newerActive = Memories.select(Memories.id).where { (Memories.id eq row[MemoryReviews.newerId]) and (Memories.status eq MemoryStatus.ACTIVE.wire) }.any()
            if (older == null || !newerActive) throw TeamFailure("memory_review_stale", 409, "One of the two entries is no longer active. Dismiss this review.")
            supersede(older, row[MemoryReviews.newerId], now, "review:${actor.member.id}", "reviewed")
        }
        MemoryReviews.update({ MemoryReviews.id eq id }) { it[status] = if (apply) APPLIED else DISMISSED; it[resolvedAt] = now }
        audit(actor.teamId, actor.member.id, if (apply) "memory.review_applied" else "memory.review_dismissed", id)
        view ?: throw TeamFailure("not_found", 404, "Review not found.")
    }

    override suspend fun superseded(actor: Principal, limit: Int): List<SupersededMemory> = database.query {
        requireCurrent(this, actor)
        val administrator = actor.member.role.isAdministrator
        fun read(scope: MemoryScope, owner: String, extra: Op<Boolean>) = Memories.select(Memories.id, Memories.scope, Memories.ownerId, Memories.title, Memories.summary, Memories.authorId, Memories.supersededById, Memories.updatedAt)
            .where { (Memories.teamId eq actor.teamId) and (Memories.scope eq scope.wire) and (Memories.ownerId eq owner) and (Memories.status eq MemoryStatus.SUPERSEDED.wire) and extra }
            .orderBy(Memories.updatedAt to SortOrder.DESC).limit(limit).toList()
        // One range of memory_visible per scope (team, scope, owner, status), merged here, so no row of anyone else is walked.
        val rows = (read(MemoryScope.PERSONAL, actor.member.id, Op.TRUE) + read(MemoryScope.TEAM, "", if (administrator) Op.TRUE else Memories.authorId eq actor.member.id))
            .sortedByDescending { it[Memories.updatedAt] }.take(limit)
        rows.map { SupersededMemory(it[Memories.id], scopeOf(it[Memories.scope]), it[Memories.ownerId].ifEmpty { null }, it[Memories.title], it[Memories.summary], it[Memories.authorId], it[Memories.supersededById], it[Memories.updatedAt]) }
    }

    override suspend fun restore(actor: Principal, id: String): MemoryEntry = database.query {
        requireCurrent(this, actor)
        val row = Memories.selectAll().where { (Memories.id eq id) and (Memories.teamId eq actor.teamId) and (Memories.status eq MemoryStatus.SUPERSEDED.wire) }.forUpdate().singleOrNull()
            ?.takeIf { it[Memories.scope] == MemoryScope.TEAM.wire || it[Memories.ownerId] == actor.member.id } ?: throw TeamFailure("not_found", 404, "Superseded entry not found.")
        if (row[Memories.scope] == MemoryScope.TEAM.wire && !actor.member.role.isAdministrator && row[Memories.authorId] != actor.member.id)
            throw TeamFailure("forbidden", 403, "Only the author or an administrator restores a team entry.")
        val now = nowMillis()
        val replacement = row[Memories.supersededById]?.let { newer ->
            Memories.selectAll().where { (Memories.id eq newer) and (Memories.status eq MemoryStatus.ACTIVE.wire) }.forUpdate().singleOrNull()
        }
        if (replacement == null) {
            // Nothing leaves to make room: the restored entry counts against the limit like any entry.
            val held = Memories.select(Memories.id).where { (Memories.teamId eq actor.teamId) and (Memories.scope eq row[Memories.scope]) and (Memories.ownerId eq row[Memories.ownerId]) and (Memories.status inList listOf(MemoryStatus.ACTIVE.wire, MemoryStatus.PROPOSED.wire)) }.count()
            val limit = if (row[Memories.scope] == MemoryScope.TEAM.wire) MemoryRules.TEAM_LIMIT else MemoryRules.PERSONAL_LIMIT
            if (held >= limit) throw TeamFailure("memory_limit", 409, "This memory holds $limit entries. Forget an old one first.")
        }
        Memories.update({ Memories.id eq id }) { it[status] = MemoryStatus.ACTIVE.wire; it[supersededById] = null; it[updatedAt] = now; it[version] = row[Memories.version] + 1 }
        replacement?.let { supersede(it, id, now, "restore:${actor.member.id}", "restored") }
        audit(actor.teamId, actor.member.id, "memory.restored", id, details = replacement?.let { "{\"replaced\":\"${it[Memories.id]}\"}" })
        val tags = MemoryTags.select(MemoryTags.tag).where { MemoryTags.memoryId eq id }.orderBy(MemoryTags.tag to SortOrder.ASC).map { it[MemoryTags.tag] }
        entry(Memories.selectAll().where { Memories.id eq id }.single(), tags)
    }

    override suspend fun curate(runId: String, maxScopes: Int): CurationReport {
        val teams = database.query { MemorySettings.select(MemorySettings.teamId).where { MemorySettings.curationEnabled eq true }.limit(TEAMS_PER_RUN).map { it[MemorySettings.teamId] } }
        var scopes = 0; var superseded = 0; var queued = 0; var expired = 0
        for (team in teams) {
            if (scopes >= maxScopes) break
            var teamSuperseded = 0; var teamQueued = 0
            val due = database.query { dueScopes(team, maxScopes - scopes, nowMillis()) }
            for ((scope, owner) in due) {
                val result = database.query { curateScope(team, scope, owner, runId) }
                scopes++; teamSuperseded += result.superseded; teamQueued += result.queued; expired += result.expired
            }
            superseded += teamSuperseded; queued += teamQueued
            if (due.isNotEmpty()) database.query {
                MemorySettings.update({ MemorySettings.teamId eq team }) { it[lastRunAt] = nowMillis(); it[lastRunId] = runId; it[lastSuperseded] = teamSuperseded; it[lastQueued] = teamQueued }
            }
        }
        // Retention applies to every team, whether curation is still on or not.
        database.query {
            val now = nowMillis()
            Memories.deleteWhere { (status eq MemoryStatus.SUPERSEDED.wire) and (updatedAt less now - MemoryRules.SUPERSEDED_DAYS * DAY) }
            MemoryReviews.deleteWhere { (MemoryReviews.status neq OPEN) and (resolvedAt less now - MemoryRules.REJECTED_DAYS * DAY) }
        }
        return CurationReport(scopes, superseded, queued, expired)
    }

    /** The scopes of a team that changed since their last pass, or were not curated for a week; the least recently curated first. */
    private fun JdbcTransaction.dueScopes(team: String, room: Int, now: Long): List<Pair<String, String>> {
        val states = MemoryCurationState.selectAll().where { MemoryCurationState.teamId eq team }.associate { (it[MemoryCurationState.scope] to it[MemoryCurationState.ownerId]) to it[MemoryCurationState.curatedAt] }
        val latest = Memories.updatedAt.max()
        return Memories.select(Memories.scope, Memories.ownerId, latest).where { (Memories.teamId eq team) and (Memories.status eq MemoryStatus.ACTIVE.wire) }.groupBy(Memories.scope, Memories.ownerId)
            .map { Triple(it[Memories.scope], it[Memories.ownerId], it[latest]!!) }
            .filter { (scope, owner, changed) -> states[scope to owner].let { it == null || changed > it || it < now - MemoryRules.RECURATE_DAYS * DAY } }
            .sortedBy { (scope, owner, _) -> states[scope to owner] ?: 0 }.take(room).map { it.first to it.second }
    }

    private class Result(val superseded: Int, val queued: Int, val expired: Int)

    private fun JdbcTransaction.curateScope(team: String, scope: String, owner: String, runId: String): Result {
        val now = nowMillis()
        val rows = Memories.select(Memories.id, Memories.title, Memories.origin, Memories.expiresAt, Memories.updatedAt)
            .where { (Memories.teamId eq team) and (Memories.scope eq scope) and (Memories.ownerId eq owner) and (Memories.status eq MemoryStatus.ACTIVE.wire) }
            .orderBy(Memories.updatedAt to SortOrder.DESC, Memories.id to SortOrder.DESC).limit(MemoryRules.TEAM_LIMIT.toInt() + 1).toList()
        // Past their expiry for a week: deleted, with the audit trail. An entry expired more recently is only hidden, as reads already do.
        val gone = rows.filter { (it[Memories.expiresAt] ?: Long.MAX_VALUE) < now - MemoryRules.EXPIRED_DAYS * DAY }
        gone.forEach { Memories.deleteWhere { id eq it[Memories.id] }; audit(team, "system", "memory.expired", it[Memories.id], details = "{\"run\":\"$runId\"}") }
        val live = rows.filter { row -> (row[Memories.expiresAt] ?: Long.MAX_VALUE) > now }
        val groups = live.groupBy { normalize(it[Memories.title]) }.values.filter { it.size >= 2 }.take(MemoryRules.CURATION_GROUPS)
        var superseded = 0; var queued = 0
        for (group in groups) {
            val members = group.take(MemoryRules.CURATION_GROUP_SIZE)
            val ids = members.map { it[Memories.id] }
            val contents = Memories.select(Memories.id, Memories.content).where { Memories.id inList ids }.associate { it[Memories.id] to normalize(it[Memories.content]) }
            val external = MemoryTags.select(MemoryTags.memoryId).where { (MemoryTags.memoryId inList ids) and (MemoryTags.tag eq "external") }.map { it[MemoryTags.memoryId] }.toSet()
            val newer = members.first()
            for (older in members.drop(1)) {
                val same = contents[older[Memories.id]] == contents[newer[Memories.id]]
                // The older entry is a person's and the newer an agent's, or either comes from outside: a person decides.
                val held = (older[Memories.origin] == MemoryOrigin.HUMAN.wire && newer[Memories.origin] == MemoryOrigin.AGENT.wire) || older[Memories.id] in external || newer[Memories.id] in external
                if (same && !held) {
                    val full = Memories.selectAll().where { (Memories.id eq older[Memories.id]) and (Memories.status eq MemoryStatus.ACTIVE.wire) }.forUpdate().singleOrNull() ?: continue
                    supersede(full, newer[Memories.id], now, runId, "duplicate"); superseded++
                } else {
                    val reason = when { !same -> "Same title, different text: is the newer one an update?"; older[Memories.id] in external || newer[Memories.id] in external -> "Same text; one comes from outside (tagged external)."; else -> "Same text; a person wrote the older one and an agent the newer one." }
                    val inserted = MemoryReviews.insertIgnore {
                        it[MemoryReviews.id] = UUID.randomUUID().toString(); it[teamId] = team; it[MemoryReviews.scope] = scope; it[ownerId] = owner
                        it[olderId] = older[Memories.id]; it[newerId] = newer[Memories.id]; it[kind] = if (same) ReviewKind.DUPLICATE.wire else ReviewKind.SAME_TITLE.wire
                        it[MemoryReviews.reason] = reason; it[status] = OPEN; it[createdAt] = now
                    }.insertedCount
                    queued += inserted
                }
            }
        }
        MemoryCurationState.upsert { it[teamId] = team; it[MemoryCurationState.scope] = scope; it[ownerId] = owner; it[curatedAt] = now }
        return Result(superseded, queued, gone.size)
    }

    /** The older entry becomes `superseded` by the newer one, once (a concurrent change loses on the status), and nothing is deleted. */
    private fun JdbcTransaction.supersede(older: ResultRow, newerId: String, now: Long, run: String, reason: String) {
        val updated = Memories.update({ (Memories.id eq older[Memories.id]) and (Memories.status eq MemoryStatus.ACTIVE.wire) and (Memories.version eq older[Memories.version]) }) {
            it[status] = MemoryStatus.SUPERSEDED.wire; it[supersededById] = newerId; it[updatedAt] = now; it[version] = older[Memories.version] + 1
        }
        if (updated == 0) return
        Memories.update({ (Memories.id eq newerId) and (Memories.supersedesId.isNull()) }) { it[supersedesId] = older[Memories.id] }
        audit(older[Memories.teamId], "system", "memory.superseded", older[Memories.id], details = "{\"by\":\"$newerId\",\"run\":\"$run\",\"reason\":\"$reason\"}")
    }

    /** Case, accents and runs of whitespace do not make two texts different. */
    private fun normalize(text: String) = text.lowercase().trim().replace(Regex("\\s+"), " ")

    private fun scopeOf(wire: String) = MemoryScope.entries.single { it.wire == wire }
    private fun originOf(wire: String) = MemoryOrigin.entries.single { it.wire == wire }

    private fun entry(row: ResultRow, tags: List<String>) = MemoryEntry(
        row[Memories.id], scopeOf(row[Memories.scope]), row[Memories.ownerId].ifEmpty { null }, row[Memories.title], row[Memories.summary], row[Memories.content], tags,
        kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), row[Memories.refs]),
        MemoryStatus.entries.single { it.wire == row[Memories.status] }, originOf(row[Memories.origin]), row[Memories.authorId], row[Memories.sessionId], row[Memories.supersedesId],
        row[Memories.expiresAt], row[Memories.version], row[Memories.createdAt], row[Memories.updatedAt], row[Memories.lastReadAt], row[Memories.readCount],
    )

    private companion object { const val OPEN = "open"; const val APPLIED = "applied"; const val DISMISSED = "dismissed"; const val DAY = 86_400_000L; const val TEAMS_PER_RUN = 200 }
}
