package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.team.Member

/** The shape of every cloud identifier in a path, a header or a body (UUIDs and member identifiers). */
object CloudIdentifier {
    private val pattern = Regex("[a-zA-Z0-9-]{1,36}")
    fun valid(value: String): Boolean = pattern.matches(value)
}

/**
 * A change of session owner. An administrator's transfer starts `accepted`; an owner's offer starts `offered` and
 * waits for the recipient. `accepted` lasts until the session reached its paused boundary and the owner changed
 * (`completed`), or the transfer could not happen (`failed`, `cancelled`).
 */
enum class CloudTransferState(val wire: String) {
    OFFERED("offered"), ACCEPTED("accepted"), COMPLETED("completed"), DECLINED("declined"), CANCELLED("cancelled"), EXPIRED("expired"), FAILED("failed");

    val open get() = this == OFFERED || this == ACCEPTED
    companion object { fun parse(value: String) = entries.singleOrNull { it.wire == value } }
}

data class CloudTransfer(
    val id: String,
    val teamId: String,
    val sessionId: String,
    val fromMemberId: String,
    val toMemberId: String,
    /** Who asked: initiator, credential id and effective member (`TeamCode-On-Behalf-Of`). */
    val initiatedBy: Attribution,
    val state: CloudTransferState,
    /** Resume the session under the recipient once the owner changed. */
    val resume: Boolean,
    /** Pause without waiting for the running turn (crash-consistent workspace). */
    val force: Boolean,
    /** The team policy in force when the transfer was accepted; applied at completion. */
    val pendingInputs: TransferPendingInputs,
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /** Offers only: when the recipient can no longer accept. */
    val expiresAt: Long? = null,
    /** Who accepted, declined or cancelled. */
    val decidedBy: Attribution? = null,
    val decidedAt: Long? = null,
    val completedAt: Long? = null,
    /** The last epoch run for the former owner; executions after it run for the recipient. */
    val atEpoch: Int? = null,
    /** The transcript event that records the change of owner. */
    val eventSeq: Long? = null,
    val failure: CloudProblem? = null,
    val idempotencyKey: String? = null,
    val requestDigest: String? = null,
)

data class CloudTransferRequest(val toMemberId: String, val resume: Boolean, val force: Boolean)

enum class CloudTransferDirection { INCOMING, OUTGOING, BOTH }
data class CloudTransferQuery(
    /** The caller's transfers, or every team transfer for an administrator with [team]. */
    val team: Boolean = false,
    val direction: CloudTransferDirection = CloudTransferDirection.BOTH,
    val states: Set<CloudTransferState> = emptySet(),
    val sessionId: String? = null,
    val beforeId: String? = null,
    val limit: Int = 50,
)
data class CloudTransferPage(val transfers: List<CloudTransfer>, val nextBeforeId: String?)

sealed interface CloudTransferOutcome {
    val transfer: CloudTransfer
    data class Created(override val transfer: CloudTransfer) : CloudTransferOutcome
    data class Replayed(override val transfer: CloudTransfer) : CloudTransferOutcome
}

/** A session compare-and-set committed in the same transaction as a transfer write. */
data class SessionChange(val expected: CloudSession, val next: CloudSession, val leaseOwner: String? = null) {
    init {
        require(next.id == expected.id && next.teamId == expected.teamId && next.generation == expected.generation) { "A change must derive from the row it replaces." }
        require(next.currentEpoch == expected.currentEpoch && next.ownerId == expected.ownerId) { "Epoch and owner change only through start() and complete()." }
        CloudStateMachine.require(expected.state, next.state)
    }
}

sealed interface TransferCompletion {
    data class Completed(val transfer: CloudTransfer, val session: CloudSession) : TransferCompletion
    /** The recipient reached the retained-session cap; nothing was written. */
    data object RecipientAtLimit : TransferCompletion
    /** The recipient was disabled meanwhile; nothing was written. */
    data object RecipientUnavailable : TransferCompletion
    /** The session or the transfer changed, or the lease was lost; nothing was written. */
    data object Conflict : TransferCompletion
}

/** Transfers in shared persistence. Every write that touches a session is a compare-and-set on its generation. */
interface CloudTransferStore {
    suspend fun find(teamId: String, id: String): CloudTransfer?
    /** The transfer created by [initiatorId] with this idempotency key, while the key is kept (24 hours). */
    suspend fun byKey(teamId: String, initiatorId: String, key: String): CloudTransfer?
    /** [memberId] is the caller: their incoming and outgoing transfers, unless the query asks for the whole team. */
    suspend fun list(teamId: String, memberId: String, query: CloudTransferQuery): CloudTransferPage

    /**
     * Inserts [transfer] under the session row lock. A repeated idempotency key replays the stored transfer (or fails
     * with `idempotency_conflict`); another open transfer of the session fails with `transfer_pending`. With
     * [session], its compare-and-set commits in the same transaction (`session_busy` when it lost).
     */
    suspend fun open(transfer: CloudTransfer, actor: CloudActor, auditAction: String, session: SessionChange?): CloudTransferOutcome

    /** Compare-and-set on the transfer version, with [session] in the same transaction; null when either changed. */
    suspend fun update(expected: CloudTransfer, next: CloudTransfer, actor: CloudActor?, auditAction: String?, session: SessionChange? = null): CloudTransfer?

    /**
     * Changes the owner, in one transaction: rechecks the recipient (enabled, under [retainedLimit]) under its member
     * row lock, commits [next] (with its new owner) against [expected] and the reconciler lease, completes the
     * transfer, removes every attachment lease of the session, applies the pending-input policy, appends a
     * `transfer` transcript event and journals `cloud.session.transferred` with the initiator and both members.
     */
    suspend fun complete(expected: CloudSession, next: CloudSession, transfer: CloudTransfer, leaseOwner: String, retainedLimit: Int): TransferCompletion

    /** Marks offers past their expiry `expired` (bounded); returns how many changed. */
    suspend fun expireOffers(limit: Int): Int
    /** Forgets idempotency keys older than 24 hours (bounded). */
    suspend fun maintain()
}

/**
 * One reason a member cannot own a session: model policy, quota, environment variables, MCP or skill assignments.
 * Checks are read-only and run when a transfer is requested, accepted, and again right before the owner changes.
 */
fun interface CloudRecipientCheck {
    suspend fun problems(session: CloudSession, recipient: Member): List<CloudProblem>
}

/** The recipient's daily inference allowance, from the same calculation as admission. */
fun interface MemberAllowanceSource {
    suspend fun dailyAllowance(teamId: String, memberId: String): Long
}
