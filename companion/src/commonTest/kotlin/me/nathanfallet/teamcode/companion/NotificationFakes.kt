package me.nathanfallet.teamcode.companion

import me.nathanfallet.teamcode.companion.domain.models.NotificationKind
import me.nathanfallet.teamcode.companion.domain.models.PushTestOutcome
import me.nathanfallet.teamcode.companion.domain.repositories.NotificationPreferencesRepository

/** The member's switches held like the server does (every kind on until turned off); calls are recorded in [calls]. */
class FakeNotificationPreferencesRepository : NotificationPreferencesRepository {
    val calls = mutableListOf<String>()
    val stored = NotificationKind.entries.associateWith { true }.toMutableMap()
    var failure: Exception? = null
    var outcome: PushTestOutcome = PushTestOutcome.Sent(1, 1)

    override suspend fun switches(accountId: String): Map<NotificationKind, Boolean> {
        calls += "switches $accountId"
        failure?.let { throw it }
        return stored.toMap()
    }

    override suspend fun set(accountId: String, kind: NotificationKind, enabled: Boolean): Map<NotificationKind, Boolean> {
        calls += "set $accountId ${kind.wire} $enabled"
        failure?.let { throw it }
        stored[kind] = enabled
        return stored.toMap()
    }

    override suspend fun test(accountId: String): PushTestOutcome {
        calls += "test $accountId"
        failure?.let { throw it }
        return outcome
    }
}
