package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.domain.skills.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Exposed skill catalog. Administrative writes lock the team row; a version's content insert locks only its skill row.
 * Every write revalidates the actor against current SQL state and appends its metadata-only audit event in the same
 * transaction, so concurrent replicas cannot interleave catalog changes.
 */
class SqlSkillStore(private val database: Database) : SkillStore {
    override suspend fun catalog(actor: Principal): List<Skill> {
        actor.requireAdmin()
        return database.query {
            requireCurrent(this, actor)
            Skills.join(SkillVersions, JoinType.LEFT, Skills.pinnedVersionId, SkillVersions.id)
                .select(Skills.columns + SkillVersions.version).where { Skills.teamId eq actor.teamId }
                .orderBy(Skills.updatedAt to SortOrder.DESC, Skills.name to SortOrder.ASC).limit(SkillStore.SKILL_LIMIT.toInt())
                .map { skill(it, it.getOrNull(SkillVersions.version)) }
        }
    }

    override suspend fun versions(actor: Principal, name: String): Pair<Skill, List<SkillVersion>> {
        actor.requireAdmin()
        return database.query {
            requireCurrent(this, actor)
            val row = skillRow(actor.teamId, name)
            skill(row) to SkillVersions.selectAll().where { SkillVersions.skillId eq row[Skills.id] }
                .orderBy(SkillVersions.publishedAt to SortOrder.DESC, SkillVersions.version to SortOrder.ASC)
                .limit(SkillStore.VERSION_LIMIT.toInt()).map(::version)
        }
    }

