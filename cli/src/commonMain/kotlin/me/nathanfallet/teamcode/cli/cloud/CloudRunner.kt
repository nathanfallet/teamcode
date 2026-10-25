package me.nathanfallet.teamcode.cli.cloud

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.SystemExit
import me.nathanfallet.teamcode.api.BuildInfo
import me.nathanfallet.teamcode.cli.cloud.agent.*
import me.nathanfallet.teamcode.cli.platform.AgentProcess
import me.nathanfallet.teamcode.cli.platform.UnixConnection
import me.nathanfallet.teamcode.cli.platform.listenUnix
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.platform.startAgentProcess
import me.nathanfallet.teamcode.cli.proxy.proxyServer
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.launch.AgentEnvironment
import me.nathanfallet.teamcode.cli.platform.installServiceSignalHandlers
import me.nathanfallet.teamcode.cli.platform.serviceStopRequested
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.FileSystem
import okio.Path.Companion.toPath

/** The runner's calls to the server, bound to one session epoch. */
internal interface RunnerCalls {
    suspend fun register(): RunnerInstructionView
    suspend fun heartbeat(ready: Boolean): RunnerInstructionView
    /** A heartbeat that also says whether the agent has background work (subagents, background commands, scheduled wake-ups). */
    suspend fun heartbeat(ready: Boolean, background: Boolean): RunnerInstructionView = heartbeat(ready)
    suspend fun checkpoint(completed: Boolean): RunnerInstructionView
}

/**
 * The runner: register, prepare the environment while heartbeating every 15 s, report ready once prepared, and
 * checkpoint when a pause is requested. The loop does not wait for the next 15-second tick when something changes:
 * the end of the preparation (ready is reported at once), the end of the drain (the checkpoint is sent at once) and a
 * change of the desired state seen by the agent's input long-poll ([wake]) all start the next heartbeat right away.
 * Each heartbeat also says whether the agent has background work ([RunnerAgent.backgroundWork]), which the server's idle
 * pause counts for a bounded time.
 * Once the server accepted the checkpoint the runner exits, so the pod stops without waiting for its grace period.
 *
 * `409 execution_fenced` means this execution is fenced: stop and exit without checkpointing; any other `409` is
 * retried. A `401` means the pod's token is not accepted. Network failures are retried; the server marks the execution lost after 90 s without heartbeats.
 */
