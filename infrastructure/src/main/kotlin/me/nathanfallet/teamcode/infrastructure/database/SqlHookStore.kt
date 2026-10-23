package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.domain.hooks.*
import me.nathanfallet.teamcode.domain.skills.SkillDecision
import me.nathanfallet.teamcode.domain.skills.SkillFile
import me.nathanfallet.teamcode.domain.skills.SkillFileMode
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Exposed hook catalog, on the skill catalog's model: administrative writes lock the team row, a version's content
 * insert locks only its hook row, and every write revalidates the actor and appends its metadata-only audit event in
 * the same transaction. Publication never pins; only an interactive administrator does.
 */
class SqlHookStore(private val database: Database) : HookStore {
    override suspend fun catalog(actor: Principal): List<Hook> {
        actor.requireAdmin()
        return database.query {
            requireCurrent(this, actor)
            Hooks.join(HookVersions, JoinType.LEFT, Hooks.pinnedVersionId, HookVersions.id)
                .select(Hooks.columns + HookVersions.version).where { Hooks.teamId eq actor.teamId }
                .orderBy(Hooks.updatedAt to SortOrder.DESC, Hooks.name to SortOrder.ASC).limit(HookStore.HOOK_LIMIT.toInt())
                .map { hook(it, it.getOrNull(HookVersions.version)) }
        }
    }

    override suspend fun versions(actor: Principal, name: String): Pair<Hook, List<HookVersion>> {
        actor.requireAdmin()
        return database.query {
            requireCurrent(this, actor)
            val row = hookRow(actor.teamId, name)
            hook(row) to HookVersions.selectAll().where { HookVersions.hookId eq row[Hooks.id] }
                .orderBy(HookVersions.publishedAt to SortOrder.DESC, HookVersions.version to SortOrder.ASC)
                .limit(HookStore.VERSION_LIMIT.toInt()).map(::version)
        }
    }

