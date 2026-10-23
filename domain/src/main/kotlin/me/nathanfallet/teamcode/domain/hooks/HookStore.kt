package me.nathanfallet.teamcode.domain.hooks

import me.nathanfallet.teamcode.domain.skills.SkillDecision
import me.nathanfallet.teamcode.domain.skills.SkillFile
import me.nathanfallet.teamcode.domain.team.Principal

/**
 * Persistence port for the hook catalog. Implementations revalidate the actor against shared state inside the
 * transaction, serialize writes per team and append metadata-only audit events with each write.
 */
interface HookStore {
    /** Administrator catalog, newest update first. */
    suspend fun catalog(actor: Principal): List<Hook>

    /** Administrator view of a hook's versions, newest first. */
    suspend fun versions(actor: Principal, name: String): Pair<Hook, List<HookVersion>>

    /**
     * Creates the hook on its first publication and stores an immutable version. Republishing the same version with
     * the same digest is an idempotent success; a different digest is a conflict. Never pins.
     *
     * With [allMembers], the hook is also delivered to all members once an administrator pins it. A service key may
     * ask for it only while no version is pinned (or when the hook already has that audience), so the interactive pin
     * approves the audience along with the code; on a pinned hook it is refused with `hook_audience_requires_administrator`.
     * Without it, the audience is left as it is.
     */
    suspend fun publish(actor: Principal, name: String, version: String, artifact: HookArtifact, allMembers: Boolean = false): HookPublication

    /** Approves [version] as the delivered one (update or rollback). Interactive administrators only. */
    suspend fun pin(actor: Principal, name: String, version: String): Hook
    suspend fun unpin(actor: Principal, name: String): Hook

    suspend fun assignments(actor: Principal, name: String): List<HookAssignment>

    /** All members without an explicit decision (true), or only members set to *yes* (false). Interactive administrators only. */
    suspend fun setAudience(actor: Principal, name: String, allMembers: Boolean): Hook

    /** Sets a member's explicit decision, or clears it with null. */
    suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?): HookAssignment?

    /** Every catalog entry of the member's team (bounded), its pinned version and that member's decision. */
    suspend fun candidates(actor: Principal, memberId: String): List<HookCandidate>

    /** Files of one version in path order, with their content. */
    suspend fun files(actor: Principal, versionId: String): List<SkillFile>

    companion object {
        const val HOOK_LIMIT = 100L
        const val VERSION_LIMIT = 200L
    }
}
