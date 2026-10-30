package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
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
            f.options.map { WaitOption(it.value, it.label, it.description) }, f.multiple, f.allowOther, f.maxLength,
        )
    },
    actions.map { WaitAction(it.id, it.label, it.requires, it.style) },
    wire(resolver, WaitResolver::parse, "resolver", Waits.resolvers),
)

private fun WaitField.view() = WaitFieldView(id, type.wire, label, help, required, default, options.map { WaitOptionView(it.value, it.label, it.description) }, multiple, allowOther, maxLength)

internal fun Wait.view() = WaitView(
    id, source.wire, kind.wire, resolver.wire, status.wire, sessionId, sessionName, requestId, assigneeId, assigneeName, createdById, title, body,
    refs.map { WaitRefView(it.kind, it.id, it.url, it.title) }, fields.map { it.view() }, actions.map { WaitActionView(it.id, it.label, it.requires, it.style) },
    answer?.let { WaitAnswerView(it.action, it.values, it.authorId, it.answeredAt) }, createdAt, resolvedAt,
)

/** A wait with its fields, refs and an answer of at most 128 KiB, encoded. */
private const val WAIT_BYTES = 256 * 1024

/** Members read and answer what is asked of them; the service decides who may do what (docs/waits.md). */
internal fun Route.waitRoutes(waits: WaitService) {
    route("/api/v1/waits") {
        get {
            val query = call.request.queryParameters
            val page = waits.list(call.principal, query["assignee"], query["status"] ?: "open", query["before"], query["limit"]?.toIntOrNull() ?: 50, query["session"]?.also { if (!waitId.matches(it)) throw invalid("Use a session identifier.") })
            call.respond(WaitPageView(page.waits.map { it.view() }, page.nextBefore))
        }
        post {
            val input = call.jsonBody<CreateWaitRequest>(WAIT_BYTES)
            val change = waits.create(call.principal, input.wait.domain(), call.idempotencyKey(required = false))
            if (!change.changed) call.response.header(CloudHeaders.IDEMPOTENCY_REPLAYED, "true")
            call.respond(if (change.changed) HttpStatusCode.Created else HttpStatusCode.OK, change.wait.view())
        }
        get("/{id}") { call.respond(waits.get(call.principal, call.waitId()).view()) }
        post("/{id}/answer") {
            val input = call.jsonBody<AnswerWaitRequest>(WAIT_BYTES)
            call.respond(waits.answer(call.principal, call.waitId(), input.action, input.values).view())
        }
        post("/{id}/cancel") { call.respond(waits.cancel(call.principal, call.waitId()).view()) }
    }
}
