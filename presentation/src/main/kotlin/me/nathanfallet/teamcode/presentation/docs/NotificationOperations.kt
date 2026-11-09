package me.nathanfallet.teamcode.presentation.docs

import me.nathanfallet.teamcode.api.models.*

private const val NOTIFICATION_TEXT = "A member is notified when they are asked something (`waiting`), when the agent of their session finishes its turn " +
    "(`turn_completed`) or fails it (`turn_failed`), and when their session fails (`session_failed`), on every channel the server offers and that can reach them. " +
    "Every kind is on until the member turns it off. Interactive credentials only: a service key has no person to notify. See docs/notifications.md."

/** A member's notification switches (`notifications.v1`). */
internal fun ApiCatalog.notificationOperations() {
    get("/api/v1/notifications/settings") {
        operationId = "getNotificationSettings"; tag(ApiTags.NOTIFICATIONS)
        summary = "Read your notification settings"
        description = "The channels this server offers (`push`, later `slack` and `discord`; empty when none is configured) and one switch per channel and kind. $NOTIFICATION_TEXT"
        responses { ok<NotificationSettingsView>("The switches in force.") }
    }
    put("/api/v1/notifications/settings") {
        operationId = "setNotificationSetting"; tag(ApiTags.NOTIFICATIONS)
        summary = "Turn a notification on or off"
        description = "One kind on one channel, for the caller. $NOTIFICATION_TEXT"
        body<NotificationSettingRequest>("The channel, the kind and the new state.")
        responses {
            ok<NotificationSettingsView>("The switches in force.")
            badRequest("A channel this server does not offer, or an unknown kind (`invalid_notification_setting`).")
        }
    }
}
