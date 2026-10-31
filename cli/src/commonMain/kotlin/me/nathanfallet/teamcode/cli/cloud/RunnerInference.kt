package me.nathanfallet.teamcode.cli.cloud

import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import me.nathanfallet.teamcode.api.models.RunnerInferenceCredentialView
import me.nathanfallet.teamcode.client.TeamCodeException

/**
 * The execution's `tcex_` inference credential, held in the runner's memory only. The runner's loopback proxy asks
 * for it on every forwarded request: it is fetched on first use and renewed after a third of its lifetime, so the
 * agent never holds it and a copy taken from the runner expires quickly. Deadlines are measured on the runner's
 * clock from the lifetime the server returns, so clock skew between the pod and the server does not matter.
 *
 * Only one caller fetches at a time. While a renewal is in flight, other callers keep using the current credential
 * as long as it is usable, instead of waiting on the network.
 *
 * A server without `cloud.inference.v1` answers `404`: [unsupported] then tells the runner to start the agent with
 * its own provider configuration. A fenced execution (`409`) or a refused runner (`401`/`403`) drops the credential;
 * the runner loop handles the fencing itself.
 */
internal class RunnerInference(
    private val fetch: suspend () -> RunnerInferenceCredentialView,
    private val log: (String) -> Unit = {},
    /** What the credential is called in the log: a remote host asks for a confined session credential through the same renewal. */
    private val what: String = "inference credential",
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private class Held(val token: String, val renewAt: Long, val usableUntil: Long)
    private val lock = Mutex()
    @Volatile private var held: Held? = null
    @Volatile private var retryAt = 0L
    /** The server has no inference route for runners (`404`). */
    @Volatile var unsupported = false; private set

    suspend fun token(): String? {
        val at = now()
        held?.let { if (at < it.renewAt) return it.token }
        val usable = held?.takeIf { at < it.usableUntil }?.token
        // A renewal in flight elsewhere: keep serving the current credential rather than waiting for the network.
        if (usable != null) { if (!lock.tryLock()) return usable } else lock.lock()
        try {
            val current = now()
            held?.let { if (current < it.renewAt) return it.token }
            if (current >= retryAt) renew(current)
            return held?.takeIf { now() < it.usableUntil }?.token
        } finally { lock.unlock() }
    }

    private suspend fun renew(at: Long) {
        try {
            val credential = fetch()
            val lifetime = (credential.expiresInMillis ?: DEFAULT_LIFETIME_MILLIS).coerceIn(MIN_LIFETIME_MILLIS, MAX_LIFETIME_MILLIS)
            held = Held(credential.token, at + lifetime / 3, at + lifetime - SAFETY_MILLIS.coerceAtMost(lifetime / 4))
            unsupported = false
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            retryAt = at + RETRY_MILLIS
            if (error is TeamCodeException) {
                if (error.statusCode == 404) { if (!unsupported) log("This server does not issue a $what to this host."); unsupported = true }
                else log("The $what was refused (HTTP ${error.statusCode}${error.code?.let { " $it" } ?: ""}).")
                if (error.statusCode in setOf(401, 403, 404, 409)) held = null
            } else log("The $what could not be renewed; retrying.")
        }
    }

    /** Whether a credential is in hand or can be fetched now; the proxy answers `503` instead of forwarding without one. */
    suspend fun available(): Boolean = token() != null

    /** Forgets the credential, for example once the runner stops. */
    fun clear() { held = null }

    companion object {
        /** Used when the server does not say (it always does from this release). */
        const val DEFAULT_LIFETIME_MILLIS = 60 * 60_000L
        const val MIN_LIFETIME_MILLIS = 60_000L
        const val MAX_LIFETIME_MILLIS = 24 * 60 * 60_000L
        /** A credential is not offered in its last minutes, even if renewal keeps failing. */
        const val SAFETY_MILLIS = 5 * 60_000L
        const val RETRY_MILLIS = 15_000L
    }
}
