package me.nathanfallet.teamcode.cli.cloud.agent

import kotlin.concurrent.Volatile
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.cli.platform.*

/**
 * The line protocol between the runner and the exec supervisor, over a Unix socket in the pod. One connection is
 * one process: the runner sends `start`, then `in` (a line for the process's stdin), `close` (end of stdin) and
 * `stop`; the supervisor answers `out` (a line of stdout) and, last, `exit`. A connection that only says `hold` is
 * the runner's lifeline: the supervisor ends when it closes.
 */
@Serializable
internal data class ExecMessage(
    val t: String,
    val line: String? = null,
    val command: String? = null,
    val arguments: List<String> = emptyList(),
    val environment: Map<String, String?> = emptyMap(),
    val directory: String? = null,
    val graceMillis: Long? = null,
    val code: Int? = null,
) {
    fun encode() = codec.encodeToString(serializer(), this)
    companion object {
        const val START = "start"; const val IN = "in"; const val CLOSE = "close"; const val STOP = "stop"; const val HOLD = "hold"
        const val OUT = "out"; const val EXIT = "exit"; const val ERROR = "error"
        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        fun decode(text: String?): ExecMessage? = text?.let { try { codec.decodeFromString(serializer(), it) } catch (_: Exception) { null } }
    }
}

/**
 * Starts the agent and the preparation commands for the runner, in the agent's own container and under its own UID.
 * It listens on a Unix socket of a volume the two containers share; nothing but the runner's UID and group can
 * connect (mode 0660). It holds no TeamCode credential: the runner's token never leaves the runner's container.
 *
 * The supervisor ends, stopping every process it started, when the runner's lifeline closes, and gives up when the
 * runner never connects within [startupMillis].
 */
internal class AgentSupervisor(
    private val listener: UnixListener,
    private val start: (String, List<String>, Map<String, String?>, String) -> AgentProcess,
    private val defaultDirectory: String,
    private val startupMillis: Long = 120_000,
    private val log: (String) -> Unit = {},
) {
    /** Returns the process exit code: 0 once the runner is gone, 1 when it never came. */
    suspend fun serve(): Int = coroutineScope {
        val lifeline = CompletableDeferred<Int>()
        val held = CompletableDeferred<Unit>()
        val connections = launch {
            while (true) {
                val connection = listener.accept() ?: break
                launch { handle(connection, held, lifeline) }
            }
        }
        val code = try {
            if (withTimeoutOrNull(startupMillis) { held.await() } == null) { log("The runner never connected."); 1 } else lifeline.await()
        } finally { listener.close() }
        // The lifeline ended: stop every process still running (each connection stops its own), then leave.
        connections.cancelAndJoin()
        code
    }

    private suspend fun handle(connection: UnixConnection, held: CompletableDeferred<Unit>, lifeline: CompletableDeferred<Int>) {
        val first = ExecMessage.decode(connection.readLine(MAX_REQUEST_LINE))
        when (first?.t) {
            ExecMessage.HOLD -> {
                held.complete(Unit)
                // The lifeline: it stays open as long as the runner lives. Its end, or a failed read, ends the supervisor.
                while (connection.readLine(MAX_REQUEST_LINE) != null) Unit
                lifeline.complete(0)
            }
            ExecMessage.START -> run(connection, first)
            else -> connection.close()
        }
    }

    private suspend fun run(connection: UnixConnection, request: ExecMessage) = coroutineScope {
        val command = request.command
        val child = try { if (command.isNullOrEmpty()) null else start(command, request.arguments, request.environment, request.directory ?: defaultDirectory) }
            catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        if (child == null) {
            log("A process could not be started.")
            connection.writeLine(ExecMessage(ExecMessage.ERROR).encode()); connection.close()
            return@coroutineScope
        }
        val writes = Mutex()
        suspend fun send(message: ExecMessage) = writes.withLock { connection.writeLine(message.encode()) }
        val output = launch {
            while (true) { val line = child.readLine() ?: break; if (!send(ExecMessage(ExecMessage.OUT, line = line))) break }
            send(ExecMessage(ExecMessage.EXIT, code = child.stop(OUTPUT_END_GRACE_MILLIS)))
            connection.close()
        }
        try {
            while (true) {
                val message = ExecMessage.decode(connection.readLine(MAX_REQUEST_LINE)) ?: break
                when (message.t) {
                    ExecMessage.IN -> message.line?.let { child.writeLine(it) }
                    ExecMessage.CLOSE -> child.closeInput()
                    ExecMessage.STOP -> launch { child.stop(message.graceMillis ?: DEFAULT_STOP_GRACE_MILLIS) }
                }
            }
        } finally {
            // The runner went away (or the supervisor is ending): nothing may keep running on its behalf.
            withContext(NonCancellable) { child.stop(LOST_RUNNER_GRACE_MILLIS); output.cancel(); connection.close() }
        }
    }

    companion object {
        const val MAX_REQUEST_LINE = 8 * 1024 * 1024
        const val OUTPUT_END_GRACE_MILLIS = 1_000L
        const val DEFAULT_STOP_GRACE_MILLIS = 10_000L
        const val LOST_RUNNER_GRACE_MILLIS = 1_000L
    }
}

