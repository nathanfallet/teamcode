package me.nathanfallet.teamcode.domain.accounts

import me.nathanfallet.teamcode.domain.team.Principal

/** Provider identity is populated by a server-side verifier, never by display-name matching. */
data class AccountIdentity(val teamId: String, val provider: String, val accountId: String, val organizationId: String) {
    init { require(listOf(teamId, provider, accountId, organizationId).all { it.isNotBlank() }) }
}

class ProviderCredentials(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long) {
    init { require(accessToken.isNotBlank() && refreshToken.isNotBlank() && expiresAtMillis > 0) }
    override fun toString(): String = "ProviderCredentials([REDACTED])"
}

/** [email] is the address the provider reports for the subscription, when it reports one. */
data class VerifiedProviderAccount(val identity: AccountIdentity, val displayName: String, val email: String? = null) {
    init { require(email == null || (email.length <= MAX_ACCOUNT_EMAIL && email.none { it.isISOControl() })) }
}
const val MAX_ACCOUNT_EMAIL = 320
data class AccountRecord(
    val id: String,
    val identity: AccountIdentity,
    val displayName: String,
    val credentialVersion: Long,
    val disabled: Boolean,
    val poolId: String?,
    val priority: Int,
    val state: AccountState,
    /** Provider-reported email; null for accounts enrolled before it was recorded, until they are added or imported again. */
    val email: String? = null,
    /** Member who first added the account; null for an account added by the system or before this was recorded. */
    val enrolledBy: String? = null,
)
enum class AccountState { PENDING_HANDOVER, READY, ACCESS_ONLY, RECONNECT_REQUIRED;
    val canServe: Boolean get() = this == READY || this == ACCESS_ONLY
}

interface AccountVerifier {
    suspend fun verify(teamId: String, credentials: ProviderCredentials): VerifiedProviderAccount
}

/** Every write names a human principal or an explicit trusted server operation. Never deserialize this from a request. */
sealed interface AccountMutationActor {
    data class User(val principal: Principal) : AccountMutationActor
    data object System : AccountMutationActor
}

/**
 * Where enrolled credentials come from. A [SIGN_IN] is an OAuth sign-in TeamCode ran itself (`teamcode accounts add`):
 * nothing else holds that refresh token, so TeamCode owns it at once and the account serves requests immediately. An
 * [IMPORT] reuses a session another tool may still refresh, so a new account waits for an explicit handover.
 */
enum class EnrollmentOrigin { SIGN_IN, IMPORT }

interface AccountsRepository {
    suspend fun find(identity: AccountIdentity): AccountRecord?
    /** Must atomically enforce identity uniqueness, version and metadata-preserving updates. */
    suspend fun upsert(author: AccountMutationActor, verified: VerifiedProviderAccount, credentials: ProviderCredentials, expectedVersion: Long?, operationId: String, origin: EnrollmentOrigin = EnrollmentOrigin.IMPORT): EnrollmentResult
}

sealed interface EnrollmentResult {
    data class Created(val account: AccountRecord) : EnrollmentResult
    data class Updated(val account: AccountRecord) : EnrollmentResult
    data object Conflict : EnrollmentResult
}

class CredentialVersionConflict : Exception("Account credentials changed during enrollment. Retry using current credentials.")

interface CredentialSource {
    /** Rejected access tokens force renewal only if they are still current in shared storage. */
    suspend fun fresh(account: AccountRecord, rejectedAccessToken: String? = null): ProviderCredentials
}
