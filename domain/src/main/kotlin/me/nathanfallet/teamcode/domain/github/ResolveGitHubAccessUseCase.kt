package me.nathanfallet.teamcode.domain.github

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.nathanfallet.teamcode.domain.cloud.GitHubAccess
import me.nathanfallet.teamcode.domain.cloud.GitHubAccessSource
import me.nathanfallet.teamcode.domain.cloud.GitHubUserToken

/**
 * A member's GitHub access for their own cloud sessions, from the stored link. A token that expires within
 * [validForMillis] is refreshed first. The refresh is serialized across replicas: the right to spend the rotating
 * refresh token is committed before GitHub is called, other callers wait for it, and an owner silent for
 * [staleAfterMillis] is taken over.
 *
 * - No App, no link, or a link GitHub refused to refresh: [GitHubAccess.NotLinked] (the member links again).
 * - GitHub unreachable and no token still valid: [GitHubAccess.Unavailable]; the old tokens are kept for a later try.
 */
class ResolveGitHubAccessUseCase(
    private val apps: GitHubAppStore,
    private val links: GitHubLinkStore,
    private val github: GitHubPort,
    private val validForMillis: Long = 60 * 60_000L,
    private val staleAfterMillis: Long = 60_000L,
    private val refreshTimeoutMillis: Long = 20_000L,
    private val maxWaitMillis: Long = 20_000L,
) : GitHubAccessSource {
    override suspend fun userAccess(teamId: String, memberId: String): GitHubAccess {
        val app = apps.credentials(teamId) ?: return GitHubAccess.NotLinked
        var waited = 0L
        while (true) {
            val now = apps.now()
            when (val claim = links.claim(teamId, memberId, app.clientId, now + validForMillis, now + STILL_USABLE_MILLIS, staleAfterMillis)) {
                GitHubTokenClaim.NotLinked, GitHubTokenClaim.ReconnectRequired -> return GitHubAccess.NotLinked
                is GitHubTokenClaim.Ready -> return linked(claim.link, claim.accessToken, claim.link.accessExpiresAt)
                is GitHubTokenClaim.Refresh -> return refresh(teamId, memberId, app, claim, now)
                GitHubTokenClaim.Wait -> {
                    if (waited >= maxWaitMillis) return GitHubAccess.Unavailable
                    delay(WAIT_STEP_MILLIS); waited += WAIT_STEP_MILLIS
                }
            }
        }
    }

    private suspend fun refresh(teamId: String, memberId: String, app: GitHubAppCredentials, claim: GitHubTokenClaim.Refresh, now: Long): GitHubAccess {
        val result = try { withTimeoutOrNull(refreshTimeoutMillis) { github.refresh(app, claim.refreshToken) } }
            catch (error: CancellationException) {
                withContext(NonCancellable) { links.abandonRefresh(teamId, memberId, claim, reconnect = false) }
                throw error
            }
            catch (_: Exception) { null }
        return when (result) {
            is GitHubRefresh.Refreshed -> {
                // GitHub already rotated the refresh token: losing these tokens would force the member to link again.
                val saved = withContext(NonCancellable) { save(teamId, memberId, claim, result.tokens) }
                when (saved) {
                    // Saved, or not saved because of a passing database error: the new token is valid either way.
                    true, null -> linked(claim.link, result.tokens.accessToken, result.tokens.accessExpiresAt)
                    // Fenced by a relink, an unlink or a takeover: this token belongs to no stored link any more.
                    false -> {
                        GitHubLinkService.revokeToken(github, app, GitHubRevocable(app.clientId, result.tokens.accessToken))
                        GitHubAccess.Unavailable
                    }
                }
            }
            GitHubRefresh.Rejected -> {
                links.abandonRefresh(teamId, memberId, claim, reconnect = true)
                GitHubAccess.NotLinked
            }
            null -> {
                links.abandonRefresh(teamId, memberId, claim, reconnect = false)
                // A token that is still valid for a while beats no access at all.
                val expiresAt = claim.link.accessExpiresAt
                if (expiresAt != null && expiresAt > now + STILL_USABLE_MILLIS) linked(claim.link, claim.accessToken, expiresAt) else GitHubAccess.Unavailable
            }
        }
    }

    /** True when saved, false when fenced, null when the database kept failing. */
    private suspend fun save(teamId: String, memberId: String, claim: GitHubTokenClaim.Refresh, tokens: GitHubTokens): Boolean? {
        repeat(SAVE_ATTEMPTS) { attempt ->
            try { return links.completeRefresh(teamId, memberId, claim, tokens) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (attempt < SAVE_ATTEMPTS - 1) delay(200L * (attempt + 1)) }
        }
        return null
    }

    private fun linked(link: GitHubLink, token: String, expiresAt: Long?) =
        GitHubAccess.Linked(GitHubUserToken(token, expiresAt, link.identity.login, link.identity.userId))

    private companion object {
        const val WAIT_STEP_MILLIS = 100L
        const val STILL_USABLE_MILLIS = 5 * 60_000L
        const val SAVE_ATTEMPTS = 3
    }
}
