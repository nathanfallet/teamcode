package me.nathanfallet.teamcode.domain.skills

import me.nathanfallet.teamcode.domain.team.Principal

/**
 * Persistence port for the skill catalog. Implementations revalidate the actor against shared state inside the
 * transaction, serialize writes per team and append metadata-only audit events with each write.
 */
interface SkillStore {
    /** Administrator catalog, newest update first. */
    suspend fun catalog(actor: Principal): List<Skill>

    /** Administrator view of a skill's versions, newest first. */
    suspend fun versions(actor: Principal, name: String): Pair<Skill, List<SkillVersion>>

    /** The caller's own personal skills, newest update first. Interactive members only. */
    suspend fun mine(actor: Principal): List<Skill>

    /** A personal skill's versions, for its owner. */
    suspend fun personalVersions(actor: Principal, name: String): Pair<Skill, List<SkillVersion>>

    /**
     * Turns a personal skill into a team skill after an administrator reviewed it: the owner is cleared, the pinned
     * version and the audience are reset, so nothing is delivered until the administrator pins a version.
     */
    suspend fun promote(actor: Principal, name: String): Skill

    /**
     * Creates the skill on its first publication and stores an immutable version. Republishing the same version with
     * the same digest is an idempotent success; a different digest is a conflict. Pins the version in the same
     * transaction only when [pin] is set, which is also idempotent: republishing the pinned version changes nothing.
     * With [allMembers], the skill is also delivered to all members in the same transaction (idempotent as well); the
     * caller has checked [requireSkillAudienceWriter]. Without it, the audience is left as it is.
     */
    suspend fun publish(actor: Principal, name: String, version: String, artifact: SkillArtifact, pin: Boolean = false, allMembers: Boolean = false): SkillPublication

    /**
     * Publishes a version of one of the caller's personal skills, creating it on the first publication. The name must
     * be free in the whole team. With [pin] the owner approves the version for themself.
     */
    suspend fun publishPersonal(actor: Principal, name: String, version: String, artifact: SkillArtifact, pin: Boolean = false): SkillPublication

    /** Pin, unpin, retire and restore for the owner of a personal skill. */
    suspend fun pinPersonal(actor: Principal, name: String, version: String): Skill
    suspend fun unpinPersonal(actor: Principal, name: String): Skill
    suspend fun retirePersonal(actor: Principal, name: String): Skill
    suspend fun restorePersonal(actor: Principal, name: String): Skill

    /**
     * Approves [version] as the delivered version. Pinning an older version is a rollback. Allowed to skill publishers
     * (an administrator or an administrator's `skills:publish` key); the audit event names the credential.
     */
    suspend fun pin(actor: Principal, name: String, version: String): Skill
    suspend fun unpin(actor: Principal, name: String): Skill

    /** Removes a skill from delivery and blocks publication; versions remain for audit and restoration. */
    suspend fun retire(actor: Principal, name: String): Skill
    suspend fun restore(actor: Principal, name: String): Skill

    /** Prevents a version from being pinned again. The pinned version must be changed first. */
    suspend fun retireVersion(actor: Principal, name: String, version: String): SkillVersion

    suspend fun assignments(actor: Principal, name: String): List<SkillAssignment>

    /**
     * Delivers the skill to all members without an explicit decision (true), or only to members set to *yes* (false).
     * Interactive administrators only; idempotent.
     */
    suspend fun setAudience(actor: Principal, name: String, allMembers: Boolean): Skill

    /** Sets a member's explicit decision, or clears it with null. */
    suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?): SkillAssignment?

    /** Every catalog entry of the member's team (bounded), its pinned version and that member's decision. Delivery policy is the caller's. */
    suspend fun candidates(actor: Principal, memberId: String): List<SkillCandidate>

    /** Files of one version in path order, with their content. */
    suspend fun files(actor: Principal, versionId: String): List<SkillFile>

    /** Upserts the status of skills on the device bound to the caller's login session. */
    suspend fun reportInstallations(actor: Principal, reports: List<SkillInstallationReport>)

    suspend fun installations(actor: Principal, memberId: String): List<SkillInstallation>

    companion object {
        const val SKILL_LIMIT = 500L
        const val VERSION_LIMIT = 200L
        /** Personal skills per member. */
        const val PERSONAL_LIMIT = 50L
    }
}
