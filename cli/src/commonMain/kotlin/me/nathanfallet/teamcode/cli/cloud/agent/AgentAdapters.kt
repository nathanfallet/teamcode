package me.nathanfallet.teamcode.cli.cloud.agent

import kotlin.math.roundToLong
import kotlin.uuid.Uuid
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.CloudEventTypes
import me.nathanfallet.teamcode.api.models.CloudQuestionAnswer
import me.nathanfallet.teamcode.api.models.CloudTranscriptLimits

/** A normalized transcript event before upload. The server masks and bounds it again. */
data class AgentEvent(val type: String, val data: JsonObject)

/** What one line of agent output produced: events to upload and lines to write back to the agent. */
data class AgentOutput(val events: List<AgentEvent> = emptyList(), val replies: List<String> = emptyList())

data class AgentCommand(val command: String, val arguments: List<String>)

/**
 * Agent settings from the server (`runner/agent`). [permissionRelay]: the server takes permission requests and their
 * answers. [permissionDeadlineMinutes]: how long a request waits before the host denies it; 0 for no deadline.
 */
data class AgentSettings(val kind: String, val version: String?, val model: String?, val effort: String?, val permissionRelay: Boolean = false, val permissionDeadlineMinutes: Int = 0)

/**
 * The lines answering a permission request, or the rejection reason when there are none ([UNKNOWN] or [INVALID]);
 * [input] is the request's tool input, to [AgentAdapter.reopen] it when [lines] could not be written.
 */
data class AnswerOutcome(val lines: List<String>, val refusal: String? = null, val input: JsonObject? = null) {
    companion object {
        const val UNKNOWN = "unknown_permission_request"
        const val INVALID = "invalid_answer"
    }
}

/**
 * One long-lived agent process with streaming input. Adapters are pure translators between TeamCode inputs and an
 * agent's wire protocol; the host owns the process, the uploads and the acknowledgements.
 */
interface AgentAdapter {
    /** The agent's own conversation id, once known; resumed after a new pod. */
    val conversationId: String?
    /** True while a turn runs. */
    val turnActive: Boolean
    fun command(resume: String?): AgentCommand
    /** Lines written right after the process starts (protocol handshake, thread start or resume). */
    fun startup(resume: String?, workingDirectory: String): List<String>
    /** Lines delivering a member message; may be held until the agent is ready. */
    fun message(text: String): List<String>
    /** Lines interrupting the current turn, or empty when no turn runs. */
    fun interrupt(): List<String>
    fun parse(line: String): AgentOutput
    /**
     * Lines answering the open permission request [requestId] (`allow` with optional question [answers] by index, or
     * deny with [message]). Refused when no such request is open, or when an index is not in the request; a refused
     * answer leaves the request open.
     */
    fun answer(requestId: String, allow: Boolean, message: String?, answers: List<CloudQuestionAnswer>): AnswerOutcome = AnswerOutcome(emptyList(), AnswerOutcome.UNKNOWN)
    /** Whether the permission request [requestId] still waits for an answer. */
    fun permissionOpen(requestId: String): Boolean = false
    /** Opens [requestId] again after its answer could not be written, so another answer (or the deadline) can close it. */
    fun reopen(requestId: String, input: JsonObject) {}
    /** The permission requests still waiting for an answer. */
    fun openPermissions(): Set<String> = emptySet()
    /** Events the adapter produced outside [parse] (for example a turn started by [message]); emptied by the call. */
    fun takeEvents(): List<AgentEvent> = emptyList()
}

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private fun JsonElement?.string() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.obj() = this as? JsonObject
/** At most [limit] characters, never ending on half a surrogate pair. */
internal fun String.cut(limit: Int): String = if (length <= limit) this else take(if (this[limit - 1].isHighSurrogate()) limit - 1 else limit)
private fun JsonElement?.array() = this as? JsonArray
private fun parseObject(line: String): JsonObject? = try { json.parseToJsonElement(line) as? JsonObject } catch (_: IllegalArgumentException) { null }
private fun turn(phase: String, extra: JsonObjectBuilder.() -> Unit = {}) = AgentEvent(CloudEventTypes.TURN, buildJsonObject { put("phase", phase); extra() })

