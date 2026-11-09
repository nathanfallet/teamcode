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

/** The push destination of the caller's device (`notifications.push.v1`). */
internal fun ApiCatalog.pushOperations() {
    put("/api/v1/devices/current/push") {
        operationId = "registerDevicePush"; tag(ApiTags.NOTIFICATIONS)
        summary = "Register this device for push notifications"
        description = "The iOS or Android app sends its FCM registration token and its P-256 public key at every start and when FCM rotates the token; " +
            "a new value replaces the previous one. Every notification is encrypted for that key, so the push provider and the relay see only a blob. " +
            "The device of the calling session (an app login), interactive credentials only; only on a server with the `push` channel. See docs/notifications.md."
        body<DevicePushRequest>("The token and the public key (SubjectPublicKeyInfo, DER, base64).")
        responses {
            empty("Registered.")
            badRequest("A malformed token (`invalid_push_token`) or key (`invalid_push_key`), or a desktop device (`push_unsupported`).")
            conflict("The session is not tied to a registered device: sign in again with the app (`device_required`).")
        }
    }
    delete("/api/v1/devices/current/push") {
        operationId = "unregisterDevicePush"; tag(ApiTags.NOTIFICATIONS)
        summary = "Stop push notifications on this device"
        description = "Sent by the app before it signs out, or when the member turns notifications off in the system settings. Interactive credentials only."
        responses { empty("Cleared."); conflict("The session is not tied to a registered device (`device_required`).") }
    }
}

private const val PUSH_ADMIN_TEXT = "Interactive administrators only; no service key reaches these routes."

/** Who sends the team's pushes (administrators). */
internal fun ApiCatalog.pushAdminOperations() {
    get("/api/v1/notifications/push/settings") {
        operationId = "getPushSettings"; tag(ApiTags.NOTIFICATIONS)
        summary = "Read the push settings"
        description = "The provider (`off`, `relay` or `firebase`), the relay URL, and which Firebase service account is stored (never its key). $PUSH_ADMIN_TEXT"
        responses { ok<PushSettingsView>("The settings; `off` with version 0 before the first save.") }
    }
    put("/api/v1/notifications/push/settings") {
        operationId = "savePushSettings"; tag(ApiTags.NOTIFICATIONS)
        summary = "Save the push settings"
        description = "`relay` sends through the TeamCode Push relay (the published apps); `firebase` through the team's own Firebase project (apps it builds " +
            "itself), with a service account JSON key sealed with the server's key. Leave `firebaseServiceAccount` null to keep the stored key; any provider " +
            "but `firebase` removes it. See docs/notifications.md. $PUSH_ADMIN_TEXT"
        body<SavePushSettingsRequest>("The provider, the relay URL (default https://push.teamcode.nathanfallet.me), the key, and the version read.")
        responses {
            ok<PushSettingsView>("Saved.")
            badRequest("An unknown provider, a relay URL that is not https without a path, or a key that is not a Google service account JSON (`invalid_push_settings`, `push_relay_host_not_allowed`), or Firebase without a key (`push_firebase_credentials_required`).")
            conflict("The settings changed meanwhile (`push_settings_conflict`).")
        }
    }
    post("/api/v1/notifications/push/test") {
        operationId = "testPush"; tag(ApiTags.NOTIFICATIONS)
        summary = "Send a test push"
        description = "Sends a test notification to the caller's own phones through the provider in force, at once. Each installation reports `sent`, " +
            "`unregistered` (the token is then forgotten), `retry` or `rejected`, with a short code, never a provider body. $PUSH_ADMIN_TEXT"
        responses {
            ok<PushTestView>("One result per installation.")
            conflict("Push is off (`push_off`), or the caller has no phone registered (`push_no_device`).")
        }
    }
}
