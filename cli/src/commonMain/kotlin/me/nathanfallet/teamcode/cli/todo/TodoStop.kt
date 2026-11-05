package me.nathanfallet.teamcode.cli.todo

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.launch.AgentEnvironment
import me.nathanfallet.teamcode.cli.mcp.relayClient
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * `teamcode cloud todo-stop --relay URL --session ID [--run ID] [--mode observe|block]`: the `Stop` hook of an agent that keeps a todo
 * (docs/todos.md, "The Stop hook"). Reads the hook input on standard input (at most 8 MiB), decides ([TodoStop.decide]) and prints Claude
 * Code's `{"decision":"block","reason":"..."}` when the turn must go on. Anything else prints nothing and exits 0: a failure of TeamCode never
 * keeps the agent from stopping, but it is recorded on the list (`hook_error`). The input's content is not kept or logged; only the last
 * message (at most 600 characters) reaches the owner, in the wait opened when the agent is blocked.
 */
internal class CloudTodoStop : SuspendingCliktCommand(name = "todo-stop") {
    override val hiddenFromHelp = true
    override fun help(context: Context) = "Send the agent back to work while its todo has open items and nothing waits (run by the agent's Stop hook, not by hand)."
    private val relay by option("--relay", help = "The launcher's loopback relay, http://127.0.0.1:<port>.").required()
    private val session by option("--session", help = "The session whose todo this is.").required()
    private val run by option("--run", help = "The agent run this hook belongs to.")
    private val mode by option("--mode", help = "observe (the default: record what would be blocked, never block) or block.").default(TodoStop.OBSERVE)
    override suspend fun run() {
        val input = StringBuilder()
        for (line in generateSequence(::readlnOrNull)) {
            if (input.length + line.length > MAX_INPUT_CHARS) break
            input.append(line).append('\n')
        }
        if (!SESSION_ID.matches(session) || (run?.let { RunIdentifiers.isValid(it) } == false) || mode !in setOf(TodoStop.OBSERVE, TodoStop.BLOCK)) return
        val answer = try {
            relayClient("TEAMCODE_TODO", run, relay).use { client ->
                TodoStop.decide(input.toString(), mode == TodoStop.OBSERVE, ClientTodoStopApi(client, session), TodoChecks(ProcessRunner, session))
            }
        } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        if (answer != null) echo(answer)
    }
    private companion object { const val MAX_INPUT_CHARS = 8 * 1_048_576; val SESSION_ID = Regex("[a-zA-Z0-9-]{1,36}") }
}

/**
 * `teamcode cloud todo-context --relay URL --session ID [--run ID]`: the `SessionStart` hook of an agent that keeps a todo. After a
 * compaction (`compact`) and when the session resumes (`resume`) it prints the plan reference, the list and the next item as the
 * context Claude Code injects ([TodoStop.contextOutput]); at any other start, or on any failure, it prints nothing and exits 0.
 */
internal class CloudTodoContext : SuspendingCliktCommand(name = "todo-context") {
    override val hiddenFromHelp = true
    override fun help(context: Context) = "Remind the agent of its todo after a compaction or on resume (run by the agent's SessionStart hook, not by hand)."
    private val relay by option("--relay", help = "The launcher's loopback relay, http://127.0.0.1:<port>.").required()
    private val session by option("--session", help = "The session whose todo this is.").required()
    private val run by option("--run", help = "The agent run this hook belongs to.")
    override suspend fun run() {
        val input = StringBuilder()
        for (line in generateSequence(::readlnOrNull)) {
            if (input.length + line.length > 1_048_576) break
            input.append(line).append('\n')
        }
        if (!Regex("[a-zA-Z0-9-]{1,36}").matches(session) || (run?.let { RunIdentifiers.isValid(it) } == false)) return
        val output = try {
            relayClient("TEAMCODE_TODO", run, relay).use { client -> TodoStop.contextOutput(input.toString()) { withTimeoutOrNull(TodoStop.CALL_MILLIS) { client.todoContext().text } } }
        } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        if (output != null) echo(output)
    }
}

/** What the hook asks of the server, so the decision can be tested without one. */
internal interface TodoStopApi {
    suspend fun stop(request: TodoStopRequest): TodoStopView
    suspend fun link(itemId: String, link: LinkTodoItemRequest)
    suspend fun hookError(text: String)
}

internal class ClientTodoStopApi(private val client: TeamCodeClient, private val session: String) : TodoStopApi {
    override suspend fun stop(request: TodoStopRequest) = client.todoStop(request.copy(sessionId = null))
    override suspend fun link(itemId: String, link: LinkTodoItemRequest) {
        try { client.linkTodoItem(itemId, link.copy(sessionId = null)) } catch (error: TeamCodeException) { if (error.code != "already_linked") throw error }
    }
    override suspend fun hookError(text: String) { client.todoHookError(TodoHookErrorRequest(null, text)) }
}