/** Text of a tool result, which agents send as a string or as a list of content blocks; images become a marker. */
private fun resultText(content: JsonElement?): String = when (content) {
    null, JsonNull -> ""
    is JsonPrimitive -> content.contentOrNull.orEmpty()
    is JsonArray -> content.joinToString("\n") { block -> block.obj()?.let { it["text"].string() ?: "[${it["type"].string() ?: "content"}]" } ?: block.toString() }
    is JsonObject -> content["text"].string() ?: content.toString()
}

/**
 * Claude Code in print mode with stream-json input and output: `claude -p --input-format stream-json
 * --output-format stream-json --verbose --dangerously-skip-permissions`, resumed with `--resume <session_id>`.
 * Messages written while a turn runs are queued by Claude Code itself; an interrupt is a `control_request`.
 * Thinking blocks are dropped here and never uploaded. Events produced by a subagent keep its parent's tool call id as
 * `parentToolUseId`. A finished turn carries Claude Code's own cost estimate and the turn's tokens from its `result`
 * line.
 *
 * With [AgentSettings.permissionRelay], Claude Code also gets `--permission-prompt-tool stdio`. Bypass mode still
 * skips ordinary permission checks; what remains, an `ask` permission rule or a question to the user
 * (`AskUserQuestion`), arrives as a `can_use_tool` control request. It becomes a `permission.request` event, and the
 * member's answer a `control_response`. A request is open until it is answered or its turn ends.
 */
class ClaudeStreamJsonAdapter(private val settings: AgentSettings) : AgentAdapter {
    override var conversationId: String? = null; private set
    /**
     * Messages written since the last `result` line. Claude Code answers every message it has read with one `result`:
     * two messages written together (or one written during a turn) are often one turn, so a `result` ends them all. A
     * message it reads only after that `result` starts a turn of its own, seen by its first `assistant` line.
     */
    private var pending = 0
    override val turnActive get() = pending > 0

    override fun command(resume: String?) = AgentCommand("claude", buildList {
        addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--dangerously-skip-permissions"))
        if (settings.permissionRelay) addAll(listOf("--permission-prompt-tool", "stdio"))
        settings.model?.let { addAll(listOf("--model", it)) }
        settings.effort?.let { addAll(listOf("--effort", it)) }
        resume?.let { addAll(listOf("--resume", it)) }
    })
    override fun startup(resume: String?, workingDirectory: String): List<String> {
        // A resumed conversation starts from the cost it had in the previous process, which this one never saw.
        reportedCostUsd = if (resume == null) 0.0 else null
        return emptyList()
    }

    /** The conversation's cost at the last priced `result`; null while unknown (before [startup], or a resumed conversation's first turn). */
    private var reportedCostUsd: Double? = null

    /** A subagent's lines carry the `tool_use` id of the parent's Agent (Task) call; the main agent's carry null. */
    private fun JsonObjectBuilder.parent(line: JsonObject) { line["parent_tool_use_id"].string()?.let { put("parentToolUseId", it) } }

    private val started = mutableListOf<AgentEvent>()
    /** Open permission requests by request id, with the tool input an `allow` passes back. */
    private val permissions = mutableMapOf<String, JsonObject>()
    override fun permissionOpen(requestId: String) = requestId in permissions
    // A request whose turn ended meanwhile stays closed.
    override fun reopen(requestId: String, input: JsonObject) { if (turnActive) permissions[requestId] = input }
    override fun openPermissions() = permissions.keys.toSet()

    override fun answer(requestId: String, allow: Boolean, message: String?, answers: List<CloudQuestionAnswer>): AnswerOutcome {
        val input = permissions[requestId] ?: return AnswerOutcome(emptyList(), AnswerOutcome.UNKNOWN)
        val texts = if (answers.isEmpty()) emptyMap() else questionTexts(input, answers) ?: return AnswerOutcome(emptyList(), AnswerOutcome.INVALID)
        permissions.remove(requestId)
        return AnswerOutcome(listOf(buildJsonObject {
            put("type", "control_response")
            putJsonObject("response") {
                put("subtype", "success"); put("request_id", requestId)
                putJsonObject("response") {
                    if (allow) {
                        put("behavior", "allow")
                        put("updatedInput", if (texts.isEmpty()) input else JsonObject(input + ("answers" to buildJsonObject { texts.forEach { (question, value) -> put(question, value) } })))
                    } else { put("behavior", "deny"); put("message", message ?: "A team member denied this request.") }
                }
            }
        }.toString()), input = input)
    }

