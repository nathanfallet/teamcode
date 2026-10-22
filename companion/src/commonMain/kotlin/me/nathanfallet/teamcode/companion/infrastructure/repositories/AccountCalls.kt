package me.nathanfallet.teamcode.companion.infrastructure.repositories

import io.ktor.client.call.NoTransformationFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider

/** Short calls with an account's client, their failures translated to the domain's (never a server message). */
class AccountCalls(private val clients: TeamCodeClientProvider, private val accounts: AccountRepository) {
    /** [resource]: the call reads one session or artifact, so a `403` or `404` means it is gone or hidden. */
    suspend fun <T> withAccount(accountId: String, resource: Boolean = false, call: suspend (TeamCodeClient) -> T): T =
        translateFailures(credentialsFailure = false, resource) { call(client(accountId)) }

    /** The account's client, for a stream that handles its own failures; an unknown account is signed out. */
    suspend fun client(accountId: String): TeamCodeClient {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: throw CompanionException.Unauthorized()
        return clients.forAccount(accountId, account.origin)
    }
}

internal suspend fun <T> translateFailures(credentialsFailure: Boolean, resource: Boolean = false, call: suspend () -> T): T = try {
    // Every call here is a short request and answer: a stalled server or a captive portal ends as unreachable
    // instead of a spinner that lasts minutes.
    // (Timed on the default dispatcher, in real time, whatever the caller's dispatcher.)
    try {
        withContext(Dispatchers.Default) { withTimeout(REQUEST_TIMEOUT_MILLIS) { call() } }
    } catch (_: TimeoutCancellationException) {
        throw CompanionException.Unreachable()
    }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    throw error.translated(credentialsFailure, resource)
}

/** The domain failure for an exception of the typed client or its engine. */
internal fun Exception.translated(credentialsFailure: Boolean = false, resource: Boolean = false): Exception = when (this) {
    is CompanionException -> this
    is TeamCodeException -> when {
        statusCode == 0 && code == TeamCodeClient.MALFORMED_EVENT -> CompanionException.UnreadableEvent()
        statusCode == 0 -> CompanionException.Unreachable()
        statusCode == 401 && credentialsFailure -> CompanionException.InvalidCredentials()
        statusCode == 401 -> CompanionException.Unauthorized()
        resource && (statusCode == 403 || statusCode == 404) -> CompanionException.NotFound()
        statusCode == 429 -> CompanionException.RateLimited(retryAfterSeconds)
        statusCode == 400 && code == "invalid_password" -> CompanionException.PasswordRejected(code)
        else -> CompanionException.Server(statusCode, code)
    }
    else -> when {
        isDecodingFailure() -> CompanionException.NotTeamCode()
        // Origins are normalized before any call, so an argument error is a bug or the typed client's own refusal
        // (its message is readable): shown as is, never disguised as a network failure.
        // Only the exact type: engines throw subclasses such as UnresolvedAddressException for network failures.
        this::class == IllegalArgumentException::class -> this
        // Connection refused, unknown host, TLS failure, timeout: the engines throw their own types.
        else -> CompanionException.Unreachable()
    }
}

private const val REQUEST_TIMEOUT_MILLIS = 30_000L

// A JSON body that is not TeamCode's, or a body that is not JSON at all (an HTML portal page answering 200).
internal fun Throwable.isDecodingFailure(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is SerializationException || it is NoTransformationFoundException }