internal object TodoStop {
    const val OBSERVE = "observe"
    const val BLOCK = "block"
    /** The whole decision, inside the hook's own timeout of 150 s ([me.nathanfallet.teamcode.cli.cloud.agent.cloudHookSettings]). */
    const val TIMEOUT_MILLIS = 140_000L
    /** One question to the server may take: a slower one is an infrastructure problem, never a reason to hold the agent. */
    const val CALL_MILLIS = 20_000L
    private const val MAX_LAST_MESSAGE = 600
    /** The checks that cost one `gh` call or nothing (the server reads artifacts and waits itself): run for every open item, not `command`. */
    private val CHEAP = setOf("pr_merged", "release_contains", "artifact_published", "wait_answered")

    /**
     * The hook's output for one `Stop` [input], or null for no output. Never holds the agent for the list's sake when the server cannot be
     * reached, answers late, is older than `todos.v1`, or fails: that is recorded (best effort) and the stop passes. It never relaxes on
     * `stop_hook_active`: the bound is the server's counter of stops without progress.
     */
    suspend fun decide(input: String, observe: Boolean, api: TodoStopApi, checks: TodoChecks, transcripts: (String) -> List<String> = ::pullRequestsOf): String? {
        val body = try { Json.parseToJsonElement(input) as? JsonObject } catch (_: Exception) { null } ?: return null
        val event = (body["hook_event_name"] as? JsonPrimitive)?.contentOrNull
        if (event != null && event != "Stop") return null
        fun listed(key: String) = (body[key] as? JsonArray)?.isNotEmpty() == true
        val background = listed("background_tasks") || listed("session_crons")
        val last = (body["last_assistant_message"] as? JsonPrimitive)?.contentOrNull?.take(MAX_LAST_MESSAGE)
        val transcript = (body["transcript_path"] as? JsonPrimitive)?.contentOrNull
        return try {
            // A null output is "let it stop"; only a missing outcome is a timeout.
            val outcome = withTimeoutOrNull(TIMEOUT_MILLIS) { Output(run(background, observe, last, transcript, api, checks, transcripts)) }
            if (outcome == null) { failed(api, "The stop hook ran out of time (${TIMEOUT_MILLIS / 1000} s)."); null } else outcome.text
        } catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) { failed(api, "The server answered ${error.code} to the stop hook."); null }
        catch (error: Exception) { failed(api, "The stop hook failed (${error::class.simpleName})."); null }
    }

    private class Output(val text: String?)

    private suspend fun failed(api: TodoStopApi, text: String) {
        try { withTimeoutOrNull(CALL_MILLIS) { api.hookError(text) } } catch (error: CancellationException) { throw error } catch (_: Exception) { }
    }

    private suspend fun run(background: Boolean, observe: Boolean, last: String?, transcript: String?, api: TodoStopApi, checks: TodoChecks, transcripts: (String) -> List<String>): String? {
        var answer = call(api, TodoStopRequest(phase = "begin", background = background, observe = observe))
        if (answer.action == "verify") {
            val list = answer.todo ?: return null
            val reports = verification(list, api, checks) { item -> item.status == "doing" || (item.status == "todo" && item.doneWhen.checks.all { it.type in CHEAP }) }
            val orphans = transcript?.let { try { transcripts(it) } catch (_: Exception) { emptyList() } }.orEmpty().filter { url -> list.items.none { item -> item.links.any { it.ref == url } } }.take(5)
            answer = call(api, TodoStopRequest(phase = "verify", background = background, observe = observe, items = reports, orphans = orphans, lastMessage = last))
        }
        if (answer.action == "settle") {
            val list = answer.todo ?: return null
            val reports = verification(list, api, checks) { it.status == "done" }
            answer = call(api, TodoStopRequest(phase = "settle", background = background, observe = observe, items = reports, lastMessage = last))
        }
        if (answer.action == "block" && !observe) answer.reason?.let { return buildJsonObject { put("decision", "block"); put("reason", it) }.toString() }
        return null
    }

    private suspend fun call(api: TodoStopApi, request: TodoStopRequest): TodoStopView =
        withTimeoutOrNull(CALL_MILLIS) { api.stop(request) } ?: throw IllegalStateException("The server did not answer in ${CALL_MILLIS / 1000} s.")

    /** Runs the checks of the selected items in parallel, binds what they found (a PR by its marker), and reports each outcome by position. */
    private suspend fun verification(list: TodoView, api: TodoStopApi, checks: TodoChecks, selected: (TodoItemView) -> Boolean): List<TodoStopItemInput> = coroutineScope {
        list.items.filter(selected).map { item ->
            async {
                val outcomes = checks.run(list, item, null, emptyList())
                for (link in outcomes.flatMap { it.second.links }.distinctBy { it.kind to it.ref }) {
                    try { api.link(item.id, link) } catch (error: CancellationException) { throw error } catch (_: Exception) { }
                }
                TodoStopItemInput(item.id, outcomes.map { (index, outcome) -> TodoCheckReportInput(index, outcome.passed, outcome.observed.take(2_000), outcome.urls.take(10)) })
            }
        }.awaitAll()
    }

    /**
     * The hook as a shell command, for the agent's settings: the binary, the relay of this launch, the session and the run. [mode] is
     * `observe` (the default of every profile until the observation has been read: the decision is recorded, never enforced) or `block`.
     */
    fun hookCommand(binary: String, port: Int, sessionId: String, runId: String?, mode: String = OBSERVE): String {
        require(port in 1..65535 && Regex("[a-zA-Z0-9-]{1,36}").matches(sessionId) && (runId == null || RunIdentifiers.isValid(runId)) && mode in setOf(OBSERVE, BLOCK))
        return "${AgentEnvironment.quote(binary)} cloud todo-stop --relay http://127.0.0.1:$port --session $sessionId --mode $mode" + (runId?.let { " --run $it" } ?: "")
    }

    /** The `SessionStart` hook as a shell command, for the agent's settings. */
    fun contextCommand(binary: String, port: Int, sessionId: String, runId: String?): String {
        require(port in 1..65535 && Regex("[a-zA-Z0-9-]{1,36}").matches(sessionId) && (runId == null || RunIdentifiers.isValid(runId)))
        return "${AgentEnvironment.quote(binary)} cloud todo-context --relay http://127.0.0.1:$port --session $sessionId" + (runId?.let { " --run $it" } ?: "")
    }

    /** The mode a launch uses: `TEAMCODE_TODO_STOP_MODE` (observe or block) when the operator set it, else observation. */
    fun modeOf(value: String?) = value?.trim()?.lowercase()?.takeIf { it == BLOCK } ?: OBSERVE

    /** The `SessionStart` sources that get the reminder: a compaction loses the list from the context, a resume starts without it. */
    val CONTEXT_SOURCES = setOf("compact", "resume")

    /**
     * The hook output for one `SessionStart` [input]: Claude Code's `additionalContext` carrying [text], or null (another source, no
     * active list, an unreadable input or a failing [text]). Reading the list never keeps a session from starting.
     */
    suspend fun contextOutput(input: String, text: suspend () -> String?): String? {
        val body = try { Json.parseToJsonElement(input) as? JsonObject } catch (_: Exception) { null } ?: return null
        if ((body["hook_event_name"] as? JsonPrimitive)?.contentOrNull.let { it != null && it != "SessionStart" }) return null
        if ((body["source"] as? JsonPrimitive)?.contentOrNull !in CONTEXT_SOURCES) return null
        val block = try { text() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }?.takeIf { it.isNotBlank() } ?: return null
        return buildJsonObject { putJsonObject("hookSpecificOutput") { put("hookEventName", "SessionStart"); put("additionalContext", block) } }.toString()
    }

    private val PR_URL = Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/pull/\\d+")
    private const val MAX_TRANSCRIPT_BYTES = 64L * 1_048_576
    private const val MAX_LINE = 1_048_576

    /**
     * The pull requests this session opened: the address a successful `gh pr create` printed, found in the Claude Code transcript (a Bash
     * call whose command has `gh pr create`, then the result with the same `tool_use_id`). Only addresses leave this function: nothing else
     * of the transcript is kept. The format is Claude Code's own: a line that does not read as expected is skipped.
     */
    fun pullRequestsOf(path: String, fs: FileSystem = FileSystem.SYSTEM): List<String> {
        val file = path.toPath()
        if (fs.metadataOrNull(file)?.isRegularFile != true) return emptyList()
        val creating = HashSet<String>()
        val found = LinkedHashSet<String>()
        var read = 0L
        fs.read(file) {
            while (true) {
                val line = readUtf8Line() ?: break
                read += line.length + 1
                if (read > MAX_TRANSCRIPT_BYTES) break
                if (line.length > MAX_LINE || ("gh pr create" !in line && creating.isEmpty())) continue
                val message = (try { Json.parseToJsonElement(line) as? JsonObject } catch (_: Exception) { null })?.get("message") as? JsonObject ?: continue
                val content = message["content"] as? JsonArray ?: continue
                for (block in content) {
                    val o = block as? JsonObject ?: continue
                    when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                        "tool_use" -> {
                            val command = ((o["input"] as? JsonObject)?.get("command") as? JsonPrimitive)?.contentOrNull
                            val id = (o["id"] as? JsonPrimitive)?.contentOrNull
                            if (command != null && id != null && "gh pr create" in command) creating += id
                        }
                        "tool_result" -> {
                            val id = (o["tool_use_id"] as? JsonPrimitive)?.contentOrNull
                            if (id !in creating || (o["is_error"] as? JsonPrimitive)?.booleanOrNull == true) continue
                            val text = when (val c = o["content"]) { is JsonPrimitive -> c.contentOrNull.orEmpty(); is JsonArray -> c.joinToString("\n") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }; else -> "" }
                            PR_URL.findAll(text).forEach { found += it.value }
                        }
                    }
                }
            }
        }
        return found.toList()
    }
}
