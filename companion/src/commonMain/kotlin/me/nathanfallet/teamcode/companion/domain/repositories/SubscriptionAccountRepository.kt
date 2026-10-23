package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.EnrollmentAccess
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccountType
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionSignIn

/**
 * The subscription accounts of an account's server, and adding one with a provider sign-in made on this device, as
 * `teamcode accounts add` does. Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s.
 */
interface SubscriptionAccountRepository {
    /** Every kind of account, in the CLI's order, the unavailable ones included. */
    val types: List<SubscriptionAccountType>
    /** Asks the server, with a call every member may make (an older server: by the member's role). */
    suspend fun access(accountId: String): EnrollmentAccess
    /** The pool; the server answers this to administrators only. */
    suspend fun accounts(accountId: String): List<SubscriptionAccount>
    /** Draws a new sign-in attempt for an available [type]; no network call. */
    fun startSignIn(accountId: String, type: SubscriptionAccountType): SubscriptionSignIn
}
