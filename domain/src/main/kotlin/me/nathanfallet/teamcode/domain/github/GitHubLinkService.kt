package me.nathanfallet.teamcode.domain.github

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import me.nathanfallet.teamcode.domain.team.*

/**
 * The team's GitHub App and its members' links. A link holds GitHub App user-to-server tokens, used only to give a
 * member's own cloud sessions Git and `gh` access; no API returns them.
 */
class GitHubLinkService(
    private val apps: GitHubAppStore,
    private val links: GitHubLinkStore,
    private val github: GitHubPort,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun app(actor: Principal): GitHubApp? {
        actor.requireAdmin(); links.revalidate(actor)
        return apps.app(actor.teamId)
    }

    /** Verifies the App id, client id and private key with GitHub before storing them. */
    suspend fun saveApp(actor: Principal, expectedVersion: Int?, input: GitHubAppInput): GitHubApp {
        actor.requireAdmin()
        validate(input, creating = expectedVersion == null)
        links.revalidate(actor)
        val current = apps.credentials(actor.teamId)
        if (current != null && input.clientId != current.clientId && input.clientSecret == null)
            throw TeamFailure("invalid_github_app", 400, "Provide the client secret of the new App.")
        val privateKey = input.privateKey ?: current?.privateKey ?: throw TeamFailure("invalid_github_app", 400, "Provide the App's private key.")
        val metadata = remote { github.verifyApp(input.appId, input.clientId, privateKey) }
        val saved = apps.saveApp(actor, expectedVersion, input, metadata)
        if (current != null && current.clientId != input.clientId) {
            // Links of the previous App can never be refreshed again; revoke their tokens while its credentials are known.
            for (stale in links.removeLinksOfOtherApps(actor, input.clientId)) revoke(current, stale)
        }
        return saved
    }

    /** The caller's own link, or another member's for an administrator. */
    suspend fun status(actor: Principal, memberId: String? = null): GitHubLinkStatus {
        val target = target(actor, memberId)
        return GitHubLinkStatus.of(target, apps.app(actor.teamId), links.link(actor.teamId, target))
    }

    /** Every member's link, for administrators. */
    suspend fun links(actor: Principal): List<GitHubLinkStatus> {
        actor.requireAdmin(); links.revalidate(actor)
        val app = apps.app(actor.teamId)
        return links.links(actor.teamId).map { GitHubLinkStatus.of(it.memberId, app, it) }
    }

    /** Starts GitHub's device flow; the member enters the user code on GitHub, then polls [pollDevice]. */
    suspend fun startDevice(actor: Principal): GitHubDeviceLink {
        member(actor)
        val app = apps.credentials(actor.teamId) ?: notConfigured()
        val device = remote { github.startDevice(app.clientId) }
        val now = apps.now()
        val interval = device.intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
        val expiresAt = now + device.expiresInSeconds.coerceIn(60, 15 * 60) * 1000L
        val attempt = GitHubLinkAttempt(UUID.randomUUID().toString(), actor.teamId, actor.member.id, GitHubLinkKind.DEVICE, device.deviceCode, null,
            interval, now + interval * 1000L, expiresAt)
        links.startAttempt(actor, attempt)
        return GitHubDeviceLink(attempt.id, device.userCode, device.verificationUri, expiresAt, interval)
    }

    /**
     * One poll of a device link. GitHub is asked at most once per interval whatever the caller's pace, so a client
     * polling too fast never earns the member a `slow_down` penalty.
     */
    suspend fun pollDevice(actor: Principal, id: String): GitHubDeviceLinkProgress {
        member(actor)
        val attempt = links.attempt(id)?.takeIf { it.kind == GitHubLinkKind.DEVICE && it.teamId == actor.teamId && it.memberId == actor.member.id }
            ?: throw attemptNotFound()
        val now = apps.now()
        if (attempt.expiresAt <= now) { links.consumeAttempt(id); return GitHubDeviceLinkProgress.Expired }
        if (now < attempt.nextPollAt) return GitHubDeviceLinkProgress.Pending(attempt.pollIntervalSeconds)
        val app = apps.credentials(actor.teamId) ?: notConfigured()
        // The poll slot is taken before GitHub is asked, so a concurrent poll or a GitHub error never shortens the interval.
        if (!links.deferPoll(id, attempt.nextPollAt, attempt.pollIntervalSeconds, now + attempt.pollIntervalSeconds * 1000L))
            return GitHubDeviceLinkProgress.Pending(attempt.pollIntervalSeconds)
        return when (val poll = remote { github.pollDevice(app.clientId, attempt.secret) }) {
            GitHubDevicePoll.Pending -> GitHubDeviceLinkProgress.Pending(attempt.pollIntervalSeconds)
            is GitHubDevicePoll.SlowDown -> {
                val interval = poll.intervalSeconds.coerceIn(attempt.pollIntervalSeconds, MAX_INTERVAL_SECONDS)
                links.deferPoll(id, null, interval, now + interval * 1000L)
                GitHubDeviceLinkProgress.Pending(interval)
            }
            GitHubDevicePoll.Expired -> { links.consumeAttempt(id); GitHubDeviceLinkProgress.Expired }
            GitHubDevicePoll.Denied -> { links.consumeAttempt(id); GitHubDeviceLinkProgress.Denied }
            is GitHubDevicePoll.Authorized -> {
                links.consumeAttempt(id)
                GitHubDeviceLinkProgress.Linked(complete(app, actor.teamId, actor.member.id, actor, poll.tokens))
            }
        }
    }

    /**
     * Starts the web flow (state and PKCE). The returned nonce must be set as a cookie in the member's browser: the
     * callback is accepted only from that browser, so nobody can bind their own TeamCode member to someone else's
     * GitHub authorization by sending them a link.
     */
    suspend fun startWeb(actor: Principal, redirectUri: String): GitHubWebLink {
        member(actor)
        val app = apps.credentials(actor.teamId) ?: notConfigured()
        val state = token(); val verifier = token(); val nonce = token()
        val now = apps.now()
        val expiresAt = now + WEB_ATTEMPT_MILLIS
        links.startAttempt(actor, GitHubLinkAttempt(sha256(state), actor.teamId, actor.member.id, GitHubLinkKind.WEB, verifier, sha256(nonce), 0, now, expiresAt))
        return GitHubWebLink(github.authorizationUrl(app.clientId, redirectUri, state, challenge(verifier)), nonce, expiresAt)
    }

    /** The web flow's callback. The attempt is single-use and bound to the browser that started it. */
    suspend fun completeWeb(state: String?, code: String?, error: String?, browserNonce: String?, redirectUri: String): GitHubLinkStatus {
        if (state == null || !tokenPattern.matches(state)) throw attemptNotFound()
        val attempt = links.attempt(sha256(state))?.takeIf { it.kind == GitHubLinkKind.WEB } ?: throw attemptNotFound()
        if (browserNonce == null || !tokenPattern.matches(browserNonce) || attempt.browserHash == null ||
            !MessageDigest.isEqual(sha256(browserNonce).toByteArray(), attempt.browserHash.toByteArray()))
            throw TeamFailure("github_link_browser_mismatch", 403, "Finish linking GitHub in the browser that started it.")
        if (!links.consumeAttempt(attempt.id)) throw attemptNotFound()
        if (attempt.expiresAt <= apps.now()) throw attemptNotFound()
        if (error != null) throw TeamFailure("github_link_denied", 400, "GitHub authorization was cancelled. Start again from the dashboard.")
        if (code == null || code.length !in 1..512 || code.any { it.isWhitespace() || it.isISOControl() }) throw attemptNotFound()
        val app = apps.credentials(attempt.teamId) ?: notConfigured()
        val tokens = remote { github.exchangeCode(app, code, redirectUri, attempt.secret) }
        return complete(app, attempt.teamId, attempt.memberId, null, tokens)
    }

    /** Removes the link and revokes its access token at GitHub (best effort). Unlinking twice is not an error. */
    suspend fun unlink(actor: Principal, memberId: String? = null) {
        val target = target(actor, memberId)
        val removed = links.removeLink(actor.teamId, target, actor) ?: return
        apps.credentials(actor.teamId)?.let { revoke(it, removed) }
    }

    private suspend fun complete(app: GitHubAppCredentials, teamId: String, memberId: String, actor: Principal?, tokens: GitHubTokens): GitHubLinkStatus {
        val issued = GitHubRevocable(app.clientId, tokens.accessToken)
        val identity = try { remote { github.user(tokens.accessToken) } }
            catch (error: TeamFailure) { revoke(app, issued); throw error }
        val link = try { links.saveLink(teamId, memberId, actor, identity, app.clientId, tokens) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                // Never keep a live token nobody stores. Only this token is revoked: the member's grant, and any
                // other token GitHub issued for it, stay untouched.
                revoke(app, issued)
                throw error
            }
        return GitHubLinkStatus(memberId, true, link, link.state)
    }

    private suspend fun revoke(app: GitHubAppCredentials, token: GitHubRevocable) = revokeToken(github, app, token)

    private suspend fun target(actor: Principal, memberId: String?): String {
        member(actor)
        if (memberId == null || memberId == actor.member.id) return actor.member.id
        actor.requireAdmin()
        return links.member(actor.teamId, memberId)?.id ?: throw TeamFailure("member_not_found", 404, "Member not found.")
    }
    private suspend fun member(actor: Principal) {
        actor.requireReady(); actor.requireInteractive()
        links.revalidate(actor)
    }
    private fun token() = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        /**
         * Best effort: the stored tokens are already gone and an access token expires within hours. Only the App that
         * issued a token can revoke it; a token of a previous App is left to expire.
         */
        internal suspend fun revokeToken(github: GitHubPort, app: GitHubAppCredentials, token: GitHubRevocable) {
            if (token.clientId != app.clientId) return
            try { withTimeoutOrNull(10_000) { github.revoke(app, token.accessToken) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { }
        }
        const val MIN_INTERVAL_SECONDS = 5
        const val MAX_INTERVAL_SECONDS = 60
        const val WEB_ATTEMPT_MILLIS = 10 * 60_000L
        private val tokenPattern = Regex("[A-Za-z0-9_-]{43}")
        private val clientIdPattern = Regex("Iv[0-9A-Za-z._-]{2,62}")

        fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.encodeToByteArray()))

        fun validate(input: GitHubAppInput, creating: Boolean) {
            val secret = input.clientSecret
            val key = input.privateKey
            if (input.appId !in 1..999_999_999_999 || !clientIdPattern.matches(input.clientId) ||
                (creating && (secret == null || key == null)) ||
                (secret != null && (secret.length !in 20..256 || secret.any { it.isWhitespace() || it.isISOControl() })) ||
                (key != null && (key.length > 16_384 || !key.contains("-----BEGIN ") || !key.contains("PRIVATE KEY-----"))))
                throw TeamFailure("invalid_github_app", 400, "Provide the App ID, the client ID of a GitHub App (it starts with Iv), its client secret and its PEM private key.")
        }

        /** Remote failures become a retryable `502` without GitHub's response: it may echo request content. */
        internal suspend fun <T> remote(block: suspend () -> T): T = try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: TeamFailure) { throw error }
            catch (_: Exception) { throw TeamFailure("github_unreachable", 502, "GitHub could not be reached. Try again.") }
        private fun notConfigured(): Nothing = throw TeamFailure("github_app_not_configured", 409, "An administrator must configure the team's GitHub App first.")
        private fun attemptNotFound() = TeamFailure("github_link_not_found", 404, "This GitHub link attempt expired or was already used. Start again.")
    }
}
