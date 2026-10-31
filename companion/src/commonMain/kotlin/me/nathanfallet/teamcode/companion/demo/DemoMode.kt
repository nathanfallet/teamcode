package me.nathanfallet.teamcode.companion.demo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.RunStatus
import me.nathanfallet.teamcode.companion.domain.models.RunVisit
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.domain.models.VisitStatus
import me.nathanfallet.teamcode.companion.domain.models.Workflow
import me.nathanfallet.teamcode.companion.domain.models.WorkflowRun
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitAnswer
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import kotlinx.serialization.json.JsonElement
import me.nathanfallet.teamcode.companion.domain.services.Clock

/**
 * The built-in demo: an account on no server, whose sessions, transcripts and artifacts live in memory only. Started
 * from the sign-in screen ([start]), ended by signing the account out ([end]), which drops everything it held; nothing
 * of it is written to the device, so quitting the app ends it too.
 */
class DemoMode(private val clock: Clock) {
    private val current = MutableStateFlow<DemoStore?>(null)

    /** The demo account while the demo runs; null otherwise. */
    val account: Account? get() = current.value?.account?.value

    val active: Boolean get() = current.value != null

    /** Starts a fresh demo (or restarts the running one from scratch) and returns its account. */
    fun start(deviceName: String): Account = DemoStore(clock, DemoContent.account(deviceName)).also { current.value = it }.account.value

    /** Ends the demo: its account, sessions and messages are gone. */
    fun end() { current.value = null }

    /** The running demo's data; a demo that ended reads as a session that no longer exists. */
    internal fun store(): DemoStore = current.value ?: throw CompanionException.NotFound()
}

/** One run of the demo: what the member sees and changes, until the demo ends. */
internal class DemoStore(private val clock: Clock, account: Account) {
    val account = MutableStateFlow(account)
    private val startedAt = clock.nowMillis()
    private val _sessions = MutableStateFlow(DemoContent.sessions(startedAt).associateBy { it.id })
    val sessions: StateFlow<Map<String, CloudSession>> = _sessions.asStateFlow()
    private val _transcripts = MutableStateFlow(DemoContent.sessions(startedAt).associate { it.id to DemoContent.transcript(it.id, startedAt) })
    val transcripts: StateFlow<Map<String, List<TranscriptEvent>>> = _transcripts.asStateFlow()
    val artifacts = DemoContent.artifacts(startedAt)
    private val _waits = MutableStateFlow(DemoContent.waits(startedAt))
    val waits: StateFlow<List<Wait>> = _waits.asStateFlow()

    val workflows = DemoContent.workflows(startedAt)
    private val _runs = MutableStateFlow(DemoContent.workflowRuns(startedAt))
    val runs: StateFlow<List<WorkflowRun>> = _runs.asStateFlow()

    /** The caller-wide feed: what the session list hears about the demo's sessions. */
    val feed = MutableSharedFlow<SessionChange>(extraBufferCapacity = 64)
    private val counter = MutableStateFlow(0L)

    private fun next(): Long = counter.updateAndGet { it + 1 }

    fun now(): Long = clock.nowMillis()

    fun session(id: String): CloudSession = sessions.value[id] ?: throw CompanionException.NotFound()

    fun add(session: CloudSession) {
        _sessions.update { it + (session.id to session) }
        _transcripts.update { it + (session.id to emptyList()) }
        announce(session.id, session.state, null)
    }

    /** Moves [id] to [state] at once (the demo has no pod to wait for) and tells the feed. */
    fun move(id: String, state: SessionState): CloudSession {
        val moved = session(id).copy(state = state, updatedAt = now())
        _sessions.update { it + (id to moved) }
        announce(id, state, null)
        return moved
    }

    /** Renames [id] at once and tells the feed. */
    fun rename(id: String, name: String): CloudSession {
        val renamed = session(id).copy(name = name, updatedAt = now())
        _sessions.update { it + (id to renamed) }
        feed.tryEmit(SessionChange(next(), SessionChange.RENAMED, id, DemoContent.MEMBER_ID, now(), renamed.state, null, name = name))
        return renamed
    }

    /**
     * Records the member's [text] (when there is one) and the agent's scripted reply after it, as one finished turn;
     * returns the input id of the message.
     */
    fun exchange(sessionId: String, text: String?): String {
        session(sessionId)
        val inputId = "demo-input-${next()}"
        val at = now()
        _transcripts.update { all ->
            val events = all[sessionId].orEmpty()
            var seq = events.lastOrNull()?.seq ?: 0
            val added = buildList {
                if (text != null) add(TranscriptEvent.UserMessage(++seq, at, text, inputId, DemoContent.MEMBER_ID))
                add(TranscriptEvent.AgentMessage(++seq, at, DemoContent.SCRIPTED_REPLY, null))
                add(TranscriptEvent.Turn(++seq, at, TurnPhase.COMPLETED, 0, 1))
            }
            all + (sessionId to events + added)
        }
        _sessions.update { all -> all[sessionId]?.let { all + (sessionId to it.copy(lastActivityAt = at, updatedAt = at)) } ?: all }
        announce(sessionId, null, TurnPhase.COMPLETED.wire)
        return inputId
    }

