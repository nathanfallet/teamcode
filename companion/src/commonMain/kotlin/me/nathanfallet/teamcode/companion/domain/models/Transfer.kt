package me.nathanfallet.teamcode.companion.domain.models

/** The server's transfer states (`cloud.transfer.v1`); [UNKNOWN] for a state a newer server added. */
enum class TransferState(val wire: String) {
    OFFERED("offered"),
    ACCEPTED("accepted"),
    COMPLETED("completed"),
    DECLINED("declined"),
    CANCELLED("cancelled"),
    EXPIRED("expired"),
    FAILED("failed"),
    UNKNOWN("unknown");

    /** Still waiting for a decision ([OFFERED]) or for the owner change ([ACCEPTED]). */
    val open: Boolean get() = this == OFFERED || this == ACCEPTED

    companion object {
        fun of(wire: String?): TransferState = entries.firstOrNull { it.wire == wire && it != UNKNOWN } ?: UNKNOWN
    }
}

/** A change of owner of a session: an administrator's transfer (accepted at once) or an owner's offer. */
data class SessionTransfer(
    val id: String,
    val sessionId: String,
    val fromMemberId: String,
    val toMemberId: String,
    val state: TransferState,
    /** The session resumes for the recipient once it is theirs. */
    val resume: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    /** Offers: the recipient must accept before this time. */
    val expiresAt: Long?,
    /** Why it failed or was cancelled by the server (`profile_unassigned`, `transfer_timeout`, ...). */
    val problemCode: String?,
)

data class TransferPage(val transfers: List<SessionTransfer>, val nextBefore: String?)

/** Which transfers a list shows, from the signed-in member's side. */
enum class TransferDirection(val wire: String) { INCOMING("incoming"), OUTGOING("outgoing") }

/** A member a session can be given to, as an administrator's member list shows them. */
data class TeamMember(
    val id: String,
    val username: String,
    val administrator: Boolean,
    /** Enabled with a password set up: the server refuses the others (`recipient_unavailable`). */
    val available: Boolean,
)

/** What the transfer form sends: [resume] and [force] are an administrator's options. */
data class TransferRequest(val toMemberId: String, val resume: Boolean = false, val force: Boolean = false)
