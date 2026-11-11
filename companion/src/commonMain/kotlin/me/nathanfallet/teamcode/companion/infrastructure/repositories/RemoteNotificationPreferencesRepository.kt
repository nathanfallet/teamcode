package me.nathanfallet.teamcode.companion.infrastructure.repositories

import me.nathanfallet.teamcode.api.models.NotificationSettingsView
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.companion.domain.models.NotificationKind
import me.nathanfallet.teamcode.companion.domain.models.PushTestOutcome
import me.nathanfallet.teamcode.companion.domain.repositories.NotificationPreferencesRepository

/** The push channel's switches (`GET`/`PUT /api/v1/notifications/settings`) and the test push (`POST /api/v1/notifications/push/test`). One request each. */
class RemoteNotificationPreferencesRepository(private val calls: AccountCalls) : NotificationPreferencesRepository {
    override suspend fun switches(accountId: String) = calls.withAccount(accountId) { it.notificationSettings().push() }

    override suspend fun set(accountId: String, kind: NotificationKind, enabled: Boolean) =
        calls.withAccount(accountId) { it.setNotificationSetting(PUSH, kind.wire, enabled).push() }

    override suspend fun test(accountId: String): PushTestOutcome = calls.withAccount(accountId) { client ->
        try {
            val answer = client.testPush()
            PushTestOutcome.Sent(answer.results.count { it.status == "sent" }, answer.results.size)
        } catch (error: TeamCodeException) {
            when (error.takeIf { it.statusCode == 409 }?.code) {
                "push_off" -> PushTestOutcome.Off
                "push_no_device" -> PushTestOutcome.NoPhone
                else -> throw error
            }
        }
    }

    private companion object { const val PUSH = "push" }

    private fun NotificationSettingsView.push(): Map<NotificationKind, Boolean> {
        val stored = settings.filter { it.channel == PUSH }.mapNotNull { setting -> NotificationKind.parse(setting.kind)?.let { it to setting.enabled } }.toMap()
        return NotificationKind.entries.associateWith { stored[it] ?: true }
    }
}
