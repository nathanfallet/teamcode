package me.nathanfallet.teamcode.cli.remote

import com.github.ajalt.clikt.core.CliktError
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.api.BuildInfo
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.cloud.RunnerCalls
import me.nathanfallet.teamcode.cli.cloud.RunnerLoop
import me.nathanfallet.teamcode.cli.cloud.agent.*
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.hooks.HookEvents
import me.nathanfallet.teamcode.cli.hooks.teamHookSync
import me.nathanfallet.teamcode.cli.hooks.teamHooksSupported
import me.nathanfallet.teamcode.cli.launch.AgentEnvironment
import me.nathanfallet.teamcode.cli.launch.ArtifactsMcp
import me.nathanfallet.teamcode.cli.launch.ENVIRONMENT_CAPABILITY
import me.nathanfallet.teamcode.cli.launch.GuidancePlugin
import me.nathanfallet.teamcode.cli.launch.trackedRun
import me.nathanfallet.teamcode.cli.platform.installServiceSignalHandlers
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.platform.serviceStopRequested
import me.nathanfallet.teamcode.cli.platform.servicePlatform
import me.nathanfallet.teamcode.cli.platform.startAgentProcess
import me.nathanfallet.teamcode.cli.proxy.proxyServer
import me.nathanfallet.teamcode.cli.service.loadService
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.Path.Companion.toPath

/** The run label that ties the host's registered run to its session, for quotas, budgets and the runs page. */
internal const val SESSION_RUN_LABEL = "teamcode.session"

/**
 * Hosts a remote session on this device until it pauses, is deleted or is taken over by another host: the cloud
 * runner's lifecycle ([RunnerLoop]: register, heartbeats every 15 s, drain and checkpoint on a pause) and agent host
 * ([AgentHost]: the agent in stream-json or app-server mode, transcript upload, input long-poll), called with the member's
 * own login instead of a pod's token. The agent's inference goes through a loopback proxy in this process, exactly as
 * under `teamcode run`, for a registered run labelled with the session; it gets the team's MCP servers, the member's
 * approved team hooks (Claude Code), the artifact tools and the member's environment variables. Nothing is written in
 * the session's directory by TeamCode itself; the conversation id stays beside `config.json`.
 *
 * SIGINT or SIGTERM asks the server for a pause: the agent finishes its turn (up to the drain deadline), the host
 * checkpoints and exits. Returns the process exit code.
 */
internal suspend fun hostRemoteSession(client: TeamCodeClient, record: RemoteSessionRecord, report: (String) -> Unit,
    stopRequested: () -> Boolean = ::serviceStopRequested): Int = coroutineScope {
    val features = client.capabilities().features
    val hosted = try { client.hostCloudSession(record.sessionId) }
        catch (error: CancellationException) { throw error }
        catch (error: TeamCodeException) { throw CliktError("The session cannot run here (${error.describe()}).") }
    val team = hosted.teamId; val session = record.sessionId; val epoch = hosted.epoch
    report("Hosting ${hosted.session.name} (epoch $epoch) in ${record.directory}.")
    // An update of the CLI waits for this host to end before it replaces the binary under it.
    val lease = me.nathanfallet.teamcode.cli.update.agentInstallationLease(loadService(configPath())?.executable, report)
    try {
        trackedRun(client, record.agent, "interactive", "team.runs.v1" in features, report = report, labels = mapOf(SESSION_RUN_LABEL to session)) { runId ->
            hostExecution(client, record, team, epoch, runId, features, report, stopRequested)
        }
    } finally {
        try { lease?.close() } finally {
            lease?.let { withContext(NonCancellable) { me.nathanfallet.teamcode.cli.update.applyPendingServiceRestart(it.store, report = report) } }
        }
    }
}

