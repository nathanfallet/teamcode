package me.nathanfallet.teamcode.domain.github

import me.nathanfallet.teamcode.domain.team.Principal

/** The team's GitHub App, as administrators and members see it. Its client secret and private key are never returned. */
data class GitHubApp(val appId: Long, val clientId: String, val slug: String?, val name: String?, val version: Int, val updatedAt: Long)

/** What an administrator submits. A null secret or key keeps the stored one on update. */
class GitHubAppInput(val appId: Long, val clientId: String, val clientSecret: String?, val privateKey: String?) {
    override fun toString() = "GitHubAppInput(appId=$appId, clientId=$clientId, clientSecret=[REDACTED], privateKey=[REDACTED])"
}

/** Decrypted App credentials, for the GitHub adapter only. Never logged or serialized. */
class GitHubAppCredentials(val appId: Long, val clientId: String, val clientSecret: String, val privateKey: String) {
    override fun toString() = "GitHubAppCredentials(appId=$appId, clientId=$clientId, [REDACTED])"
}

/** What GitHub reports about the App when its private key signs a request. */
data class GitHubAppMetadata(val appId: Long, val clientId: String, val slug: String?, val name: String?)

data class GitHubIdentity(val userId: Long, val login: String)

/** A GitHub App user-to-server token pair. Expiries are null when the App does not expire user tokens. */
class GitHubTokens(val accessToken: String, val accessExpiresAt: Long?, val refreshToken: String?, val refreshExpiresAt: Long?) {
    override fun toString() = "GitHubTokens(accessExpiresAt=$accessExpiresAt, refreshExpiresAt=$refreshExpiresAt, [REDACTED])"
}

enum class GitHubLinkState(val wire: String) {
    /** Usable: the token is valid or can be refreshed. */
    LINKED("linked"),
    /** GitHub refused the refresh token, or the team switched to another App: the member links again. */
    RECONNECT_REQUIRED("reconnect_required"),
}

/** One member's link, without any token. */
data class GitHubLink(
    val memberId: String,
    val identity: GitHubIdentity,
    val clientId: String,
    val state: GitHubLinkState,
    val accessExpiresAt: Long?,
    val refreshExpiresAt: Long?,
    val linkedAt: Long,
    val refreshedAt: Long,
)

/**
 * A member's link status. [state] is null when the member never linked or unlinked; a link made with another App
 * than the configured one cannot be refreshed and is reported as [GitHubLinkState.RECONNECT_REQUIRED].
 */
data class GitHubLinkStatus(val memberId: String, val appConfigured: Boolean, val link: GitHubLink?, val state: GitHubLinkState?) {
    companion object {
        fun of(memberId: String, app: GitHubApp?, link: GitHubLink?) = GitHubLinkStatus(memberId, app != null, link, when {
            link == null -> null
            app == null || app.clientId != link.clientId -> GitHubLinkState.RECONNECT_REQUIRED
            else -> link.state
        })
    }
}

enum class GitHubLinkKind(val wire: String) { DEVICE("device"), WEB("web") }

/**
 * A pending link, bound to one member. [secret] is GitHub's device code (device flow) or the PKCE verifier (web
 * flow); it is stored encrypted. [browserHash] binds a web attempt to the browser that started it.
 */
class GitHubLinkAttempt(
    val id: String,
    val teamId: String,
    val memberId: String,
    val kind: GitHubLinkKind,
    val secret: String,
    val browserHash: String?,
    val pollIntervalSeconds: Int,
    val nextPollAt: Long,
    val expiresAt: Long,
) {
    override fun toString() = "GitHubLinkAttempt(id=$id, memberId=$memberId, kind=$kind, [REDACTED])"
}

/** GitHub's answer to a device authorization request. [deviceCode] is a secret; the user code is shown to the member. */
class GitHubDeviceAuthorization(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresInSeconds: Int, val intervalSeconds: Int) {
    override fun toString() = "GitHubDeviceAuthorization(userCode=$userCode, verificationUri=$verificationUri, [REDACTED])"
}

sealed interface GitHubDevicePoll {
    data object Pending : GitHubDevicePoll
    data class SlowDown(val intervalSeconds: Int) : GitHubDevicePoll
    data object Expired : GitHubDevicePoll
    data object Denied : GitHubDevicePoll
    class Authorized(val tokens: GitHubTokens) : GitHubDevicePoll
}

sealed interface GitHubRefresh {
    class Refreshed(val tokens: GitHubTokens) : GitHubRefresh
    /** GitHub refused the refresh token (revoked, expired or already used): the member must link again. */
    data object Rejected : GitHubRefresh
}

/** What a member sees after starting the device flow. */
data class GitHubDeviceLink(val id: String, val userCode: String, val verificationUri: String, val expiresAt: Long, val intervalSeconds: Int)

sealed interface GitHubDeviceLinkProgress {
    data class Pending(val intervalSeconds: Int) : GitHubDeviceLinkProgress
    data class Linked(val status: GitHubLinkStatus) : GitHubDeviceLinkProgress
    data object Expired : GitHubDeviceLinkProgress
    data object Denied : GitHubDeviceLinkProgress
}

