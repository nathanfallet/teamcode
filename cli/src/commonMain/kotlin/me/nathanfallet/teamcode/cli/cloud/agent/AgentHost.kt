package me.nathanfallet.teamcode.cli.cloud.agent

import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.launch.OwnerIdentity
import me.nathanfallet.teamcode.cli.launch.agentLaunch
import me.nathanfallet.teamcode.cli.platform.AgentProcess
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.FileSystem
import okio.Path.Companion.toPath

/** What the runner loop needs from the agent: run beside heartbeats, drain before a checkpoint, stop on the way out. */
internal interface RunnerAgent {
    val fenced: Boolean
    suspend fun run()
    suspend fun drain(deadline: Long?): Boolean
    fun resumeAfterDrain()
    suspend fun stop()
    /** Registers [changed], called when the agent learns that the session's desired state changed (a pause, a resume). */
    fun onInstructionChange(changed: () -> Unit) {}
    /** The server terminated the session: it never runs again, so what it keeps on this host can go at [stop]. */
    fun sessionEnded() {}
    /**
     * Whether the agent has background work: tasks in flight (subagents, background commands, monitors) or scheduled
     * wake-ups. Reported with each heartbeat; the server's idle pause counts it, up to the team's cap.
     */
    val backgroundWork: Boolean get() = false
}

/** The agent half of the runner channel, bound to one session epoch. */
interface AgentChannel {
    suspend fun agent(): RunnerAgentView
    suspend fun events(events: List<RunnerEventView>): RunnerEventsAckView
    suspend fun inputs(waitMillis: Long): List<RunnerInputView>
    /**
     * [inputs], also returning the session's desired state; the server ends the wait early when it differs from
     * [desiredState], the one the runner last knew. A server without the field answers it as null. [acceptsFiles]
     * announces that this host downloads the files of a message (`cloud.files.v1`), [acceptsTaskStops] that it handles a
     * stop of a background task (`cloud.sessions.background-tasks.v1`), rejecting it when its agent cannot stop one.
     */
    suspend fun poll(waitMillis: Long, desiredState: String, acceptsFiles: Boolean = false, acceptsTaskStops: Boolean = false): RunnerInputsView = RunnerInputsView(inputs(waitMillis))
    suspend fun acknowledge(acks: List<RunnerInputAckView>)
    /** Streams the bytes of the message file [fileId] to [receive], at most [maxBytes]. */
    suspend fun file(fileId: String, maxBytes: Long, receive: (ByteArray, Int) -> Unit): Unit = throw UnsupportedOperationException("This channel downloads no file.")
}

/**
 * The agent's conversation id, kept on the workspace volume so the next pod resumes it. [fork]: the conversation is
 * another one's on the member's device (`cloud.sessions.remote.resume.v1`), continued under a new id at the first start
 * (Claude Code's `--fork-session`) so the original stays as it was; the new id then replaces this state. [identity]: the
 * owner sentence ([OwnerIdentity]) the conversation was last told, so a resume after a transfer tells the new owner.
 */
@Serializable
data class AgentState(val kind: String, val version: String? = null, val conversationId: String, val fork: Boolean = false, val identity: String? = null)
interface AgentStateStore {
    fun read(): AgentState?
    fun write(state: AgentState)
    fun clear()
}

/**
 * Runs the session's agent as one long-lived process with streaming input. The process starts with the first
 * message (resuming the stored conversation when the agent kind and version are unchanged), messages and
 * interrupts are written as they arrive and acknowledged once handed to the agent, and the normalized output is
 * uploaded in ordered batches. [drain] finishes or interrupts the running turn before a checkpoint.
 *
 * An execution fenced by the server (`409 execution_fenced`) stops everything; [fenced] tells the runner loop.
 */
