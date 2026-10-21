package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import java.security.MessageDigest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*

/** Transcript, messages, attachments and access policy; mounted with the cloud routes. */
class CloudConversation(val service: CloudConversationService, val access: CloudAccessService, val runner: RunnerConversationUseCase, val events: TeamEventService? = null)

private const val MESSAGE_BODY_BYTES = 256 * 1024
private const val RUNNER_BODY_BYTES = 2 * 1024 * 1024
private const val MAX_JSON_DEPTH = 16
private const val MAX_JSON_ARRAY = 200
/** Keys whose values are model reasoning or its signature; never stored even if an adapter forwards them. */
private val DROPPED_KEYS = setOf("thinking", "signature", "reasoning", "redacted_thinking")

internal fun CloudEvent.view() = CloudEventView(seq, type.wire, at, epoch, Serialization.json.parseToJsonElement(data).jsonObject)
private fun CloudEventPage.view() = CloudEventPageView(events.map { it.view() }, firstSeq, lastSeq, nextAfter, nextBefore)
private fun CloudInput.view() = CloudInputView(id, kind.wire, status.wire, authorId, text, wake, woke, clientKey, createdAt, deliveredAt, deliveredEpoch, consumedAt, finishedAt, reason, eventSeq)
private fun CloudAttachment.view() = CloudAttachmentView(id, sessionId, memberId, leaseUntil, permissions.map { it.wire }.sorted(), createdAt)
private fun CloudAccessPolicy.view() = CloudAccessPolicyView(version, rules.map { rule ->
    CloudAccessRuleView(rule.subject.wire, rule.permissions.map { it.wire }.sorted(), rule.scope.wire)
}, updatedAt, updatedBy)
private fun CloudAccessRuleView.domain() = CloudAccessRule(
    CloudAccessSubject.parse(subject) ?: throw TeamFailure("invalid_access_policy", 400, "Use a subject of everyone, member:<id> or group:<id>."),
    permissions.map { CloudPermission.parse(it) ?: throw TeamFailure("invalid_access_policy", 400, "Unknown permission $it.") }.toSet(),
    CloudAccessScope.parse(scope) ?: throw TeamFailure("invalid_access_policy", 400, "Use scope own or team."),
)

private suspend inline fun <reified T> ApplicationCall.boundedJson(limit: Int): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) throw TeamFailure("invalid_body", 400, "A JSON body is required.")
    val bytes = try { receiveChannel().readBounded(limit) } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        throw TeamFailure("payload_too_large", 413, "The request body exceeds $limit bytes.")
    }
    return try { Serialization.json.decodeFromString<T>(bytes.decodeToString()) }
    catch (_: IllegalArgumentException) { throw TeamFailure("invalid_body", 400, "Invalid request body.") }
}
private fun ApplicationCall.sessionId() = parameters["id"]!!.also { if (!identifier.matches(it)) throw TeamFailure("not_found", 404, "Not found.") }
private fun ApplicationCall.childId(name: String) = parameters[name]!!.also { if (!identifier.matches(it)) throw TeamFailure("not_found", 404, "Not found.") }
private val identifier = Regex("[a-zA-Z0-9-]{1,36}")
private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

/** Messages are authored by the caller; delegating them to another member is not supported. */
private fun ApplicationCall.refuseOnBehalf() {
    val values = request.headers.getAll(CloudHeaders.ON_BEHALF_OF) ?: return
    if (values.singleOrNull() != principal.member.id)
        throw TeamFailure("on_behalf_unsupported", 400, "Messages are always sent as the caller; omit ${CloudHeaders.ON_BEHALF_OF}.")
}
/** `Idempotency-Key` header or `clientMessageId`; when both are sent they must be equal. */
private fun ApplicationCall.clientKey(bodyKey: String?): String? {
    val header = request.headers.getAll(CloudHeaders.IDEMPOTENCY_KEY)?.let { it.singleOrNull() ?: throw invalidKey() }
    for (key in listOfNotNull(header, bodyKey)) if (!CloudHeaders.idempotencyKey.matches(key)) throw invalidKey()
    if (header != null && bodyKey != null && header != bodyKey) throw TeamFailure("invalid_idempotency_key", 400, "Idempotency-Key and clientMessageId differ.")
    return header ?: bodyKey
}
private fun invalidKey() = TeamFailure("invalid_idempotency_key", 400, "Use one idempotency key of 1–128 characters from A-Z a-z 0-9 . _ : -.")
private fun ApplicationCall.longQuery(name: String): Long? = request.queryParameters.getAll(name)?.let { values ->
    values.singleOrNull()?.toLongOrNull() ?: throw TeamFailure("invalid_query", 400, "Send $name once, as a number.")
}

