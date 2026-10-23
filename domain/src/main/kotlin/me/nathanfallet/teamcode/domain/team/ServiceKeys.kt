package me.nathanfallet.teamcode.domain.team

/** Narrow permissions for unattended agents; null scopes on a principal mean an interactive login. */
enum class ServiceScope(val wire: String) {
    INFERENCE("inference"), USAGE_READ("usage:read"), GUIDANCE("guidance"),
    /** Use the MCP gateway with the owner's MCP assignments. */
    MCP("mcp"),
    /** Download the owner's effective skills. */
    SKILLS_READ("skills:read"),
    /** Publish skill versions to the catalog; only valid for a key owned by an administrator. */
    SKILLS_PUBLISH("skills:publish"),
    /**
     * With `skills:publish`, also deliver a published skill to all members (`allMembers` at publication). Only valid for
     * a key owned by an administrator. Separate so that a leaked publishing key cannot widen who receives a skill.
     */
    SKILLS_ASSIGN("skills:assign"),
    /** Download the owner's effective team hooks. */
    HOOKS_READ("hooks:read"),
    /**
     * Publish hook versions to the catalog; only valid for a key owned by an administrator. Unlike `skills:publish`,
     * it never pins: a hook runs code on members' computers, so an interactive administrator approves each version.
     */
    HOOKS_PUBLISH("hooks:publish"),
    /** Read the owner's cloud sessions, transcripts, inputs and transfers, and the profiles the owner can use. */
    CLOUD_READ("cloud:read"),
    /** Everything in `cloud:read`, plus creating and managing the owner's cloud sessions, messages, attachments and transfers. */
    CLOUD_WRITE("cloud:write"),
    /**
     * Everything in `cloud:write` with the cloud rights of an administrator over the team's sessions and transfers,
     * including `TeamCode-On-Behalf-Of`. Only valid for a key owned by an administrator; clusters, profiles, settings
     * and the access policy stay interactive.
     */
    ADMIN_CLOUD("admin:cloud"),
    /**
     * Read-only operational view of the whole team, as an interactive administrator sees it: every member's agent
     * launches, capacity and usage, pool accounts and quota windows, MCP usage, and every member's cloud sessions,
     * transcripts and transfers. Includes `usage:read` and `cloud:read` but no write of any kind and no
     * `TeamCode-On-Behalf-Of`. Only valid for a key owned by an administrator.
     */
    TEAM_READ("team:read"),
    /** List and read the artifacts the owner can see. */
    ARTIFACTS_READ("artifacts:read"),
    /** Everything in `artifacts:read`, plus publishing versions and inviting members; never making public or deleting. */
    ARTIFACTS_WRITE("artifacts:write"),
}
enum class CredentialAction { DISCOVERY, IDENTITY, LOGOUT, INFERENCE, USAGE_READ, GUIDANCE, SKILLS_READ, SKILLS_PUBLISH, HOOKS_READ, HOOKS_PUBLISH, MCP, CLOUD_READ, CLOUD_WRITE, TEAM_READ, ARTIFACTS_READ, ARTIFACTS_WRITE, INTERACTIVE }

fun Principal.requireInteractive() {
    if (serviceScopes != null) throw TeamFailure("credential_scope", 403, "Sign in with an interactive account for this operation.")
}
fun Principal.requireScope(scope: ServiceScope) {
    requireReady()
    if (serviceScopes != null && scope !in serviceScopes) throw TeamFailure("credential_scope", 403, "This service key does not grant ${scope.wire} access.")
}
/** Usage reads: an interactive login, or a key with `usage:read` or `team:read` (which reads the owner's own figures too). */
fun Principal.requireUsageRead() {
    requireReady()
    if (serviceScopes != null && serviceScopes.none { it == ServiceScope.USAGE_READ || it == ServiceScope.TEAM_READ })
        throw TeamFailure("credential_scope", 403, "This service key does not grant usage:read access.")
}
/**
 * Read access to the whole team's operational data: an interactive administrator, or an administrator's key with
 * `team:read`. A `usage:read` key acts as its owner alone, even when the owner is an administrator. This never grants a
 * write: administration (`requireAdmin`) stays interactive.
 */
val Principal.canReadTeam: Boolean
    get() = member.role.isAdministrator && (serviceScopes == null || ServiceScope.TEAM_READ in serviceScopes)
fun Principal.requireTeamRead() {
    requireReady()
    // A key without the scope is a scope problem whoever owns it, as it was under requireAdmin.
    if (serviceScopes != null && ServiceScope.TEAM_READ !in serviceScopes) throw TeamFailure("credential_scope", 403, "This service key does not grant team:read access.")
    if (!canReadTeam) throw TeamFailure("forbidden", 403, "Administrator access is required.")
}
/** Cloud reads: an interactive login, or a key with `cloud:read`, `cloud:write`, `admin:cloud` or `team:read`. */
fun Principal.requireCloudRead() {
    requireReady()
    if (serviceScopes != null && serviceScopes.none { it == ServiceScope.CLOUD_READ || it == ServiceScope.CLOUD_WRITE || it == ServiceScope.ADMIN_CLOUD || it == ServiceScope.TEAM_READ })
        throw TeamFailure("credential_scope", 403, "This service key does not grant cloud:read access.")
}
/** Artifact reads: an interactive login, or a key with `artifacts:read`, `artifacts:write` or `team:read`. */
fun Principal.requireArtifactRead() {
    requireReady()
    if (serviceScopes != null && serviceScopes.none { it == ServiceScope.ARTIFACTS_READ || it == ServiceScope.ARTIFACTS_WRITE || it == ServiceScope.TEAM_READ })
        throw TeamFailure("credential_scope", 403, "This service key does not grant artifacts:read access.")
}
/** Artifact publication and invitations: an interactive login, or a key with `artifacts:write`. */
fun Principal.requireArtifactWrite() = requireScope(ServiceScope.ARTIFACTS_WRITE)
/** Cloud writes: an interactive login, or a key with `cloud:write` or `admin:cloud`. */
fun Principal.requireCloudWrite() {
    requireReady()
    if (serviceScopes != null && serviceScopes.none { it == ServiceScope.CLOUD_WRITE || it == ServiceScope.ADMIN_CLOUD })
        throw TeamFailure("credential_scope", 403, "This service key does not grant cloud:write access.")
}
/**
 * Administrator rights over the team's cloud sessions: an interactive administrator, or an administrator's key with
 * `admin:cloud`. Any other key acts as its owner alone, even when the owner is an administrator.
 */
