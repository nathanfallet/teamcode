package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Wire names of notifications (`notifications.v1`, docs/notifications.md). A client must tolerate an unknown channel or kind from a newer server. */
object Notifications {
    const val CAPABILITY = "notifications.v1"
    /** The server has the push channel: the apps register their device with `PUT /api/v1/devices/current/push`. */
    const val PUSH_CAPABILITY = "notifications.push.v1"
    /** Any member may send `POST /api/v1/notifications/push/test` to their own phones (before it, administrators only). */
    const val PUSH_TEST_CAPABILITY = "notifications.push.test.v1"
    /** What a member is told about, one switch per channel. */
    val kinds = listOf("waiting", "turn_completed", "turn_failed", "session_failed")
}

/** A member's switch for one kind on one channel. */
@Serializable
data class NotificationSettingView(val channel: String, val kind: String, val enabled: Boolean)

/** `GET /api/v1/notifications/settings`: the channels this server offers (`push`, later `slack`, `discord`) and every switch in force, defaults filled. */
@Serializable
data class NotificationSettingsView(val channels: List<String>, val settings: List<NotificationSettingView>)

/** `PUT /api/v1/notifications/settings`: turns one kind on or off on one channel for the caller. */
@Serializable
data class NotificationSettingRequest(val channel: String, val kind: String, val enabled: Boolean)
