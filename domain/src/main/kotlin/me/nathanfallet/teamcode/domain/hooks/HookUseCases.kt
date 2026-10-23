package me.nathanfallet.teamcode.domain.hooks

import me.nathanfallet.teamcode.domain.skills.SkillAccessPolicy
import me.nathanfallet.teamcode.domain.skills.SkillArtifacts
import me.nathanfallet.teamcode.domain.skills.SkillDecision
import me.nathanfallet.teamcode.domain.skills.SkillFile
import me.nathanfallet.teamcode.domain.skills.SkillFileEntry
import me.nathanfallet.teamcode.domain.team.*

/**
 * Publication right: an interactive administrator, or a service key with `hooks:publish` whose owner is an
 * administrator. Publishing never delivers: a hook runs code on members' computers, so only an interactive
 * administrator pins a version (see [ManageHookCatalogUseCase.pin]).
 */
fun Principal.requireHookPublisher() {
    if (serviceScopes == null) return requireAdmin()
    requireScope(ServiceScope.HOOKS_PUBLISH)
    if (!member.role.isAdministrator) throw TeamFailure("forbidden", 403, "Hook publication requires a key owned by an administrator.")
}

/** Reading one's own delivery: an interactive member, or a service key with `hooks:read`. Others require an administrator. */
fun Principal.requireHookReader(memberId: String) {
    if (member.id != memberId) return requireAdmin()
    if (serviceScopes == null) requireReady() else requireScope(ServiceScope.HOOKS_READ)
}

/**
 * Validates a CI or administrator upload and stores it as an immutable version. It is not delivered until pinned. With
 * `allMembers`, it will be delivered to all members once pinned; a key may ask for it only before the first pin (see
 * [HookStore.publish]), so no key widens what already runs on workstations.
 */
class PublishHookVersionUseCase(private val store: HookStore) {
    /** Lets transports refuse an unauthorized caller before reading the upload. */
    fun authorize(actor: Principal) = actor.requireHookPublisher()

    suspend operator fun invoke(
        actor: Principal, name: String, version: String, definition: HookDefinition, files: List<SkillFile>, allMembers: Boolean = false,
    ): HookPublication {
        actor.requireHookPublisher()
        HookArtifacts.validateName(name)
        HookArtifacts.validateVersion(version)
        return store.publish(actor, name, version, HookArtifacts.validate(definition, files), allMembers)
    }
}

/** The hooks a member receives: hooks with a pinned version that [SkillAccessPolicy] allows (a *yes*, or all members without a *no*). */
class ResolveEffectiveHooksUseCase(private val store: HookStore) {
    suspend operator fun invoke(actor: Principal, memberId: String = actor.member.id): List<EffectiveHook> =
        resolve(actor, memberId).map { it.first }

    suspend fun resolve(actor: Principal, memberId: String = actor.member.id): List<Pair<EffectiveHook, HookVersion>> {
        actor.requireHookReader(memberId)
        return store.candidates(actor, memberId).mapNotNull { candidate ->
            val pinned = candidate.pinned ?: return@mapNotNull null
            if (!SkillAccessPolicy.allowed(candidate.memberDecision, allMembers = candidate.hook.allMembers)) return@mapNotNull null
            EffectiveHook(candidate.hook.name, pinned.version, pinned.digest, pinned.definition, pinned.sizeBytes, pinned.fileCount) to pinned
        }.sortedBy { it.first.name }
    }
}

class HookDownload(val hook: EffectiveHook, val entries: List<SkillFileEntry>, val files: List<SkillFile>) {
    override fun toString() = "HookDownload(hook=$hook, files=${entries.size})"
}

/** Serves only the caller's currently effective pinned version and verifies stored content against its digest. */
class DownloadHookUseCase(private val store: HookStore, private val effective: ResolveEffectiveHooksUseCase) {
    suspend operator fun invoke(actor: Principal, name: String, version: String): HookDownload {
        val (hook, pinned) = effective.resolve(actor).find { it.first.name == name && it.first.version == version }
            ?: throw TeamFailure("not_found", 404, "This hook version is not assigned to you or is not the approved version.")
        val files = store.files(actor, pinned.id)
        val entries = files.map { SkillFileEntry(it.path, it.mode, it.content.size, SkillArtifacts.sha256(it.content)) }
        if (HookArtifacts.digest(hook.definition, entries) != hook.digest)
            throw TeamFailure("hook_integrity", 500, "The stored hook artifact does not match its published digest.")
        return HookDownload(hook, entries, files)
    }
}

/** Catalog administration: interactive administrators only, pin included. Input is validated before persistence. */
class ManageHookCatalogUseCase(private val store: HookStore) {
    suspend fun catalog(actor: Principal): List<Hook> { actor.requireAdmin(); return store.catalog(actor) }
    suspend fun versions(actor: Principal, name: String) = admin(actor, name) { store.versions(actor, name) }
    suspend fun pin(actor: Principal, name: String, version: String): Hook = admin(actor, name, version) { store.pin(actor, name, version) }
    /** Any published version's files, so an administrator reads the code before pinning it. Verified against the digest. */
    suspend fun read(actor: Principal, name: String, version: String): Pair<HookVersion, List<SkillFile>> = admin(actor, name, version) {
        val record = store.versions(actor, name).second.find { it.version == version } ?: throw TeamFailure("not_found", 404, "Hook version not found.")
        val files = store.files(actor, record.id)
        val entries = files.map { SkillFileEntry(it.path, it.mode, it.content.size, SkillArtifacts.sha256(it.content)) }
        if (HookArtifacts.digest(record.definition, entries) != record.digest)
            throw TeamFailure("hook_integrity", 500, "The stored hook artifact does not match its published digest.")
        record to files
    }
    suspend fun unpin(actor: Principal, name: String): Hook = admin(actor, name) { store.unpin(actor, name) }
    suspend fun assignments(actor: Principal, name: String): List<HookAssignment> = admin(actor, name) { store.assignments(actor, name) }
    suspend fun setAudience(actor: Principal, name: String, allMembers: Boolean): Hook = admin(actor, name) { store.setAudience(actor, name, allMembers) }
    suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?): HookAssignment? =
        admin(actor, name) { store.assign(actor, name, memberId, decision) }
    private inline fun <T> admin(actor: Principal, name: String, version: String? = null, block: () -> T): T {
        actor.requireAdmin()
        HookArtifacts.validateName(name)
        version?.let(HookArtifacts::validateVersion)
        return block()
    }
}

/** Composition bundle for the transport layer. */
class HookCatalog(val store: HookStore) {
    val manage = ManageHookCatalogUseCase(store)
    val publish = PublishHookVersionUseCase(store)
    val effective = ResolveEffectiveHooksUseCase(store)
    val download = DownloadHookUseCase(store, effective)
}
