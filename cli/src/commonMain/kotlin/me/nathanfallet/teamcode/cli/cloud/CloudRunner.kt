package me.nathanfallet.teamcode.cli.cloud

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.SystemExit
import me.nathanfallet.teamcode.api.BuildInfo
import me.nathanfallet.teamcode.cli.cloud.agent.*
import me.nathanfallet.teamcode.cli.platform.startAgentProcess
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.FileSystem
import okio.Path.Companion.toPath

/** The runner's calls to the server, bound to one session epoch. */
internal interface RunnerCalls {
    suspend fun register(): RunnerInstructionView
    suspend fun heartbeat(ready: Boolean): RunnerInstructionView
    suspend fun checkpoint(completed: Boolean): RunnerInstructionView
}

/**
 * The runner skeleton: register, report ready, heartbeat every 15 s, and checkpoint when a pause is requested.
 * It starts no agent yet (the agent, its loopback proxy and transcript come with later releases), so there is
 * nothing to drain and the checkpoint is immediately complete.
 *
 * `409 execution_fenced` means this execution is fenced: stop and exit without checkpointing; any other `409` is
 * retried. A `401` means the pod's token is not accepted. Network failures are retried; the server marks the execution lost after 90 s without heartbeats.
 */
internal class RunnerLoop(
    private val calls: RunnerCalls,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
    private val maxIterations: Int = Int.MAX_VALUE,
    /** The session's agent; null runs the lifecycle alone. */
    private val agent: RunnerAgent? = null,
) {
    private sealed interface Outcome {
        data class Done(val instruction: RunnerInstructionView) : Outcome
        /** `409 execution_fenced`: stop without a checkpoint. */
        data object Fenced : Outcome
        /** Another `409` (a pause cancelled before the checkpoint landed, a concurrent write): try again later. */
        data object Conflict : Outcome
    }

    /** Returns the process exit code. The agent runs beside the heartbeats and is always stopped on the way out. */
    suspend fun run(): Int = coroutineScope {
        var agentJob: Job? = null
        try { lifecycle(this) { agentJob = agent?.let { host -> launch { host.run() } } } }
        finally { agent?.stop(); agentJob?.cancel() }
    }

    private suspend fun lifecycle(scope: CoroutineScope, started: () -> Unit): Int {
        var registered: RunnerInstructionView? = null
        while (registered == null) {
            when (val outcome = attempt { calls.register() }) {
                is Outcome.Done -> registered = outcome.instruction
                Outcome.Fenced -> return EXIT_FENCED_OR_DONE
                Outcome.Conflict -> sleep(HEARTBEAT_MILLIS)
            }
        }
        log("Registered for epoch ${registered.epoch}.")
        started()
        var checkpointed = false
        // The agent drains beside the heartbeats: a silent runner would be declared lost after 90 s.
        var draining: Deferred<Boolean>? = null
        var iterations = 0
        while (iterations++ < maxIterations) {
            if (agent?.fenced == true) { log("This execution is fenced; exiting without a checkpoint."); return EXIT_FENCED_OR_DONE }
            val instruction = when (val outcome = attempt { calls.heartbeat(ready = true) }) {
                is Outcome.Done -> outcome.instruction
                Outcome.Fenced -> return EXIT_FENCED_OR_DONE
                Outcome.Conflict -> null
            }
            when (instruction?.desiredState) {
                "terminated" -> { log("The session is being deleted."); return EXIT_FENCED_OR_DONE }
                "paused" -> if (!checkpointed) {
                    val drain = draining ?: (if (agent == null) CompletableDeferred(true) else scope.async { agent.drain(instruction.pauseDeadline) }).also { draining = it }
                    if (drain.isCompleted) when (attempt { calls.checkpoint(completed = drain.await()) }) {
                        is Outcome.Done -> { checkpointed = true; log("Checkpoint complete; waiting for the pod to stop.") }
                        Outcome.Fenced -> return EXIT_FENCED_OR_DONE
                        Outcome.Conflict -> Unit // The pause may have been cancelled; the next heartbeat tells.
                    }
                }
                "running" -> {
                    if (draining != null) { draining?.cancel(); draining = null; agent?.resumeAfterDrain() }
                    checkpointed = false
                }
            }
            sleep(HEARTBEAT_MILLIS)
        }
        return EXIT_FENCED_OR_DONE
    }

    /** Retries network failures and server errors with backoff; throws [RunnerRefused] for an authentication failure. */
    private suspend fun attempt(call: suspend () -> RunnerInstructionView): Outcome {
        var wait = 1_000L
        while (true) {
            try { return Outcome.Done(call()) }
            catch (error: CancellationException) { throw error }
            catch (error: TeamCodeException) {
                when {
                    error.code == "execution_fenced" -> { log("This execution is fenced; exiting without a checkpoint."); return Outcome.Fenced }
                    error.statusCode in setOf(401, 403, 404) -> throw RunnerRefused(error.statusCode)
                    error.statusCode == 409 -> { log("The server reported ${error.code ?: "a conflict"}; continuing."); return Outcome.Conflict }
                    else -> log("The server returned HTTP ${error.statusCode}; retrying.")
                }
            } catch (_: Exception) { log("The server is unreachable; retrying.") }
            sleep(wait)
            wait = (wait * 2).coerceAtMost(HEARTBEAT_MILLIS)
        }
    }

    class RunnerRefused(val status: Int) : Exception()

    companion object {
        const val HEARTBEAT_MILLIS = 15_000L
        const val EXIT_FENCED_OR_DONE = 0
        const val EXIT_REFUSED = 2
    }
}

