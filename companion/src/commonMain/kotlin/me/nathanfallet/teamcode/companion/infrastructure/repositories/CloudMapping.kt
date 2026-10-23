package me.nathanfallet.teamcode.companion.infrastructure.repositories

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import me.nathanfallet.teamcode.api.models.CloudEventPageView
import me.nathanfallet.teamcode.api.models.CloudEventTypes
import me.nathanfallet.teamcode.api.models.CloudEventView
import me.nathanfallet.teamcode.api.models.CloudProfileView
import me.nathanfallet.teamcode.api.models.CloudSessionKinds
import me.nathanfallet.teamcode.api.models.CloudSessionView
import me.nathanfallet.teamcode.api.models.TeamEventView
import me.nathanfallet.teamcode.companion.domain.models.AgentQuestion
import me.nathanfallet.teamcode.companion.domain.models.AgentQuestionOption
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionHost
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase

internal fun CloudSessionView.domain() = CloudSession(
    id = id,
    name = name,
    ownerId = ownerId,
    state = SessionState.of(state),
    phase = phase,
    agentKind = agent.kind,
    model = agent.model,
    effort = agent.effort,
    profileId = profile.id,
    // `https://github.com/team/app.git` reads as `github.com/team/app`.
    repositories = environment.repositories.map { it.url.substringAfter("://").removeSuffix(".git") },
    lastActivityAt = activity.lastActivityAt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    problemCode = (failure ?: resumeBlocked)?.code,
    kind = SessionKind.of(kind),
    host = host?.takeIf { kind == CloudSessionKinds.REMOTE }?.let {
        SessionHost(it.deviceName, it.platform, it.osVersion, it.directoryName, it.online, removed = it.deviceId == null, permissionMode = it.permissionMode)
    },
)

internal fun CloudProfileView.domain() = Profile(id, name, spec.agentKind)

/** A feed event about a session; null for the other subjects (local runs). */
internal fun TeamEventView.sessionChange(): SessionChange? {
    if (subject != "session") return null
    return SessionChange(
        seq = seq,
        type = type,
        sessionId = subjectId,
        memberId = memberId,
        at = at,
        state = data.text("state")?.let(SessionState::of),
        turnPhase = if (type == "cloud.turn") data.text("phase") ?: "unknown" else null,
        kind = data.text("kind")?.let(SessionKind::of),
    )
}

internal fun CloudEventPageView.domain() = TranscriptPage(events.map { it.domain() }, nextBefore, lastSeq)

/** Every type and field is optional: an unknown type or a missing field never fails the transcript. */
internal fun CloudEventView.domain(): TranscriptEvent {
    // The server replaces an event of more than 60 000 bytes with this marker.
    if (data["truncated"].boolean() == true && data["originalBytes"] != null) return TranscriptEvent.Truncated(seq, at, type, data["originalBytes"].long())
    return when (type) {
        CloudEventTypes.USER_MESSAGE -> TranscriptEvent.UserMessage(seq, at, data.text("text").orEmpty(), data.text("inputId"), data.text("authorId"))
        CloudEventTypes.AGENT_MESSAGE -> TranscriptEvent.AgentMessage(seq, at, data.text("text").orEmpty(), data.text("parentToolUseId"))
        CloudEventTypes.TOOL_CALL -> TranscriptEvent.ToolCall(
            seq, at, data.text("id").orEmpty(), data.text("name") ?: "tool", data["input"].pretty(), toolSummary(data["input"]), data.text("parentToolUseId"),
        )
        CloudEventTypes.TOOL_RESULT -> TranscriptEvent.ToolResult(
            seq, at, data.text("id").orEmpty(), data["output"].let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: it.pretty() },
            data["isError"].boolean() == true, data["exitCode"].int(), data.text("parentToolUseId"),
        )
        CloudEventTypes.TURN -> TranscriptEvent.Turn(seq, at, TurnPhase.of(data.text("phase")), data["durationMs"].long(), data["steps"].int())
        CloudEventTypes.STATUS -> TranscriptEvent.Status(seq, at, data.text("agent"), data.text("model"), data["exitCode"].int())
        CloudEventTypes.ERROR -> TranscriptEvent.Error(seq, at, data.text("code"), data.text("message"))
        CloudEventTypes.INPUT -> TranscriptEvent.InputChanged(seq, at, data.text("inputId"), data.text("status"), data.text("reason"), data.text("requestId"))
        CloudEventTypes.PERMISSION_REQUEST -> permissionRequest(seq, at, data)
        CloudEventTypes.PERMISSION_EXPIRED -> TranscriptEvent.PermissionExpired(seq, at, data.text("requestId"))
        "transfer" -> TranscriptEvent.Transferred(seq, at, data.text("fromMemberId"), data.text("toMemberId"))
        else -> TranscriptEvent.Unknown(seq, at, type)
    }
}

/**
 * A permission request. An input that is not an object counts as left out, as the server reads it: such a request can
 * only be denied. Questions keep their index in `input.questions`, the one the server takes back, even when one of them
 * cannot be read; a repeated question text is kept once (Claude Code keys answers by text).
 */
private fun permissionRequest(seq: Long, at: Long, data: JsonObject): TranscriptEvent.PermissionRequest {
    val input = data["input"] as? JsonObject
    val question = data["question"].boolean() == true
    val questions = if (!question || input == null) emptyList() else (input["questions"] as? JsonArray).orEmpty().mapIndexedNotNull { index, element ->
        val fields = element as? JsonObject ?: return@mapIndexedNotNull null
        val text = fields.text("question") ?: return@mapIndexedNotNull null
        val options = (fields["options"] as? JsonArray).orEmpty().map { option ->
            val o = option as? JsonObject
            AgentQuestionOption(o?.text("label").orEmpty(), o?.text("description")?.takeIf(String::isNotBlank))
        }
        AgentQuestion(index, text, fields.text("header")?.takeIf(String::isNotBlank), options, fields["multiSelect"].boolean() == true)
    }.distinctBy { it.text }
    return TranscriptEvent.PermissionRequest(
        seq, at, data.text("requestId"), data.text("toolName"), question,
        questions = questions,
        input = input?.let { (it as JsonElement).pretty() },
        inputLeftOut = data["inputTruncated"].boolean() == true || input == null,
        inputPreview = data.text("inputPreview"),
        description = data.text("description")?.takeIf(String::isNotBlank),
    )
}

private val prettyJson = Json { prettyPrint = true }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.boolean(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
private fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
private fun JsonElement?.pretty(): String = if (this == null) "" else prettyJson.encodeToString(JsonElement.serializer(), this)

/** The field that tells most about a tool use, on one line: a shell command, a path, a pattern, a URL. */
private fun toolSummary(input: JsonElement?): String? {
    val fields = input as? JsonObject ?: return null
    val value = SUMMARY_FIELDS.firstNotNullOfOrNull { fields.text(it)?.takeIf(String::isNotBlank) } ?: return null
    val trimmed = value.trim()
    val line = trimmed.lineSequence().first().trim()
    return if (line.length > 160 || trimmed.contains('\n')) line.take(160) + "…" else line
}

private val SUMMARY_FIELDS = listOf("command", "file_path", "path", "pattern", "url", "query", "description", "prompt")
