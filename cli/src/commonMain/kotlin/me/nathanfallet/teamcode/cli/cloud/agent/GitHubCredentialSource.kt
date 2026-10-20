package me.nathanfallet.teamcode.cli.cloud.agent

import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.api.models.RunnerGitHubView

/**
 * The GitHub credential for `git` and `gh`: the token of the member the execution runs for, fetched from the server
 * when first asked and renewed shortly before it expires, kept in the runner's memory only. It answers for
 * `https://github.com` and nothing else, so no other remote ever receives it; without a usable link it answers
 * nothing and `git` asks for no credential (public repositories keep working anonymously).
 */
internal class GitHubCredentialSource(
    private val fetch: suspend () -> RunnerGitHubView,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CredentialSource {
    private val lock = Mutex()
    private var token: String? = null
    private var renewAt = 0L
    private var asked = false

    override suspend fun credential(request: CredentialRequest): Credential? {
        if (request.protocol != "https" || request.host != HOST) return null
        return lock.withLock {
            if (!asked || now() >= renewAt) {
                val view = fetch()
                asked = true
                val fresh = view.token?.takeIf { view.status == "linked" && it.isNotBlank() }
                if (fresh == null) { token = null; renewAt = now() + RETRY_MILLIS; return@withLock null }
                token = fresh
                // A token with an unknown expiry is asked for again soon; otherwise renew with a margin.
                renewAt = view.expiresAt?.let { it - RENEW_MARGIN_MILLIS } ?: (now() + UNKNOWN_EXPIRY_MILLIS)
            }
            token?.let { Credential(USERNAME, it) }
        }
    }

    companion object {
        const val HOST = "github.com"
        const val USERNAME = "x-access-token"
        const val RENEW_MARGIN_MILLIS = 5 * 60_000L
        const val UNKNOWN_EXPIRY_MILLIS = 5 * 60_000L
        /** After a refusal, the server is not asked again for this long, so a missing link does not cost a request per `git` call. */
        const val RETRY_MILLIS = 30_000L
    }
}
