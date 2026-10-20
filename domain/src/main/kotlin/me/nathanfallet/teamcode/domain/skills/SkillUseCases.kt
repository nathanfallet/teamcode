package me.nathanfallet.teamcode.domain.skills

import me.nathanfallet.teamcode.domain.team.*

/**
 * Publication right: an interactive administrator, or a service key with `skills:publish` whose owner is an
 * administrator. The store rechecks the owner's current role inside its transaction.
 */
fun Principal.requireSkillPublisher() {
    if (serviceScopes == null) return requireAdmin()
    requireScope(ServiceScope.SKILLS_PUBLISH)
    if (!member.role.isAdministrator) throw TeamFailure("forbidden", 403, "Skill publication requires a key owned by an administrator.")
}

/** Reading one's own delivery: an interactive member, or a service key with `skills:read`. Others require an administrator. */
fun Principal.requireSkillReader(memberId: String) {
    if (member.id != memberId) return requireAdmin()
    if (serviceScopes == null) requireReady() else requireScope(ServiceScope.SKILLS_READ)
}

/** Validates a CI or administrator upload and stores it as an immutable version. */
class PublishSkillVersionUseCase(private val store: SkillStore) {
    /** Lets transports refuse an unauthorized caller before reading a multi-megabyte upload. */
    fun authorize(actor: Principal) = actor.requireSkillPublisher()

    suspend operator fun invoke(actor: Principal, name: String, version: String, files: List<SkillFile>): SkillPublication {
        actor.requireSkillPublisher()
        SkillArtifacts.validateName(name)
        SkillArtifacts.validateVersion(version)
        return store.publish(actor, name, version, SkillArtifacts.validate(files))
    }
}

/**
 * The skills a member receives: non-retired skills with a non-retired pinned version that [SkillAccessPolicy] allows.
 * Used by CLI synchronization and, later, by cloud sessions resolving their effective member.
 */
class ResolveEffectiveSkillsUseCase(private val store: SkillStore) {
    suspend operator fun invoke(actor: Principal, memberId: String = actor.member.id): List<EffectiveSkill> =
        resolve(actor, memberId).map { it.first }

    /** Effective skills with the pinned version records they were resolved from. */
    suspend fun resolve(actor: Principal, memberId: String = actor.member.id): List<Pair<EffectiveSkill, SkillVersion>> {
        actor.requireSkillReader(memberId)
        return store.candidates(actor, memberId).mapNotNull { candidate ->
            val pinned = candidate.pinned ?: return@mapNotNull null
            if (candidate.skill.retired || pinned.retired) return@mapNotNull null
            if (!SkillAccessPolicy.allowed(candidate.memberDecision, candidate.groupDecisions)) return@mapNotNull null
            EffectiveSkill(candidate.skill.name, pinned.version, pinned.digest, pinned.sizeBytes, pinned.fileCount) to pinned
        }.sortedBy { it.first.name }
    }
}

class SkillDownload(val skill: EffectiveSkill, val entries: List<SkillFileEntry>, val files: List<SkillFile>) {
    override fun toString() = "SkillDownload(skill=$skill, files=${entries.size})"
}

/** Serves only the caller's currently effective pinned version and verifies stored content against its digest. */
class DownloadSkillUseCase(private val store: SkillStore, private val effective: ResolveEffectiveSkillsUseCase) {
    suspend operator fun invoke(actor: Principal, name: String, version: String): SkillDownload {
        val (skill, pinned) = effective.resolve(actor).find { it.first.name == name && it.first.version == version }
            ?: throw TeamFailure("not_found", 404, "This skill version is not assigned to you or is not the approved version.")
        val files = store.files(actor, pinned.id)
        val entries = files.map { SkillFileEntry(it.path, it.mode, it.content.size, SkillArtifacts.sha256(it.content)) }
        if (SkillArtifacts.digest(entries) != skill.digest)
            throw TeamFailure("skill_integrity", 500, "The stored skill artifact does not match its published digest.")
        return SkillDownload(skill, entries, files)
    }
}

/** Device installation reports are bounded and validated; they never grant or revoke access. */
class ReportSkillInstallationsUseCase(private val store: SkillStore) {
    suspend operator fun invoke(actor: Principal, reports: List<SkillInstallationReport>) {
        actor.requireInteractive()
        actor.requireReady()
        if (reports.size > SkillStore.SKILL_LIMIT) throw TeamFailure("invalid_skill_installation", 400, "Report at most ${SkillStore.SKILL_LIMIT} skills.")
        if (reports.map { it.name }.toSet().size != reports.size) throw TeamFailure("invalid_skill_installation", 400, "Report each skill once.")
        for (report in reports) {
            SkillArtifacts.validateName(report.name)
            report.version?.let(SkillArtifacts::validateVersion)
            report.digest?.let(SkillArtifacts::validateDigest)
        }
        store.reportInstallations(actor, reports)
    }
}

/**
 * Administrator catalog operations: authorization and input validation happen here, before persistence. Rules that
 * depend on current shared state (retired skill, pinned version, limits) are enforced by the store inside its
 * team-locked transaction, so two replicas cannot both pass them.
 */
class ManageSkillCatalogUseCase(private val store: SkillStore) {
    suspend fun catalog(actor: Principal): List<Skill> { actor.requireAdmin(); return store.catalog(actor) }
    suspend fun versions(actor: Principal, name: String): Pair<Skill, List<SkillVersion>> = admin(actor, name) { store.versions(actor, name) }
    suspend fun pin(actor: Principal, name: String, version: String): Skill = admin(actor, name, version) { store.pin(actor, name, version) }
    suspend fun unpin(actor: Principal, name: String): Skill = admin(actor, name) { store.unpin(actor, name) }
    suspend fun retire(actor: Principal, name: String): Skill = admin(actor, name) { store.retire(actor, name) }
    suspend fun restore(actor: Principal, name: String): Skill = admin(actor, name) { store.restore(actor, name) }
    suspend fun retireVersion(actor: Principal, name: String, version: String): SkillVersion = admin(actor, name, version) { store.retireVersion(actor, name, version) }
    suspend fun assignments(actor: Principal, name: String): List<SkillAssignment> = admin(actor, name) { store.assignments(actor, name) }
    /** Sets a member's yes/no decision; null clears it. */
    suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?): SkillAssignment? =
        admin(actor, name) { store.assign(actor, name, memberId, decision) }
    /** Device installation status: the member themself (interactive) or an administrator. */
    suspend fun installations(actor: Principal, memberId: String): List<SkillInstallation> {
        if (actor.member.id != memberId) actor.requireAdmin() else { actor.requireInteractive(); actor.requireReady() }
        return store.installations(actor, memberId)
    }
    private inline fun <T> admin(actor: Principal, name: String, version: String? = null, block: () -> T): T {
        actor.requireAdmin()
        SkillArtifacts.validateName(name)
        version?.let(SkillArtifacts::validateVersion)
        return block()
    }
}

/** Composition bundle for the transport layer. */
class SkillCatalog(val store: SkillStore) {
    val manage = ManageSkillCatalogUseCase(store)
    val publish = PublishSkillVersionUseCase(store)
    val effective = ResolveEffectiveSkillsUseCase(store)
    val download = DownloadSkillUseCase(store, effective)
    val report = ReportSkillInstallationsUseCase(store)
}