    /** Answers an open wait of the member's at once, as the server would, and tells the feed. */
    fun answerWait(id: String, actionId: String, values: Map<String, JsonElement>): Wait {
        val wait = waits.value.firstOrNull { it.id == id } ?: throw CompanionException.NotFound()
        // A session's own request is answered in the session, a closed wait does not change.
        if (!wait.open || wait.source == WaitSource.SESSION) throw CompanionException.Server(409, if (wait.open) "answer_through_session" else "wait_closed")
        if (wait.actions.none { it.id == actionId }) throw CompanionException.Server(400, "invalid_answer")
        val at = now()
        val resolved = wait.copy(open = false, resolvedAt = at, answer = WaitAnswer(actionId, values, DemoContent.MEMBER_ID, at))
        _waits.update { all -> all.map { if (it.id == id) resolved else it } }
        feed.tryEmit(SessionChange(next(), SessionChange.WAIT_RESOLVED, wait.sessionId.orEmpty(), DemoContent.MEMBER_ID, at, null, null, waitId = id))
        finishRunBlockedOn(id, actionId, at)
        return resolved
    }

    /** The run blocked on a wait that was answered moves on: in the demo, its workflow ends there. */
    private fun finishRunBlockedOn(waitId: String, actionId: String, at: Long) {
        val run = runs.value.firstOrNull { r -> r.status.active && r.activeVisit?.waitId == waitId } ?: return
        val done = run.copy(
            status = RunStatus.SUCCEEDED, rawStatus = RunStatus.SUCCEEDED.wire, updatedAt = at, finishedAt = at, currentNodeId = null, currentKind = null, output = actionId,
            visits = run.visits.map { if (it.status == VisitStatus.ACTIVE) it.copy(status = VisitStatus.DONE, outcome = actionId, output = actionId, finishedAt = at) else it },
        )
        _runs.update { all -> all.map { if (it.id == run.id) done else it } }
        announceRun(done)
    }

    fun run(id: String): WorkflowRun = runs.value.firstOrNull { it.id == id } ?: throw CompanionException.NotFound()

    /** Starts a run of [workflow] at once, at its first step (no agent works in the demo), and tells the feed. */
    fun startRun(workflow: Workflow, inputs: Map<String, String>): WorkflowRun {
        val first = workflow.step(workflow.start) ?: throw CompanionException.NotFound()
        val at = now()
        val id = newId("run")
        val visit = RunVisit(1, first.id, first.title, first.kind, VisitStatus.ACTIVE, null, null, null, null, null, at, null)
        val started = WorkflowRun(id, workflow.id, workflow.name, workflow.version, RunStatus.RUNNING, RunStatus.RUNNING.wire, DemoContent.MEMBER_ID, inputs, null, first.id, first.kind, null, null, null, listOf(visit), at, at, null)
        _runs.update { listOf(started) + it }
        announceRun(started)
        return started
    }

    /** Cancels a run that moves, as the server would, and tells the feed. */
    fun cancelRun(id: String): WorkflowRun {
        val run = run(id)
        if (!run.status.active) throw CompanionException.Server(409, "run_finished")
        val at = now()
        val cancelled = run.copy(
            status = RunStatus.CANCELLED, rawStatus = RunStatus.CANCELLED.wire, updatedAt = at, finishedAt = at, currentNodeId = null, currentKind = null,
            visits = run.visits.map { if (it.status == VisitStatus.ACTIVE) it.copy(status = VisitStatus.CANCELLED, finishedAt = at) else it },
        )
        _runs.update { all -> all.map { if (it.id == id) cancelled else it } }
        announceRun(cancelled)
        return cancelled
    }

    private fun announceRun(run: WorkflowRun) {
        feed.tryEmit(SessionChange(next(), SessionChange.WORKFLOW_RUN, "", DemoContent.MEMBER_ID, now(), null, null, runId = run.id, runStatus = run.status, runWorkflowId = run.workflowId))
    }

    fun newId(prefix: String) = "demo-$prefix-${next()}"

    private fun announce(sessionId: String, state: SessionState?, turnPhase: String?) {
        feed.tryEmit(SessionChange(next(), if (turnPhase != null) "cloud.turn" else "cloud.session.state", sessionId, DemoContent.MEMBER_ID, now(), state, turnPhase))
    }
}
