package me.nathanfallet.teamcode.companion.domain.usecases.transfers

import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.SessionTransfer
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.models.TransferPage
import me.nathanfallet.teamcode.companion.domain.models.TransferRequest
import me.nathanfallet.teamcode.companion.domain.models.TransferState
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.TransferRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/** The server's transfer rules the screens check before asking, so an action that would be refused is not offered. */
object TransferRules {
    /** Member and session identifiers as the server takes them. */
    private val IDENTIFIER = Regex("[a-zA-Z0-9-]{1,36}")

    fun validId(value: String): Boolean = IDENTIFIER.matches(value)

    /**
     * A cloud session (never one on a member's device) its owner or an administrator may give away: not being created
     * or prepared (it runs with the owner's recipe), not ended, not already changing owner.
     */
    fun canTransfer(account: Account, session: CloudSession): Boolean =
        (account.isAdministrator || session.ownerId == account.memberId) && !session.remote && session.state in TRANSFERABLE

    private val TRANSFERABLE = setOf(SessionState.READY, SessionState.ACTIVE, SessionState.PAUSING, SessionState.PAUSED, SessionState.RESUMING)

    /** What [account] may do with [transfer]: the recipient decides an offer, its owner withdraws it, administrators do both. */
    fun actions(account: Account, transfer: SessionTransfer): Set<TransferAction> = buildSet {
        val me = account.memberId
        if (transfer.state == TransferState.OFFERED && (transfer.toMemberId == me || account.isAdministrator)) { add(TransferAction.ACCEPT); add(TransferAction.DECLINE) }
        // An administrator can also stop an accepted transfer before the owner change starts.
        if ((transfer.state == TransferState.OFFERED && transfer.fromMemberId == me) || (transfer.state.open && account.isAdministrator)) add(TransferAction.CANCEL)
    }
}

enum class TransferAction { ACCEPT, DECLINE, CANCEL }

/** Whether the transfer screens show: a signed-in account on a server that announces transfers. */
class TransferAvailabilityUseCase(private val accounts: AccountRepository, private val features: FeaturesRepository) {
    /** The transfers list (incoming and outgoing offers). */
    suspend fun available(accountId: String): Boolean {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return false
        return account.signedIn && announced(accountId)
    }

    /** The "Transfer…" action of [session]: [TransferRules.canTransfer] on a server with transfers. */
    suspend fun canTransfer(accountId: String, session: CloudSession): Boolean {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return false
        return account.signedIn && TransferRules.canTransfer(account, session) && announced(accountId)
    }

    private suspend fun announced(accountId: String): Boolean = try {
        Features.TRANSFERS in features.features(accountId)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        // Unknown for now (offline): hidden rather than offered and refused.
        false
    }
}

/**
 * Who the session can go to, the session's owner left out. A server with `cloud.transfer.recipients.v1` lists them to
 * whoever may transfer (members included); an older one lists members to administrators only, so a member there gets
 * null and names the recipient by member ID.
 */
class LoadRecipientsUseCase(
    private val transfers: TransferRepository,
    private val features: FeaturesRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String, ownerId: String): List<TeamMember>? = guard(accountId) {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: throw CompanionException.Unauthorized()
        val listed = when {
            Features.TRANSFER_RECIPIENTS in features.features(accountId) -> transfers.recipients(accountId)
            account.isAdministrator -> transfers.members(accountId)
            else -> return@guard null
        }
        listed.filter { it.id != ownerId }.sortedWith(compareByDescending<TeamMember> { it.available }.thenBy { it.username.lowercase() })
    }
}

/**
 * Transfers a session: at once for an administrator (with [TransferRequest.resume] and [TransferRequest.force]), as an
 * offer the recipient accepts for its owner (both options are an administrator's and are not sent). The caller keeps
 * [idempotencyKey] while the form is unchanged, so a retry after a network failure never transfers twice.
 */
class TransferSessionUseCase(
    private val transfers: TransferRepository,
    private val features: FeaturesRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String, sessionId: String, ownerId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer = guard(accountId) {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: throw CompanionException.Unauthorized()
        val sent = sent(account, request)
        require(TransferRules.validId(sent.toMemberId)) { "Enter the recipient's member ID." }
        require(sent.toMemberId != ownerId) { "This member already owns the session." }
        if (Features.TRANSFERS !in features.features(accountId)) throw CompanionException.Unsupported(Features.TRANSFERS)
        transfers.transfer(accountId, sessionId, sent, idempotencyKey)
    }

    companion object {
        /** What the server receives: the ID trimmed, and an administrator's options only from an administrator. */
        fun sent(account: Account, request: TransferRequest): TransferRequest =
            if (account.isAdministrator) request.copy(toMemberId = request.toMemberId.trim()) else TransferRequest(request.toMemberId.trim())
    }
}

/** The signed-in member's incoming or outgoing transfers, newest first, and the decisions on them. */
class TransferListUseCase(
    private val transfers: TransferRepository,
    private val features: FeaturesRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
) {
    suspend fun page(accountId: String, direction: TransferDirection, before: String? = null): TransferPage = guard(accountId) { transfers.transfers(accountId, direction, before) }

    /**
     * Member names by ID, in one request: the recipients' list where the server has it (members who can no longer
     * receive a session, disabled for example, show by ID), else the member list for administrators; otherwise none.
     */
    suspend fun names(accountId: String): Map<String, String> = guard(accountId) {
        val account = accounts.accounts.value.firstOrNull { it.id == accountId } ?: return@guard emptyMap()
        when {
            Features.TRANSFER_RECIPIENTS in features.features(accountId) -> transfers.recipients(accountId).associate { it.id to it.username }
            account.isAdministrator -> transfers.members(accountId).associate { it.id to it.username }
            else -> emptyMap()
        }
    }

    suspend fun accept(accountId: String, transferId: String, resume: Boolean? = null): SessionTransfer = guard(accountId) { transfers.accept(accountId, transferId, resume) }
    suspend fun decline(accountId: String, transferId: String): SessionTransfer = guard(accountId) { transfers.decline(accountId, transferId) }
    suspend fun cancel(accountId: String, transferId: String): SessionTransfer = guard(accountId) { transfers.cancel(accountId, transferId) }
}
