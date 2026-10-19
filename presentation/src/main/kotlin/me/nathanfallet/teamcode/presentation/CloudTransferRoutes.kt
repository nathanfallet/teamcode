package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

private fun Attribution.view() = CloudAttributionView(initiatorId, credentialId, effectiveMemberId)
internal fun CloudTransfer.view() = CloudTransferView(
    id = id, sessionId = sessionId, fromMemberId = fromMemberId, toMemberId = toMemberId, state = state.wire,
    initiatedBy = initiatedBy.view(), decidedBy = decidedBy?.view(), resume = resume, force = force, pendingInputs = pendingInputs.wire,
    createdAt = createdAt, updatedAt = updatedAt, expiresAt = expiresAt, decidedAt = decidedAt, completedAt = completedAt,
    atEpoch = atEpoch, eventSeq = eventSeq, failure = failure?.view(),
)

private fun ApplicationCall.pathId(name: String) = parameters[name]!!.also { if (!CloudIdentifier.valid(it)) throw TeamFailure("not_found", 404, "Not found.") }
private suspend inline fun <reified T : Any> ApplicationCall.optionalJson(default: T): T =
    if (request.contentType().withoutParameters() == ContentType.Application.Json) jsonBody<T>() else default

/** Ownership transfers (`cloud.transfer.v1`); every route is interactive-only like the other cloud routes. */
internal fun Route.cloudTransferRoutes(transfers: CloudTransferService) {
    route("/api/v1/cloud") {
        post("/sessions/{id}/transfer") {
            val key = call.idempotencyKey(required = true)!!
            val onBehalfOf = call.onBehalfOf()
            val sessionId = call.pathId("id")
            val input = call.jsonBody<CreateCloudTransferRequest>()
            // The digest covers the session, the body and the effective member, like a creation's.
            val canonical = "POST /cloud/sessions/$sessionId/transfer\n" + Serialization.json.encodeToString(input) + "\n" + (onBehalfOf ?: call.principal.member.id)
            val outcome = transfers.request(call.principal, onBehalfOf, sessionId, CloudTransferRequest(input.toMemberId, input.resume, input.force),
                IdempotencyRequest("POST /cloud/sessions/{id}/transfer", key, sha256(canonical)))
            if (outcome is CloudTransferOutcome.Replayed) call.response.header(CloudHeaders.IDEMPOTENCY_REPLAYED, "true")
            call.respond(HttpStatusCode.Accepted, outcome.transfer.view())
        }
        get("/transfers") {
            val q = call.request.queryParameters
            fun single(name: String) = q.getAll(name)?.let { it.singleOrNull() ?: throw TeamFailure("invalid_query", 400, "Send $name at most once.") }
            val direction = when (single("direction")) {
                null, "all" -> CloudTransferDirection.BOTH
                "incoming" -> CloudTransferDirection.INCOMING
                "outgoing" -> CloudTransferDirection.OUTGOING
                else -> throw TeamFailure("invalid_query", 400, "Use direction incoming, outgoing or all.")
            }
            val team = when (single("scope")) { null, "mine" -> false; "team" -> true; else -> throw TeamFailure("invalid_query", 400, "Use scope mine or team.") }
            val states = q.getAll("state").orEmpty().map { CloudTransferState.parse(it) ?: throw TeamFailure("invalid_state", 400, "Unknown transfer state.") }.toSet()
            val session = single("session")?.also { if (!CloudIdentifier.valid(it)) throw TeamFailure("invalid_query", 400, "Unknown session.") }
            val limit = single("limit")?.let { it.toIntOrNull() ?: throw TeamFailure("invalid_limit", 400, "Use a page size between 1 and 50.") } ?: 50
            val page = transfers.list(call.principal, CloudTransferQuery(team, direction, states, session, single("before"), limit))
            call.respond(CloudTransferPageView(page.transfers.map { it.view() }, page.nextBeforeId))
        }
        get("/transfers/{transfer}") { call.respond(transfers.get(call.principal, call.pathId("transfer")).view()) }
        post("/transfers/{transfer}/accept") {
            val input = call.optionalJson(AcceptCloudTransferRequest())
            call.respond(HttpStatusCode.Accepted, transfers.accept(call.principal, call.onBehalfOf(), call.pathId("transfer"), input.resume).view())
        }
        post("/transfers/{transfer}/decline") { call.respond(transfers.decline(call.principal, call.onBehalfOf(), call.pathId("transfer")).view()) }
        post("/transfers/{transfer}/cancel") { call.respond(transfers.cancel(call.principal, call.onBehalfOf(), call.pathId("transfer")).view()) }
    }
}
