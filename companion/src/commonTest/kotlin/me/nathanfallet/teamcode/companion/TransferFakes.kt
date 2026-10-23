package me.nathanfallet.teamcode.companion

import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.SessionTransfer
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.models.TransferPage
import me.nathanfallet.teamcode.companion.domain.models.TransferRequest
import me.nathanfallet.teamcode.companion.domain.models.TransferState
import me.nathanfallet.teamcode.companion.domain.repositories.TransferRepository

fun admin(id: String = "a1", memberId: String = "m1") = account(id, memberId = memberId).copy(role = "ADMIN")

fun Account.asMember() = copy(role = "MEMBER")

fun transfer(id: String, state: TransferState = TransferState.OFFERED, from: String = "m1", to: String = "m2", sessionId: String = "s1", resume: Boolean = false) =
    SessionTransfer(id, sessionId, from, to, state, resume, createdAt = 1_000, updatedAt = 1_000, expiresAt = 2_000_000, problemCode = null)

/** Transfers answered by the lambdas below; every call is recorded in [calls]. */
class FakeTransferRepository : TransferRepository {
    val calls = mutableListOf<String>()
    var members = listOf(TeamMember("m2", "grace", administrator = false, available = true), TeamMember("m1", "ada", administrator = true, available = true))
    var transferResult: (TransferRequest) -> SessionTransfer = { transfer("t1", if (it.resume || it.force) TransferState.ACCEPTED else TransferState.OFFERED, to = it.toMemberId) }
    var pages: (TransferDirection, String?) -> TransferPage = { _, _ -> TransferPage(emptyList(), null) }
    var decision: (String, String) -> SessionTransfer = { action, id ->
        transfer(id, when (action) { "accept" -> TransferState.ACCEPTED; "decline" -> TransferState.DECLINED; else -> TransferState.CANCELLED })
    }

    override suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer {
        calls += "transfer $sessionId ${request.toMemberId} resume=${request.resume} force=${request.force} $idempotencyKey"
        return transferResult(request)
    }
    override suspend fun transfers(accountId: String, direction: TransferDirection, before: String?): TransferPage {
        calls += "list ${direction.wire} $before"
        return pages(direction, before)
    }
    override suspend fun accept(accountId: String, transferId: String, resume: Boolean?) = decision("accept", transferId).also { calls += "accept $transferId $resume" }
    override suspend fun decline(accountId: String, transferId: String) = decision("decline", transferId).also { calls += "decline $transferId" }
    override suspend fun cancel(accountId: String, transferId: String) = decision("cancel", transferId).also { calls += "cancel $transferId" }
    override suspend fun members(accountId: String): List<TeamMember> { calls += "members"; return members }
    var recipients = listOf(TeamMember("m2", "grace", administrator = false, available = true), TeamMember("m1", "ada", administrator = true, available = true))
    override suspend fun recipients(accountId: String): List<TeamMember> { calls += "recipients"; return recipients }
}
