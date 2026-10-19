package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/**
 * The team's GitHub App. Never contains the client secret or the private key. [callbackUrl] is the URL to register
 * as a callback URL of the App for the dashboard's web flow.
 */
@Serializable
data class GitHubAppView(
    val configured: Boolean,
    val appId: Long? = null,
    val clientId: String? = null,
    val slug: String? = null,
    val name: String? = null,
    val version: Int? = null,
    val updatedAt: Long? = null,
    val callbackUrl: String,
)

/** `version` is required to update an existing App; a null secret or key keeps the stored one. Never log it. */
@Serializable
class SaveGitHubAppRequest(
    val appId: Long,
    val clientId: String,
    val clientSecret: String? = null,
    val privateKey: String? = null,
    val version: Int? = null,
) {
    override fun toString() = "SaveGitHubAppRequest(appId=$appId, clientId=$clientId, [REDACTED])"
}

/** Wire values of [GitHubLinkView.state]. */
object GitHubLinkStates {
    const val NOT_LINKED = "not_linked"
    const val LINKED = "linked"
    const val RECONNECT_REQUIRED = "reconnect_required"
}

/** A member's link. No token is ever returned: they reach only the member's own cloud session runners. */
@Serializable
data class GitHubLinkView(
    val memberId: String,
    val state: String,
    val appConfigured: Boolean,
    val login: String? = null,
    val userId: Long? = null,
    val linkedAt: Long? = null,
    val refreshedAt: Long? = null,
    val accessExpiresAt: Long? = null,
)

/** Open [verificationUri] and enter [userCode], then poll with [id] every [interval] seconds until [expiresAt]. */
@Serializable
data class GitHubDeviceLinkView(val id: String, val userCode: String, val verificationUri: String, val expiresAt: Long, val interval: Int)

/** `status` is `pending`, `linked`, `expired` or `denied`; [link] is set once linked. */
@Serializable
data class GitHubDeviceLinkPollView(val status: String, val interval: Int? = null, val link: GitHubLinkView? = null)