    override suspend fun publish(actor: Principal, name: String, version: String, artifact: SkillArtifact, pin: Boolean, allMembers: Boolean): SkillPublication {
        actor.requireSkillPublication(pin, allMembers)
        // Short team-locked step: create the catalog entry once. The team lock also serializes admission, so the
        // content insert below holds only this skill's row lock.
        var createdHere = false
        val skillId = database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            Skills.select(Skills.id).where { (Skills.teamId eq actor.teamId) and (Skills.name eq name) }.singleOrNull()?.get(Skills.id)
                ?: run {
                    createdHere = true
                    if (Skills.select(Skills.id).where { Skills.teamId eq actor.teamId }.count() >= SkillStore.SKILL_LIMIT)
                        throw TeamFailure("skill_limit", 409, "The catalog is limited to ${SkillStore.SKILL_LIMIT} skills.")
                    val id = UUID.randomUUID().toString()
                    val now = nowMillis()
                    Skills.insert {
                        it[Skills.id] = id; it[teamId] = actor.teamId; it[Skills.name] = name
                        it[pinnedVersionId] = null; it[retiredAt] = null; it[createdAt] = now; it[updatedAt] = now
                    }
                    audit(actor.teamId, actor.member.id, "skill.created", id, credential = keyId(actor))
                    id
                }
        }
        try {
            return publishVersion(actor, skillId, name, version, artifact, pin, allMembers)
        } catch (failure: Throwable) {
            // A first publication that fails must not leave an empty catalog entry that counts toward the skill limit.
            if (createdHere) withContext(NonCancellable) { discardEmpty(actor, skillId) }
            throw failure
        }
    }

    private suspend fun discardEmpty(actor: Principal, skillId: String) = try {
        database.query {
            lockTeam(actor.teamId)
            if (SkillVersions.select(SkillVersions.id).where { SkillVersions.skillId eq skillId }.limit(1).empty()) {
                val removed = Skills.deleteWhere { (Skills.id eq skillId) and (Skills.teamId eq actor.teamId) }
                if (removed > 0) audit(actor.teamId, actor.member.id, "skill.create_discarded", skillId)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // The original failure is the one to report; an empty entry left by a second failure is still harmless.
    }

    private suspend fun publishVersion(
        actor: Principal, skillId: String, name: String, version: String, artifact: SkillArtifact, pin: Boolean, allMembers: Boolean,
    ): SkillPublication {
        return database.query {
            requireCurrent(this, actor)
            // A concurrent first publication of the same name may have failed and discarded the empty entry just now.
            val row = Skills.selectAll().where { Skills.id eq skillId }.forUpdate().singleOrNull()
                ?: throw TeamFailure("skill_busy", 409, "This skill changed while it was being published. Retry the publication.")
            if (row[Skills.retiredAt] != null) throw TeamFailure("skill_retired", 409, "Restore this skill before publishing new versions.")
            // Read under the skill row lock (READ COMMITTED): a version retirement, which takes the same lock, is either
            // committed and seen here, or waits until this publication and its pin commit.
            val existing = SkillVersions.selectAll().where { (SkillVersions.skillId eq skillId) and (SkillVersions.version eq version) }.singleOrNull()
            if (existing != null) {
                if (existing[SkillVersions.digest] != artifact.digest)
                    throw TeamFailure("skill_version_conflict", 409, "Version $version is already published with different content. Publish a new version.")
                if (existing[SkillVersions.retiredAt] != null)
                    throw TeamFailure("skill_version_retired", 409, "Version $version is retired and cannot be pinned. Publish a new version.")
                // A retried publication with pin or allMembers converges on the same state; only an actual change is audited.
                if (!pin && !allMembers) return@query SkillPublication(skill(row), version(existing), created = false)
                if (pin) pinLocked(actor, row, existing[SkillVersions.id], nowMillis())
                if (allMembers) audienceLocked(actor, row, true, nowMillis())
                return@query SkillPublication(skill(skillRow(actor.teamId, name)), version(existing), created = false)
            }
            if (SkillVersions.select(SkillVersions.id).where { SkillVersions.skillId eq skillId }.count() >= SkillStore.VERSION_LIMIT)
                throw TeamFailure("skill_version_limit", 409, "A skill keeps at most ${SkillStore.VERSION_LIMIT} versions.")
            val now = nowMillis()
            val id = UUID.randomUUID().toString()
            SkillVersions.insert {
                it[SkillVersions.id] = id; it[SkillVersions.skillId] = skillId; it[SkillVersions.version] = version
                it[digest] = artifact.digest; it[sizeBytes] = artifact.sizeBytes; it[fileCount] = artifact.entries.size
                it[publishedBy] = actor.member.id; it[publishedAt] = now; it[retiredAt] = null
            }
            SkillFiles.batchInsert(artifact.files.zip(artifact.entries)) { (file, entry) ->
                this[SkillFiles.versionId] = id; this[SkillFiles.path] = entry.path; this[SkillFiles.mode] = entry.mode.wire
                this[SkillFiles.size] = entry.size; this[SkillFiles.sha256] = entry.sha256; this[SkillFiles.content] = file.content
            }
            Skills.update({ Skills.id eq skillId }) { it[updatedAt] = now }
            audit(actor.teamId, actor.member.id, "skill.version.published", id, credential = keyId(actor))
            // Pinned in the publication transaction: the version is never visible as published-but-unpinned to CI.
            if (pin) pinLocked(actor, row, id, now)
            if (allMembers) audienceLocked(actor, row, true, now)
            SkillPublication(skill(skillRow(actor.teamId, name)), version(versionRow(skillId, version)), created = true)
        }
    }

    /** Open to publishing keys with `skills:pin`, so CI can approve a version it published earlier. */
    override suspend fun pin(actor: Principal, name: String, version: String): Skill = write(actor, name, { requireSkillPinner() }) { row, now ->
        if (row[Skills.retiredAt] != null) throw TeamFailure("skill_retired", 409, "Restore this skill before pinning a version.")
        val target = versionRow(row[Skills.id], version)
        if (target[SkillVersions.retiredAt] != null) throw TeamFailure("skill_version_retired", 409, "This version is retired and cannot be pinned.")
        pinLocked(actor, row, target[SkillVersions.id], now)
    }

    /**
     * Points the skill at [versionId] when it is not already pinned there. The caller holds the team lock or the skill
     * row lock (and read [row] with it) and has checked that neither the skill nor the version is retired. A pin made by
     * a service key names that key in `credentialId` (as do the key's `skill.created` and `skill.version.published`); an
     * interactive pin leaves it empty, as before, so the column alone tells a CI write from an administrator's.
     */
    private fun JdbcTransaction.pinLocked(actor: Principal, row: ResultRow, versionId: String, now: Long) {
        if (row[Skills.pinnedVersionId] == versionId) return
        Skills.update({ Skills.id eq row[Skills.id] }) { it[pinnedVersionId] = versionId; it[updatedAt] = now }
        audit(actor.teamId, actor.member.id, "skill.pinned", versionId, credential = keyId(actor))
    }

    /**
     * Sets the skill's audience when it differs. The caller holds the team lock or the skill row lock (and read [row]
     * with it). A change made by a service key names that key in `credentialId`, as a pin does.
     */
    private fun JdbcTransaction.audienceLocked(actor: Principal, row: ResultRow, allMembers: Boolean, now: Long) {
        if (row[Skills.allMembers] == allMembers) return
        Skills.update({ Skills.id eq row[Skills.id] }) { it[Skills.allMembers] = allMembers; it[updatedAt] = now }
        audit(actor.teamId, actor.member.id, if (allMembers) "skill.audience.all_members" else "skill.audience.assigned_only", row[Skills.id], credential = keyId(actor))
    }

    override suspend fun setAudience(actor: Principal, name: String, allMembers: Boolean): Skill = write(actor, name) { row, now ->
        audienceLocked(actor, row, allMembers, now)
    }

    /** The service key behind a catalog write, for its audit event; null for an interactive login. */
    private fun keyId(actor: Principal) = actor.tokenId.takeIf { actor.serviceScopes != null }

    override suspend fun unpin(actor: Principal, name: String): Skill = write(actor, name) { row, now ->
        if (row[Skills.pinnedVersionId] != null) {
            Skills.update({ Skills.id eq row[Skills.id] }) { it[pinnedVersionId] = null; it[updatedAt] = now }
            audit(actor.teamId, actor.member.id, "skill.unpinned", row[Skills.id])
        }
    }

    override suspend fun retire(actor: Principal, name: String): Skill = write(actor, name) { row, now ->
        if (row[Skills.retiredAt] == null) {
            Skills.update({ Skills.id eq row[Skills.id] }) { it[retiredAt] = now; it[pinnedVersionId] = null; it[updatedAt] = now }
            audit(actor.teamId, actor.member.id, "skill.retired", row[Skills.id])
        }
    }

    override suspend fun restore(actor: Principal, name: String): Skill = write(actor, name) { row, now ->
        if (row[Skills.retiredAt] != null) {
            Skills.update({ Skills.id eq row[Skills.id] }) { it[retiredAt] = null; it[updatedAt] = now }
            audit(actor.teamId, actor.member.id, "skill.restored", row[Skills.id])
        }
    }

    override suspend fun retireVersion(actor: Principal, name: String, version: String): SkillVersion {
        actor.requireAdmin()
        return database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            // Locks the skill row: a publication pinning this version holds it without the team lock.
            val row = skillRow(actor.teamId, name, lock = true)
            val target = versionRow(row[Skills.id], version)
            if (row[Skills.pinnedVersionId] == target[SkillVersions.id])
                throw TeamFailure("skill_version_pinned", 409, "Pin another version before retiring the approved one.")
            if (target[SkillVersions.retiredAt] == null) {
                SkillVersions.update({ SkillVersions.id eq target[SkillVersions.id] }) { it[retiredAt] = nowMillis() }
                audit(actor.teamId, actor.member.id, "skill.version.retired", target[SkillVersions.id])
            }
            version(versionRow(row[Skills.id], version))
        }
    }

    override suspend fun assignments(actor: Principal, name: String): List<SkillAssignment> {
        actor.requireAdmin()
        return database.query {
            requireCurrent(this, actor)
            val skill = skillRow(actor.teamId, name)[Skills.id]
            SkillAssignments.selectAll().where { (SkillAssignments.skillId eq skill) and SkillAssignments.decision.isNotNull() }
                .orderBy(SkillAssignments.memberId to SortOrder.ASC).limit(1_000).map { assignment(it)!! }
        }
    }

    override suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?): SkillAssignment? {
        actor.requireAdmin()
        return database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            val skill = skillRow(actor.teamId, name)[Skills.id]
            if (Members.select(Members.id).where { (Members.id eq memberId) and (Members.teamId eq actor.teamId) }.forUpdate().singleOrNull() == null)
                throw TeamFailure("not_found", 404, "Member not found.")
            val where = (SkillAssignments.skillId eq skill) and (SkillAssignments.memberId eq memberId)
            val row = SkillAssignments.selectAll().where { where }.singleOrNull()
            val current = row?.let(::assignment)
            if (current?.decision == decision) return@query current
            val now = nowMillis()
            val id = row?.get(SkillAssignments.id) ?: UUID.randomUUID().toString()
            if (row == null) SkillAssignments.insert {
                it[SkillAssignments.id] = id; it[skillId] = skill; it[SkillAssignments.memberId] = memberId
                it[SkillAssignments.decision] = decision?.wire; it[updatedBy] = actor.member.id; it[updatedAt] = now
            } else SkillAssignments.update({ where }) {
                it[SkillAssignments.decision] = decision?.wire; it[updatedBy] = actor.member.id; it[updatedAt] = now
            }
            val action = when (decision) { SkillDecision.ALLOW -> "skill.access.allowed"; SkillDecision.DENY -> "skill.access.denied"; null -> "skill.access.cleared" }
            // The assignment ID resolves to both the skill and the member in skill_assignments.
            audit(actor.teamId, actor.member.id, action, id)
            SkillAssignments.selectAll().where { where }.single().let(::assignment)
        }
    }

    override suspend fun candidates(actor: Principal, memberId: String): List<SkillCandidate> = database.query {
        requireCurrent(this, actor)
        if (Members.select(Members.id).where { (Members.id eq memberId) and (Members.teamId eq actor.teamId) }.singleOrNull() == null)
            throw TeamFailure("not_found", 404, "Member not found.")
        Skills.join(SkillAssignments, JoinType.LEFT, Skills.id, SkillAssignments.skillId) { SkillAssignments.memberId eq memberId }
            .join(SkillVersions, JoinType.LEFT, Skills.pinnedVersionId, SkillVersions.id)
            .selectAll().where { Skills.teamId eq actor.teamId }
            .orderBy(Skills.name to SortOrder.ASC).limit(SkillStore.SKILL_LIMIT.toInt())
            .map { row ->
                val pinned = row.getOrNull(SkillVersions.id)?.let { version(row) }
                SkillCandidate(skill(row, pinned?.version), pinned, row.getOrNull(SkillAssignments.decision)?.let(::decision))
            }
    }

    override suspend fun files(actor: Principal, versionId: String): List<SkillFile> = database.query {
        requireCurrent(this, actor)
        val owned = (SkillVersions innerJoin Skills).select(SkillVersions.id)
            .where { (SkillVersions.id eq versionId) and (Skills.teamId eq actor.teamId) }.singleOrNull()
        if (owned == null) throw TeamFailure("not_found", 404, "Skill version not found.")
        SkillFiles.selectAll().where { SkillFiles.versionId eq versionId }.orderBy(SkillFiles.path to SortOrder.ASC)
            .limit(SkillArtifacts.MAX_FILES).map { row ->
                SkillFile(row[SkillFiles.path], mode(row[SkillFiles.mode]), row[SkillFiles.content])
            }
    }

    override suspend fun reportInstallations(actor: Principal, reports: List<SkillInstallationReport>) = database.query {
        requireCurrent(this, actor)
        val device = Tokens.select(Tokens.deviceId).where { Tokens.id eq actor.tokenId }.singleOrNull()?.get(Tokens.deviceId)
            ?: throw TeamFailure("device_required", 409, "Sign in with the TeamCode CLI to report installations from a registered device.")
        if (reports.isEmpty()) return@query
        val ids = Skills.select(Skills.id, Skills.name)
            .where { (Skills.teamId eq actor.teamId) and (Skills.name inList reports.map { it.name }) }
            .associate { it[Skills.name] to it[Skills.id] }
        val missing = reports.firstOrNull { it.name !in ids }
        if (missing != null) throw TeamFailure("not_found", 404, "Skill ${missing.name} is not in the catalog.")
        // A reported version must be a published one, and a digest must match it, so status never names unknown content.
        val labels = reports.mapNotNull { it.version }.toSet()
        val digests = if (labels.isEmpty()) emptyMap() else SkillVersions.select(SkillVersions.skillId, SkillVersions.version, SkillVersions.digest)
            .where { (SkillVersions.skillId inList ids.values) and (SkillVersions.version inList labels) }
            .associate { (it[SkillVersions.skillId] to it[SkillVersions.version]) to it[SkillVersions.digest] }
        for (report in reports) {
            if (report.version == null && report.digest != null) throw TeamFailure("invalid_skill_installation", 400, "A digest must name its version.")
            val published = report.version?.let { digests[ids.getValue(report.name) to it] }
            if (report.version != null && (published == null || (report.digest != null && report.digest != published)))
                throw TeamFailure("invalid_skill_installation", 400, "Skill ${report.name} has no published version matching this report.")
        }
        val now = nowMillis()
        SkillInstallations.batchUpsert(reports) { report ->
            this[SkillInstallations.deviceId] = device; this[SkillInstallations.skillId] = ids.getValue(report.name)
            this[SkillInstallations.version] = report.version; this[SkillInstallations.digest] = report.digest
            this[SkillInstallations.status] = report.status.wire; this[SkillInstallations.reportedAt] = now
        }
    }

    override suspend fun installations(actor: Principal, memberId: String): List<SkillInstallation> {
        if (actor.member.id != memberId) actor.requireAdmin() else { actor.requireInteractive(); actor.requireReady() }
        return database.query {
            requireCurrent(this, actor)
            SkillInstallations.join(Devices, JoinType.INNER, SkillInstallations.deviceId, Devices.id)
                .join(Skills, JoinType.INNER, SkillInstallations.skillId, Skills.id)
                .select(Devices.id, Devices.name, Skills.name, SkillInstallations.version, SkillInstallations.digest, SkillInstallations.status, SkillInstallations.reportedAt)
                .where { (Devices.memberId eq memberId) and (Skills.teamId eq actor.teamId) }
                .orderBy(SkillInstallations.reportedAt to SortOrder.DESC, Skills.name to SortOrder.ASC).limit(1_000)
                .map { row ->
                    SkillInstallation(row[Devices.id], row[Devices.name], row[Skills.name], row[SkillInstallations.version], row[SkillInstallations.digest],
                        SkillInstallationStatus.entries.single { it.wire == row[SkillInstallations.status] }, row[SkillInstallations.reportedAt])
                }
        }
    }

    /**
     * Team-locked write on one skill. The skill row is read with a lock, because a publication that pins holds only
     * that row lock: reading it without one could act on a pin state that a concurrent publication just changed.
     */
    private suspend fun write(
        actor: Principal, name: String, authorize: Principal.() -> Unit = { requireAdmin() },
        change: JdbcTransaction.(ResultRow, Long) -> Unit,
    ): Skill {
        actor.authorize()
        return database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            change(skillRow(actor.teamId, name, lock = true), nowMillis())
            skill(skillRow(actor.teamId, name))
        }
    }

    private fun skillRow(team: String, name: String, lock: Boolean = false) =
        Skills.selectAll().where { (Skills.teamId eq team) and (Skills.name eq name) }.let { if (lock) it.forUpdate() else it }.singleOrNull()
            ?: throw TeamFailure("not_found", 404, "Skill not found.")
    private fun versionRow(skill: String, version: String) =
        SkillVersions.selectAll().where { (SkillVersions.skillId eq skill) and (SkillVersions.version eq version) }.singleOrNull()
            ?: throw TeamFailure("not_found", 404, "Skill version not found.")

    /** Single-row reads resolve the pinned label by primary key; list reads join it. */
    private fun skill(row: ResultRow): Skill = skill(row, row[Skills.pinnedVersionId]?.let { id ->
        SkillVersions.select(SkillVersions.version).where { SkillVersions.id eq id }.singleOrNull()?.get(SkillVersions.version)
    })
    private fun skill(row: ResultRow, pinned: String?) =
        Skill(row[Skills.id], row[Skills.name], pinned, row[Skills.retiredAt] != null, row[Skills.createdAt], row[Skills.updatedAt], row[Skills.allMembers])
    private fun version(row: ResultRow) = SkillVersion(
        row[SkillVersions.id], row[SkillVersions.skillId], row[SkillVersions.version], row[SkillVersions.digest], row[SkillVersions.sizeBytes],
        row[SkillVersions.fileCount], row[SkillVersions.publishedBy], row[SkillVersions.publishedAt], row[SkillVersions.retiredAt] != null,
    )
    private fun assignment(row: ResultRow) = row[SkillAssignments.decision]?.let {
        SkillAssignment(row[SkillAssignments.skillId], row[SkillAssignments.memberId], decision(it), row[SkillAssignments.updatedAt])
    }
    private fun decision(value: String) = SkillDecision.entries.single { it.wire == value }
    private fun mode(value: String) = SkillFileMode.entries.single { it.wire == value }
}