internal class RunnerLoop(
    private val calls: RunnerCalls,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
    private val maxIterations: Int = Int.MAX_VALUE,
    /**
     * Prepares the environment after registration, concurrently with the heartbeats (a clone or a dependency
     * install can outlast the 90-second heartbeat deadline). The runner reports ready only once it returns true;
     * after a failure it keeps heartbeating unready until the server stops the pod.
     */
    private val prepare: suspend () -> Boolean = { true },
    /** The session's agent; null runs the lifecycle alone. */
    private val agent: RunnerAgent? = null,
) {
    /** Wakes the heartbeat loop before its next tick; conflated, so several reasons make one early heartbeat. */
    private val wakeUps = Channel<Unit>(Channel.CONFLATED)

    init { agent?.onInstructionChange(::wake) }

    /** Sends the next heartbeat now rather than at the next tick (the agent saw the desired state change). */
    fun wake() { wakeUps.trySend(Unit) }

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
        var prepared = false
        val preparation = scope.launch {
            prepared = try { prepare() }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { log("The environment could not be prepared."); false }
            if (prepared) { log("Environment prepared."); started(); wake() }
        }
        return try { heartbeats(scope) { prepared } } finally { preparation.cancel() }
    }

    private suspend fun heartbeats(scope: CoroutineScope, prepared: () -> Boolean): Int {
        // The agent drains beside the heartbeats: a silent runner would be declared lost after 90 s.
        var draining: Deferred<Boolean>? = null
        var iterations = 0
        while (iterations++ < maxIterations) {
            yield() // Let the preparation progress between heartbeats.
            if (agent?.fenced == true) { log("This execution is fenced; exiting without a checkpoint."); return EXIT_FENCED_OR_DONE }
            // This heartbeat answers every wake-up so far (a preparation that ended before the first one, in particular).
            wakeUps.tryReceive()
            val ready = prepared()
            // The agent's background work keeps the session from the idle pause; it only runs once the environment is ready.
            val background = ready && agent?.backgroundWork == true
            val instruction = when (val outcome = attempt { calls.heartbeat(ready = ready, background = background) }) {
                is Outcome.Done -> outcome.instruction
                Outcome.Fenced -> return EXIT_FENCED_OR_DONE
                Outcome.Conflict -> null
            }
            when (instruction?.desiredState) {
                "terminated" -> { log("The session is being deleted."); agent?.sessionEnded(); return EXIT_FENCED_OR_DONE }
                // Nothing to checkpoint before the environment is ready: the server stops a resume it cancels.
                "paused" -> if (ready) {
                    val drain = draining ?: (if (agent == null) CompletableDeferred(true)
                        else scope.async { agent.drain(instruction.pauseDeadline) }.also { it.invokeOnCompletion { wake() } }).also { draining = it }
                    if (drain.isCompleted) when (attempt { calls.checkpoint(completed = drain.await()) }) {
                        // Nothing is left to do here: exiting stops the pod now instead of after the kubelet's grace period.
                        is Outcome.Done -> { log("Checkpoint complete; exiting."); return EXIT_FENCED_OR_DONE }
                        Outcome.Fenced -> return EXIT_FENCED_OR_DONE
                        Outcome.Conflict -> Unit // The pause may have been cancelled; the next heartbeat tells.
                    }
                }
                "running" -> if (draining != null) { draining?.cancel(); draining = null; agent?.resumeAfterDrain() }
            }
            nextTick()
        }
        return EXIT_FENCED_OR_DONE
    }

    /** Waits for the next heartbeat: [HEARTBEAT_MILLIS], or less when [wake] is called meanwhile. */
    private suspend fun nextTick() = coroutineScope {
        val tick = launch { sleep(HEARTBEAT_MILLIS) }
        select { wakeUps.onReceive {}; tick.onJoin {} }
        tick.cancel()
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
        // The runner is PID 1 of its container, where a signal without a handler is ignored and the kubelet would wait
        // for its whole grace period: the whole run, start-up included, stops on SIGTERM.
        installServiceSignalHandlers()
        val code = untilTerminated({ echo(it, err = true) }) { client.use {
            val calls = object : RunnerCalls {
                override suspend fun register() = client.runnerRegister(RunnerRegisterRequest(team, session, epoch))
                override suspend fun heartbeat(ready: Boolean) = heartbeat(ready, background = false)
                override suspend fun heartbeat(ready: Boolean, background: Boolean) = client.runnerHeartbeat(RunnerHeartbeatRequest(team, session, epoch, ready, background))
                override suspend fun checkpoint(completed: Boolean) = client.runnerCheckpoint(RunnerCheckpointRequest(team, session, epoch, completed))
            }
            val preparation = object : PreparationCalls {
                override suspend fun environment() = client.runnerEnvironment(RunnerEnvironmentRequest(team, session, epoch))
                override suspend fun progress(phase: String, failure: RunnerPreparationFailureView?) =
                    client.runnerPreparation(RunnerPreparationRequest(team, session, epoch, phase, failure))
            }
            val log: (String) -> Unit = { echo(it, err = true) }
            val workspace = EnvironmentPreparation.WORKSPACE
            val agentChannel = object : AgentChannel {
                override suspend fun agent() = client.runnerAgent(RunnerAgentRequest(team, session, epoch))
                override suspend fun events(events: List<RunnerEventView>) = client.runnerEvents(RunnerEventsRequest(team, session, epoch, events))
                override suspend fun inputs(waitMillis: Long) = client.runnerInputs(RunnerInputsRequest(team, session, epoch, waitMillis)).inputs
                override suspend fun poll(waitMillis: Long, desiredState: String, acceptsFiles: Boolean) =
                    client.runnerInputs(RunnerInputsRequest(team, session, epoch, waitMillis, desiredState, acceptsFiles))
                override suspend fun acknowledge(acks: List<RunnerInputAckView>) = client.runnerAcknowledge(RunnerInputAckRequest(team, session, epoch, acks))
                override suspend fun file(fileId: String, maxBytes: Long, receive: (ByteArray, Int) -> Unit) = client.runnerFile(team, session, epoch, fileId, maxBytes, receive)
            }
            // The agent's inference goes through a loopback proxy in this process, authenticated to the server with
            // the execution's `tcex_` credential; the agent only gets the proxy's random local token.
            val inference = RunnerInference({ client.runnerInference(RunnerInferenceRequest(team, session, epoch)) }, log = { echo(it, err = true) })
            val inferenceClient = TeamCodeClient(server, { inference.token() }) { configure -> HttpClient { configure(this) } }
            val localToken = secureRandomBytes(32).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            val scope = CoroutineScope(SupervisorJob())
            var lifeline: UnixConnection? = null
            var githubCli: GitHubCliRenewal? = null
            var githubCliJob: Job? = null
            val proxy = proxyServer(inferenceClient, localToken, 0, upstreamReady = { inference.available() }) { echo(it, err = true) }
            try {
                proxy.start(wait = false)
                // Again after the proxy, whose engine installs its own handlers.
                installServiceSignalHandlers()
                val port = proxy.engine.resolvedConnectors().single().port
                // The runner's state stays on the workspace volume, so the conversation id survives pauses and new pods.
                // Its HOME does not: the pod gives it a volume of its own, which the agent's container never mounts.
                val home = "$workspace/$RUNNER_STATE_DIR"
                // In a session pod the agent runs in its own container under another UID: processes start through the
                // supervisor there, and the helper in that container fetches git credentials from this process.
                val processes = environment("TEAMCODE_AGENT_SOCKET")?.takeIf { it.isNotBlank() }?.let { RemoteProcesses(it, scope, log = { message -> echo(message, err = true) }) }
                lifeline = processes?.let { try { it.hold() } catch (error: CancellationException) { throw error } catch (_: Exception) { echo("The agent container is not reachable.", err = true); null } }
                // The one GitHub source: the execution owner's token, renewed through the runner channel, served to the
                // agent's git helper and written to its `gh` configuration, so `git` and `gh` always hold the same token.
                // A server that does not advertise GitHub answers an error, which the source treats as no credential.
                val github = GitHubCredentialSource({ client.runnerGitHub(RunnerGitHubRequest(team, session, epoch)) })
                environment("TEAMCODE_CREDENTIAL_SOCKET")?.takeIf { it.isNotBlank() }?.let { socket ->
                    val broker = CredentialBroker(listenUnix(socket), listOf(github), log = { message -> echo(message, err = true) })
                    scope.launch { broker.serve() }
                }
                // The agent's `gh` reads the owner's token from a memory volume only this container writes.
                githubCli = environment("TEAMCODE_GH_CONFIG_DIR")?.takeIf { it.isNotBlank() }?.let { directory ->
                    GitHubCliRenewal(github, GitHubCliConfig(directory), log = { message -> echo(message, err = true) })
                }
                val startProcess: suspend (AgentCommand, Map<String, String?>, String) -> AgentProcess = { command, overrides, directory ->
                    processes?.start(command.command, command.arguments, overrides, directory) ?: startAgentProcess(command.command, command.arguments, overrides, directory)
                }
                // Skills are installed by this binary in the agent's container, under the agent's UID, in its home.
                val binary = environment("TEAMCODE_CLOUD_BINARY")?.takeIf { it.isNotBlank() } ?: "/opt/teamcode/teamcode"
                // The owner's environment variables: in the agent's environment at each start and, for Claude Code,
                // asked of this process again before each Bash command through a script that holds no value.
                val variables = RunnerVariables({ client.runnerVariables(RunnerVariablesRequest(team, session, epoch)) }, log = { echo(it, err = true) })
                // The agent's `Stop` hook reports its scheduled wake-ups on the same socket; the agent host is built below.
                var wakeups: (Int) -> Unit = {}
                var stateHook: String? = null
                val environmentScript = environment("TEAMCODE_CREDENTIAL_SOCKET")?.takeIf { it.isNotBlank() }?.let { credential ->
                    try {
                        val socket = EnvironmentBroker.socketFor(credential)
                        val broker = EnvironmentBroker(listenUnix(socket), variables, log = { message -> echo(message, err = true) }, wakeups = { wakeups(it) })
                        stateHook = "${AgentEnvironment.quote(binary)} cloud agent-state --socket ${AgentEnvironment.quote(socket)}"
                        scope.launch { broker.serve() }
                        EnvironmentBroker.scriptFor(credential).also { path ->
                            FileSystem.SYSTEM.write(path.toPath()) { writeUtf8(AgentEnvironment.cloudScript(binary, socket)) }
                        }
                    } catch (error: CancellationException) { throw error } catch (_: Exception) {
                        echo("The environment socket could not be opened; the agent gets its variables when it starts only.", err = true); null
                    }
                }
                val agentVariables = AgentVariables { kind ->
                    variables.forStart() + (environmentScript?.takeIf { kind == "claude" }?.let { mapOf(AgentEnvironment.CLAUDE_ENV_FILE to it) }.orEmpty())
                }
                val tools = RunnerAgentTools(inferenceClient, { arguments ->
                    startProcess(AgentCommand(binary, arguments), AgentHost.AGENT_ENVIRONMENT, environment("TEAMCODE_CLOUD_WORKDIR")?.takeIf { it.isNotBlank() } ?: "/workspace")
                }, log = { echo(it, err = true) }, binary = binary,
                    workspace = environment("TEAMCODE_CLOUD_WORKDIR")?.takeIf { it.isNotBlank() } ?: "/workspace", home = home)
                val agent = AgentHost(agentChannel, startProcess,
                    FileAgentStateStore("$home/.teamcode/cloud-agent.json"), environment("TEAMCODE_CLOUD_WORKDIR")?.takeIf { it.isNotBlank() } ?: "/workspace",
                    BuildInfo.VERSION, log = { echo(it, err = true) }, inference = {
                        // Fetched before the agent starts, so a server without the route is known and the agent keeps its own configuration.
                        inference.available()
                        if (inference.unsupported) null else AgentInferenceEndpoint(port, localToken)
                    }, tools = tools, variables = agentVariables,
                    // The pod's `uploads` volume, mounted read-only in the agent's container; an older pod spec has none.
                    uploads = environment("TEAMCODE_UPLOADS_DIR")?.takeIf { it.isNotBlank() }?.let { SessionUploads.pod(it) }, stateHook = stateHook)
                wakeups = agent::scheduledWakeups
                // Preparation commands run where the agent runs, never beside the projected token; the runner masks their
                // output and a writer in the agent's container keeps the log.
                val logFile = EnvironmentPreparation.logFile(workspace, epoch)
                val commands: CommandRunner = processes?.let { remote ->
                    SupervisedCommandRunner(workspace, { command, arguments, overrides, directory -> remote.start(command, arguments, overrides, directory) }) {
                        ProcessLogSink(remote.start(binary, listOf("cloud", "preparation-log", logFile), emptyMap(), workspace))
                    }
                } ?: ShellCommandRunner(logFile)
                val prepare: suspend () -> Boolean = {
                    val prepared = EnvironmentPreparation(preparation, commands, workspace, log = log).run()
                    // The first write happens here, after registration and before the agent starts, so its first `gh` is authenticated.
                    githubCli?.takeIf { prepared && githubCliJob == null }?.let { renewal ->
                        val wait = renewal.refresh()
                        githubCliJob = scope.launch { renewal.run(wait) }
                    }
                    prepared
                }
                try { RunnerLoop(calls, log = log, prepare = prepare, agent = agent).run() }
                catch (error: RunnerLoop.RunnerRefused) { echo("The server refused this runner (HTTP ${error.status}).", err = true); RunnerLoop.EXIT_REFUSED }
            } finally {
                withContext(NonCancellable) {
                    // Joined, so the owner's token is removed from the gh configuration before the process exits.
                    githubCliJob?.cancelAndJoin()
                    githubCli?.clear()
                    scope.cancel()
                    lifeline?.close()
                    inference.clear()
                    proxy.stop(500, 5_000)
                    inferenceClient.close()
                }
            }
        } }
        if (code != 0) SystemExit.exit(code)
    }
}

/**
 * Runs [block] until it returns or the process receives SIGTERM or SIGINT, which stops it (its `finally` blocks run)
 * and returns 0. The cluster sends SIGTERM when it stops the pod: after a checkpoint, on deletion or on eviction.
 */
internal suspend fun untilTerminated(log: (String) -> Unit, stopRequested: () -> Boolean = ::serviceStopRequested, block: suspend () -> Int): Int = coroutineScope {
    val work = async { block() }
    val watch = launch {
        while (!stopRequested()) delay(SIGNAL_POLL_MILLIS)
        log("Stopping on a termination signal.")
        work.cancel()
    }
    try { work.await() }
    catch (error: CancellationException) {
        // Our own cancellation propagates; only the signal's ends the work normally.
        currentCoroutineContext().ensureActive()
        if (!stopRequested()) throw error
        RunnerLoop.EXIT_FENCED_OR_DONE
    } finally { watch.cancel() }
}

private const val SIGNAL_POLL_MILLIS = 100L
/** The runner's state on the workspace (the agent's conversation id), below `/workspace`; formerly its `HOME`. */
internal const val RUNNER_STATE_DIR = ".home"