/** `202` with the acknowledgement; a replayed idempotency key returns the current state with `Idempotency-Replayed: true`. */
private suspend fun ApplicationCall.respondInput(outcome: InputOutcome) {
    val input = when (outcome) {
        is InputOutcome.Accepted -> outcome.input
        is InputOutcome.Replayed -> outcome.input.also { response.header(CloudHeaders.IDEMPOTENCY_REPLAYED, "true") }
    }
    respond(HttpStatusCode.Accepted, input.view())
}

private fun TeamEvent.view() = TeamEventView(seq, type.wire, type.subject.wire, subjectId, ownerId, at, Serialization.json.parseToJsonElement(data).jsonObject)

/** `?types=a,b`: known event types only, so a typo is an error rather than a silent empty stream. */
private fun ApplicationCall.eventTypes(): Set<TeamEventType>? = request.queryParameters.getAll("types")?.let { values ->
    val names = values.singleOrNull()?.split(',')?.map(String::trim)?.filter(String::isNotEmpty)?.takeIf { it.isNotEmpty() && it.size <= TeamEventType.entries.size }
        ?: throw TeamFailure("invalid_query", 400, "Send types once, as a comma-separated list.")
    names.map { TeamEventType.parse(it) ?: throw TeamFailure("invalid_event_type", 400, "Unknown event type '${it.take(64)}'.") }.toSet()
}

/** The caller-wide feed; a plain function so a server without the journal does not mount it. */
internal fun Route.teamEventRoutes(events: TeamEventService) {
    get("/api/v1/cloud/events/stream") {
        // Last-Event-ID wins over ?after, as for a session's stream. Neither: only what happens from now on.
        val resume = call.request.headers.getAll("Last-Event-ID")?.let { it.singleOrNull()?.toLongOrNull() ?: throw TeamFailure("invalid_cursor", 400, "Use a non-negative event sequence.") }
        val after = resume ?: call.longQuery("after")
        // Authorize and check the cursor before committing the response, so a refusal is still a JSON error.
        val feed = events.open(call.principal, after, call.eventTypes())
        call.response.header("X-Accel-Buffering", "no")
        call.respondBytesWriter(ContentType.Text.EventStream) {
            writeStringUtf8("retry: 2000\n\n"); flush()
            try {
                feed.oldestRetained?.let { writeStringUtf8("event: gap\ndata: {\"oldestSeq\":$it}\n\n"); flush() }
                feed.follow(emit = { batch ->
                    for (event in batch) writeStringUtf8("id: ${event.seq}\nevent: ${event.type.wire}\ndata: ${Serialization.json.encodeToString(event.view())}\n\n")
                    flush()
                }, idle = { writeStringUtf8(": keep-alive\n\n"); flush() })
            } catch (error: TeamFailure) {
                // The stream is committed: report the refusal as a final event, never as a replacement response.
                writeStringUtf8("event: denied\ndata: ${Serialization.json.encodeToString(ErrorDetail(error.code, error.message ?: "Denied."))}\n\n")
            }
            flush()
        }
    }
}

