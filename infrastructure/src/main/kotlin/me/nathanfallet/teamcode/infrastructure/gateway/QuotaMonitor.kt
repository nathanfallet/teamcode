package me.nathanfallet.teamcode.infrastructure.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import me.nathanfallet.teamcode.infrastructure.database.AccountEmailBackfill
import me.nathanfallet.teamcode.infrastructure.database.SqlScheduledWork
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.AdmissionStore
import me.nathanfallet.teamcode.infrastructure.providers.ProviderTransport

/** Sequential bounded sampling; stale data stays visible as stale instead of being replaced by zero. */
class QuotaMonitor(private val accounts: AccountStore, private val credentials: CredentialSource, private val admission: AdmissionStore, private val transports: List<ProviderTransport>, private val work: SqlScheduledWork, private val emails: AccountEmailBackfill? = null) {
    suspend fun sample(): Int {
        val accountsById = accounts.list("default").associateBy { it.id }
        var failures = 0
        for (id in work.dueAccounts()) {
            val account = accountsById[id] ?: continue
            try { work.runDue("quota/$id") {
                val fresh = credentials.fresh(account)
                emails?.schedule(account, fresh)
                admission.observe(id, transports.single { it.provider == account.identity.provider }.usageUpdate(account, fresh))
            } }
            catch (_: TimeoutCancellationException) { failures++ }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { failures++ }
        }
        return failures
    }
}
