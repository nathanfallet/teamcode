package me.nathanfallet.teamcode.cli.cloud

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.SystemExit
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
 * A `409` means this execution is fenced: stop and exit without checkpointing. A `401` means the pod's token is
 * not accepted. Network failures are retried; the server marks the execution lost after 90 s without heartbeats.
 */
internal class RunnerLoop(
    private val calls: RunnerCalls,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
    private val maxIterations: Int = Int.MAX_VALUE,
) {
    /** Returns the process exit code. */
    suspend fun run(): Int {
        var instruction = retrying { calls.register() } ?: return EXIT_FENCED_OR_DONE
        log("Registered for epoch ${instruction.epoch}.")
        var checkpointed = false
        var iterations = 0
        while (iterations++ < maxIterations) {
            instruction = retrying { calls.heartbeat(ready = true) } ?: return EXIT_FENCED_OR_DONE
            when (instruction.desiredState) {
                "terminated" -> { log("The session is being deleted."); return EXIT_FENCED_OR_DONE }
                "paused" -> if (!checkpointed) {
                    retrying { calls.checkpoint(completed = true) } ?: return EXIT_FENCED_OR_DONE
                    checkpointed = true
                    log("Checkpoint complete; waiting for the pod to stop.")
                }
            }
            sleep(HEARTBEAT_MILLIS)
        }
        return EXIT_FENCED_OR_DONE
    }

    /** Null when fenced; throws [RunnerRefused] for an authentication failure. */
    private suspend fun retrying(call: suspend () -> RunnerInstructionView): RunnerInstructionView? {
        var wait = 1_000L
        while (true) {
            try { return call() }
            catch (error: CancellationException) { throw error }
            catch (error: TeamCodeException) {
                when (error.statusCode) {
                    409 -> { log("This execution is fenced; exiting without a checkpoint."); return null }
                    401, 403, 404 -> throw RunnerRefused(error.statusCode)
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
            try { RunnerLoop(calls, log = { echo(it, err = true) }).run() }
            catch (error: RunnerLoop.RunnerRefused) { echo("The server refused this runner (HTTP ${error.status}).", err = true); RunnerLoop.EXIT_REFUSED }
        }
        if (code != 0) SystemExit.exit(code)
    }
}
