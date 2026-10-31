package me.nathanfallet.teamcode.companion.domain.repositories

import kotlinx.serialization.json.JsonElement
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitPage
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus

/** What waits for the member on an account's server (`waits.v1`). Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s. */
interface WaitRepository {
    /**
     * The member's waits in [status], newest first, from the page after [before]; the first page of the open ones also
     * holds the open requests of their sessions.
     */
    suspend fun list(accountId: String, status: WaitStatus, before: String? = null): WaitPage
    /** `409 wait_closed` ([me.nathanfallet.teamcode.companion.domain.models.CompanionException.Server]) when someone answered or cancelled it first. */
    suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait

    /**
     * The open waits that resolve by themselves (a time, a webhook), soonest first: at most one page of each kind, since they wait
     * for nobody and a session holds few. Not part of [list], which is what waits for the member. A repository without them lists none.
     */
    suspend fun inProgress(accountId: String): List<Wait> = emptyList()

    /** Withdraws an open wait of the member's (an in-progress one they no longer need). `409 wait_closed` when it ended first. */
    suspend fun cancel(accountId: String, waitId: String): Wait = throw UnsupportedOperationException("Withdrawing a wait is not supported here.")
}
