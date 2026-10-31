package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.request.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.longOrNull
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.waits.*

private val waitId = Regex("[a-zA-Z0-9-]{1,36}")
private fun ApplicationCall.waitId() = parameters["id"]!!.also { if (!waitId.matches(it)) throw TeamFailure("not_found", 404, "Wait not found.") }

private fun invalid(message: String) = TeamFailure("invalid_wait", 400, message)
private fun <T> wire(value: String, parse: (String) -> T?, field: String, allowed: List<String>): T =
    parse(value) ?: throw invalid("Use $field ${allowed.joinToString(", ")}.")

internal fun WaitInput.domain() = WaitDefinition(
    sessionId, title, body, assigneeId,
    refs.map { WaitRef(it.kind, it.id, it.url, it.title) },
    fields.map { f ->
        WaitField(
            f.id, wire(f.type, FieldType::parse, "type", Waits.fieldTypes), f.label, f.help, f.required, f.default,
            f.options.map { WaitOption(it.value, it.label, it.description) }, f.multiple, f.allowOther, f.maxLength, f.oneTime,
        )
    },
    actions.map { WaitAction(it.id, it.label, it.requires, it.style) },
    wire(resolver, WaitResolver::parse, "resolver", Waits.resolvers),
    schedule = schedule(),
)

/** The settings of a wait for a time or an event; null when the request has none (a human wait). */
private fun WaitInput.schedule(): WaitSchedule? {
    if (at == null && after == null && source == null && timeoutSeconds == null && onTimeout == null && resumeMessage == null) return null
    val instant = at?.let { value ->
        if (value is JsonNull) null
        else if (value.isString) WaitRules.parseInstant(value.content) ?: throw invalid("at is an ISO-8601 instant in UTC (2026-11-02T09:00:00Z) or epoch milliseconds.")
        else value.longOrNull ?: throw invalid("at is an ISO-8601 instant in UTC (2026-11-02T09:00:00Z) or epoch milliseconds.")
    }
    return WaitSchedule(
        instant, after, source?.let { wire(it, EventSource::parse, "source", Waits.eventSources) }, timeoutSeconds,
        onTimeout?.let { wire(it, TimeoutAction::parse, "onTimeout", Waits.onTimeouts) }, resumeMessage,
    )
}

private fun WaitField.view() = WaitFieldView(id, type.wire, label, help, required, default, options.map { WaitOptionView(it.value, it.label, it.description) }, multiple, allowOther, maxLength, oneTime)

/** [eventUrl] is the event address, given by the response that creates an `event` wait and by no other: the server keeps only the hash of its secret. */
internal fun Wait.view(eventUrl: String? = null) = WaitView(
    id, source.wire, kind.wire, resolver.wire, status.wire, sessionId, sessionName, requestId, assigneeId, assigneeName, createdById, title, body,
    refs.map { WaitRefView(it.kind, it.id, it.url, it.title) }, fields.map { it.view() }, actions.map { WaitActionView(it.id, it.label, it.requires, it.style) },
    answer?.let { WaitAnswerView(it.action, it.values, it.authorId, it.answeredAt) }, createdAt, resolvedAt,
    dueAt = timer?.dueAt?.takeIf { resolver == WaitResolver.TIME }, timeoutAt = timer?.dueAt?.takeIf { resolver == WaitResolver.EVENT },
    eventSource = timer?.eventSource?.wire, onTimeout = timer?.onTimeout?.wire, resumeMessage = timer?.resumeMessage, eventUrl = eventUrl,
)

/** A wait with its fields, refs and an answer of at most 128 KiB, encoded. */
private const val WAIT_BYTES = 256 * 1024

/** Members read and answer what is asked of them; the service decides who may do what (docs/waits.md). */
internal fun Route.waitRoutes(waits: WaitService, publicOrigin: String) {
    route("/api/v1/waits") {
        get {
            val query = call.request.queryParameters
            val page = waits.list(call.principal, query["assignee"], query["status"] ?: "open", query["before"], query["limit"]?.toIntOrNull() ?: 50, query["session"]?.also { if (!waitId.matches(it)) throw invalid("Use a session identifier.") }, query["resolver"]?.also { if (it !in Waits.resolverFilters) throw TeamFailure("invalid_request", 400, "Use resolver ${Waits.resolverFilters.joinToString(", ")}.") })
            call.respond(WaitPageView(page.waits.map { it.view() }, page.nextBefore))
        }
        post {
            val input = call.jsonBody<CreateWaitRequest>(WAIT_BYTES)
            val change = waits.create(call.principal, input.wait.domain(), call.idempotencyKey(required = false))
            if (!change.changed) call.response.header(CloudHeaders.IDEMPOTENCY_REPLAYED, "true")
            // The secret of an event wait is in this answer and nowhere else; the address carries it in the query, for a CI that cannot set a header.
            val eventUrl = change.secret?.let { "${publicOrigin.trimEnd('/')}/api/v1/waits/${change.wait.id}/event?token=$it" }
            call.respond(if (change.changed) HttpStatusCode.Created else HttpStatusCode.OK, change.wait.view(eventUrl))
        }
        get("/{id}") { call.respond(waits.get(call.principal, call.waitId()).view()) }
        post("/{id}/answer") {
            val input = call.jsonBody<AnswerWaitRequest>(WAIT_BYTES)
            call.respond(waits.answer(call.principal, call.waitId(), input.action, input.values).view())
        }
        post("/{id}/cancel") { call.respond(waits.cancel(call.principal, call.waitId()).view()) }
    }
}

/** Header an event caller may use instead of the `token` query parameter. */
internal const val WAIT_EVENT_HEADER = "TeamCode-Wait-Token"

/** The address of an event wait: `POST /api/v1/waits/{id}/event`. Its secret is the only proof, so it is outside the sign-in. */
internal val waitEventPath = Regex("/api/v1/waits/[a-zA-Z0-9-]{1,36}/event")

/** The event address (no login: the wait's secret, in the `token` query parameter or the [WAIT_EVENT_HEADER] header, is the proof; the service checks it). */
internal fun Route.waitEventRoutes(waits: WaitService) {
    post("/api/v1/waits/{id}/event") {
        // A larger body is `413 payload_too_large`, answered by the gateway.
        val body = call.receiveChannel().readBounded(WaitRules.MAX_EVENT_BYTES)
        val token = call.request.headers[WAIT_EVENT_HEADER] ?: call.request.queryParameters["token"]
        waits.receiveEvent(call.waitId(), token, body)
        call.respond(HttpStatusCode.Accepted, WaitEventReceivedView(call.parameters["id"]!!, WaitStatus.RESOLVED.wire))
    }
}
