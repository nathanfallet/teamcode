package me.nathanfallet.teamcode.cli.cloud.agent

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.RunIdentifiers
import me.nathanfallet.teamcode.api.models.WorkflowSessionPendingView
import me.nathanfallet.teamcode.cli.launch.AgentEnvironment
import me.nathanfallet.teamcode.cli.mcp.relayClient

/**
 * `teamcode cloud workflow-stop --relay URL --session ID [--run ID]`: the `Stop` hook of a workflow step's agent
 * (Claude Code, docs/workflows.md "Stop hook"). Reads the hook input on standard input (at most 8 MiB), asks the launcher's
 * loopback relay whether the session still has to deliver or ask ([WorkflowStop.decide]) and prints Claude Code's
 * `{"decision":"block","reason":"..."}` when it does. Anything else prints nothing and exits 0, so a failure of TeamCode never
 * keeps the agent from stopping. The input's content (the last message, the transcript path) is not read, kept or logged.
 */
internal class CloudWorkflowStop : SuspendingCliktCommand(name = "workflow-stop") {
    override val hiddenFromHelp = true
    override fun help(context: Context) = "Send the agent of a workflow step back to work once if it stops without delivering or asking (run by the agent's Stop hook, not by hand)."
    private val relay by option("--relay", help = "The launcher's loopback relay, http://127.0.0.1:<port>.").required()
    private val session by option("--session", help = "The session the workflow step runs in.").required()
    private val run by option("--run", help = "The agent run this hook belongs to.")
    override suspend fun run() {
        val input = StringBuilder()
        for (line in generateSequence(::readlnOrNull)) {
            if (input.length + line.length > MAX_INPUT_CHARS) break
            input.append(line).append('\n')
        }
        val answer = WorkflowStop.decide(input.toString()) {
            if (!SESSION_ID.matches(session) || run?.let { RunIdentifiers.isValid(it) } == false) null
            else relayClient("TEAMCODE_WORKFLOW", run, relay).use { it.pendingWorkflowSession(session) }
        }
        if (answer != null) echo(answer)
    }
    private companion object { const val MAX_INPUT_CHARS = 8 * 1_048_576; val SESSION_ID = Regex("[a-zA-Z0-9-]{1,36}") }
}

internal object WorkflowStop {
    /** How long the question to the relay may take: a slower server is an infrastructure problem, never a reason to hold the agent. */
    const val TIMEOUT_MILLIS = 2_000L

    /**
     * The hook's output for one `Stop` [input], or null for no output. [pending] asks the relay (null: no relay).
     * Never blocks on: `stop_hook_active` (the agent was already sent back once: it ends, and the run then fails with
     * `ended_without_delivery`), an input that is not a `Stop` hook object, a relay that is missing, slow, older or
     * failing, or a session with nothing to do.
     */
    suspend fun decide(input: String, pending: suspend () -> WorkflowSessionPendingView?): String? {
        val body = try { Json.parseToJsonElement(input) as? JsonObject } catch (_: Exception) { null } ?: return null
        val event = (body["hook_event_name"] as? JsonPrimitive)?.contentOrNull
        if (event != null && event != "Stop") return null
        if ((body["stop_hook_active"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val view = try { withTimeoutOrNull(TIMEOUT_MILLIS) { pending() } } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        if (view == null || !view.needsAction) return null
        return buildJsonObject { put("decision", "block"); put("reason", reason(view)) }.toString()
    }

    /** What the agent is told: the step, what it expects, and the two ways out. Names and text only from the workflow's definition. */
    fun reason(view: WorkflowSessionPendingView): String {
        val title = view.nodeTitle?.let { clean(it) }?.takeIf { it.isNotEmpty() }
        val step = if (title == null) "a workflow step" else "the workflow step \"$title\""
        return "You are working on $step and your turn is ending without having delivered or asked a person. " +
            "Call `deliver` of the `teamcode_workflow` MCP server with the result (${expected(view)}), " +
            "or, if you need a person's answer, call `ask_human` of the `teamcode_waits` MCP server. Then end your turn."
    }

    private fun expected(view: WorkflowSessionPendingView): String = when (view.deliverable?.type) {
        "artifact" -> "expected: the id of an artifact"
        "url" -> "expected: an https address" + (view.deliverable?.domain?.let { clean(it) }?.takeIf { it.isNotEmpty() }?.let { " on $it" } ?: "")
        "text" -> "expected: the text"
        "json" -> "expected: a JSON value, as the step's instruction describes"
        else -> "exactly what the step's instruction asked for"
    }

    /** A title is written by the team: one line, no control characters, bounded. */
    private fun clean(text: String) = text.filter { !it.isISOControl() }.trim().take(120)

    /** The hook as a shell command, for the agent's settings: the binary, the relay of this launch, the session and the run. */
    fun hookCommand(binary: String, port: Int, sessionId: String, runId: String?): String {
        require(port in 1..65535 && Regex("[a-zA-Z0-9-]{1,36}").matches(sessionId) && (runId == null || RunIdentifiers.isValid(runId)))
        return "${AgentEnvironment.quote(binary)} cloud workflow-stop --relay http://127.0.0.1:$port --session $sessionId" + (runId?.let { " --run $it" } ?: "")
    }
}
