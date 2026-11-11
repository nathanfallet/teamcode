package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.NotificationKind
import me.nathanfallet.teamcode.companion.domain.models.PushTestOutcome

/**
 * The member's notification switches of the push channel on an account's server (`/notifications/settings`), and the
 * test push. Failures are [me.nathanfallet.teamcode.companion.domain.models.CompanionException]s.
 */
interface NotificationPreferencesRepository {
    /** One switch per known kind; a kind the server does not list stays on. */
    suspend fun switches(accountId: String): Map<NotificationKind, Boolean>
    /** Saves one switch and answers all of them, as the server now holds them. */
    suspend fun set(accountId: String, kind: NotificationKind, enabled: Boolean): Map<NotificationKind, Boolean>
    suspend fun test(accountId: String): PushTestOutcome
}
