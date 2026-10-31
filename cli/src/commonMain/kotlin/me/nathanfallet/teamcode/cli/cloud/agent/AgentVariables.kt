package me.nathanfallet.teamcode.cli.cloud.agent

import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import me.nathanfallet.teamcode.api.models.EnvironmentVariablesView
import me.nathanfallet.teamcode.cli.launch.AgentEnvironment
import me.nathanfallet.teamcode.cli.platform.UnixConnection
import me.nathanfallet.teamcode.cli.platform.UnixListener
import me.nathanfallet.teamcode.cli.platform.connectUnix
import me.nathanfallet.teamcode.client.TeamCodeException

/** The environment settings an agent starts with: the owner's variables, and how its commands refresh them. */
internal fun interface AgentVariables {
    suspend fun launch(kind: String): Map<String, String>
}

/**
 * What one Bash command applies: the current variables, the wait [secrets] of the session (`TEAMCODE_WAIT_…`) and the names given
 * earlier and no longer assigned.
 */
@Serializable
internal class EnvironmentAnswer(val variables: Map<String, String> = emptyMap(), val withdrawn: List<String> = emptyList(), val secrets: Map<String, String> = emptyMap()) {
    override fun toString() = "EnvironmentAnswer(names=${variables.keys.sorted()}, secrets=${secrets.keys.sorted()}, withdrawn=$withdrawn, values=[REDACTED])"
}

/**
 * The execution owner's environment variables, held in the runner's memory only and read from the runner channel
 * (`POST /cloud/runner/variables`). An agent start always asks the server; a Bash command asks again when the last
 * answer is older than [maxAgeMillis], so a rotated, added or removed variable reaches the agent's next command
 * within that delay, and an idle session costs no request. A failed read keeps the last values and is retried after
 * [retryMillis]; a server without the route (404) is not asked again, and a fenced or refused execution serves nothing.
 *
 * The session's wait secrets (docs/waits.md#secrets) arrive with the variables but are kept apart: an agent start never gets them
 * in its process environment (which would outlive their expiry and their turn), only a Bash command does. A one-time secret, which
 * the server deleted by serving it, is held in memory until [endTurn] and then withdrawn; the others follow the server's answer.
 */
internal class RunnerVariables(
    private val fetch: suspend () -> EnvironmentVariablesView,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val maxAgeMillis: Long = MAX_AGE_MILLIS,
    private val retryMillis: Long = RETRY_MILLIS,
    private val commandWaitMillis: Long = COMMAND_WAIT_MILLIS,
    private val startWaitMillis: Long = START_WAIT_MILLIS,
    private val log: (String) -> Unit = {},
) {
    private val lock = Mutex()
    private var current: Map<String, String> = emptyMap()
    private var nextRead = 0L
    private var stopped = false
    private var failing = false
    private var lastSecretNames: Set<String> = emptySet()
    /** Every name handed to the agent, by its environment or by a command: a name that disappears is unset. */
    private val given = mutableSetOf<String>()
    private var secrets: Map<String, String> = emptyMap()
    /** One-time secrets the server no longer holds: kept here until the turn that uses them ends. */
    private val onceHeld = mutableMapOf<String, String>()

    /** The variables for an agent starting now, asked of the server first. */
    suspend fun forStart(): Map<String, String> = lock.withLock {
        read(startWaitMillis)
        current.also { given += it.keys }
    }

    /** The answer for one Bash command. */
    suspend fun forCommand(): EnvironmentAnswer = lock.withLock {
        if (now() >= nextRead) read(commandWaitMillis)
        val live = secrets + onceHeld
        EnvironmentAnswer(current, (given - current.keys - live.keys).sorted(), live).also { given += current.keys + live.keys }
    }

    /** The agent's turn ended: the one-time secrets it had are dropped, and the next command unsets them. */
    suspend fun endTurn() = lock.withLock { onceHeld.clear() }

    /** Asks the server, under [lock]; any failure keeps [current]. */
    private suspend fun read(waitMillis: Long) {
        if (stopped) return
        val view = try { withTimeout(waitMillis) { fetch() } }
            catch (error: TimeoutCancellationException) { failed(); return }
            catch (error: CancellationException) { throw error }
            catch (error: TeamCodeException) {
                when {
                    // Fenced or refused: another execution owns the session, so nothing is served any more.
                    error.code == "execution_fenced" || error.statusCode in setOf(401, 403) -> { stopped = true; current = emptyMap(); secrets = emptyMap(); onceHeld.clear() }
                    // A server without the route: the agent runs without variables, as before it existed.
                    error.statusCode == 404 -> { stopped = true; log("The server does not serve variables to running sessions; the agent gets none.") }
                    else -> failed()
                }
                return
            } catch (_: Exception) { failed(); return }
        if (failing) log("Environment variables are read again.")
        failing = false
        val accepted = AgentEnvironment.accepted(view.variables)
        val refused = AgentEnvironment.refused(view.variables)
        if (refused.isNotEmpty() && accepted.keys != current.keys) log("Environment variables not given to the agent (reserved names): ${refused.joinToString(", ")}.")
        if (current.isNotEmpty() && accepted != current) log("Environment variables changed: ${accepted.keys.joinToString(", ").ifEmpty { "none" }}.")
        current = accepted
        val served = AgentEnvironment.acceptedSecrets(view.secrets)
        secrets = served.filterKeys { it !in view.oneTime }
        onceHeld += served.filterKeys { it in view.oneTime }
        if (served.keys.toSet() != lastSecretNames) { lastSecretNames = served.keys.toSet(); if (served.isNotEmpty()) log("Wait secrets for the agent's commands: ${served.keys.sorted().joinToString(", ")}.") }
        nextRead = now() + maxAgeMillis
    }

    private fun failed() {
        if (!failing) log("Environment variables could not be read; the agent keeps the last values.")
        failing = true
        nextRead = now() + retryMillis
    }

    companion object {
        const val MAX_AGE_MILLIS = 30_000L
        const val RETRY_MILLIS = 15_000L
        /** A Bash command waits at most this long for the server, then runs with the last values. */
        const val COMMAND_WAIT_MILLIS = 3_000L
        const val START_WAIT_MILLIS = 20_000L
    }
}