internal fun Route.cloudConversationRoutes(conversation: CloudConversation) {
    val service = conversation.service
    conversation.events?.let { teamEventRoutes(it) }
    route("/api/v1/cloud") {
        get("/access") { call.respond(conversation.access.policy(call.principal).view()) }
        put("/access") {
            val input = call.boundedJson<CloudAccessPolicyView>(64 * 1024)
            call.respond(conversation.access.savePolicy(call.principal, input.version, input.rules.map { it.domain() }).view())
        }

        get("/sessions/{id}/events") {
            val limit = call.longQuery("limit")?.toInt() ?: 100
            call.respond(service.events(call.principal, call.sessionId(), call.longQuery("after"), call.longQuery("before"), limit).view())
        }
        get("/sessions/{id}/events/stream") {
            // Last-Event-ID wins over ?after so a reconnecting EventSource resumes where it stopped.
            val resume = call.request.headers.getAll("Last-Event-ID")?.singleOrNull()?.toLongOrNull()
            val after = resume ?: call.longQuery("after") ?: 0L
            if (after < 0) throw TeamFailure("invalid_cursor", 400, "Use a non-negative event sequence.")
            val principal = call.principal
            val id = call.sessionId()
            // Authorize before committing the response, so a refusal is still a JSON error.
            service.events(principal, id, after, null, 1)
            call.response.header("X-Accel-Buffering", "no")
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeStringUtf8("retry: 2000\n\n"); flush()
                try {
                    service.follow(principal, id, after, emit = { events ->
                        for (event in events) writeStringUtf8("id: ${event.seq}\nevent: ${event.type.wire}\ndata: ${Serialization.json.encodeToString(event.view())}\n\n")
                        flush()
                    }, idle = { writeStringUtf8(": keep-alive\n\n"); flush() })
                    writeStringUtf8("event: end\ndata: {}\n\n")
                } catch (error: TeamFailure) {
                    // The stream is committed: report the refusal as a final event, never as a replacement response.
                    writeStringUtf8("event: denied\ndata: ${Serialization.json.encodeToString(ErrorDetail(error.code, error.message ?: "Denied."))}\n\n")
                }
                flush()
            }
        }

        post("/sessions/{id}/messages") {
            call.refuseOnBehalf()
            val input = call.boundedJson<SendCloudMessageRequest>(MESSAGE_BODY_BYTES)
            val key = call.clientKey(input.clientMessageId)
            val request = InputRequest(input.text, input.wake, key, key?.let { digest("message\n${input.wake}\n${input.text}") }, input.expectedLastEventSeq)
            call.respondInput(service.send(call.principal, call.sessionId(), request))
        }
        get("/sessions/{id}/messages") {
            val limit = call.longQuery("limit")?.toInt() ?: 50
            val before = call.request.queryParameters.getAll("before")?.let { it.singleOrNull()?.takeIf(identifier::matches) ?: throw TeamFailure("invalid_cursor", 400, "Unknown input cursor.") }
            val page = service.inputs(call.principal, call.sessionId(), before, limit)
            call.respond(CloudInputPageView(page.inputs.map { it.view() }, page.nextBefore))
        }
        get("/sessions/{id}/messages/{input}") { call.respond(service.input(call.principal, call.sessionId(), call.childId("input")).view()) }
        post("/sessions/{id}/interrupt") {
            call.refuseOnBehalf()
            val key = call.clientKey(null)
            call.respondInput(service.interrupt(call.principal, call.sessionId(), InputRequest(null, false, key, key?.let { digest("interrupt") }, null)))
        }

        post("/sessions/{id}/attachments") { call.respond(HttpStatusCode.Created, service.attach(call.principal, call.sessionId()).view()) }
        post("/sessions/{id}/attachments/{attachment}/renew") { call.respond(service.renew(call.principal, call.sessionId(), call.childId("attachment")).view()) }
        delete("/sessions/{id}/attachments/{attachment}") {
            service.detach(call.principal, call.sessionId(), call.childId("attachment"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** Agent channel of the runner, authenticated like the lifecycle channel by the pod's projected token. */
internal fun Route.cloudRunnerConversationRoutes(channel: CloudRunnerChannel, conversation: CloudConversation) {
    val runner = conversation.runner
    fun ApplicationCall.token() = request.headers.getAll(HttpHeaders.Authorization)?.singleOrNull()?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
    post(RUNNER_PATH + "agent") {
        val body = call.boundedJson<RunnerAgentRequest>(4_096)
        val pod = channel.authenticate(call.token(), body.teamId, body.sessionId, body.epoch)
        val agent = runner.agent(pod.teamId, pod.sessionId, pod.epoch)
        call.respond(RunnerAgentView(agent.kind, agent.version, agent.model, agent.effort, agent.conversation.wire))
    }
    post(RUNNER_PATH + "events") {
        val body = call.boundedJson<RunnerEventsRequest>(RUNNER_BODY_BYTES)
        val pod = channel.authenticate(call.token(), body.teamId, body.sessionId, body.epoch)
        val events = body.events.map { event ->
            val type = CloudEventType.parse(event.type)?.takeIf { it in CloudEventType.fromRunner } ?: throw TeamFailure("invalid_events", 400, "Unknown event type.")
            RunnerEvent(event.sequence, type, CloudTranscriptSanitizer.encode(event.data), turn = CloudTranscriptSanitizer.turn(type, event.data),
                conversation = CloudTranscriptSanitizer.conversation(type, event.data))
        }
        val ack = runner.events(pod.teamId, pod.sessionId, pod.epoch, events)
        call.respond(RunnerEventsAckView(ack.acceptedThrough, ack.lastSeq))
    }
    post(RUNNER_PATH + "inputs") {
        val body = call.boundedJson<RunnerInputsRequest>(4_096)
        val pod = channel.authenticate(call.token(), body.teamId, body.sessionId, body.epoch)
        val inputs = runner.inputs(pod.teamId, pod.sessionId, pod.epoch, body.waitMillis)
        call.respond(RunnerInputsView(inputs.map { RunnerInputView(it.id, it.kind.wire, it.text, it.attempts) }))
    }
    post(RUNNER_PATH + "inputs/ack") {
        val body = call.boundedJson<RunnerInputAckRequest>(64 * 1024)
        val pod = channel.authenticate(call.token(), body.teamId, body.sessionId, body.epoch)
        val acks = body.acks.map { ack ->
            if (ack.status != CloudInputStatuses.CONSUMED && ack.status != CloudInputStatuses.REJECTED) throw TeamFailure("invalid_acks", 400, "Acknowledge as consumed or rejected.")
            RunnerInputAck(ack.id, ack.status == CloudInputStatuses.CONSUMED, ack.reason)
        }
        runner.acknowledge(pod.teamId, pod.sessionId, pod.epoch, acks)
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Bounds, masks and strips reasoning from runner event data before it is stored. */
internal object CloudTranscriptSanitizer {
    fun encode(data: JsonObject): String {
        val clean = sanitize(data, 0) as JsonObject
        val encoded = clean.toString()
        if (encoded.encodeToByteArray().size <= CloudConversationRules.MAX_EVENT_BYTES) return encoded
        return buildJsonObject { put("truncated", true); put("originalBytes", encoded.encodeToByteArray().size) }.toString()
    }
    fun turn(type: CloudEventType, data: JsonObject): TurnMarker? = if (type != CloudEventType.TURN) null else when ((data["phase"] as? JsonPrimitive)?.contentOrNull) {
        "started" -> TurnMarker.STARTED
        "completed", "interrupted", "failed" -> TurnMarker.ENDED
        else -> null
    }
    fun conversation(type: CloudEventType, data: JsonObject): ConversationResume? = if (type != CloudEventType.STATUS) null
        else (data["conversation"] as? JsonPrimitive)?.contentOrNull?.let { value -> ConversationResume.entries.singleOrNull { it.wire == value } }

    private fun sanitize(element: JsonElement, depth: Int): JsonElement = when (element) {
        is JsonObject -> if (depth >= MAX_JSON_DEPTH) JsonPrimitive(CloudConversationRules.TRUNCATION_MARK) else JsonObject(
            element.entries.asSequence().filter { (key, value) -> key !in DROPPED_KEYS && !(value is JsonObject && (value["type"] as? JsonPrimitive)?.contentOrNull in DROPPED_KEYS) }
                .take(MAX_JSON_ARRAY).associate { (key, value) -> key.take(64) to sanitize(value, depth + 1) })
        is JsonArray -> if (depth >= MAX_JSON_DEPTH) JsonPrimitive(CloudConversationRules.TRUNCATION_MARK) else JsonArray(
            element.filterNot { it is JsonObject && (it["type"] as? JsonPrimitive)?.contentOrNull in DROPPED_KEYS }.take(MAX_JSON_ARRAY).map { sanitize(it, depth + 1) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(CloudConversationRules.bound(element.content)) else element
        JsonNull -> JsonNull
    }
}
