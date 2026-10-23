package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.SessionTransfer
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.models.TransferPage
import me.nathanfallet.teamcode.companion.domain.models.TransferRequest

/**
 * Session transfers of an account's server (`cloud.transfer.v1`). Failures are
 * [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s; a refusal of the recipient is
 * [me.nathanfallet.teamcode.companion.domain.models.CompanionException.TransferRefused] with its problem codes.
 */
interface TransferRepository {
    /** [idempotencyKey]: a retry with the same key and body returns the transfer the first attempt made. */
    suspend fun transfer(accountId: String, sessionId: String, request: TransferRequest, idempotencyKey: String): SessionTransfer
    /** Newest first, at most 50 per page; [before] is the previous page's cursor. */
    suspend fun transfers(accountId: String, direction: TransferDirection, before: String?): TransferPage
    /** [resume] null keeps what the offer asked for. */
    suspend fun accept(accountId: String, transferId: String, resume: Boolean?): SessionTransfer
    suspend fun decline(accountId: String, transferId: String): SessionTransfer
    suspend fun cancel(accountId: String, transferId: String): SessionTransfer
    /** The members a session can be given to (`cloud.transfer.recipients.v1`), for whoever may transfer. */
    suspend fun recipients(accountId: String): List<TeamMember>
    /** Every member of the team; the server answers this to administrators only. */
    suspend fun members(accountId: String): List<TeamMember>
}