val Principal.isCloudAdministrator: Boolean
    get() = member.role.isAdministrator && (serviceScopes == null || ServiceScope.ADMIN_CLOUD in serviceScopes)
/** Reading every member's cloud sessions: a cloud administrator, or `team:read`. Writes use [isCloudAdministrator] only. */
val Principal.canReadTeamCloud: Boolean get() = isCloudAdministrator || canReadTeam
fun Principal.requireCloudTeamRead() {
    requireReady()
    if (!canReadTeamCloud) throw TeamFailure("forbidden", 403, "Administrator access to cloud sessions is required.")
}
fun Principal.requireCloudAdministrator() {
    requireReady()
    if (!isCloudAdministrator) throw TeamFailure("forbidden", 403, "Administrator access to cloud sessions is required.")
}
class AuthorizeCredentialUseCase {
    fun check(actor: Principal, action: CredentialAction) {
        if (actor.serviceScopes == null) return
        when (action) {
            CredentialAction.DISCOVERY, CredentialAction.IDENTITY, CredentialAction.LOGOUT -> actor.requireReady()
            CredentialAction.INFERENCE -> actor.requireScope(ServiceScope.INFERENCE)
            CredentialAction.USAGE_READ -> actor.requireUsageRead()
            CredentialAction.TEAM_READ -> actor.requireTeamRead()
            CredentialAction.GUIDANCE -> actor.requireScope(ServiceScope.GUIDANCE)
            CredentialAction.SKILLS_READ -> actor.requireScope(ServiceScope.SKILLS_READ)
            CredentialAction.SKILLS_PUBLISH -> actor.requireScope(ServiceScope.SKILLS_PUBLISH)
            CredentialAction.HOOKS_READ -> actor.requireScope(ServiceScope.HOOKS_READ)
            CredentialAction.HOOKS_PUBLISH -> actor.requireScope(ServiceScope.HOOKS_PUBLISH)
            CredentialAction.MCP -> actor.requireScope(ServiceScope.MCP)
            CredentialAction.CLOUD_READ -> actor.requireCloudRead()
            CredentialAction.CLOUD_WRITE -> actor.requireCloudWrite()
            CredentialAction.ARTIFACTS_READ -> actor.requireArtifactRead()
            CredentialAction.ARTIFACTS_WRITE -> actor.requireArtifactWrite()
            CredentialAction.INTERACTIVE -> actor.requireInteractive()
        }
    }
}
/** [createdBy] is the member who created the key (the owner themselves, or an administrator acting for them); null for keys that predate the record. */
data class ServiceKey(val id: String, val memberId: String, val name: String, val scopes: Set<ServiceScope>, val createdAt: Long, val expiresAt: Long, val createdBy: String? = null, val createdByName: String? = null) {
    /** Keys without expiry store [ServiceKeyService.NO_EXPIRY], so every existing `expires_at > now` check keeps working unchanged. */
    val neverExpires get() = expiresAt == ServiceKeyService.NO_EXPIRY
}
class CreatedServiceKey(val metadata: ServiceKey, val accessToken: String) {
    override fun toString() = "CreatedServiceKey(metadata=$metadata, accessToken=[REDACTED])"
}
interface ServiceKeyService {
    suspend fun list(actor: Principal, memberId: String = actor.member.id): List<ServiceKey>
    suspend fun create(actor: Principal, memberId: String, name: String, scopes: Set<ServiceScope>, validDays: Int?): CreatedServiceKey
    suspend fun revoke(actor: Principal, id: String): String
    companion object {
        const val LIMIT = 20L
        /** Stored expiry of a key that never expires. */
        const val NO_EXPIRY = Long.MAX_VALUE
        /** [validDays] null requests a key without expiry, which only an administrator may create, for themselves or for another member (see [requireNoExpiryAllowed]). */
        fun validate(name: String, scopes: Set<ServiceScope>, validDays: Int?) {
            if (name.length !in 1..120 || name.isBlank() || name.any { it.isISOControl() } || scopes.isEmpty() || (validDays != null && validDays !in 1..90))
                throw TeamFailure("invalid_service_key", 400, "Provide a name, at least one supported scope and a lifetime between 1 and 90 days.")
        }
        /** A key without expiry is created by an administrator (for themselves or a member), never by a member for themselves. */
        fun requireNoExpiryAllowed(validDays: Int?, actorIsAdministrator: Boolean) {
            if (validDays == null && !actorIsAdministrator)
                throw TeamFailure("invalid_service_key", 400, "Only an administrator can create a key without an expiry. Choose a lifetime between 1 and 90 days.")
        }
    }
}
