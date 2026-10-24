package me.nathanfallet.teamcode.companion.domain.models

/** Where the member's GitHub link is (`teamcode github status`). */
enum class GitHubLinkState {
    NOT_LINKED,
    LINKED,
    /** GitHub refused the stored refresh token: the member links again. */
    RECONNECT_REQUIRED,
}

/** The member's GitHub link. No token is ever read: only the account it names. */
data class GitHubLink(
    val state: GitHubLinkState,
    /** False until an administrator saved the team's GitHub App: nothing can be linked. */
    val appConfigured: Boolean,
    val login: String? = null,
    val linkedAt: Long? = null,
)

/** A device flow started on the server: open [verificationUri], enter [userCode], poll with [id] every [interval] seconds. */
data class GitHubDeviceLink(val id: String, val userCode: String, val verificationUri: String, val expiresAt: Long, val interval: Int)

/** One answer of the server to the polling of a device flow. */
sealed interface GitHubPoll {
    /** Not authorized yet; [interval] is the new delay in seconds when GitHub asked to slow down. */
    data class Pending(val interval: Int?) : GitHubPoll
    data class Linked(val link: GitHubLink) : GitHubPoll
    data object Expired : GitHubPoll
    data object Denied : GitHubPoll
}
