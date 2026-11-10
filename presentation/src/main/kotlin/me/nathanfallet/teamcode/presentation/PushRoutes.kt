package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.notifications.*
import me.nathanfallet.teamcode.domain.team.*

internal const val PUSH_SETTINGS_PATH = "/dashboard/push"

/** The push destination of the caller's device, mounted when the server has a push channel. */
internal fun Route.pushRoutes(push: PushRegistrationService) {
    route("/api/v1/devices/current/push") {
        put {
            val input = call.jsonBody<DevicePushRequest>()
            push.register(call.principal, input.token, input.publicKey, input.platform)
            call.respond(HttpStatusCode.NoContent)
        }
        delete { push.clear(call.principal); call.respond(HttpStatusCode.NoContent) }
    }
}

private fun PushSettings.view() = PushSettingsView(provider.wire, relayUrl, firebaseProjectId, firebaseClientEmail, version, updatedAt)
private fun PushTestResult.view() = PushTestView(ok, results.map { PushTestResultView(it.status.name.lowercase(), it.error) })
private fun provider(value: String) = PushProvider.parse(value) ?: throw TeamFailure("invalid_push_settings", 400, "The provider is off, relay or firebase.")

/** Who sends the team's pushes: interactive administrators only, rechecked by the service on every call. */
internal fun Route.pushAdminRoutes(push: PushAdministrationService, publicOrigin: String) {
    val origin = publicOrigin.trimEnd('/')
    route("/api/v1/notifications/push") {
        get("/settings") { call.respond(push.settings(call.principal).view()) }
        put("/settings") {
            val input = call.jsonBody<SavePushSettingsRequest>(FORM_LIMIT)
            call.respond(push.save(call.principal, PushSettingsInput(provider(input.provider), input.relayUrl.orEmpty(), input.firebaseServiceAccount, input.version)).view())
        }
        post("/test") { call.respond(push.test(call.principal).view()) }
    }

    suspend fun ApplicationCall.settingsPage(test: PushTestResult? = null) {
        val actor = principal
        val settings = push.settings(actor)
        fun option(value: PushProvider, label: String) = """<option value="${value.wire}"${if (value == settings.provider) " selected" else ""}>${escape(label)}</option>"""
        val state = when (settings.provider) {
            PushProvider.OFF -> "<div class=\"notice\"><strong>Push notifications are off.</strong> Members' phones are not notified; choose a provider below.</div>"
            PushProvider.RELAY -> "<div class=\"notice\"><strong>Push notifications go through the TeamCode Push relay</strong> at <code>${escape(settings.relayUrl)}</code>, for the TeamCode apps of the App Store and Google Play.</div>"
            PushProvider.FIREBASE -> "<div class=\"notice\"><strong>Push notifications go through your Firebase project</strong> <code>${escape(settings.firebaseProjectId.orEmpty())}</code>, for apps you build and sign yourself.</div>"
        }
        val testResult = test?.let { result ->
            val rows = result.results.joinToString("") { r -> "<tr><td>${escape(r.status.name.lowercase())}</td><td>${escape(r.error.orEmpty())}</td></tr>" }
            """<h2>Test</h2><p>${if (result.ok) "<strong>A test notification was sent.</strong> It shows on your phone in a few seconds." else "<strong>No installation received the test.</strong>"}</p><table><thead><tr><th>Installation</th><th>Code</th></tr></thead><tbody>$rows</tbody></table>"""
        } ?: ""
        val stored = settings.firebaseProjectId?.let { " (stored: ${escape(it)}, ${escape(settings.firebaseClientEmail.orEmpty())}; leave empty to keep it)" } ?: ""
        val form = """<h2>Provider</h2>""" + help("Which provider", """<p><strong>TeamCode Push relay</strong>: for the published TeamCode apps. The relay holds their Firebase project; this server registers with it automatically and signs every request. Each notification is encrypted on this server for the phone it goes to: the relay and Firebase see a token and an encrypted blob, never a member, a session or the text.</p><p><strong>Your Firebase project</strong>: for apps you build and sign yourself with your own Firebase project. Paste the JSON key of a service account with the Firebase Cloud Messaging API Admin role. The key is encrypted with the server's key and never shown again.</p>""") +
            """<form action="$PUSH_SETTINGS_PATH" method="post">${csrf(actor)}<input type="hidden" name="version" value="${settings.version}">""" +
            """<label>Provider <select name="provider">${option(PushProvider.OFF, "Off")}${option(PushProvider.RELAY, "TeamCode Push relay (published apps)")}${option(PushProvider.FIREBASE, "Your Firebase project (apps you build)")}</select></label>""" +
            """<label>Relay URL <input name="relayUrl" maxlength="255" placeholder="${PushAdministrationService.DEFAULT_RELAY_URL}" value="${escape(settings.relayUrl)}"></label>""" +
            """<label>Firebase service account key (JSON)$stored <textarea name="firebaseServiceAccount" rows="6" maxlength="${PushAdministrationService.MAX_SERVICE_ACCOUNT_CHARS}" autocomplete="off" spellcheck="false"></textarea></label>""" +
            """<button>Save</button></form>""" +
            (if (settings.provider != PushProvider.OFF) """<form action="$PUSH_SETTINGS_PATH/test" method="post">${csrf(actor)}<button>Send a test to my phone</button></form>""" else "") +
            (settings.updatedAt?.let { "<p><small>Saved ${escape(Instant.ofEpochMilli(it).toString())}, version ${settings.version}.</small></p>" } ?: "")
        respondText(page("Push notifications", state + testResult + form), ContentType.Text.Html)
    }

    get(PUSH_SETTINGS_PATH) { call.settingsPage() }
    post(PUSH_SETTINGS_PATH) {
        val input = call.form(origin, limit = FORM_LIMIT)
        push.save(call.principal, PushSettingsInput(provider(input["provider"].orEmpty()), input["relayUrl"].orEmpty(),
            input["firebaseServiceAccount"]?.trim()?.takeIf { it.isNotEmpty() },
            input["version"]?.toIntOrNull() ?: throw TeamFailure("invalid_push_settings", 400, "Reload the page.")))
        call.respondRedirect(PUSH_SETTINGS_PATH)
    }
    post("$PUSH_SETTINGS_PATH/test") { call.form(origin); call.settingsPage(push.test(call.principal)) }
}

/** A service account key, URL-encoded, is about three times its 2.5 KB. */
private const val FORM_LIMIT = 65_536