/** The browser leg of the web flow: the URL to send the member to and the value of the browser-binding cookie. */
class GitHubWebLink(val authorizationUrl: String, val browserNonce: String, val expiresAt: Long) {
    override fun toString() = "GitHubWebLink([REDACTED])"
}

/** The App configuration, encrypted at rest. Writes revalidate the interactive administrator in their transaction. */
interface GitHubAppStore {
    suspend fun now(): Long
    suspend fun app(teamId: String): GitHubApp?
    suspend fun saveApp(actor: Principal, expectedVersion: Int?, input: GitHubAppInput, metadata: GitHubAppMetadata): GitHubApp
    suspend fun credentials(teamId: String): GitHubAppCredentials?
}

/** The current token of a link, or the right to refresh it (see [GitHubLinkStore.claim]). */
sealed interface GitHubTokenClaim {
    data object NotLinked : GitHubTokenClaim
    data object ReconnectRequired : GitHubTokenClaim
    /** Another replica is refreshing this link; ask again shortly. */
    data object Wait : GitHubTokenClaim
    class Ready(val link: GitHubLink, val accessToken: String) : GitHubTokenClaim {
        override fun toString() = "Ready(${link.identity.login}, [REDACTED])"
    }
    /** This caller owns the refresh; [startedAt] and [tokenVersion] fence its write. */
    class Refresh(val link: GitHubLink, val accessToken: String, val refreshToken: String, val tokenVersion: Int, val startedAt: Long) : GitHubTokenClaim {
        override fun toString() = "Refresh(${link.identity.login}, [REDACTED])"
    }
}

/** Links and pending attempts. Tokens and attempt secrets are encrypted at rest, bound to the member. */
interface GitHubLinkStore {
    /** Checks the caller's credential, role and member state against the shared database. */
    suspend fun revalidate(actor: Principal)
    /** A member of the team, or null. */
    suspend fun member(teamId: String, memberId: String): me.nathanfallet.teamcode.domain.team.Member?
    /** Replaces the member's previous attempt and purges expired ones; revalidates the member in its transaction. */
    suspend fun startAttempt(actor: Principal, attempt: GitHubLinkAttempt)
    suspend fun attempt(id: String): GitHubLinkAttempt?
    /** Removes the attempt; false when another request already consumed it. */
    suspend fun consumeAttempt(id: String): Boolean
    suspend fun deferPoll(id: String, intervalSeconds: Int, nextPollAt: Long)

    /**
     * Stores the member's link, replacing any previous one. Refuses a GitHub account already linked to another
     * member of the team, and a member that is disabled or awaiting a password change.
     */
    suspend fun saveLink(teamId: String, memberId: String, actor: Principal?, identity: GitHubIdentity, clientId: String, tokens: GitHubTokens): GitHubLink
    suspend fun link(teamId: String, memberId: String): GitHubLink?
    suspend fun links(teamId: String): List<GitHubLink>
    /** Deletes the link and returns its last access token for a best-effort revocation at GitHub. */
    suspend fun removeLink(teamId: String, memberId: String, actor: Principal): String?

    /**
     * The member's token when it stays valid past [validUntil]; otherwise the right to refresh it, committed before
     * GitHub is called so two replicas never spend the same rotating refresh token. An owner silent for
     * [staleAfterMillis] is taken over. A link for another [clientId] than the configured App needs a new link.
     */
    suspend fun claim(teamId: String, memberId: String, clientId: String, validUntil: Long, staleAfterMillis: Long): GitHubTokenClaim
    /** Saves refreshed tokens if [claim] still owns the refresh. */
    suspend fun completeRefresh(teamId: String, memberId: String, claim: GitHubTokenClaim.Refresh, tokens: GitHubTokens): Boolean
    /** Ends an owned refresh: [reconnect] marks the link `reconnect_required`; otherwise the old tokens are kept for a later attempt. */
    suspend fun abandonRefresh(teamId: String, memberId: String, claim: GitHubTokenClaim.Refresh, reconnect: Boolean)
}

/** GitHub's OAuth and REST endpoints. Implementations never surface response bodies in errors. */
interface GitHubPort {
    /** Signs a JWT with the App's private key and reads the App it identifies. */
    suspend fun verifyApp(appId: Long, clientId: String, privateKey: String): GitHubAppMetadata
    suspend fun startDevice(clientId: String): GitHubDeviceAuthorization
    suspend fun pollDevice(clientId: String, deviceCode: String): GitHubDevicePoll
    fun authorizationUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String): String
    suspend fun exchangeCode(credentials: GitHubAppCredentials, code: String, redirectUri: String, codeVerifier: String): GitHubTokens
    /** Throws when GitHub could not answer; [GitHubRefresh.Rejected] only for a definitive refusal of the refresh token. */
    suspend fun refresh(credentials: GitHubAppCredentials, refreshToken: String): GitHubRefresh
    suspend fun user(accessToken: String): GitHubIdentity
    /** Revokes one access token. The member's authorization of the App stays on GitHub, where only they manage it. */
    suspend fun revoke(credentials: GitHubAppCredentials, accessToken: String)
}
