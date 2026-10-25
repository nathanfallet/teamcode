package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*

/** The daemon channel and the owner's view of a device's folders (`cloud.sessions.remote.daemon.v1`). */
class DeviceDaemonChannel(val daemon: DeviceDaemonUseCase, val sessions: CloudSessionService)

internal fun DaemonPresence.view() = DaemonPresenceView(online, lastSeenAt, version, features)
private fun DeviceFolder.view() = DeviceFolderView(id, name, label)
private fun DaemonOrder.view() = DaemonOrderView(sessionId, folderId, directoryName, agentKind, permissionMode.wire, state.wire, requestedAt, resumeSessionId)
private fun DaemonListing.view() = DaemonListingView(requestId, folderId, limit, before)
private fun ResumableSession.view() = ResumableSessionView(id, title, lastActiveAt, turns)
private val sessionIdentifier = Regex("[a-zA-Z0-9-]{1,36}")

/**
 * Not listed in [credentialAction]: every route here takes an interactive login. The daemon's routes also require that
 * login to be bound to a device (`422 daemon_requires_device` otherwise), and act on that device only.
 */
internal fun Route.deviceDaemonRoutes(channel: DeviceDaemonChannel) {
    val daemon = channel.daemon
    route("/api/v1/devices") {
        post("/current/daemon/poll") {
            val input = call.jsonBody<DaemonPollRequest>()
            val poll = daemon.poll(call.principal, input.waitMillis, input.version, input.features)
            call.respond(DaemonPollView(poll.orders.map { it.view() }, DeviceDaemonRules.ONLINE_TTL_MILLIS, DeviceDaemonRules.MAX_WAIT_MILLIS, poll.listings.map { it.view() }))
        }
        // Titles are never logged: the body is read here and handed to the use case, and failures name only a code.
        post("/current/daemon/listings/{id}") {
            val id = call.parameters["id"]!!.takeIf(sessionIdentifier::matches) ?: throw TeamFailure("listing_not_found", 404, "Nobody waits for this listing any more.")
            val input = call.jsonBody<DaemonListingAnswerRequest>(64 * 1024)
            daemon.answer(call.principal, id, ResumableAnswer(input.sessions.map { ResumableSession(it.id, it.title, it.lastActiveAt, it.turns) }, input.nextBefore, input.refused))
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/current/daemon") { daemon.offline(call.principal); call.respond(HttpStatusCode.NoContent) }
        put("/current/folders") {
            val input = call.jsonBody<PublishDeviceFoldersRequest>(128 * 1024)
            val folders = daemon.publish(call.principal, input.folders.map { DeviceFolder(it.id, it.name, it.label) })
            call.respond(folders.map { it.view() })
        }
        post("/current/daemon/sessions/{id}/refuse") {
            val id = call.parameters["id"]!!.takeIf(sessionIdentifier::matches) ?: throw TeamFailure("not_found", 404, "Not found.")
            val input = call.jsonBody<RefuseDaemonOrderRequest>()
            daemon.device(call.principal)
            call.respond(channel.sessions.refuseHost(call.principal, id, input.reason).view())
        }
        get("/{id}/folders/{folderId}/resumable") {
            val folderId = call.parameters["folderId"]!!.takeIf(DeviceDaemonRules::validFolderId) ?: throw TeamFailure("folder_not_published", 422, "This device does not offer this folder.")
            val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: throw TeamFailure("invalid_limit", 400, "Use a limit from 1 to ${DeviceDaemonRules.MAX_LISTING_LIMIT}.") }
                ?: DeviceDaemonRules.DEFAULT_LISTING_LIMIT
            val before = call.request.queryParameters["before"]?.let { it.toLongOrNull() ?: throw TeamFailure("invalid_cursor", 400, "Use the nextBefore of the previous page.") }
            val answer = daemon.resumable(call.principal, call.parameters["id"]!!, folderId, limit, before)
            call.respond(ResumableSessionsView(answer.sessions.map { it.view() }, answer.nextBefore))
        }
        get("/{id}/folders") {
            val device = daemon.owned(call.principal, call.parameters["id"]!!)
            call.respond(DeviceFoldersView(device.id, device.daemon?.view(), device.folders.map { it.view() }))
        }
    }
}