/**
 * Serves [RunnerVariables] to the agent's commands over a Unix socket on the pod's memory volume: the values stay in
 * the runner's memory, and the agent's container receives them only when one of its commands asks, the way the git
 * helper receives a credential. One request per connection, at most [MAX_CONCURRENT] at once.
 *
 * The same socket takes the agent's `Stop` hook report ([CloudAgentState]): `wakeups <n>`, the number of its scheduled
 * wake-ups, given to [wakeups] and answered with nothing; it also ends the turn for the one-time wait secrets ([RunnerVariables.endTurn]). A count only says whether the session has background work, so
 * the worst an agent writing it can do is keep its session running up to the team's cap, as a background command would.
 */
internal class EnvironmentBroker(
    private val listener: UnixListener,
    private val variables: RunnerVariables,
    private val timeoutMillis: Long = 10_000,
    private val log: (String) -> Unit = {},
    private val wakeups: (Int) -> Unit = {},
) {
    private val permits = Semaphore(MAX_CONCURRENT)

    suspend fun serve() = coroutineScope {
        try {
            while (true) {
                val connection = listener.accept() ?: break
                launch { permits.withPermit { handle(connection) } }
            }
        } finally { listener.close() }
    }

    private suspend fun handle(connection: UnixConnection) {
        try {
            val request = withTimeoutOrNull(timeoutMillis) { connection.readLine(MAX_REQUEST_LINE) }
            // The Stop hook reports at the end of every turn: that is also when a one-time wait secret is dropped.
            if (request != null && request.startsWith(WAKEUPS)) { wakeupCount(request)?.let { wakeups(it); variables.endTurn() }; return }
            if (request != REQUEST) return
            val answer = try { variables.forCommand() } catch (error: CancellationException) { throw error } catch (_: Exception) {
                log("The environment variables could not be served."); EnvironmentAnswer()
            }
            connection.writeLine(codec.encodeToString(EnvironmentAnswer.serializer(), answer))
        } finally { connection.close() }
    }

    companion object {
        const val REQUEST = "environment"
        /** The agent's `Stop` hook report: this prefix, then the number of scheduled wake-ups. */
        const val WAKEUPS = "wakeups "
        /** Claude Code holds at most 50 scheduled tasks per session; anything far above is not a count. */
        const val MAX_WAKEUPS = 1_000
        const val MAX_CONCURRENT = 8
        const val MAX_REQUEST_LINE = 1024
        /** The count in a `wakeups <n>` line, or null when it is not one. */
        fun wakeupCount(request: String): Int? = request.removePrefix(WAKEUPS).toIntOrNull()?.takeIf { it in 0..MAX_WAKEUPS }
        /** The socket next to the credential socket, on the same memory volume. */
        fun socketFor(credentialSocket: String) = credentialSocket.substringBeforeLast('/', ".") + "/environment.sock"
        /** The script Claude Code runs before each command (`CLAUDE_ENV_FILE`), next to the sockets; it holds no value. */
        fun scriptFor(credentialSocket: String) = credentialSocket.substringBeforeLast('/', ".") + "/agent-env.sh"
        internal val codec = Json { ignoreUnknownKeys = true }
    }
}