internal class AgentHost(
    private val channel: AgentChannel,
    private val start: suspend (AgentCommand, Map<String, String?>, String) -> AgentProcess,
    private val state: AgentStateStore,
    private val workingDirectory: String,
    private val clientVersion: String,
    private val log: (String) -> Unit = {},
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * The runner's loopback inference proxy, resolved when the agent starts; null (or a null result, for a server that
     * issues no inference credentials) starts the agent with its own provider configuration.
     */
    private val inference: (suspend () -> AgentInferenceEndpoint?)? = null,
    /** The owner's team MCP servers and skills, prepared before each agent start; needs [inference], whose proxy relays the MCP messages. */
    private val tools: AgentTools? = null,
    /** The owner's environment variables, read again at each agent start (see [AgentVariables]). */
    private val variables: AgentVariables? = null,
    /**
     * Where the files of a message are written for the agent; null (or one [SessionUploads.prepare] refuses) announces
     * no file support, so the server rejects such messages (`files_unsupported`) instead of delivering their text alone.
     */
    private val uploads: SessionUploads? = null,
    /**
     * A shell command Claude Code runs on `Stop` to hand the runner the number of its scheduled wake-ups
     * (`teamcode cloud agent-state`, see [CloudAgentState]); null reports none. Added to the team hooks' settings.
     */
    private val stateHook: String? = null,
) : RunnerAgent {
    override var fenced = false; private set
    /** Scheduled wake-ups of the current agent process, from its last `Stop` ([scheduledWakeups]); 0 for a new process. */
    @kotlin.concurrent.Volatile private var wakeups = 0
    /** Background tasks of the current agent process ([AgentAdapter.backgroundTasks]) or scheduled wake-ups; none without a process. */
    override val backgroundWork: Boolean get() = backgroundTasksInFlight || (adapter != null && wakeups > 0)
    /** Background tasks in flight (subagents, background commands, monitors): the work a stop of the agent kills. */
    val backgroundTasksInFlight: Boolean get() = (adapter?.backgroundTasks ?: 0) > 0
    /** Records [count] scheduled wake-ups, reported by the agent's `Stop` hook through the runner's environment socket. */
    fun scheduledWakeups(count: Int) {
        wakeups = count.coerceAtLeast(0)
        // Members see how many wake-ups are scheduled beside the tasks (`tasks` event), when the server takes it.
        if (::scope.isInitialized) scope.launch { lock.withLock { adapter?.scheduled(wakeups)?.let { enqueue(it.type, it.data) } } }
    }
    /** Guards the adapter state and the buffer. Never held while writing to the agent: its stdout must keep draining. */
    private val lock = Mutex()
    /** Serializes writes to the agent's stdin (fair, so lines keep their order). */
    private val writeLock = Mutex()
    /** Acknowledgements already sent, by input id: a redelivered input is acknowledged again, never written twice. */
    private val handled = LinkedHashMap<String, RunnerInputAckView>()
    private val buffer = ArrayDeque<RunnerEventView>()
    private var sequence = 0L
    /** Events per upload; narrowed while the server refuses a batch for its content. */
    private var batchLimit = MAX_BATCH
    private var settings: AgentSettings? = null
    private var adapter: AgentAdapter? = null
    private var process: AgentProcess? = null
    private var reader: Job? = null
    private var resumed: String? = null
    private var stopping = false
    /** Deadline timers of open permission requests, by request id, with the process that asked; guarded by [lock]. */
    private val deadlines = mutableMapOf<String, Pair<AgentProcess, Job>>()
    /** Requests whose answer is being written: their deadline stays until the write succeeds. Under [lock]. */
    private val writing = mutableSetOf<String>()
    /** Requests whose deadline passed while their answer was being written: denied at once if that write fails. Under [lock]. */
    private val dueWhileWriting = mutableSetOf<String>()
    private lateinit var scope: CoroutineScope
    /** The desired state the last input poll returned, sent with the next one. */
    private var desiredState = "running"
    private var instructionChanged: () -> Unit = {}
    /** The uploads directory once prepared, given to the agent at start; null announces no file support. */
    private var uploadsDirectory: String? = null
    /** The owner sentence the running conversation knows, recorded with its id. Under [lock]. */
    private var told: String? = null
    /**
     * The owner sentence to put before the next message: a resumed conversation keeps the instructions it started with
     * (Codex keeps its developer instructions, measured on 0.163), so a new owner after a transfer is told there. Under [lock].
     */
    private var ownerNote: String? = null

    override fun onInstructionChange(changed: () -> Unit) { instructionChanged = changed }

    /** Set when the server terminated the session; only then does [stop] remove its files. */
    private var ended = false
    override fun sessionEnded() { ended = true }

    /** Runs input delivery and uploads until cancelled or fenced. */
    override suspend fun run() = coroutineScope {
        scope = this
        // The sweep at start: what a previous execution left, and anything older than a week.
        uploadsDirectory = uploads?.takeIf { it.prepare(log) }?.directory?.toString()
        val uploads = launch { uploadLoop() }
        try { inputLoop() } finally {
            uploads.cancel()
            // Fenced: another execution owns the session, so this agent stops now (and its reader with it).
            if (fenced) withContext(NonCancellable) { stop() }
        }
    }

    /** Finishes the running turn before [deadline] (interrupting it when needed), stops the agent and flushes the transcript. Returns whether the stop was clean. */
    override suspend fun drain(deadline: Long?): Boolean {
        stopping = true
        // No answer reaches a pausing session: its open requests are denied now, so a turn waiting on one can end.
        lock.withLock {
            val child = process; val active = adapter
            if (child == null || active == null) null
            else child to active.openPermissions().flatMap { id -> deadlines.remove(id)?.second?.cancel(); active.answer(id, false, PAUSE_DENIAL, emptyList()).lines }
        }?.let { (child, lines) -> if (lines.isNotEmpty()) write(child, lines) }
        val limit = (deadline ?: (now() + DEFAULT_DRAIN_MILLIS)) - SAFETY_MARGIN_MILLIS
        suspend fun running() = lock.withLock { adapter?.turnActive == true && process?.exitCode() == null }
        // Background tasks (subagents, background commands, monitors) die with the agent: the drain waits for them too, and
        // for the turn in which Claude Code reports their end. Scheduled wake-ups are not waited for: `--resume` restores them.
        suspend fun tasks() = lock.withLock { if (process?.exitCode() == null) adapter?.backgroundTasks ?: 0 else 0 }
        if (tasks() > 0) log("Pausing: waiting for the agent's background work to end before checkpointing.")
        while (now() < limit && (running() || tasks() > 0)) sleep(250)
        var clean = true
        val left = tasks()
        if (left > 0) {
            // Said in the transcript: the pause stops work the member may expect to finish.
            clean = false
            lock.withLock { enqueue(CloudEventTypes.STATUS, buildJsonObject { put("agent", "background_stopped"); put("tasks", left) }) }
            log("Pausing: $left background task${if (left == 1) "" else "s"} of the agent did not end before the pause deadline; stopping ${if (left == 1) "it" else "them"}.")
        }
        if (running()) {
            clean = false
            lock.withLock { process?.let { child -> adapter?.interrupt()?.let { child to it } } }?.let { (child, lines) -> write(child, lines) }
            val until = now() + INTERRUPT_GRACE_MILLIS
            while (now() < until && running()) sleep(250)
        }
        lock.withLock { process }?.stop(STOP_GRACE_MILLIS)
        // The process is stopped; a reader still blocked after that (an output stream some descendant keeps open) must
        // not hold the checkpoint back: the pause would only end at the server's deadline.
        reader?.let { job -> if (withTimeoutOrNull(READER_END_MILLIS) { job.join() } == null) { log("The agent output did not end; checkpointing anyway."); job.cancel(); clean = false } }
        return flush(now() + FLUSH_MILLIS) && clean
    }

    /** A pause was cancelled before the pod stopped: accept messages again (the agent restarts with the next one). */
    override fun resumeAfterDrain() { stopping = false }

    /** Stops the agent at once; used when fenced or when the session is deleted. Nothing more is uploaded. */
    override suspend fun stop() {
        stopping = true
        try {
            process?.stop(1_000)
            reader?.cancel()
            lock.withLock { cancelDeadlines(null) }
        } finally {
            // Files stay until the session ends (a week at most): a pause, a host restart or a resume keeps them, so
            // the resumed conversation can read them again. A fenced execution never removes them, since the execution
            // that fenced it may run on this device and use the same directory.
            if (ended && !fenced) uploads?.clear()
        }
    }

    private suspend fun inputLoop() {
        var wait = 1_000L
        while (!fenced) {
            val view = try { channel.poll(INPUT_WAIT_MILLIS, desiredState, uploadsDirectory != null, acceptsTaskStops = true).also { wait = 1_000L } }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (handle(error)) return; sleep(wait); wait = (wait * 2).coerceAtMost(15_000); continue }
            // A pause (or its cancellation) reaches the runner loop now, through the poll already open, not at its next heartbeat.
            view.desiredState?.takeIf { it != desiredState }?.let { desiredState = it; instructionChanged() }
            val inputs = view.inputs
            if (inputs.isEmpty()) continue
            val acks = inputs.mapNotNull { input -> handled[input.id] ?: deliver(input)?.also { remember(it) } }
            if (acks.isEmpty()) continue
            try { retrying { channel.acknowledge(acks) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { return } // Fenced or refused: [retrying] already recorded it.
        }
    }

    private fun remember(ack: RunnerInputAckView) {
        handled[ack.id] = ack
        if (handled.size > MAX_HANDLED) handled.remove(handled.keys.first())
    }

    /** Hands one input to the agent; null leaves it unacknowledged, so the server offers it again later. */
    private suspend fun deliver(input: RunnerInputView): RunnerInputAckView? {
        val consumed = RunnerInputAckView(input.id, CloudInputStatuses.CONSUMED)
        val unavailable = RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, "agent_unavailable")
        if (input.kind == "interrupt") {
            val (child, lines) = lock.withLock { process?.let { it to adapter?.interrupt().orEmpty() } } ?: (null to emptyList())
            if (child == null || lines.isEmpty()) return RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, "no_turn")
            return if (write(child, lines)) consumed else unavailable
        }
        if (input.kind == CloudTasks.KIND) {
            // Never starts the agent: only a running process has tasks to stop.
            val taskId = input.text?.takeIf { CloudTasks.TASK_ID.matches(it) } ?: return RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, "invalid_input")
            val (child, lines, supported) = lock.withLock {
                val active = adapter
                // No process: nothing runs, so no task either.
                Triple(process, active?.stopTask(taskId).orEmpty(), active?.canStopTasks ?: true)
            }
            if (!supported) return RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, CloudTasks.UNSUPPORTED)
            if (child == null || lines.isEmpty()) return RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, CloudTasks.UNKNOWN)
            return if (write(child, lines)) consumed else unavailable
        }
        if (input.kind == "permission") {
            val answer = input.text?.let { text -> try { Json.parseToJsonElement(text) as? JsonObject } catch (_: IllegalArgumentException) { null } }
            val requestId = (answer?.get("requestId") as? JsonPrimitive)?.contentOrNull ?: return RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, "invalid_input")
            val answers = try { answer["answers"]?.let { stateJson.decodeFromJsonElement<List<CloudQuestionAnswer>>(it) }.orEmpty() }
                catch (_: IllegalArgumentException) { return RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, "invalid_input") }
            val allow = (answer["behavior"] as? JsonPrimitive)?.contentOrNull == "allow"
            val message = (answer["message"] as? JsonPrimitive)?.contentOrNull
            // Never starts the agent: only the process that asked can take the answer.
            val (child, active, outcome) = lock.withLock {
                val child = process ?: return@withLock null
                val active = adapter ?: return@withLock null
                // The deadline keeps running until the answer is written: a failed write leaves it as it was.
                Triple(child, active, active.answer(requestId, allow, message, answers)).also { if (it.third.lines.isNotEmpty()) writing += requestId }
            } ?: return RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, AnswerOutcome.UNKNOWN)
            if (outcome.lines.isEmpty()) return RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, outcome.refusal ?: AnswerOutcome.UNKNOWN)
            val written = write(child, outcome.lines)
            lock.withLock {
                writing -= requestId
                val due = dueWhileWriting.remove(requestId)
                if (written) deadlines.remove(requestId)?.second?.cancel()
                // Not written: the request stays open for another answer, with its deadline; one that passed meanwhile denies it now.
                else if (process === child && outcome.input != null) {
                    active.reopen(requestId, outcome.input)
                    if (due) expireLater(child, active, requestId, delayMillis = 0)
                }
            }
            return if (written) consumed else unavailable
        }
        if (input.text == null && input.files.isEmpty()) return RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, "invalid_input")
        var text = input.text.orEmpty()
        if (input.files.isNotEmpty()) {
            // Stopping for a pause: nothing is downloaded, the message waits for the next execution.
            if (lock.withLock { stopping }) return null
            // Downloaded before the agent starts: a file that cannot be written rejects the message and starts no turn.
            val attached = when (val outcome = attach(input)) {
                is Attachment.Refused -> return RunnerInputAckView(input.id, CloudInputStatuses.REJECTED, outcome.reason)
                Attachment.Fenced -> return null
                is Attachment.Written -> outcome.lines
            }
            text = (listOf(text).filter { it.isNotBlank() } + attached).joinToString("\n\n")
        }
        val (child, lines, note) = lock.withLock {
            // Stopping for a pause: the message stays queued on the server for the next execution, never lost.
            if (stopping) return null
            val active = try { ensureStarted() } catch (error: CancellationException) { throw error } catch (error: Exception) {
                log("The agent could not start (${error::class.simpleName}).")
                enqueue(CloudEventTypes.ERROR, buildJsonObject { put("code", "agent_start_failed"); put("message", "The agent process could not start.") })
                null
            } ?: return unavailable
            val note = ownerNote.also { ownerNote = null }
            val lines = active.message(if (note != null) "$note\n\n$text" else text)
            active.takeEvents().forEach { enqueue(it.type, it.data) }
            Triple(process, lines, note)
        }
        if (child == null) return unavailable
        if (!write(child, lines)) return unavailable
        // Told: recorded with the conversation, so the next resume says nothing again. Not written, the next start tells again.
        if (note != null) lock.withLock {
            if (process === child) told = note
            state.read()?.takeIf { stored -> settings?.let { it.kind == stored.kind && it.version == stored.version } == true }?.let { state.write(it.copy(identity = note)) }
        }
        return consumed
    }

    private sealed interface Attachment {
        class Written(val lines: List<String>) : Attachment
        class Refused(val reason: String) : Attachment
        data object Fenced : Attachment
    }

    /**
     * Downloads the files of [input] through the runner channel into [uploads], each once (by file id, so a
     * redelivered message writes nothing again), and returns the line the agent reads for each: its absolute path,
     * type and size. A file the server no longer offers, a failed download, a size or SHA-256 mismatch or a write
     * that is not safe refuses the whole message with a reason.
     */
    private suspend fun attach(input: RunnerInputView): Attachment {
        val target = uploads?.takeIf { uploadsDirectory != null } ?: return Attachment.Refused(SessionUploads.UNAVAILABLE)
        val lines = mutableListOf<String>()
        for (file in input.files) {
            var attempt = 0
            while (true) {
                try {
                    val path = target.write(file) { receive -> channel.file(file.id, file.size, receive) }
                    lines += SessionUploads.line(path, file)
                    break
                } catch (error: CancellationException) { throw error }
                catch (error: SessionUploads.Refused) { log("A message file was refused (${error.reason})."); return Attachment.Refused(error.reason) }
                catch (error: Exception) {
                    if (error is TeamCodeException) {
                        if (error.code == "execution_fenced" || error.statusCode == 401 || error.statusCode == 403) { handle(error); return Attachment.Fenced }
                        // Not offered to this execution any more (acknowledged, expired): retrying cannot help.
                        if (error.statusCode == 404) return Attachment.Refused(SessionUploads.NOT_FOUND)
                        if (error.statusCode in 400..499) return Attachment.Refused(SessionUploads.DOWNLOAD_FAILED)
                    }
                    if (++attempt >= FILE_ATTEMPTS) { log("A message file could not be downloaded."); return Attachment.Refused(SessionUploads.DOWNLOAD_FAILED) }
                    sleep(1_000L * attempt)
                }
            }
        }
        return Attachment.Written(lines)
    }

    private suspend fun write(child: AgentProcess, lines: List<String>): Boolean = writeLock.withLock {
        lines.all { child.writeLine(it) }
    }

    /** Starts the agent process if none runs; resumes the stored conversation when it belongs to the same agent. */
    private suspend fun ensureStarted(): AgentAdapter {
        adapter?.let { current -> if (process?.exitCode() == null) return current }
        reader?.cancel()
        // That reader's tail, which cancels its process's timers, may never run now.
        cancelDeadlines(null)
        val config = settings ?: retrying { channel.agent() }.let { AgentSettings(it.kind, it.version, it.model, it.effort, it.permissionRelay, it.permissionDeadlineMinutes, it.liveText, it.permissionMode, it.backgroundTasks, it.owner) }.also { settings = it }
        val stored = state.read()
        val kept = stored?.takeIf { it.kind == config.kind && it.version == config.version }
        val resume = kept?.conversationId
        if (stored != null && resume == null) {
            // Another agent or version: never resume across them; history stays in the transcript.
            state.clear()
            enqueue(CloudEventTypes.STATUS, buildJsonObject { put("agent", config.kind); put("conversation", "transcript_only") })
        }
        // The agent is told who the execution runs for in its instructions; a resumed conversation whose owner changed
        // since it was last told (a transfer) is told again before its next message, since its instructions may be kept.
        val identity = config.owner?.let { OwnerIdentity.sentence(it.username, it.githubLogin) }
        ownerNote = identity?.takeIf { resume != null && it != kept?.identity }
        told = if (resume == null) identity else kept?.identity
        val next = agentAdapter(config, clientVersion, now, readableDirectories = listOfNotNull(uploadsDirectory))
        val (command, environment) = launch(config.kind, next.command(resume, fork = kept?.fork == true))
        val child = start(command, environment, workingDirectory)
        process = child; adapter = next; resumed = resume; wakeups = 0
        val startup = next.startup(resume, workingDirectory)
        writeLock.withLock { for (line in startup) child.writeLine(line) }
        reader = scope.launch { readLoop(child, next) }
        log("Started the ${config.kind} agent" + if (resume != null) " (resuming its conversation)." else ".")
        return next
    }

    /**
     * Points the agent at the runner's loopback proxy exactly as `teamcode run` does for a local session: the agent
     * receives only the proxy's random local token, and its own provider credentials are removed from its environment.
     * The owner's team MCP servers are connected through the same proxy, one agent-side server per assignment, and
     * the owner's team hooks installed in the agent's home are added to Claude Code's settings. The owner's environment
     * variables come first, so none of these settings can be replaced by one. The agent is told who the execution runs
     * for, as the server says at this execution's start: a transfer changes the owner only between two executions.
     */
    private suspend fun launch(kind: String, base: AgentCommand): Pair<AgentCommand, Map<String, String?>> {
        // Claude Code forwards a subagent's text only when asked (`--forward-subagent-text`, v2.1.211); the variable does
        // the same and an older version ignores it, where the flag would stop it from starting. The owner's own value wins.
        val owner = (if (kind == "claude") mapOf("CLAUDE_CODE_FORWARD_SUBAGENT_TEXT" to "1") else emptyMap()) + variables?.launch(kind).orEmpty()
        val endpoint = inference?.invoke() ?: return base to owner + AGENT_ENVIRONMENT
        val servers = tools?.prepare(kind).orEmpty()
        // Team hooks run in Claude Code sessions only; their settings go first, away from the variadic --mcp-config.
        val hooks = cloudHookSettings(tools?.hooks(kind).orEmpty(), runnerStop = stateHook.takeIf { kind == "claude" })?.let { listOf("--settings", it) }.orEmpty()
        val plugin = if (kind == "claude") tools?.claudePlugin() else null
        val plan = agentLaunch(kind, base.arguments, endpoint.port, endpoint.token, guidancePlugin = plugin, runId = endpoint.runId, mcpServers = servers,
            hookToken = false, artifacts = tools?.artifacts(), waits = tools?.waits(), identity = settings?.owner?.let { OwnerIdentity.sentence(it.username, it.githubLogin) })
        return AgentCommand(base.command, hooks + plan.arguments) to owner + AGENT_ENVIRONMENT + plan.environment
    }

    private suspend fun readLoop(child: AgentProcess, active: AgentAdapter) {
        var known = active.conversationId
        while (true) {
            val line = child.readLine() ?: break
            val replies = lock.withLock {
                val result = active.parse(line)
                val denials = mutableListOf<String>()
                result.events.forEach { event ->
                    val kept = enqueue(event.type, event.data)
                    if (event.type != CloudEventTypes.PERMISSION_REQUEST) return@forEach
                    val requestId = (event.data["requestId"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                    // A request no member can see (the buffer is full while the server is unreachable) is denied at once
                    // rather than left blocking the agent, and so is one raised while the session pauses (no answer reaches it).
                    if (stopping) denials += active.answer(requestId, false, PAUSE_DENIAL, emptyList()).lines
                    else if (kept) expireLater(child, active, requestId)
                    else denials += active.answer(requestId, false, UNSEEN_DENIAL, emptyList()).lines
                }
                // A request withdrawn or ended with its turn needs no timer any more.
                if (deadlines.isNotEmpty()) cancelDeadlines(child) { !active.permissionOpen(it) && it !in writing }
                result.replies + denials
            }
            // Written by another coroutine: this loop must keep reading even while stdin is full.
            if (replies.isNotEmpty()) scope.launch { write(child, replies) }
            val id = active.conversationId
            if (id != null && id != known) {
                known = id
                val identity = lock.withLock { told }
                settings?.let { state.write(AgentState(it.kind, it.version, id, identity = identity)) }
            }
        }
        val code = child.stop(1_000)
        lock.withLock {
            cancelDeadlines(child)
            if (process === child) {
                val failedResume = resumed != null && active.conversationId == null
                if (failedResume) {
                    // The stored conversation could not be resumed: the next message starts a fresh one.
                    state.clear()
                    enqueue(CloudEventTypes.STATUS, buildJsonObject { put("agent", active.command(null).command); put("conversation", "transcript_only") })
                }
                if (!stopping) enqueue(CloudEventTypes.STATUS, buildJsonObject { put("agent", "exited"); put("exitCode", code) })
                if (active.turnActive && !stopping) enqueue(CloudEventTypes.TURN, buildJsonObject { put("phase", "failed") })
                // Its tasks died with it: members no longer see them as running.
                active.tasksEnded()?.let { enqueue(it.type, it.data) }
                process = null; adapter = null; resumed = null; wakeups = 0
            }
        }
    }

    /**
     * With a team deadline, denies the request [requestId] when it is still open after it: the agent reads why, and
     * the transcript gets a `permission.expired` event. Called under [lock].
     */
    private fun expireLater(child: AgentProcess, active: AgentAdapter, requestId: String, delayMillis: Long? = null) {
        val minutes = settings?.permissionDeadlineMinutes?.takeIf { it > 0 } ?: return
        deadlines.remove(requestId)?.second?.cancel()
        deadlines[requestId] = child to scope.launch {
            sleep(delayMillis ?: (minutes * 60_000L))
            val message = "No answer within $minutes minute${if (minutes == 1) "" else "s"}: the request was denied."
            val outcome = lock.withLock {
                deadlines.remove(requestId)
                // An answer being written: denied only if that write fails.
                if (requestId in writing) { dueWhileWriting += requestId; return@launch }
                // Answered, withdrawn, ended with its turn, or another process by now: nothing to deny.
                if (process !== child) return@launch
                active.answer(requestId, false, message, emptyList()).takeIf { it.lines.isNotEmpty() }?.also {
                    // Recorded before the agent reads the denial, so it precedes whatever the agent does next (the end
                    // of its turn in particular).
                    enqueue(CloudEventTypes.PERMISSION_EXPIRED, buildJsonObject { put("requestId", requestId); put("deadlineMinutes", minutes); put("message", message) })
                } ?: return@launch
            }
            // Not written (the process is going away): the request stays open, like an answer that could not be written.
            if (!write(child, outcome.lines)) lock.withLock { if (process === child && outcome.input != null) active.reopen(requestId, outcome.input) }
        }
    }

    /** Cancels the deadline timers of [child] (every process when null) whose request id matches [which]. Called under [lock]. */
    private fun cancelDeadlines(child: AgentProcess?, which: (String) -> Boolean = { true }) {
        val iterator = deadlines.entries.iterator()
        while (iterator.hasNext()) {
            val (id, timer) = iterator.next()
            if ((child == null || timer.first === child) && which(id)) { timer.second.cancel(); iterator.remove() }
        }
    }

    /** Buffers an event for upload; false when it was dropped. */
    private fun enqueue(type: String, data: kotlinx.serialization.json.JsonObject): Boolean {
        if (buffer.size >= MAX_BUFFERED) {
            // Never grow without bound while the server is unreachable. Newer events are dropped without a sequence
            // number, so the numbering the server expects has no gap; an overflow event says how many were lost.
            dropped++
            return false
        }
        buffer.addLast(RunnerEventView(++sequence, type, data))
        return true
    }
    private var dropped = 0

    private suspend fun uploadLoop() {
        while (!fenced) {
            if (!uploadOnce()) sleep(UPLOAD_MILLIS)
        }
    }

    /** Uploads one batch; returns true when more is waiting. */
    private suspend fun uploadOnce(): Boolean {
        val batch = lock.withLock {
            if (dropped > 0) {
                val lost = dropped; dropped = 0
                buffer.addLast(RunnerEventView(++sequence, CloudEventTypes.ERROR, buildJsonObject { put("code", "transcript_overflow"); put("dropped", lost) }))
            }
            var bytes = 0
            // The server limit counts UTF-8 bytes, not characters.
            buffer.asSequence().take(batchLimit).takeWhile { event -> bytes += event.data.toString().encodeToByteArray().size; bytes <= MAX_BATCH_BYTES }.toList().ifEmpty { buffer.take(1) }
        }
        if (batch.isEmpty()) return false
        val ack = try { channel.events(batch) } catch (error: CancellationException) { throw error } catch (error: Exception) {
            if (error is TeamCodeException && error.code != "execution_fenced" && refusesContent(error.statusCode)) return isolateRejected(batch)
            if (handle(error)) return false
            sleep(UPLOAD_MILLIS * 4); return false
        }
        batchLimit = MAX_BATCH
        lock.withLock { while (buffer.isNotEmpty() && buffer.first().sequence <= ack.acceptedThrough) buffer.removeFirst() }
        return lock.withLock { buffer.isNotEmpty() }
    }

    /**
     * The statuses the runner events route answers when the events themselves are refused: an invalid event (400), a
     * body over the size limit (413) or an unprocessable body (422). Retrying the same batch unchanged cannot succeed.
     * Any other status is retried with a pause, so an unrelated refusal never consumes transcript events.
     */
    private fun refusesContent(status: Int) = status == 400 || status == 413 || status == 422

    /**
     * Narrows a permanently rejected batch: halves the next batches until one event is left, then replaces that
     * event, in place so sequence numbers stay consecutive, with a small marker. One bad event never blocks the transcript.
     * A refused marker means the refusal is not about the content: the host backs off like any other error instead of
     * replacing event after event or retrying without pause.
     */
    private suspend fun isolateRejected(batch: List<RunnerEventView>): Boolean {
        if (batch.size > 1) { batchLimit = batch.size / 2; return true }
        val rejected = batch.single()
        if (rejected.isRejectionMarker()) { sleep(UPLOAD_MILLIS * 4); return false }
        log("The server refused one transcript event; replacing it with a marker.")
        val denial = lock.withLock {
            val index = buffer.indexOfFirst { it.sequence == rejected.sequence }
            if (index >= 0) buffer[index] = RunnerEventView(rejected.sequence, CloudEventTypes.ERROR, buildJsonObject { put("code", REJECTED_CODE); put("type", rejected.type) })
            // A request no member will see is denied rather than left blocking the agent.
            val requestId = (rejected.data["requestId"] as? JsonPrimitive)?.contentOrNull
            val child = process; val active = adapter
            if (rejected.type != CloudEventTypes.PERMISSION_REQUEST || requestId == null || child == null || active == null) null
            else { deadlines.remove(requestId)?.second?.cancel(); child to active.answer(requestId, false, UNSEEN_DENIAL, emptyList()).lines }
        }
        denial?.let { (child, lines) -> if (lines.isNotEmpty()) write(child, lines) }
        return true
    }

    private fun RunnerEventView.isRejectionMarker() = type == CloudEventTypes.ERROR && (data["code"] as? JsonPrimitive)?.contentOrNull == REJECTED_CODE

    private suspend fun flush(deadline: Long): Boolean {
        while (now() < deadline && !fenced) {
            if (lock.withLock { buffer.isEmpty() }) return true
            if (!uploadOnce()) sleep(UPLOAD_MILLIS)
        }
        return lock.withLock { buffer.isEmpty() }
    }

    /** True when the error ends the host (fenced or refused); logs the error code only. */
    private fun handle(error: Exception): Boolean {
        if (error is TeamCodeException && (error.code == "execution_fenced" || error.statusCode in setOf(401, 403, 404))) {
            fenced = true
            return true
        }
        log(if (error is TeamCodeException) "The server returned HTTP ${error.statusCode}${error.code?.let { " ($it)" } ?: ""}; retrying." else "The server is unreachable; retrying.")
        return false
    }

    private suspend fun <T> retrying(call: suspend () -> T): T {
        var wait = 1_000L
        while (true) {
            try { return call() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (handle(error)) throw error
                sleep(wait); wait = (wait * 2).coerceAtMost(15_000)
            }
        }
    }

    companion object {
        const val INPUT_WAIT_MILLIS = 20_000L
        const val UPLOAD_MILLIS = 500L
        const val MAX_BATCH = 200
        const val MAX_BATCH_BYTES = 1_000_000
        private const val REJECTED_CODE = "transcript_event_rejected"
        const val MAX_BUFFERED = 5_000
        /** What the agent reads when its request could not be recorded for the team. */
        const val UNSEEN_DENIAL = "The request could not be shown to the team: it was denied."
        /** What the agent reads when the session pauses while its request waits. */
        const val PAUSE_DENIAL = "The session is pausing: the request was denied."
        const val MAX_HANDLED = 1_000
        /** Downloads of one file before its message is rejected; a refusal by the server is not retried. */
        const val FILE_ATTEMPTS = 3
        const val DEFAULT_DRAIN_MILLIS = 10 * 60_000L
        const val SAFETY_MARGIN_MILLIS = 60_000L
        const val INTERRUPT_GRACE_MILLIS = 15_000L
        const val STOP_GRACE_MILLIS = 10_000L
        const val READER_END_MILLIS = 5_000L
        const val FLUSH_MILLIS = 30_000L
        /** Runner coordinates are not the agent's business; the token path in particular stays out of its environment. */
        val AGENT_ENVIRONMENT: Map<String, String?> = listOf("TEAMCODE_SERVER_URL", "TEAMCODE_CLOUD_TOKEN_FILE", "TEAMCODE_CLOUD_TEAM", "TEAMCODE_CLOUD_SESSION", "TEAMCODE_CLOUD_EPOCH")
            .associateWith { null }
        val stateJson = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The runner's loopback inference proxy: its port and the random local token the agent presents to it. [runId]: the
 * registered run the agent's requests belong to, on a member's device (as under `teamcode run`); null in a pod.
 */
internal class AgentInferenceEndpoint(val port: Int, val token: String, val runId: String? = null) {
    override fun toString() = "AgentInferenceEndpoint(port=$port, token=[REDACTED], runId=$runId)"
}

/** The state file on the workspace volume; a missing or unreadable file means "no conversation to resume". */
internal class FileAgentStateStore(private val path: String) : AgentStateStore {
    private val file = path.toPath()
    override fun read(): AgentState? = try {
        if (!FileSystem.SYSTEM.exists(file)) null
        else AgentHost.stateJson.decodeFromString<AgentState>(FileSystem.SYSTEM.read(file) { readUtf8() })
    } catch (_: Exception) { null }
    // Private and atomic, like the records beside it: on Windows a directory created otherwise is refused by their writes.
    override fun write(state: AgentState) = writePrivateFile(path, AgentHost.stateJson.encodeToString(state))
    override fun clear() { FileSystem.SYSTEM.delete(file, mustExist = false) }
}
