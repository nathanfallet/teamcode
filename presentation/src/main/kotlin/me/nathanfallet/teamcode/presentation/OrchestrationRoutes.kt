package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.TimeoutAction

private fun ApplicationCall.childId() = parameters["id"]!!.also { if (!CloudIdentifier.valid(it)) throw TeamFailure("not_found", 404, "No child session of this session has this id.") }
private fun ApplicationCall.intQuery(name: String, default: Int) = request.queryParameters[name]?.let { it.toIntOrNull() ?: throw TeamFailure("invalid_limit", 400, "$name is a whole number.") } ?: default

/**
 * Session orchestration (docs/orchestration.md): the calling session's child sessions, for its execution credential's
 * `sessions:children` scope. The service confines every call to the session the credential is bound to; routes decode and encode.
 */
internal fun Route.orchestrationRoutes(orchestration: OrchestrationService) {
    route("/api/v1/cloud/children") {
        get {
            val page = orchestration.list(call.principal, call.request.queryParameters["before"], call.intQuery("limit", 20))
            call.respond(CloudSessionPageView(page.sessions.map { it.view() }, page.nextBeforeId))
        }
        post {
            val key = call.idempotencyKey(required = true)!!
            // Room for an initial message of 32,000 characters.
            val input = call.jsonBody<SpawnChildSessionRequest>(256 * 1024)
            if (input.agent?.kind != null) throw TeamFailure("invalid_session", 400, "A child takes its agent from its profile.")
            val canonical = Serialization.json.encodeToString(input.copy(labels = input.labels.toSortedMap()))
            val request = ChildSessionRequest(input.name, input.profileId, input.agent?.model, input.agent?.effort, input.labels, input.initialMessage)
            when (val outcome = orchestration.spawn(call.principal, request, key, canonical)) {
                is CloudCreateOutcome.Created -> call.respond(HttpStatusCode.Accepted, outcome.session.view())
                is CloudCreateOutcome.Replayed -> {
                    call.response.header(CloudHeaders.IDEMPOTENCY_REPLAYED, "true")
                    call.respond(HttpStatusCode.Accepted, outcome.session.view())
                }
            }
        }
        get("/{id}") { call.respond(orchestration.get(call.principal, call.childId()).view()) }
        post("/{id}/messages") {
            val input = call.jsonBody<ChildMessageRequest>(256 * 1024)
            // Without a key the message is sent once per request; the MCP tool sends a fresh key with each call.
            val key = call.idempotencyKey(required = false) ?: UUID.randomUUID().toString()
            call.respondInput(orchestration.send(call.principal, call.childId(), input.text, key))
        }
        get("/{id}/events") {
            val before = call.request.queryParameters["before"]?.let { it.toLongOrNull() ?: throw TeamFailure("invalid_cursor", 400, "before is an event sequence.") }
            call.respond(orchestration.transcript(call.principal, call.childId(), before, call.intQuery("limit", 20)).view())
        }
        post("/{id}/interrupt") { call.respondInput(orchestration.interrupt(call.principal, call.childId(), call.idempotencyKey(required = false))) }
        post("/{id}/terminate") { call.respond(HttpStatusCode.Accepted, orchestration.terminate(call.principal, call.childId()).view()) }
        post("/{id}/wait") {
            val input = if (call.request.contentType().withoutParameters() == ContentType.Application.Json) call.jsonBody<ChildWaitRequest>(16 * 1024) else ChildWaitRequest()
            val onTimeout = input.onTimeout?.let { TimeoutAction.parse(it) ?: throw TeamFailure("invalid_wait", 400, "onTimeout is wake or turn_failed.") }
            val change = orchestration.waitFor(call.principal, call.childId(), input.title, input.timeoutSeconds, onTimeout, input.resumeMessage, call.idempotencyKey(required = false))
            if (!change.changed) call.response.header(CloudHeaders.IDEMPOTENCY_REPLAYED, "true")
            call.respond(if (change.changed) HttpStatusCode.Created else HttpStatusCode.OK, change.wait.view())
        }
    }
}