/**
 * A remote session's host serves the agent's `Stop` hook report ([CloudAgentState]): the same `wakeups <n>` line as
 * [EnvironmentBroker], on a socket of its own, handed to [wakeups]. Anything else is ignored and nothing is answered. As there, the
 * count only says whether the session has background work.
 *
 * With [variables] (the host's [RunnerVariables], reading only the session's wait secrets) it also answers the `environment`
 * request of the agent's `CLAUDE_ENV_FILE` script exactly as [EnvironmentBroker] does, and the Stop hook ends the turn for the
 * one-time secrets. The member's own variables are still given at the agent's start only.
 */
internal class WakeupBroker(
    private val listener: UnixListener, private val wakeups: (Int) -> Unit, private val timeoutMillis: Long = 10_000,
    private val variables: RunnerVariables? = null, private val log: (String) -> Unit = {},
) {
    private val permits = Semaphore(EnvironmentBroker.MAX_CONCURRENT)

    suspend fun serve() = coroutineScope {
        try {
            while (true) {
                val connection = listener.accept() ?: break
                launch {
                    permits.withPermit {
                        try {
                            val request = withTimeoutOrNull(timeoutMillis) { connection.readLine(EnvironmentBroker.MAX_REQUEST_LINE) }
                            if (request != null && request.startsWith(EnvironmentBroker.WAKEUPS)) {
                                EnvironmentBroker.wakeupCount(request)?.let { wakeups(it); variables?.endTurn() }
                            } else if (request == EnvironmentBroker.REQUEST && variables != null) {
                                val answer = try { variables.forCommand() } catch (error: CancellationException) { throw error } catch (_: Exception) {
                                    log("The wait secrets could not be served."); EnvironmentAnswer()
                                }
                                connection.writeLine(EnvironmentBroker.codec.encodeToString(EnvironmentAnswer.serializer(), answer))
                            }
                        } finally { connection.close() }
                    }
                }
            }
        } finally { listener.close() }
    }
}

/** Asks the runner at [socket] for the current variables; null when it cannot be reached. Nothing is logged. */
internal suspend fun requestEnvironment(socket: String, connect: (String) -> UnixConnection = ::connectUnix): EnvironmentAnswer? {
    val connection = try { connect(socket) } catch (_: Exception) { return null }
    try {
        if (!connection.writeLine(EnvironmentBroker.REQUEST)) return null
        val line = withTimeoutOrNull(CLIENT_TIMEOUT_MILLIS) { connection.readLine() } ?: return null
        return try { EnvironmentBroker.codec.decodeFromString(EnvironmentAnswer.serializer(), line) } catch (_: Exception) { null }
    } finally { connection.close() }
}
private const val CLIENT_TIMEOUT_MILLIS = 15_000L

/** Hands [count] scheduled wake-ups to the runner at [socket]; false when it cannot be reached. Nothing is logged. */
internal suspend fun reportWakeups(socket: String, count: Int, connect: (String) -> UnixConnection = ::connectUnix): Boolean {
    val connection = try { connect(socket) } catch (_: Exception) { return false }
    try { return connection.writeLine(EnvironmentBroker.WAKEUPS + count) } finally { connection.close() }
}

/**
 * The number of scheduled wake-ups (`session_crons`: `CronCreate`, `ScheduleWakeup`, `/loop`) in a Claude Code `Stop`
 * hook input (https://code.claude.com/docs/en/hooks#stop-input); nothing else of it is read. An input too large to parse
 * counts 1 when a non-empty list appears as a key, as in `HookActivity`. 0 for anything else.
 */
internal fun scheduledWakeups(input: String): Int = try {
    if (input.length > MAX_PARSED_HOOK_INPUT) { if (WAKEUP_LIST.containsMatchIn(input)) 1 else 0 }
    else ((Json.parseToJsonElement(input) as? JsonObject)?.get("session_crons") as? JsonArray)?.size ?: 0
} catch (_: Exception) { 0 }
private const val MAX_PARSED_HOOK_INPUT = 1_048_576
private val WAKEUP_LIST = Regex("""[{,\s]"session_crons"\s*:\s*\[\s*\{""")
