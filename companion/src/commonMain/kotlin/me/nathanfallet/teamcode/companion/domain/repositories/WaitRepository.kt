package me.nathanfallet.teamcode.companion.domain.repositories

import kotlinx.serialization.json.JsonElement
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitPage

/** What waits for the member on an account's server (`waits.v1`). Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s. */
interface WaitRepository {
    /** The member's open waits, newest first; the first page also holds the open requests of their sessions. */
    suspend fun open(accountId: String, before: String? = null): WaitPage
    /** `409 wait_closed` ([me.nathanfallet.teamcode.companion.domain.models.CompanionException.Server]) when someone answered or cancelled it first. */
    suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait
}
