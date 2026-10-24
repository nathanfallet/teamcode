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

/**
 * Delivering a skill to all members at publication: an interactive administrator, or a publishing key that also holds
 * `skills:assign` (administrator-owned only, rechecked by the store). `skills:publish` alone can choose the delivered
 * version for members already set to *yes*, never widen who receives a skill.
 */
fun Principal.requireSkillAudienceWriter() {
    requireSkillPublisher()
    if (serviceScopes != null) requireScope(ServiceScope.SKILLS_ASSIGN)
}

/**
 * Pinning (at publication or afterwards): an interactive administrator, or a publishing key that also holds `skills:pin`
 * (administrator-owned only, rechecked by the store). `skills:publish` alone stores versions and never changes what is
 * delivered (audit L3).
 */
fun Principal.requireSkillPinner() {
    requireSkillPublisher()
    if (serviceScopes != null) requireScope(ServiceScope.SKILLS_PIN)
}

/** The right a publication needs: [requireSkillPinner] with `pin`, [requireSkillAudienceWriter] with `allMembers`. */
fun Principal.requireSkillPublication(pin: Boolean, allMembers: Boolean) {
    requireSkillPublisher()
    if (pin) requireSkillPinner()
    if (allMembers) requireSkillAudienceWriter()
}

/** Reading one's own delivery: an interactive member, or a service key with `skills:read`. Others require an administrator. */
fun Principal.requireSkillReader(memberId: String) {
    if (member.id != memberId) return requireAdmin()
    if (serviceScopes == null) requireReady() else requireScope(ServiceScope.SKILLS_READ)
}

/**
 * Validates a CI or administrator upload and stores it as an immutable version. With `pin`, the uploaded version also
 * becomes the delivered one, which needs [requireSkillPinner]. With `allMembers`, the skill is delivered to all members
 * without an explicit decision, which needs [requireSkillAudienceWriter].
 */
class PublishSkillVersionUseCase(private val store: SkillStore) {
    /** Lets transports refuse an unauthorized caller before reading a multi-megabyte upload. */
    fun authorize(actor: Principal) = actor.requireSkillPublisher()

    suspend operator fun invoke(
        actor: Principal, name: String, version: String, files: List<SkillFile>, pin: Boolean = false, allMembers: Boolean = false,
    ): SkillPublication {
        actor.requireSkillPublication(pin, allMembers)
        SkillArtifacts.validateName(name)
        SkillArtifacts.validateVersion(version)
        return store.publish(actor, name, version, SkillArtifacts.validate(files), pin, allMembers)
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
            if (!SkillAccessPolicy.allowed(candidate.memberDecision, candidate.groupDecisions, candidate.skill.allMembers)) return@mapNotNull null
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
 * Catalog operations. All are administrator-only, except pinning, which a publishing key with `skills:pin` may also do. Authorization and
 * input validation happen here, before persistence. Rules that depend on current shared state (retired skill, pinned
 * version, limits) are enforced by the store inside its locked transaction, so two replicas cannot both pass them.
 */
class ManageSkillCatalogUseCase(private val store: SkillStore) {
    suspend fun catalog(actor: Principal): List<Skill> { actor.requireAdmin(); return store.catalog(actor) }
    suspend fun versions(actor: Principal, name: String): Pair<Skill, List<SkillVersion>> = admin(actor, name) { store.versions(actor, name) }
    /** Open to a publishing key that holds `skills:pin`, so CI can approve what it publishes. Everything else is administrator-only. */
    suspend fun pin(actor: Principal, name: String, version: String): Skill =
        admin(actor, name, version, authorize = { requireSkillPinner() }) { store.pin(actor, name, version) }
    /** Lets transports refuse a caller that may not pin before reading its body. */
    fun authorizePin(actor: Principal) = actor.requireSkillPinner()
    suspend fun unpin(actor: Principal, name: String): Skill = admin(actor, name) { store.unpin(actor, name) }
    suspend fun retire(actor: Principal, name: String): Skill = admin(actor, name) { store.retire(actor, name) }
    suspend fun restore(actor: Principal, name: String): Skill = admin(actor, name) { store.restore(actor, name) }
    suspend fun retireVersion(actor: Principal, name: String, version: String): SkillVersion = admin(actor, name, version) { store.retireVersion(actor, name, version) }
    suspend fun assignments(actor: Principal, name: String): List<SkillAssignment> = admin(actor, name) { store.assignments(actor, name) }
    /** All members (an explicit *no* still excludes) or only members set to *yes*. Interactive administrators only. */
    suspend fun setAudience(actor: Principal, name: String, allMembers: Boolean): Skill =
        admin(actor, name) { store.setAudience(actor, name, allMembers) }
    /** Sets a member's yes/no decision; null clears it. */
    suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?): SkillAssignment? =
        admin(actor, name) { store.assign(actor, name, memberId, decision) }
    /** Device installation status: the member themself (interactive) or an administrator. */
    suspend fun installations(actor: Principal, memberId: String): List<SkillInstallation> {
        if (actor.member.id != memberId) actor.requireAdmin() else { actor.requireInteractive(); actor.requireReady() }
        return store.installations(actor, memberId)
    }
    private inline fun <T> admin(
        actor: Principal, name: String, version: String? = null, authorize: Principal.() -> Unit = { requireAdmin() }, block: () -> T,
    ): T {
        actor.authorize()
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
