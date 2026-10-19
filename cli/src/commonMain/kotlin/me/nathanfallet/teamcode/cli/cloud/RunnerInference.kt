package me.nathanfallet.teamcode.cli.cloud

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.api.models.RunnerInferenceCredentialView
import me.nathanfallet.teamcode.client.TeamCodeException

/**
 * The execution's `tcex_` inference credential, held in the runner's memory only. The runner's loopback proxy asks
 * for it on every forwarded request: it is fetched on first use and renewed every [RENEW_MILLIS], well inside its
 * one-hour lifetime, so the agent never holds it and a copy taken from the runner expires quickly.
 *
 * A refusal (fenced execution, a server without `cloud.inference.v1`) leaves no credential: the request then reaches
 * the server unauthenticated and the agent sees an authentication error, while the runner loop handles fencing.
 */
internal class RunnerInference(
    private val fetch: suspend () -> RunnerInferenceCredentialView,
    private val log: (String) -> Unit = {},
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val lock = Mutex()
    private var token: String? = null
    private var fetchedAt = 0L
    private var retryAt = 0L

    suspend fun token(): String? = lock.withLock {
        val at = now()
        if (token != null && at - fetchedAt < RENEW_MILLIS) return@withLock token
        if (at < retryAt) return@withLock token?.takeIf { at - fetchedAt < USABLE_MILLIS }
        try {
            token = fetch().token
            fetchedAt = at
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            retryAt = at + RETRY_MILLIS
            log(if (error is TeamCodeException) "The inference credential was refused (HTTP ${error.statusCode}${error.code?.let { " $it" } ?: ""})."
                else "The inference credential could not be renewed; retrying.")
            if (error is TeamCodeException && error.statusCode in setOf(401, 403, 404, 409)) token = null
        }
        token?.takeIf { at - fetchedAt < USABLE_MILLIS }
    }

    /** Forgets the credential, for example once the execution is fenced. */
    suspend fun clear() = lock.withLock { token = null }

    companion object {
        const val RENEW_MILLIS = 20 * 60_000L
        /** A credential older than this is not offered any more, even if renewal keeps failing (it expires at 60). */
        const val USABLE_MILLIS = 55 * 60_000L
        const val RETRY_MILLIS = 15_000L
    }
}
