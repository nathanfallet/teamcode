package me.nathanfallet.teamcode.cli.cloud.agent

import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

/** What one Bash command applies: the current variables and the names given earlier and no longer assigned. */
@Serializable
internal class EnvironmentAnswer(val variables: Map<String, String> = emptyMap(), val withdrawn: List<String> = emptyList()) {
    override fun toString() = "EnvironmentAnswer(names=${variables.keys.sorted()}, withdrawn=$withdrawn, values=[REDACTED])"
}

/**
 * The execution owner's environment variables, held in the runner's memory only and read from the runner channel
 * (`POST /cloud/runner/variables`). An agent start always asks the server; a Bash command asks again when the last
 * answer is older than [maxAgeMillis], so a rotated, added or removed variable reaches the agent's next command
 * within that delay, and an idle session costs no request. A failed read keeps the last values and is retried after
 * [retryMillis]; a server without the route (404) is not asked again, and a fenced or refused execution serves nothing.
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
    /** Every name handed to the agent, by its environment or by a command: a name that disappears is unset. */
    private val given = mutableSetOf<String>()

    /** The variables for an agent starting now, asked of the server first. */
    suspend fun forStart(): Map<String, String> = lock.withLock {
        read(startWaitMillis)
        current.also { given += it.keys }
    }

    /** The answer for one Bash command. */
    suspend fun forCommand(): EnvironmentAnswer = lock.withLock {
        if (now() >= nextRead) read(commandWaitMillis)
        EnvironmentAnswer(current, (given - current.keys).sorted()).also { given += current.keys }
    }

    /** Asks the server, under [lock]; any failure keeps [current]. */
    private suspend fun read(waitMillis: Long) {
        if (stopped) return
        val view = try { withTimeout(waitMillis) { fetch() } }
            catch (error: TimeoutCancellationException) { failed(); return }
            catch (error: CancellationException) { throw error }
            catch (error: TeamCodeException) {
                when {
                    // Fenced or refused: another execution owns the session, so nothing is served any more.
                    error.code == "execution_fenced" || error.statusCode in setOf(401, 403) -> { stopped = true; current = emptyMap() }
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
 */
internal class EnvironmentBroker(
    private val listener: UnixListener,
    private val variables: RunnerVariables,
    private val timeoutMillis: Long = 10_000,
    private val log: (String) -> Unit = {},
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
            if (withTimeoutOrNull(timeoutMillis) { connection.readLine(MAX_REQUEST_LINE) } != REQUEST) return
            val answer = try { variables.forCommand() } catch (error: CancellationException) { throw error } catch (_: Exception) {
                log("The environment variables could not be served."); EnvironmentAnswer()
            }
            connection.writeLine(codec.encodeToString(EnvironmentAnswer.serializer(), answer))
        } finally { connection.close() }
    }

    companion object {
        const val REQUEST = "environment"
        const val MAX_CONCURRENT = 8
        const val MAX_REQUEST_LINE = 1024
        /** The socket next to the credential socket, on the same memory volume. */
        fun socketFor(credentialSocket: String) = credentialSocket.substringBeforeLast('/', ".") + "/environment.sock"
        /** The script Claude Code runs before each command (`CLAUDE_ENV_FILE`), next to the sockets; it holds no value. */
        fun scriptFor(credentialSocket: String) = credentialSocket.substringBeforeLast('/', ".") + "/agent-env.sh"
        internal val codec = Json { ignoreUnknownKeys = true }
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