    /**
     * Whether the server stores [element] as is: within its depth, entry and key limits and without the reasoning keys
     * it drops (as keys, or as the `type` of a nested object). Otherwise the event says the input was left out, rather
     * than showing an approver a silently cut one. Secrets the server masks stay masked for the approver, as everywhere
     * in the transcript; `allow` still returns the original input.
     */
    private fun keptWhole(element: JsonElement, depth: Int): Boolean = when (element) {
        is JsonObject -> depth < MAX_KEPT_DEPTH && element.size <= MAX_KEPT_ENTRIES && (depth == 0 || element["type"].string() !in DROPPED_KEYS) &&
            element.all { (key, value) -> key.length <= CloudTranscriptLimits.MAX_KEY_CHARS && key !in DROPPED_KEYS && keptWhole(value, depth + 1) }
        is JsonArray -> depth < MAX_KEPT_DEPTH && element.size <= MAX_KEPT_ENTRIES && element.all { keptWhole(it, depth + 1) }
        else -> true
    }

    /**
     * Claude Code's answer format: question text to the chosen label, several labels joined with ", " for a
     * multi-select question, or the free text. Null when an index is not in the request.
     */
    private fun questionTexts(input: JsonObject, answers: List<CloudQuestionAnswer>): Map<String, String>? {
        val questions = input["questions"].array() ?: return null
        // Claude Code keys answers by question text: two answered questions with one text cannot both reach it.
        val texts = answers.map { questions.getOrNull(it.question).obj()?.get("question").string() }
        if (texts.toSet().size != texts.size) return null
        return answers.associate { answer ->
            val question = questions.getOrNull(answer.question).obj() ?: return null
            val text = question["question"].string() ?: return null
            val options = question["options"].array().orEmpty()
            if (answer.text == null && (answer.options.isEmpty() || (answer.options.size > 1 && (question["multiSelect"] as? JsonPrimitive)?.booleanOrNull != true))) return null
            val value = answer.text ?: answer.options.map { options.getOrNull(it).obj()?.get("label").string() ?: return null }.joinToString(", ")
            text to value
        }
    }
    override fun takeEvents(): List<AgentEvent> = started.toList().also { started.clear() }

