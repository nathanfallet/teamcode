package me.nathanfallet.teamcode.domain.accounts

import me.nathanfallet.teamcode.domain.team.*

interface AccountStore : AccountsRepository {
    suspend fun list(teamId: String): List<AccountRecord>
    suspend fun configure(author: AccountMutationActor, teamId: String, id: String, displayName: String, disabled: Boolean, priority: Int, poolId: String? = null): AccountRecord
    /** With [requirePending], refuses an account that no longer waits for its handover (a stale dashboard page). */
    suspend fun handover(author: AccountMutationActor, teamId: String, id: String, accessOnly: Boolean = false, requirePending: Boolean = false): AccountRecord
    suspend fun credentials(teamId: String, id: String): ProviderCredentials
    /** Read without a lock for the early refusal; [AccountsRepository.upsert] reads it again under the team lock. */
    suspend fun enrollmentPolicy(teamId: String): AccountEnrollmentPolicy
    /** Administrator-only; must revalidate the author under the team lock and audit a change. */
    suspend fun setEnrollmentPolicy(author: AccountMutationActor, teamId: String, policy: AccountEnrollmentPolicy): AccountEnrollmentPolicy
}
class AccountManagement(private val store: AccountStore, private val verifier: AccountVerifier, private val additionalVerifiers: Map<String, AccountVerifier> = emptyMap()) {
    suspend fun list(actor: Principal): List<AccountRecord> { actor.requireTeamRead(); return store.list(actor.teamId) }
    suspend fun enroll(actor: Principal, credentials: ProviderCredentials, operationId: String, provider: String = "anthropic", origin: EnrollmentOrigin = EnrollmentOrigin.IMPORT): EnrollmentResult {
        // Refuse before any provider call; the store decides again under the team lock with the current account row.
        AccountEnrollmentRules.authorize(actor, store.enrollmentPolicy(actor.teamId), origin)
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
    /** Any ready interactive member may ask, so `accounts add` can check before opening the provider sign-in. */
    suspend fun enrollmentAccess(actor: Principal): AccountEnrollmentAccess {
        actor.requireReady()
        val policy = store.enrollmentPolicy(actor.teamId)
        return AccountEnrollmentAccess(policy, AccountEnrollmentRules.mayEnroll(actor, policy))
    }
    suspend fun setEnrollmentPolicy(actor: Principal, policy: AccountEnrollmentPolicy): AccountEnrollmentPolicy {
        actor.requireAdmin()
        return store.setEnrollmentPolicy(AccountMutationActor.User(actor), actor.teamId, policy)
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
