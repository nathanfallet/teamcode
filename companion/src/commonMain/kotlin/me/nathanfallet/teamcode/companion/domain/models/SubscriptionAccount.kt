package me.nathanfallet.teamcode.companion.domain.models

/** A subscription account of the team's pool (`GET /accounts`, administrators only). No credential is ever read. */
data class SubscriptionAccount(
    val id: String,
    val label: String,
    val provider: String,
    /** The server's state: `READY`, `PENDING_HANDOVER`, `RECONNECT_REQUIRED`... */
    val state: String,
    val disabled: Boolean,
)

/**
 * A kind of account the app offers to add, in the order of the CLI's selector. [available] is false for a kind the
 * server cannot hold yet (an Anthropic API key): it is listed, and cannot be chosen.
 */
data class SubscriptionAccountType(val id: String, val label: String, val available: Boolean)

/** Whether this member may add subscription accounts on the account's server, asked before any provider sign-in. */
enum class EnrollmentAccess {
    ALLOWED,
    /** Only administrators may; an administrator can let every member (dashboard, "Who can add accounts"). */
    ADMINISTRATORS_ONLY,
    /** Every member may, so this session cannot (a pending password change). */
    SESSION_CANNOT_ENROLL,
}

/** What the server did with a finished sign-in. */
data class AddedSubscriptionAccount(val created: Boolean, val account: SubscriptionAccount) {
    /** An account taken over from another tool waits for its handover before serving requests. */
    val pendingHandover: Boolean get() = account.state == "PENDING_HANDOVER"
}

/**
 * One provider sign-in in progress: the [url] to open in the browser and what to paste back. Its secrets (PKCE
 * verifier, state) stay in the infrastructure, behind [complete]; [toString] shows none of them.
 */
class SubscriptionSignIn(
    val type: SubscriptionAccountType,
    /** The provider's own name: Claude, Codex. */
    val providerName: String,
    val url: String,
    val pasteInstructions: String,
    private val finish: suspend (paste: String) -> AddedSubscriptionAccount,
) {
    /** Exchanges the pasted code at the provider, then sends the credentials to the server. */
    suspend fun complete(paste: String): AddedSubscriptionAccount = finish(paste)

    override fun toString() = "SubscriptionSignIn(${type.id})"
}
