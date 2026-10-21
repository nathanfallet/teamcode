package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.infrastructure.providers.*
import java.util.concurrent.ConcurrentHashMap

fun interface AccountEmailStore {
    /** Writes [email] only while the account named by [identity] and [accountId] has none. */
    suspend fun recordEmail(identity: AccountIdentity, accountId: String, email: String): Boolean
}

/**
 * Learns the provider email of accounts enrolled before it was recorded, from credentials TeamCode already holds:
 * the token response of a refresh, or a verification with a fresh access token (the same call as enrollment).
 * Best effort and off the request path: it runs in its own scope, a failure never reaches the caller and each
 * account is tried once per process. An address is written only when the provider names the stored identity.
 * Neither tokens nor addresses are logged.
 */
class AccountEmailBackfill(
    private val transports: List<ProviderTransport>,
    private val verifiers: Map<String, AccountVerifier>,
    private val store: AccountEmailStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val log: (String) -> Unit = { System.err.println(it) },
) {
    private val attempted = ConcurrentHashMap.newKeySet<String>()

    /** Refreshes through the provider's transport and, for an account without an email, schedules the lookup. */
    suspend fun renew(account: AccountRecord, previous: ProviderCredentials): ProviderCredentials {
        val refreshed = transports.single { it.provider == account.identity.provider }.renew(previous)
        schedule(account, refreshed.credentials, refreshed.reported)
        return refreshed.credentials
    }

    fun schedule(account: AccountRecord, credentials: ProviderCredentials, reported: ReportedAccount? = null) {
        if (account.email != null || !attempted.add(account.id)) return
        scope.launch {
            try { withTimeout(30_000) { lookup(account, credentials, reported) } }
            catch (error: CancellationException) { if (error !is TimeoutCancellationException) throw error; log("TeamCode could not record an account email: the lookup timed out.") }
            catch (_: Exception) { log("TeamCode could not record an account email: the provider lookup failed.") }
        }
    }

    internal suspend fun lookup(account: AccountRecord, credentials: ProviderCredentials, reported: ReportedAccount?) {
        val identity = account.identity
        if (reported?.conclusive == true && !reported.names(identity)) return mismatch()
        val email = reported?.takeIf { it.conclusive }?.email ?: run {
            val verified = verifiers[identity.provider]?.verify(identity.teamId, credentials) ?: return
            if (verified.identity != identity) return mismatch()
            verified.email
        } ?: return
        store.recordEmail(identity, account.id, email)
    }

    private fun mismatch() = log("TeamCode did not record an account email: the provider named a different account.")
}
