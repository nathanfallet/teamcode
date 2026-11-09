package me.nathanfallet.teamcode.presentation

import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.notifications.*

private fun NotificationSettings.view() = NotificationSettingsView(channels, settings.map { NotificationSettingView(it.channel, it.kind.wire, it.enabled) })

/** The caller's own notification switches. */
internal fun Route.notificationRoutes(notifications: NotificationService) {
    route("/api/v1/notifications/settings") {
        get { call.respond(notifications.settings(call.principal).view()) }
        put {
            val input = call.jsonBody<NotificationSettingRequest>()
            call.respond(notifications.set(call.principal, input.channel, input.kind, input.enabled).view())
        }
    }
}
