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
}
enum class CredentialAction { DISCOVERY, IDENTITY, LOGOUT, INFERENCE, USAGE_READ, GUIDANCE, SKILLS_READ, SKILLS_PUBLISH, HOOKS_READ, HOOKS_PUBLISH, MCP, CLOUD_READ, CLOUD_WRITE, INTERACTIVE }

fun Principal.requireInteractive() {
    if (serviceScopes != null) throw TeamFailure("credential_scope", 403, "Sign in with an interactive account for this operation.")
}
fun Principal.requireScope(scope: ServiceScope) {
    requireReady()
    if (serviceScopes != null && scope !in serviceScopes) throw TeamFailure("credential_scope", 403, "This service key does not grant ${scope.wire} access.")
}
/** Cloud reads: an interactive login, or a key with `cloud:read`, `cloud:write` or `admin:cloud`. */
fun Principal.requireCloudRead() {
    requireReady()
    if (serviceScopes != null && serviceScopes.none { it == ServiceScope.CLOUD_READ || it == ServiceScope.CLOUD_WRITE || it == ServiceScope.ADMIN_CLOUD })
        throw TeamFailure("credential_scope", 403, "This service key does not grant cloud:read access.")
}
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
            CredentialAction.USAGE_READ -> actor.requireScope(ServiceScope.USAGE_READ)
            CredentialAction.GUIDANCE -> actor.requireScope(ServiceScope.GUIDANCE)
            CredentialAction.SKILLS_READ -> actor.requireScope(ServiceScope.SKILLS_READ)
            CredentialAction.SKILLS_PUBLISH -> actor.requireScope(ServiceScope.SKILLS_PUBLISH)
            CredentialAction.HOOKS_READ -> actor.requireScope(ServiceScope.HOOKS_READ)
            CredentialAction.HOOKS_PUBLISH -> actor.requireScope(ServiceScope.HOOKS_PUBLISH)
            CredentialAction.MCP -> actor.requireScope(ServiceScope.MCP)
            CredentialAction.CLOUD_READ -> actor.requireCloudRead()
            CredentialAction.CLOUD_WRITE -> actor.requireCloudWrite()
            CredentialAction.INTERACTIVE -> actor.requireInteractive()
        }
    }
}
data class ServiceKey(val id: String, val memberId: String, val name: String, val scopes: Set<ServiceScope>, val createdAt: Long, val expiresAt: Long) {
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
        /** [validDays] null requests a key without expiry, which only an administrator's key may have (see [requireNoExpiryAllowed]). */
        fun validate(name: String, scopes: Set<ServiceScope>, validDays: Int?) {
            if (name.length !in 1..120 || name.isBlank() || name.any { it.isISOControl() } || scopes.isEmpty() || (validDays != null && validDays !in 1..90))
                throw TeamFailure("invalid_service_key", 400, "Provide a name, at least one supported scope and a lifetime between 1 and 90 days.")
        }
        fun requireNoExpiryAllowed(validDays: Int?, ownerIsAdministrator: Boolean) {
            if (validDays == null && !ownerIsAdministrator)
                throw TeamFailure("invalid_service_key", 400, "Only an administrator's key can be created without an expiry. Choose a lifetime between 1 and 90 days.")
        }
    }
}
