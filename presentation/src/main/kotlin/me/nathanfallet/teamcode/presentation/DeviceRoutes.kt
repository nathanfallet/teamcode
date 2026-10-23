package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.team.*
import java.time.Instant

private fun Device.view() = DeviceView(id, memberId, name, createdAt, lastLoginAt, activeSessions, current, platform, osVersion, daemon?.view())
private val platformNames = mapOf("macos" to "macOS", "windows" to "Windows", "linux" to "Linux", "ios" to "iOS", "android" to "Android")
/** "macOS 15.1", or null until the installation reported its system. */
internal fun systemName(platform: String?, osVersion: String?): String? =
    platform?.let { (platformNames[it] ?: it) + (osVersion?.let { version -> " $version" } ?: "") }
private fun MemberSession.view() = AuthSessionView(id, memberId, name, deviceId, createdAt, expiresAt, current)
private fun devicesPath(actor: Principal, member: String) = if (actor.member.id == member) "/dashboard/devices" else "/dashboard/members/$member/devices"
private fun date(value: Long?) = value?.let { Instant.ofEpochMilli(it).toString() } ?: "Unknown (older session)"

internal fun Route.deviceRoutes(devices: DeviceService, origin: String) {
    for (base in listOf("/api/v1", "/api/v1/members/{memberId}")) {
        get("$base/devices") { call.respond(devices.devices(call.principal, call.parameters["memberId"] ?: call.principal.member.id).map { it.view() }) }
        get("$base/auth/sessions") { call.respond(devices.sessions(call.principal, call.parameters["memberId"] ?: call.principal.member.id).map { it.view() }) }
    }
    patch("/api/v1/devices/{id}") {
        val input = call.jsonBody<RenameDeviceRequest>()
        call.respond(devices.rename(call.principal, call.parameters["id"]!!, input.name).view())
    }
    delete("/api/v1/devices/{id}") { devices.revokeDevice(call.principal, call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }
    delete("/api/v1/auth/sessions/{id}") { devices.revokeSession(call.principal, call.parameters["id"]!!); call.respond(HttpStatusCode.NoContent) }

    for (base in listOf("/dashboard/devices", "/dashboard/members/{memberId}/devices")) {
        get(base) {
            val actor = call.principal
            val member = call.parameters["memberId"] ?: actor.member.id
            val path = devicesPath(actor, member)
            val rows = devices.devices(actor, member).joinToString("") { device ->
                """<tr><td>${escape(device.name)}${if (device.current) " <small>Current device</small>" else ""}<br><small>${systemName(device.platform, device.osVersion)?.let { escape(it) + " · " } ?: ""}Registered ${date(device.createdAt)}</small></td><td>${date(device.lastLoginAt)}<br><small>${device.activeSessions} active sessions</small></td><td><form method="post" action="$path/${escape(device.id)}/rename">${csrf(actor)}<label>Device name <input name="name" value="${escape(device.name)}" maxlength="120" required></label><button>Rename</button></form><form method="post" action="$path/${escape(device.id)}/revoke">${csrf(actor)}<button>Revoke and remove device</button></form></td></tr>"""
            }
            val sessions = devices.sessions(actor, member).joinToString("") { session ->
                """<tr><td>${escape(session.name)}${if (session.current) " <small>Current session</small>" else ""}<br><small>${if (session.deviceId == null) "Browser or older client" else "Registered device"}</small></td><td>${date(session.createdAt)}</td><td>${date(session.expiresAt)}</td><td><form method="post" action="$path/sessions/${escape(session.id)}/revoke">${csrf(actor)}<button>Revoke session</button></form></td></tr>"""
            }
            call.respondText(call.page("Devices and sessions", """<details class='help'><summary>How this works</summary><p>Removing a device signs out all its sessions. A new password login can register that installation again. Quotas are shared across every device of the member; revocation and reconnecting do not reset usage.</p></details><h2>Registered CLI devices</h2><table><thead><tr><th>Device</th><th>Last password login</th><th>Manage</th></tr></thead><tbody>$rows</tbody></table>${if (rows.isEmpty()) "<div class='empty'><strong>No devices registered. Sign in with the current TeamCode CLI to register an installation.</strong></div>" else ""}<h2>Active sessions</h2><p>Browser sessions and older CLI sessions can be revoked individually. Times are UTC. Credentials are never displayed.</p><table><thead><tr><th>Session</th><th>Created</th><th>Expires</th><th>Manage</th></tr></thead><tbody>$sessions</tbody></table>"""), ContentType.Text.Html)
        }
        post("$base/{id}/rename") {
            val input = call.form(origin.trimEnd('/'))
            val saved = devices.rename(call.principal, call.parameters["id"]!!, input["name"].orEmpty())
            call.respondRedirect(devicesPath(call.principal, saved.memberId))
        }
        post("$base/{id}/revoke") {
            call.form(origin.trimEnd('/'))
            val member = devices.revokeDevice(call.principal, call.parameters["id"]!!)
            call.respondRedirect(devicesPath(call.principal, member))
        }
        post("$base/sessions/{id}/revoke") {
            call.form(origin.trimEnd('/'))
            val member = devices.revokeSession(call.principal, call.parameters["id"]!!)
            if (call.parameters["id"] == call.principal.tokenId) {
                call.sessionCookie("", origin, 0)
                call.respondRedirect("/dashboard/login")
            } else call.respondRedirect(devicesPath(call.principal, member))
        }
    }
}
