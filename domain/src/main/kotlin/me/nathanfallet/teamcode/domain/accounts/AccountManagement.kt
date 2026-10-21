package me.nathanfallet.teamcode.domain.accounts

import me.nathanfallet.teamcode.domain.team.*

interface AccountStore : AccountsRepository {
    suspend fun list(teamId: String): List<AccountRecord>
    suspend fun configure(author: AccountMutationActor, teamId: String, id: String, displayName: String, disabled: Boolean, priority: Int, poolId: String? = null): AccountRecord
    /** With [requirePending], refuses an account that no longer waits for its handover (a stale dashboard page). */
    suspend fun handover(author: AccountMutationActor, teamId: String, id: String, accessOnly: Boolean = false, requirePending: Boolean = false): AccountRecord
    suspend fun credentials(teamId: String, id: String): ProviderCredentials
}
class AccountManagement(private val store: AccountStore, private val verifier: AccountVerifier, private val additionalVerifiers: Map<String, AccountVerifier> = emptyMap()) {
    suspend fun list(actor: Principal): List<AccountRecord> { actor.requireTeamRead(); return store.list(actor.teamId) }
    suspend fun enroll(actor: Principal, credentials: ProviderCredentials, operationId: String, provider: String = "anthropic", origin: EnrollmentOrigin = EnrollmentOrigin.IMPORT): EnrollmentResult {
        actor.requireAdmin()
        if (!Regex("[a-zA-Z0-9_-]{16,64}").matches(operationId)) throw TeamFailure("invalid_operation", 400, "A unique enrollment operation identifier is required.")
        if (credentials.accessToken.length > 16_384 || credentials.refreshToken.length > 16_384) throw TeamFailure("invalid_credentials", 400, "Invalid provider credentials.")
        // Import must never rotate credentials while the source is still their refresh owner.
        if (credentials.expiresAtMillis <= System.currentTimeMillis()) throw TeamFailure("provider_reconnect", 409, "These credentials have expired. Add the account again through the CLI.")
        val selected = if (provider == "anthropic") verifier else additionalVerifiers[provider]
            ?: throw TeamFailure("unsupported_provider", 400, "Unsupported subscription provider.")
        val verified = selected.verify(actor.teamId, credentials)
        val existing = store.find(verified.identity)
        return store.upsert(AccountMutationActor.User(actor), verified, credentials, existing?.credentialVersion, operationId, origin)
    }
    suspend fun configure(actor: Principal, id: String, name: String, disabled: Boolean, priority: Int, poolId: String? = null): AccountRecord {
        actor.requireAdmin()
        if (poolId != null && !Regex("[a-zA-Z0-9_-]{1,36}").matches(poolId)) throw TeamFailure("invalid_pool", 400, "Use a pool identifier containing letters, digits, underscores or hyphens (up to 36 characters).")
        if (name.isBlank() || name.length > 120 || name.any { it.isISOControl() } || priority !in -100..100) throw TeamFailure("invalid_account", 400, "Use a name up to 120 characters and a priority between -100 and 100.")
        return store.configure(AccountMutationActor.User(actor), actor.teamId, id, name, disabled, priority, poolId)
    }
    suspend fun handover(actor: Principal, id: String, accessOnly: Boolean = false, requirePending: Boolean = false): AccountRecord {
        actor.requireAdmin()
        return store.handover(AccountMutationActor.User(actor), actor.teamId, id, accessOnly, requirePending)
    }
}