/** `teamcode cloud runner`: started inside the session pod; reads its coordinates from the pod environment. */
internal class CloudRunner : SuspendingCliktCommand(name = "runner") {
    override val hiddenFromHelp = true
    override fun help(context: Context) = "Run the in-pod session runner (started by the cluster, not by hand)."
    override suspend fun run() {
        fun required(name: String) = requireNotNull(environment(name)?.takeIf { it.isNotBlank() }) { "Set $name." }
        val server = required("TEAMCODE_SERVER_URL")
        val tokenFile = required("TEAMCODE_CLOUD_TOKEN_FILE")
        val team = required("TEAMCODE_CLOUD_TEAM")
        val session = required("TEAMCODE_CLOUD_SESSION")
        val epoch = required("TEAMCODE_CLOUD_EPOCH").toInt()
        // The kubelet rotates the projected token: read it again for every call.
        val client = TeamCodeClient(server, { FileSystem.SYSTEM.read(tokenFile.toPath()) { readUtf8() }.trim() }) { configure -> HttpClient { configure(this) } }
        val code = client.use {
            val calls = object : RunnerCalls {
                override suspend fun register() = client.runnerRegister(RunnerRegisterRequest(team, session, epoch))
                override suspend fun heartbeat(ready: Boolean) = client.runnerHeartbeat(RunnerHeartbeatRequest(team, session, epoch, ready))
                override suspend fun checkpoint(completed: Boolean) = client.runnerCheckpoint(RunnerCheckpointRequest(team, session, epoch, completed))
            }
            val agentChannel = object : AgentChannel {
                override suspend fun agent() = client.runnerAgent(RunnerAgentRequest(team, session, epoch))
                override suspend fun events(events: List<RunnerEventView>) = client.runnerEvents(RunnerEventsRequest(team, session, epoch, events))
                override suspend fun inputs(waitMillis: Long) = client.runnerInputs(RunnerInputsRequest(team, session, epoch, waitMillis)).inputs
                override suspend fun acknowledge(acks: List<RunnerInputAckView>) = client.runnerAcknowledge(RunnerInputAckRequest(team, session, epoch, acks))
            }
            // HOME is on the workspace volume, so the conversation id survives pauses and new pods.
            val home = environment("HOME")?.takeIf { it.isNotBlank() } ?: "/workspace/.home"
            val agent = AgentHost(agentChannel, { command, overrides, directory -> startAgentProcess(command.command, command.arguments, overrides, directory) },
                FileAgentStateStore("$home/.teamcode/cloud-agent.json"), environment("TEAMCODE_CLOUD_WORKDIR")?.takeIf { it.isNotBlank() } ?: "/workspace",
                BuildInfo.VERSION, log = { echo(it, err = true) })
            try { RunnerLoop(calls, log = { echo(it, err = true) }, agent = agent).run() }
            catch (error: RunnerLoop.RunnerRefused) { echo("The server refused this runner (HTTP ${error.status}).", err = true); RunnerLoop.EXIT_REFUSED }
        }
        if (code != 0) SystemExit.exit(code)
    }
}