    override suspend fun publish(actor: Principal, name: String, version: String, artifact: HookArtifact, allMembers: Boolean): HookPublication {
        actor.requireHookPublisher()
        var createdHere = false
        val hookId = database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            Hooks.select(Hooks.id).where { (Hooks.teamId eq actor.teamId) and (Hooks.name eq name) }.singleOrNull()?.get(Hooks.id)
                ?: run {
                    createdHere = true
                    if (Hooks.select(Hooks.id).where { Hooks.teamId eq actor.teamId }.count() >= HookStore.HOOK_LIMIT)
                        throw TeamFailure("hook_limit", 409, "The catalog is limited to ${HookStore.HOOK_LIMIT} hooks.")
                    val id = UUID.randomUUID().toString()
                    val now = nowMillis()
                    Hooks.insert {
                        it[Hooks.id] = id; it[teamId] = actor.teamId; it[Hooks.name] = name
                        it[pinnedVersionId] = null; it[createdAt] = now; it[updatedAt] = now
                    }
                    audit(actor.teamId, actor.member.id, "hook.created", id, credential = keyId(actor))
                    id
                }
        }
        try {
            return publishVersion(actor, hookId, version, artifact, allMembers)
        } catch (failure: Throwable) {
            // A first publication that fails must not leave an empty catalog entry that counts toward the limit.
            if (createdHere) withContext(NonCancellable) { discardEmpty(actor, hookId) }
            throw failure
        }
    }

    private suspend fun discardEmpty(actor: Principal, hookId: String) = try {
        database.query {
            lockTeam(actor.teamId)
            if (HookVersions.select(HookVersions.id).where { HookVersions.hookId eq hookId }.limit(1).empty()) {
                val removed = Hooks.deleteWhere { (Hooks.id eq hookId) and (Hooks.teamId eq actor.teamId) }
                if (removed > 0) audit(actor.teamId, actor.member.id, "hook.create_discarded", hookId)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The original failure is the one to report; an empty entry left by a second failure is still harmless.
    }

    private suspend fun publishVersion(actor: Principal, hookId: String, version: String, artifact: HookArtifact, allMembers: Boolean): HookPublication = database.query {
        requireCurrent(this, actor)
        val row = Hooks.selectAll().where { Hooks.id eq hookId }.forUpdate().singleOrNull()
            ?: throw TeamFailure("hook_busy", 409, "This hook changed while it was being published. Retry the publication.")
        // Read under the hook row lock, which pin and unpin also take: a key widens the audience only of a hook that no
        // administrator has pinned yet, so the interactive pin approves the code and who runs it together.
        val widen = allMembers && !row[Hooks.allMembers]
        if (widen && actor.serviceScopes != null && row[Hooks.pinnedVersionId] != null)
            throw TeamFailure("hook_audience_requires_administrator", 403,
                "This hook is pinned: only an administrator can deliver it to all members. Publish without allMembers, or ask an administrator.")
        val existing = HookVersions.selectAll().where { (HookVersions.hookId eq hookId) and (HookVersions.version eq version) }.singleOrNull()
        if (existing != null) {
            if (existing[HookVersions.digest] != artifact.digest)
                throw TeamFailure("hook_version_conflict", 409, "Version $version is already published with different content. Publish a new version.")
            if (!widen) return@query HookPublication(hook(row), version(existing), created = false)
            audienceLocked(actor, row, true, nowMillis())
            return@query HookPublication(hook(Hooks.selectAll().where { Hooks.id eq hookId }.single()), version(existing), created = false)
        }
        if (HookVersions.select(HookVersions.id).where { HookVersions.hookId eq hookId }.count() >= HookStore.VERSION_LIMIT)
            throw TeamFailure("hook_version_limit", 409, "A hook keeps at most ${HookStore.VERSION_LIMIT} versions.")
        val now = nowMillis()
        val id = UUID.randomUUID().toString()
        val definition = artifact.definition
        HookVersions.insert {
            it[HookVersions.id] = id; it[HookVersions.hookId] = hookId; it[HookVersions.version] = version
            it[digest] = artifact.digest; it[events] = definition.events.map { e -> e.wire }.sorted().joinToString(",")
            it[matcher] = definition.matcher; it[command] = definition.command; it[timeoutSeconds] = definition.timeoutSeconds
            it[sizeBytes] = artifact.sizeBytes; it[fileCount] = artifact.entries.size
            it[publishedBy] = actor.member.id; it[publishedAt] = now
        }
        HookFiles.batchInsert(artifact.files.zip(artifact.entries)) { (file, entry) ->
            this[HookFiles.versionId] = id; this[HookFiles.path] = entry.path; this[HookFiles.mode] = entry.mode.wire
            this[HookFiles.size] = entry.size; this[HookFiles.sha256] = entry.sha256; this[HookFiles.content] = file.content
        }
        Hooks.update({ Hooks.id eq hookId }) { it[updatedAt] = now }
        audit(actor.teamId, actor.member.id, "hook.version.published", id, credential = keyId(actor))
        if (widen) audienceLocked(actor, row, true, now)
        HookPublication(hook(Hooks.selectAll().where { Hooks.id eq hookId }.single()), version(versionRow(hookId, version)), created = true)
    }

    /** Sets the audience when it differs; the caller holds the hook row lock and read [row] with it. */
    private fun JdbcTransaction.audienceLocked(actor: Principal, row: ResultRow, allMembers: Boolean, now: Long) {
        if (row[Hooks.allMembers] == allMembers) return
        Hooks.update({ Hooks.id eq row[Hooks.id] }) { it[Hooks.allMembers] = allMembers; it[updatedAt] = now }
        audit(actor.teamId, actor.member.id, if (allMembers) "hook.audience.all_members" else "hook.audience.assigned_only", row[Hooks.id], credential = keyId(actor))
    }

    override suspend fun setAudience(actor: Principal, name: String, allMembers: Boolean): Hook = write(actor, name) { row, now ->
        audienceLocked(actor, row, allMembers, now)
    }

    /** The service key behind a publication, for its audit event; null for an interactive login. */
    private fun keyId(actor: Principal) = actor.tokenId.takeIf { actor.serviceScopes != null }

    override suspend fun pin(actor: Principal, name: String, version: String): Hook = write(actor, name) { row, now ->
        val target = versionRow(row[Hooks.id], version)[HookVersions.id]
        if (row[Hooks.pinnedVersionId] != target) {
            Hooks.update({ Hooks.id eq row[Hooks.id] }) { it[pinnedVersionId] = target; it[updatedAt] = now }
            audit(actor.teamId, actor.member.id, "hook.pinned", target)
        }
    }

    override suspend fun unpin(actor: Principal, name: String): Hook = write(actor, name) { row, now ->
        if (row[Hooks.pinnedVersionId] != null) {
            Hooks.update({ Hooks.id eq row[Hooks.id] }) { it[pinnedVersionId] = null; it[updatedAt] = now }
            audit(actor.teamId, actor.member.id, "hook.unpinned", row[Hooks.id])
        }
    }

    override suspend fun assignments(actor: Principal, name: String): List<HookAssignment> {
        actor.requireAdmin()
        return database.query {
            requireCurrent(this, actor)
            val hook = hookRow(actor.teamId, name)[Hooks.id]
            HookAssignments.selectAll().where { (HookAssignments.hookId eq hook) and HookAssignments.decision.isNotNull() }
                .orderBy(HookAssignments.memberId to SortOrder.ASC).limit(1_000).map { assignment(it)!! }
        }
    }

    override suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?): HookAssignment? {
        actor.requireAdmin()
        return database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            val hook = hookRow(actor.teamId, name)[Hooks.id]
            if (Members.select(Members.id).where { (Members.id eq memberId) and (Members.teamId eq actor.teamId) }.forUpdate().singleOrNull() == null)
                throw TeamFailure("not_found", 404, "Member not found.")
            val where = (HookAssignments.hookId eq hook) and (HookAssignments.memberId eq memberId)
            val row = HookAssignments.selectAll().where { where }.singleOrNull()
            val current = row?.let(::assignment)
            if (current?.decision == decision) return@query current
            val now = nowMillis()
            val id = row?.get(HookAssignments.id) ?: UUID.randomUUID().toString()
            if (row == null) HookAssignments.insert {
                it[HookAssignments.id] = id; it[hookId] = hook; it[HookAssignments.memberId] = memberId
                it[HookAssignments.decision] = decision?.wire; it[updatedBy] = actor.member.id; it[updatedAt] = now
            } else HookAssignments.update({ where }) {
                it[HookAssignments.decision] = decision?.wire; it[updatedBy] = actor.member.id; it[updatedAt] = now
            }
            val action = when (decision) { SkillDecision.ALLOW -> "hook.access.allowed"; SkillDecision.DENY -> "hook.access.denied"; null -> "hook.access.cleared" }
            audit(actor.teamId, actor.member.id, action, id)
            HookAssignments.selectAll().where { where }.single().let(::assignment)
        }
    }

    override suspend fun candidates(actor: Principal, memberId: String): List<HookCandidate> = database.query {
        requireCurrent(this, actor)
        if (Members.select(Members.id).where { (Members.id eq memberId) and (Members.teamId eq actor.teamId) }.singleOrNull() == null)
            throw TeamFailure("not_found", 404, "Member not found.")
        Hooks.join(HookAssignments, JoinType.LEFT, Hooks.id, HookAssignments.hookId) { HookAssignments.memberId eq memberId }
            .join(HookVersions, JoinType.LEFT, Hooks.pinnedVersionId, HookVersions.id)
            .selectAll().where { Hooks.teamId eq actor.teamId }
            .orderBy(Hooks.name to SortOrder.ASC).limit(HookStore.HOOK_LIMIT.toInt())
            .map { row ->
                val pinned = row.getOrNull(HookVersions.id)?.let { version(row) }
                HookCandidate(hook(row, pinned?.version), pinned, row.getOrNull(HookAssignments.decision)?.let(::decision))
            }
    }

    override suspend fun files(actor: Principal, versionId: String): List<SkillFile> = database.query {
        requireCurrent(this, actor)
        val owned = (HookVersions innerJoin Hooks).select(HookVersions.id)
            .where { (HookVersions.id eq versionId) and (Hooks.teamId eq actor.teamId) }.singleOrNull()
        if (owned == null) throw TeamFailure("not_found", 404, "Hook version not found.")
        HookFiles.selectAll().where { HookFiles.versionId eq versionId }.orderBy(HookFiles.path to SortOrder.ASC)
            .limit(HookArtifacts.MAX_FILES).map { row ->
                SkillFile(row[HookFiles.path], SkillFileMode.entries.single { it.wire == row[HookFiles.mode] }, row[HookFiles.content])
            }
    }

    /** Team-locked administrator write on one hook, its row read with a lock. */
    private suspend fun write(actor: Principal, name: String, change: JdbcTransaction.(ResultRow, Long) -> Unit): Hook {
        actor.requireAdmin()
        return database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            change(hookRow(actor.teamId, name, lock = true), nowMillis())
            hook(hookRow(actor.teamId, name))
        }
    }

    private fun hookRow(team: String, name: String, lock: Boolean = false) =
        Hooks.selectAll().where { (Hooks.teamId eq team) and (Hooks.name eq name) }.let { if (lock) it.forUpdate() else it }.singleOrNull()
            ?: throw TeamFailure("not_found", 404, "Hook not found.")
    private fun versionRow(hook: String, version: String) =
        HookVersions.selectAll().where { (HookVersions.hookId eq hook) and (HookVersions.version eq version) }.singleOrNull()
            ?: throw TeamFailure("not_found", 404, "Hook version not found.")

    private fun hook(row: ResultRow): Hook = hook(row, row[Hooks.pinnedVersionId]?.let { id ->
        HookVersions.select(HookVersions.version).where { HookVersions.id eq id }.singleOrNull()?.get(HookVersions.version)
    })
    private fun hook(row: ResultRow, pinned: String?) = Hook(row[Hooks.id], row[Hooks.name], pinned, row[Hooks.createdAt], row[Hooks.updatedAt], row[Hooks.allMembers])
    private fun version(row: ResultRow) = HookVersion(
        row[HookVersions.id], row[HookVersions.hookId], row[HookVersions.version], row[HookVersions.digest],
        HookDefinition(row[HookVersions.events].split(',').mapNotNull(HookEvent::of).toSet(), row[HookVersions.matcher],
            row[HookVersions.command], row[HookVersions.timeoutSeconds]),
        row[HookVersions.sizeBytes], row[HookVersions.fileCount], row[HookVersions.publishedBy], row[HookVersions.publishedAt],
    )
    private fun assignment(row: ResultRow) = row[HookAssignments.decision]?.let {
        HookAssignment(row[HookAssignments.hookId], row[HookAssignments.memberId], decision(it), row[HookAssignments.updatedAt])
    }
    private fun decision(value: String) = SkillDecision.entries.single { it.wire == value }
}