/**
 * Starts processes through the supervisor and presents each as an [AgentProcess]. The agent container may still be
 * starting when the runner needs it, so a connection is retried until [connectMillis] pass.
 */
internal class RemoteProcesses(
    private val socket: String,
    private val scope: CoroutineScope,
    private val connect: (String) -> UnixConnection = ::connectUnix,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val connectMillis: Long = 60_000,
    private val log: (String) -> Unit = {},
) {
    private suspend fun open(): UnixConnection {
        var waited = 0L
        while (true) {
            try { return connect(socket) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (waited >= connectMillis) throw IllegalStateException("The agent container did not answer.")
                sleep(RETRY_MILLIS); waited += RETRY_MILLIS
            }
        }
    }

    /** Connects the lifeline. The supervisor ends when it closes, so keep it until the runner leaves. */
    suspend fun hold(): UnixConnection {
        val connection = open()
        check(connection.writeLine(ExecMessage(ExecMessage.HOLD).encode())) { "The agent container did not accept the runner." }
        return connection
    }

    suspend fun start(command: String, arguments: List<String>, environment: Map<String, String?>, directory: String): AgentProcess {
        val connection = open()
        if (!connection.writeLine(ExecMessage(ExecMessage.START, command = command, arguments = arguments, environment = environment, directory = directory).encode())) {
            connection.close(); throw IllegalStateException("The agent container did not accept the process.")
        }
        return RemoteProcess(connection, scope, sleep)
    }

    private companion object { const val RETRY_MILLIS = 500L }
}

private class RemoteProcess(private val connection: UnixConnection, private val scope: CoroutineScope, private val sleep: suspend (Long) -> Unit) : AgentProcess {
    private val lines = Channel<String>(BUFFERED_LINES)
    @Volatile private var exit: Int? = null
    private val writes = Mutex()

    init {
        scope.launch {
            try {
                while (true) {
                    val message = ExecMessage.decode(connection.readLine()) ?: break
                    when (message.t) {
                        ExecMessage.OUT -> message.line?.let { lines.send(it) }
                        ExecMessage.EXIT -> { exit = message.code ?: -1; break }
                        ExecMessage.ERROR -> { exit = START_FAILED; break }
                    }
                }
            } finally {
                if (exit == null) exit = -1
                lines.close()
                connection.close()
            }
        }
    }

    private suspend fun send(message: ExecMessage) = writes.withLock { connection.writeLine(message.encode()) }

    override suspend fun writeLine(line: String): Boolean = exit == null && send(ExecMessage(ExecMessage.IN, line = line))

    override suspend fun readLine(maxLine: Int): String? = lines.receiveCatching().getOrNull()?.let { if (it.length > maxLine) it.take(maxLine) else it }

    override fun closeInput() { if (exit == null) scope.launch { send(ExecMessage(ExecMessage.CLOSE)) } }

    override fun exitCode(): Int? = exit

    override suspend fun stop(graceMillis: Long): Int = withContext(NonCancellable) {
        if (exit == null) send(ExecMessage(ExecMessage.STOP, graceMillis = graceMillis))
        // The supervisor closes stdin, waits the grace, terminates and kills; leave it that long and a little more.
        var waited = 0L
        val limit = graceMillis + STOP_SLACK_MILLIS
        while (exit == null && waited < limit) { sleep(50); waited += 50 }
        // Never answered: drop the connection, which makes the supervisor stop the process.
        if (exit == null) { exit = -1; connection.close() }
        exit ?: -1
    }

    private companion object {
        const val BUFFERED_LINES = 256
        const val STOP_SLACK_MILLIS = 15_000L
        const val START_FAILED = 127
    }
}