private suspend fun hostExecution(client: TeamCodeClient, record: RemoteSessionRecord, team: String, epoch: Int, runId: String?, features: List<String>,
    report: (String) -> Unit, stopRequested: () -> Boolean): Int = coroutineScope {
    val session = record.sessionId
    val calls = object : RunnerCalls {
        override suspend fun register() = client.runnerRegister(RunnerRegisterRequest(team, session, epoch))
        override suspend fun heartbeat(ready: Boolean) = client.runnerHeartbeat(RunnerHeartbeatRequest(team, session, epoch, ready))
        override suspend fun checkpoint(completed: Boolean) = client.runnerCheckpoint(RunnerCheckpointRequest(team, session, epoch, completed))
    }
    val channel = object : AgentChannel {
        override suspend fun agent() = client.runnerAgent(RunnerAgentRequest(team, session, epoch))
        override suspend fun events(events: List<RunnerEventView>) = client.runnerEvents(RunnerEventsRequest(team, session, epoch, events))
        override suspend fun inputs(waitMillis: Long) = client.runnerInputs(RunnerInputsRequest(team, session, epoch, waitMillis)).inputs
        override suspend fun poll(waitMillis: Long, desiredState: String) = client.runnerInputs(RunnerInputsRequest(team, session, epoch, waitMillis, desiredState))
        override suspend fun acknowledge(acks: List<RunnerInputAckView>) = client.runnerAcknowledge(RunnerInputAckRequest(team, session, epoch, acks))
    }
    val hooks = if (record.agent == "claude") approvedHooks(client, features, report) else emptyList()
    val plugin = if (hooks.isEmpty()) null else try { GuidancePlugin.create(configPath(), null, hooks) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { report("The team hooks could not be prepared; the agent starts without them."); null }
    val localToken = secureRandomBytes(32).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    val proxy = proxyServer(client, localToken, 0) { report(it) }
    try {
        proxy.start(wait = false)
        // Again after the proxy, whose engine installs its own handlers.
        installServiceSignalHandlers()
        val port = proxy.engine.resolvedConnectors().single().port
        val tools = HostAgentTools(client, record.directory, features, plugin?.directory?.toString(), report)
        val variables = if (ENVIRONMENT_CAPABILITY in features) AgentVariables { _ -> memberVariables(client, report) } else null
        val agent = AgentHost(channel, { command, overrides, directory -> startAgentProcess(command.command, command.arguments, overrides, directory) },
            RemoteSessions().conversation(session), record.directory, BuildInfo.VERSION, log = report,
            inference = { AgentInferenceEndpoint(port, localToken, runId) }, tools = tools, variables = variables)
        val loop = RunnerLoop(calls, log = report, agent = agent)
        val work = async {
            try { loop.run() }
            catch (error: RunnerLoop.RunnerRefused) {
                report("The server refused this host (HTTP ${error.status}): sign in again with teamcode login, or check that this device was not removed.")
                RunnerLoop.EXIT_REFUSED
            }
        }
        var aborted = false
        val stop = launch { pauseOnSignal(client, session, stopRequested, report) { aborted = true; work.cancel() } }
        try { work.await() }
        catch (error: CancellationException) {
            // Our own cancellation propagates; only the abort after a failed pause request ends the host normally.
            currentCoroutineContext().ensureActive()
            if (!aborted) throw error
            RunnerLoop.EXIT_FENCED_OR_DONE
        } finally { stop.cancel() }
    } finally {
        withContext(NonCancellable) {
            proxy.stop(500, 5_000)
            try { plugin?.close() } catch (_: Exception) { report("The temporary team hooks plugin could not be removed.") }
        }
    }
}

/**
 * On SIGINT or SIGTERM, asks the server to pause the session: the runner loop sees it, drains the turn, checkpoints and
 * exits. When the pause cannot be requested (the server is unreachable), [abort] stops the host now; the server then
 * pauses the session itself 90 s after the last heartbeat.
 */
private suspend fun pauseOnSignal(client: TeamCodeClient, session: String, stopRequested: () -> Boolean, report: (String) -> Unit, abort: () -> Unit) {
    while (!stopRequested()) delay(SIGNAL_POLL_MILLIS)
    report("Pausing the session: the agent finishes its turn, then the host checkpoints and exits.")
    try { client.pauseCloudSession(session) }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { report("The pause could not be requested; stopping now."); abort() }
}

/** The member's environment variables for each agent start; a failed read starts the agent without them. Only names are printed. */
private suspend fun memberVariables(client: TeamCodeClient, report: (String) -> Unit): Map<String, String> = try {
    val view = client.environmentVariables()
    if (view.unavailable.isNotEmpty()) report("Environment variables unavailable right now: ${view.unavailable.sorted().joinToString(", ")}.")
    AgentEnvironment.accepted(view.variables)
} catch (error: CancellationException) { throw error }
catch (_: Exception) { report("Environment variables could not be read; starting the agent without them."); emptyMap() }

/** The team hooks this member approved on this device, as under `teamcode run`; never blocks the host. */
private suspend fun approvedHooks(client: TeamCodeClient, features: List<String>, report: (String) -> Unit) =
    if ("team.hooks.v1" !in features || !teamHooksSupported()) emptyList() else try {
        val plan = teamHookSync(client).plan()
        if (plan.awaiting.isNotEmpty()) report("Team hooks awaiting your approval: ${plan.awaiting.joinToString(", ") { it.name }}; review them with teamcode hooks list.")
        plan.installed.filter { HookEvents.forAgent("claude", it.definition).isNotEmpty() }
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { report("Team hooks could not be read; continuing without them."); emptyList() }

/** The team's tools on the member's own device: MCP servers through the local proxy, the artifact tools rooted at the directory. */
private class HostAgentTools(
    private val client: TeamCodeClient,
    private val directory: String,
    private val features: List<String>,
    private val plugin: String?,
    private val report: (String) -> Unit,
) : AgentTools {
    override suspend fun prepare(agent: String): List<String> = if ("mcp.gateway.v1" !in features) emptyList() else try {
        withTimeout(20_000) { client.mcpServers() }.map { it.name }.filter { TeamCodeClient.MCP_SERVER_NAME.matches(it) }.distinct()
    } catch (error: TimeoutCancellationException) { report("Team MCP servers could not be listed; the agent starts without them."); emptyList() }
    catch (error: CancellationException) { throw error }
    catch (_: Exception) { report("Team MCP servers could not be listed; the agent starts without them."); emptyList() }

    override suspend fun artifacts(): ArtifactsMcp? {
        if ("artifacts.policy.v1" !in features) return null
        val allowed = try { withTimeout(20_000) { client.artifactPolicy() }.canPublish }
            catch (_: TimeoutCancellationException) { false }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { false }
        return if (allowed) ArtifactsMcp(servicePlatform().executable, directory, configDir = configPath().toPath().parent?.toString()) else null
    }

    override suspend fun claudePlugin() = plugin
}

private const val SIGNAL_POLL_MILLIS = 100L
