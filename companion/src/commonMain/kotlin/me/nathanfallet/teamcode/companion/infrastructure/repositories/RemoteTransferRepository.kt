package me.nathanfallet.teamcode.companion.infrastructure.repositories

import me.nathanfallet.teamcode.api.models.CloudTransferView
import me.nathanfallet.teamcode.api.models.CreateCloudTransferRequest
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.SessionTransfer
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.models.TransferPage
import me.nathanfallet.teamcode.companion.domain.models.TransferRequest
import me.nathanfallet.teamcode.companion.domain.models.TransferState
import me.nathanfallet.teamcode.companion.domain.repositories.TransferRepository

class RemoteTransferRepository(private val calls: AccountCalls) : TransferRepository {
    override suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer = refusals(accountId) { client ->
        client.transferCloudSession(sessionId, CreateCloudTransferRequest(request.toMemberId, request.resume, request.force), idempotencyKey).domain()
    }

    override suspend fun transfers(accountId: String, direction: TransferDirection, before: String?): TransferPage = calls.withAccount(accountId) { client ->
        val page = client.cloudTransfers(direction = direction.wire, before = before, limit = PAGE)
        TransferPage(page.transfers.map { it.domain() }, page.nextBefore)
    }

    override suspend fun accept(accountId: String, transferId: String, resume: Boolean?): SessionTransfer = refusals(accountId) { it.acceptCloudTransfer(transferId, resume).domain() }
    override suspend fun decline(accountId: String, transferId: String): SessionTransfer = refusals(accountId) { it.declineCloudTransfer(transferId).domain() }
    override suspend fun cancel(accountId: String, transferId: String): SessionTransfer = refusals(accountId) { it.cancelCloudTransfer(transferId).domain() }

    override suspend fun members(accountId: String): List<TeamMember> = calls.withAccount(accountId) { client ->
        client.members().map { TeamMember(it.id, it.username, administrator = it.role.uppercase() in ADMIN_ROLES, available = !it.disabled && !it.passwordChangeRequired) }
    }

    /**
     * A refusal of the transfer (`403`, `409` or `422` with a code) keeps its code and the recipient's problems, which
     * the screens word; a `404` means the session or transfer is gone or hidden.
     */
    private suspend fun <T> refusals(accountId: String, call: suspend (TeamCodeClient) -> T): T = calls.withAccount(accountId, resource = true) { client ->
        try {
            call(client)
        } catch (error: TeamCodeException) {
            val code = error.code
            if (code != null && error.statusCode in REFUSALS) throw CompanionException.TransferRefused(code, error.problems)
            throw error
        }
    }

    private companion object {
        const val PAGE = 50
        val REFUSALS = setOf(403, 409, 422)
        val ADMIN_ROLES = setOf("ADMIN", "OWNER")
    }
}

internal fun CloudTransferView.domain() = SessionTransfer(
    id = id,
    sessionId = sessionId,
    fromMemberId = fromMemberId,
    toMemberId = toMemberId,
    state = TransferState.of(state),
    resume = resume,
    createdAt = createdAt,
    updatedAt = updatedAt,
    expiresAt = expiresAt,
    problemCode = failure?.code,
)