    override fun message(text: String): List<String> {
        // Claude Code reports no turn start: the first message of an idle agent starts one.
        if (pending++ == 0) started += turn("started")
        return listOf(buildJsonObject {
            put("type", "user")
            putJsonObject("message") { put("role", "user"); putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } } }
        }.toString())
    }

    override fun interrupt(): List<String> = if (!turnActive) emptyList() else listOf(buildJsonObject {
        put("type", "control_request"); put("request_id", Uuid.random().toString()); putJsonObject("request") { put("subtype", "interrupt") }
    }.toString())

    override fun parse(line: String): AgentOutput {
        val value = parseObject(line) ?: return AgentOutput()
        return when (value["type"].string()) {
            "system" -> if (value["subtype"].string() == "init") {
                val id = value["session_id"].string()
                val first = conversationId == null
                conversationId = id ?: conversationId
                if (first && id != null) AgentOutput(listOf(AgentEvent(CloudEventTypes.STATUS, buildJsonObject {
                    put("agent", "claude"); put("conversation", "resumable"); put("conversationId", id); value["model"].string()?.let { put("model", it) }
                }))) else AgentOutput()
            } else AgentOutput()
            "assistant" -> AgentOutput((if (pending == 0) listOf(turn("started")).also { pending = 1 } else emptyList()) + value["message"].obj()?.get("content").array().orEmpty().mapNotNull { block ->
                val content = block.obj() ?: return@mapNotNull null
                when (content["type"].string()) {
                    "text" -> content["text"].string()?.takeIf { it.isNotBlank() }?.let { AgentEvent(CloudEventTypes.AGENT_MESSAGE, buildJsonObject { put("text", it); parent(value) }) }
                    "tool_use" -> AgentEvent(CloudEventTypes.TOOL_CALL, buildJsonObject {
                        content["id"].string()?.let { put("id", it) }; put("name", content["name"].string() ?: "tool"); content["input"]?.let { put("input", it) }; parent(value)
                    })
                    else -> null // thinking and redacted_thinking are never uploaded.
                }
            })
            "user" -> AgentOutput(value["message"].obj()?.get("content").array().orEmpty().mapNotNull { block ->
                val content = block.obj()?.takeIf { it["type"].string() == "tool_result" } ?: return@mapNotNull null
                AgentEvent(CloudEventTypes.TOOL_RESULT, buildJsonObject {
                    content["tool_use_id"].string()?.let { put("id", it) }; put("output", resultText(content["content"]))
                    put("isError", (content["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false)
                    parent(value)
                })
            })
            "control_request" -> {
                val id = value["request_id"].string() ?: return AgentOutput()
                // Another control request needs a reply too, or Claude Code would wait for it: refused, since none is relayed.
                val request = value["request"].obj()?.takeIf { it["subtype"].string() == "can_use_tool" } ?: return AgentOutput(replies = listOf(buildJsonObject {
                    put("type", "control_response")
                    putJsonObject("response") { put("subtype", "error"); put("request_id", id); put("error", "This control request is not supported by the TeamCode runner.") }
                }.toString()))
                val input = request["input"].obj() ?: JsonObject(emptyMap())
                // An id no member could send back (the server takes 1 to 128 printable ASCII characters) is denied at once.
                if (!PERMISSION_REQUEST_ID.matches(id)) return AgentOutput(replies = listOf(buildJsonObject {
                    put("type", "control_response")
                    putJsonObject("response") {
                        put("subtype", "success"); put("request_id", id)
                        putJsonObject("response") { put("behavior", "deny"); put("message", "The request could not be shown to the team: it was denied.") }
                    }
                }.toString()))
                permissions[id] = input
                AgentOutput(listOf(AgentEvent(CloudEventTypes.PERMISSION_REQUEST, buildJsonObject {
                    // The request must stay answerable: an input too large for one event is left out, never the whole event.
                    put("requestId", id); put("toolName", request["tool_name"].string()?.cut(MAX_PERMISSION_FIELD_CHARS) ?: "tool")
                    val encoded = input.toString()
                    if (encoded.encodeToByteArray().size <= MAX_PERMISSION_INPUT_BYTES && keptWhole(input, 1)) put("input", input)
                    else { put("inputTruncated", true); put("inputPreview", encoded.cut(PERMISSION_PREVIEW_CHARS)) }
                    request["tool_use_id"].string()?.let { put("toolUseId", it.cut(MAX_PERMISSION_FIELD_CHARS)) }
                    request["description"].string()?.let { put("description", it.cut(PERMISSION_PREVIEW_CHARS)) }
                    // Only AskUserQuestion is a question to the user; any other tool, interactive or not, is a tool use to approve.
                    put("question", request["tool_name"].string() == "AskUserQuestion")
                    request["decision_reason_type"].string()?.let { put("reason", it.cut(MAX_PERMISSION_FIELD_CHARS)) }
                })))
            }
            // Claude Code withdrew a request (its turn was interrupted): an answer to it is rejected.
            "control_cancel_request" -> { value["request_id"].string()?.let(permissions::remove); AgentOutput() }
            "result" -> {
                // A request cannot outlive its turn.
                permissions.clear()
                pending = 0
                val failed = (value["is_error"] as? JsonPrimitive)?.booleanOrNull == true
                val interrupted = value["subtype"].string() == "error_during_execution"
                // `total_cost_usd` is the conversation's running total (kept across `--resume`); `usage` is this turn's.
                // A total below the previous one (a counter that restarted) gives the turn no cost of its own rather than a wrong one.
                val totalCostUsd = (value["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it >= 0 }
                val costMicros = totalCostUsd?.let { total -> reportedCostUsd?.takeIf { it <= total }?.let { ((total - it) * 1_000_000).roundToLong() } }
                val totalCostMicros = totalCostUsd?.let { (it * 1_000_000).roundToLong() }
                if (totalCostUsd != null) reportedCostUsd = totalCostUsd
                val usage = value["usage"].obj()
                AgentOutput(listOf(turn(if (interrupted) "interrupted" else if (failed) "failed" else "completed") {
                    (value["duration_ms"] as? JsonPrimitive)?.longOrNull?.let { put("durationMs", it) }
                    (value["num_turns"] as? JsonPrimitive)?.intOrNull?.let { put("steps", it) }
                    costMicros?.let { put("costMicros", it) }
                    totalCostMicros?.let { put("totalCostMicros", it) }
                    for ((from, to) in USAGE_FIELDS) (usage?.get(from) as? JsonPrimitive)?.longOrNull?.let { put(to, it) }
                }))
            }
            else -> AgentOutput()
        }
    }

    private companion object {
        /**
         * UTF-8 bytes, under the server's 60,000-byte event limit (which would replace the event and its request id)
         * and its 16,000-character limit per string (which would cut a command silently).
         */
        const val MAX_PERMISSION_INPUT_BYTES = 15_000
        /** Characters of a left-out input still shown, so whoever decides sees what the tool would do. */
        const val PERMISSION_PREVIEW_CHARS = 2_000
        /**
         * The other fields of a request event stay short (the description at [PERMISSION_PREVIEW_CHARS]), so the event as a
         * whole (at most about 15 KB of input or 8 KB of preview, plus 8 KB of description) stays well under the server's 60 KB.
         */
        const val MAX_PERMISSION_FIELD_CHARS = 200
        /** The request ids the server accepts in an answer (`CloudConversationRules.validatePermissionAnswer`). */
        val PERMISSION_REQUEST_ID = Regex("[\\x21-\\x7E]{1,128}")
        /** The server's transcript limits and the reasoning keys it drops. */
        const val MAX_KEPT_DEPTH = CloudTranscriptLimits.MAX_DEPTH
        const val MAX_KEPT_ENTRIES = CloudTranscriptLimits.MAX_ENTRIES
        val DROPPED_KEYS = CloudTranscriptLimits.DROPPED_KEYS
        val USAGE_FIELDS = listOf(
            "input_tokens" to "inputTokens", "output_tokens" to "outputTokens",
            "cache_creation_input_tokens" to "cacheCreationInputTokens", "cache_read_input_tokens" to "cacheReadInputTokens",
        )
    }
}

/**
 * Codex through `codex app-server` (JSON-RPC over stdio, one object per line, no `jsonrpc` member). Bypass mode is
 * the thread's `approvalPolicy: never` with `sandbox: danger-full-access`, the app-server form of
 * `--dangerously-bypass-approvals-and-sandbox`. A message during a turn steers it (`turn/steer`), otherwise it
 * starts one (`turn/start`); an interrupt is `turn/interrupt`. Resume is `thread/resume` with the stored thread id;
 * when it fails a fresh thread starts and the conversation is reported `transcript_only`.
 */
class CodexAppServerAdapter(private val settings: AgentSettings, private val clientVersion: String) : AgentAdapter {
    override var conversationId: String? = null; private set
    private var threadReady = false
    private var activeTurn: String? = null
    private var turnStarting = false
    private var nextId = 1L
    private val requests = mutableMapOf<Long, String>()
    private val held = ArrayDeque<String>()
    /** Text of in-flight `turn/steer` requests, re-sent as a new turn if the turn ended first. */
    private val steering = mutableMapOf<Long, String>()
    private var workingDirectory = "/workspace"
    override val turnActive get() = activeTurn != null || turnStarting

    override fun command(resume: String?) = AgentCommand("codex", listOf("app-server", "-c", "approval_policy=\"never\"", "-c", "sandbox_mode=\"danger-full-access\""))

    private fun request(method: String, params: JsonObject): String {
        val id = nextId++
        requests[id] = method
        return buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString()
    }
    private fun threadParams(extra: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("approvalPolicy", "never"); put("sandbox", "danger-full-access"); put("cwd", workingDirectory)
        settings.model?.let { put("model", it) }
        extra()
    }

    override fun startup(resume: String?, workingDirectory: String): List<String> {
        this.workingDirectory = workingDirectory
        return listOf(
            request("initialize", buildJsonObject { putJsonObject("clientInfo") { put("name", "teamcode"); put("title", "TeamCode cloud runner"); put("version", clientVersion) } }),
            buildJsonObject { put("method", "initialized") }.toString(),
            if (resume != null) request("thread/resume", threadParams { put("threadId", resume) }) else request("thread/start", threadParams {}),
        )
    }

    private fun input(text: String) = buildJsonArray { addJsonObject { put("type", "text"); put("text", text); putJsonArray("text_elements") {} } }

    override fun message(text: String): List<String> {
        val thread = conversationId
        if (!threadReady || thread == null || turnStarting) { held.addLast(text); return emptyList() }
        val turn = activeTurn
        return if (turn != null) {
            val line = request("turn/steer", buildJsonObject { put("threadId", thread); put("expectedTurnId", turn); put("input", input(text)) })
            steering[nextId - 1] = text
            listOf(line)
        }
        else {
            turnStarting = true
            listOf(request("turn/start", buildJsonObject {
                put("threadId", thread); put("input", input(text)); settings.effort?.let { put("effort", it) }
            }))
        }
    }

    override fun interrupt(): List<String> {
        val thread = conversationId ?: return emptyList()
        val turn = activeTurn ?: return emptyList()
        return listOf(request("turn/interrupt", buildJsonObject { put("threadId", thread); put("turnId", turn) }))
    }

    /** Held messages, once the thread or a turn is ready to take them. */
    private fun release(): List<String> {
        val lines = mutableListOf<String>()
        while (held.isNotEmpty() && threadReady && !turnStarting) lines += message(held.removeFirst())
        return lines
    }

    override fun parse(line: String): AgentOutput {
        val value = parseObject(line) ?: return AgentOutput()
        val id = (value["id"] as? JsonPrimitive)?.longOrNull
        val method = value["method"].string()
        if (id != null && method != null) {
            // A server request (an approval or elicitation): bypass mode should not produce any; refuse it.
            return AgentOutput(replies = listOf(buildJsonObject { put("id", id); putJsonObject("error") { put("code", -32601); put("message", "Not supported by the TeamCode runner.") } }.toString()))
        }
        if (id != null) return response(id, value)
        val params = value["params"].obj() ?: JsonObject(emptyMap())
        return when (method) {
            "turn/started" -> {
                activeTurn = params["turn"].obj()?.get("id").string() ?: activeTurn
                turnStarting = false
                AgentOutput(listOf(turn("started")), release())
            }
            "turn/completed" -> {
                activeTurn = null; turnStarting = false
                val turnValue = params["turn"].obj()
                val phase = when (turnValue?.get("status").string()) { "interrupted" -> "interrupted"; "failed" -> "failed"; else -> "completed" }
                val events = buildList {
                    turnValue?.get("error").obj()?.get("message").string()?.let { add(AgentEvent(CloudEventTypes.ERROR, buildJsonObject { put("code", "turn_failed"); put("message", it) })) }
                    add(turn(phase) { (turnValue?.get("durationMs") as? JsonPrimitive)?.longOrNull?.let { put("durationMs", it) } })
                }
                AgentOutput(events, release())
            }
            "item/completed" -> AgentOutput(item(params["item"].obj()))
            "error" -> AgentOutput(listOf(AgentEvent(CloudEventTypes.ERROR, buildJsonObject {
                put("code", "agent_error"); put("message", params["error"].obj()?.get("message").string() ?: "The agent reported an error.")
            })))
            else -> AgentOutput()
        }
    }

    private fun response(id: Long, value: JsonObject): AgentOutput {
        val method = requests.remove(id) ?: return AgentOutput()
        val steered = steering.remove(id)
        val error = value["error"].obj()
        val result = value["result"].obj()
        return when (method) {
            "thread/start", "thread/resume" -> {
                if (error != null && method == "thread/resume") {
                    // The stored thread cannot be resumed: start a fresh one; history stays in the transcript only.
                    return AgentOutput(listOf(AgentEvent(CloudEventTypes.STATUS, buildJsonObject { put("agent", "codex"); put("conversation", "transcript_only") })),
                        listOf(request("thread/start", threadParams {})))
                }
                if (error != null) return AgentOutput(listOf(AgentEvent(CloudEventTypes.ERROR, buildJsonObject { put("code", "agent_start_failed"); put("message", error["message"].string() ?: "The agent could not start a thread.") })))
                conversationId = result?.get("thread").obj()?.get("id").string()
                threadReady = conversationId != null
                AgentOutput(listOfNotNull(conversationId?.let { thread -> AgentEvent(CloudEventTypes.STATUS, buildJsonObject {
                    put("agent", "codex"); put("conversation", "resumable"); put("conversationId", thread); result?.get("model").string()?.let { put("model", it) }
                }) }), release())
            }
            "turn/start" -> {
                if (error != null) {
                    turnStarting = false
                    AgentOutput(listOf(AgentEvent(CloudEventTypes.ERROR, buildJsonObject { put("code", "turn_rejected"); put("message", error["message"].string() ?: "The agent refused the message.") })), release())
                } else {
                    activeTurn = result?.get("turn").obj()?.get("id").string() ?: activeTurn
                    turnStarting = false
                    AgentOutput(replies = release())
                }
            }
            "turn/steer" -> if (error != null && steered != null) {
                // The turn ended before the steer landed: the message is not lost, it starts the next turn.
                held.addFirst(steered)
                AgentOutput(replies = release())
            } else AgentOutput()
            else -> AgentOutput()
        }
    }

    private fun item(item: JsonObject?): List<AgentEvent> {
        if (item == null) return emptyList()
        val itemId = item["id"].string()
        fun JsonObjectBuilder.id() { itemId?.let { put("id", it) } }
        return when (item["type"].string()) {
            "agentMessage" -> listOfNotNull(item["text"].string()?.takeIf { it.isNotBlank() }?.let { AgentEvent(CloudEventTypes.AGENT_MESSAGE, buildJsonObject { put("text", it) }) })
            "commandExecution" -> listOf(
                AgentEvent(CloudEventTypes.TOOL_CALL, buildJsonObject { id(); put("name", "shell"); putJsonObject("input") { item["command"]?.let { put("command", it) }; item["cwd"]?.let { put("cwd", it) } } }),
                AgentEvent(CloudEventTypes.TOOL_RESULT, buildJsonObject {
                    id(); put("output", item["aggregatedOutput"].string().orEmpty()); (item["exitCode"] as? JsonPrimitive)?.intOrNull?.let { put("exitCode", it) }
                    put("isError", item["status"].string() == "failed" || ((item["exitCode"] as? JsonPrimitive)?.intOrNull ?: 0) != 0)
                }),
            )
            "fileChange" -> listOf(
                AgentEvent(CloudEventTypes.TOOL_CALL, buildJsonObject {
                    id(); put("name", "apply_patch")
                    putJsonObject("input") { putJsonArray("paths") { item["changes"].array().orEmpty().forEach { change -> change.obj()?.get("path").string()?.let { add(it) } } } }
                }),
                AgentEvent(CloudEventTypes.TOOL_RESULT, buildJsonObject { id(); put("output", item["status"].string() ?: "completed"); put("isError", item["status"].string() == "failed") }),
            )
            "mcpToolCall", "dynamicToolCall" -> listOf(
                AgentEvent(CloudEventTypes.TOOL_CALL, buildJsonObject {
                    id(); put("name", listOfNotNull(item["server"].string() ?: item["namespace"].string(), item["tool"].string()).joinToString("/")); item["arguments"]?.let { put("input", it) }
                }),
                AgentEvent(CloudEventTypes.TOOL_RESULT, buildJsonObject {
                    id()
                    val error = item["error"]
                    put("output", if (error != null && error != JsonNull) resultText(error.obj()?.get("message") ?: error) else resultText(item["result"].obj()?.get("content") ?: item["contentItems"] ?: item["result"]))
                    put("isError", (error != null && error != JsonNull) || item["status"].string() == "failed")
                }),
            )
            "webSearch" -> listOf(AgentEvent(CloudEventTypes.TOOL_CALL, buildJsonObject { id(); put("name", "web_search"); putJsonObject("input") { item["query"]?.let { put("query", it) } } }))
            else -> emptyList() // reasoning, plans, user messages (the server records those) and other items are not uploaded.
        }
    }
}

fun agentAdapter(settings: AgentSettings, clientVersion: String): AgentAdapter = when (settings.kind) {
    "claude" -> ClaudeStreamJsonAdapter(settings)
    "codex" -> CodexAppServerAdapter(settings, clientVersion)
    else -> throw IllegalArgumentException("Unsupported agent kind ${settings.kind}.")
}
