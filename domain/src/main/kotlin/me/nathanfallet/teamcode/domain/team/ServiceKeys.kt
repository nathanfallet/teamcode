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
}
enum class CredentialAction { DISCOVERY, IDENTITY, LOGOUT, INFERENCE, USAGE_READ, GUIDANCE, SKILLS_READ, SKILLS_PUBLISH, MCP, INTERACTIVE }

fun Principal.requireInteractive() {
    if (serviceScopes != null) throw TeamFailure("credential_scope", 403, "Sign in with an interactive account for this operation.")
}
fun Principal.requireScope(scope: ServiceScope) {
    requireReady()
    if (serviceScopes != null && scope !in serviceScopes) throw TeamFailure("credential_scope", 403, "This service key does not grant ${scope.wire} access.")
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
            CredentialAction.MCP -> actor.requireScope(ServiceScope.MCP)
            CredentialAction.INTERACTIVE -> actor.requireInteractive()
        }
    }
}
data class ServiceKey(val id: String, val memberId: String, val name: String, val scopes: Set<ServiceScope>, val createdAt: Long, val expiresAt: Long)
class CreatedServiceKey(val metadata: ServiceKey, val accessToken: String) {
    override fun toString() = "CreatedServiceKey(metadata=$metadata, accessToken=[REDACTED])"
}
interface ServiceKeyService {
    suspend fun list(actor: Principal, memberId: String = actor.member.id): List<ServiceKey>
    suspend fun create(actor: Principal, memberId: String, name: String, scopes: Set<ServiceScope>, validDays: Int): CreatedServiceKey
    suspend fun revoke(actor: Principal, id: String): String
    companion object {
        const val LIMIT = 20L
        fun validate(name: String, scopes: Set<ServiceScope>, validDays: Int) {
            if (name.length !in 1..120 || name.isBlank() || name.any { it.isISOControl() } || scopes.isEmpty() || validDays !in 1..90)
                throw TeamFailure("invalid_service_key", 400, "Provide a name, at least one supported scope and a lifetime between 1 and 90 days.")
        }
    }
}
