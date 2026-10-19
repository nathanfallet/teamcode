package me.nathanfallet.teamcode.cli.cloud.agent

import kotlin.uuid.Uuid
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.CloudEventTypes

/** A normalized transcript event before upload. The server masks and bounds it again. */
data class AgentEvent(val type: String, val data: JsonObject)

/** What one line of agent output produced: events to upload and lines to write back to the agent. */
data class AgentOutput(val events: List<AgentEvent> = emptyList(), val replies: List<String> = emptyList())

data class AgentCommand(val command: String, val arguments: List<String>)

/** Agent settings from the server (`runner/agent`). */
data class AgentSettings(val kind: String, val version: String?, val model: String?, val effort: String?)

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
}

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private fun JsonElement?.string() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.obj() = this as? JsonObject
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
 * Thinking blocks are dropped here and never uploaded.
 */
class ClaudeStreamJsonAdapter(private val settings: AgentSettings) : AgentAdapter {
    override var conversationId: String? = null; private set
    /** Messages written and not yet answered by a `result` line. */
    private var pending = 0
    override val turnActive get() = pending > 0

    override fun command(resume: String?) = AgentCommand("claude", buildList {
        addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--dangerously-skip-permissions"))
        settings.model?.let { addAll(listOf("--model", it)) }
        settings.effort?.let { addAll(listOf("--effort", it)) }
        resume?.let { addAll(listOf("--resume", it)) }
    })
    override fun startup(resume: String?, workingDirectory: String) = emptyList<String>()

    override fun message(text: String): List<String> {
        pending++
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
            "assistant" -> AgentOutput(value["message"].obj()?.get("content").array().orEmpty().mapNotNull { block ->
                val content = block.obj() ?: return@mapNotNull null
                when (content["type"].string()) {
                    "text" -> content["text"].string()?.takeIf { it.isNotBlank() }?.let { AgentEvent(CloudEventTypes.AGENT_MESSAGE, buildJsonObject { put("text", it) }) }
                    "tool_use" -> AgentEvent(CloudEventTypes.TOOL_CALL, buildJsonObject {
                        content["id"].string()?.let { put("id", it) }; put("name", content["name"].string() ?: "tool"); content["input"]?.let { put("input", it) }
                    })
                    else -> null // thinking and redacted_thinking are never uploaded.
                }
            })
            "user" -> AgentOutput(value["message"].obj()?.get("content").array().orEmpty().mapNotNull { block ->
                val content = block.obj()?.takeIf { it["type"].string() == "tool_result" } ?: return@mapNotNull null
                AgentEvent(CloudEventTypes.TOOL_RESULT, buildJsonObject {
                    content["tool_use_id"].string()?.let { put("id", it) }; put("output", resultText(content["content"]))
                    put("isError", (content["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false)
                })
            })
            "result" -> {
                if (pending > 0) pending--
                val failed = (value["is_error"] as? JsonPrimitive)?.booleanOrNull == true
                val interrupted = value["subtype"].string() == "error_during_execution"
                AgentOutput(listOf(turn(if (interrupted) "interrupted" else if (failed) "failed" else "completed") {
                    (value["duration_ms"] as? JsonPrimitive)?.longOrNull?.let { put("durationMs", it) }
                    (value["num_turns"] as? JsonPrimitive)?.intOrNull?.let { put("steps", it) }
                }))
            }
            else -> AgentOutput()
        }
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
