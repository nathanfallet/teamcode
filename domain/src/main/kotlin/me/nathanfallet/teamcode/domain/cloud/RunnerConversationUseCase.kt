package me.nathanfallet.teamcode.domain.cloud

import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

/**
 * [permissionRelay]: the runner relays the agent's permission requests (Claude Code only; Codex's bypass mode produces
 * none). [permissionDeadlineMinutes]: the team's deadline for unanswered permission requests, 0 for none. [liveText]: the
 * runner streams the agent's text as `message.agent.delta` events (Claude Code only; Codex sends whole messages).
 * [permissionMode]: a remote session's mode on its host's files; null for a cloud session, whose pod is its sandbox.
 */
data class RunnerAgentConfig(val kind: String, val version: String?, val model: String?, val effort: String?, val conversation: ConversationResume,
    val permissionRelay: Boolean = false, val permissionDeadlineMinutes: Int = 0, val liveText: Boolean = false,
    val permissionMode: RemotePermissionMode? = null)

/** Inputs delivered to the runner, and the session's desired state when they were read. */
data class RunnerInputs(val inputs: List<CloudInput>, val desiredState: DesiredState)

/**
 * The agent side of the runner channel: configuration, transcript upload, input delivery and acknowledgements.
 * Every call is fenced like the lifecycle channel: only the registered execution of the current epoch of a live
 * session is served, anything else is `409 execution_fenced`.
 */
class RunnerConversationUseCase(
    private val sessions: CloudSessionStore,
    private val store: CloudConversationStore,
    /** Whether a member has linked GitHub; see [CloudConversationService]. Required: the delivery guard must not fail open. */
    private val githubLinked: suspend (teamId: String, memberId: String) -> Boolean,
    private val transition: TransitionCloudSessionUseCase = TransitionCloudSessionUseCase(sessions),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun agent(teamId: String, sessionId: String, epoch: Int): RunnerAgentConfig {
        val session = live(teamId, sessionId, epoch)
        return RunnerAgentConfig(session.agentKind, session.agentVersion, session.agentModel, session.agentEffort, session.resumeConversation,
            permissionRelay = session.agentKind == "claude", permissionDeadlineMinutes = sessions.settings(teamId).permissionDeadlineMinutes,
            liveText = session.agentKind == "claude",
            permissionMode = session.host?.permissionMode?.takeIf { session.kind == CloudSessionKind.REMOTE })
    }

    /**
     * Stores a batch in order. A turn that starts makes a ready session `active` and one that ends makes it `ready`
     * again, both refreshing its activity time; a conversation marker records whether the conversation can resume.
     */
    suspend fun events(teamId: String, sessionId: String, epoch: Int, events: List<RunnerEvent>): RunnerEventsAck {
        if (events.size > CloudConversationRules.MAX_EVENTS_PER_BATCH) throw TeamFailure("invalid_events", 400, "Send at most ${CloudConversationRules.MAX_EVENTS_PER_BATCH} events per batch.")
        if (events.any { it.type !in CloudEventType.fromRunner || it.sequence < 1 || it.data.encodeToByteArray().size > CloudConversationRules.MAX_EVENT_BYTES })
            throw TeamFailure("invalid_events", 400, "Send normalized events with positive sequence numbers.")
        if (events.zipWithNext().any { (a, b) -> b.sequence != a.sequence + 1 }) throw TeamFailure("invalid_events", 400, "Send consecutive sequence numbers.")
        live(teamId, sessionId, epoch)
        val ack = store.appendRunnerEvents(sessionId, epoch, events)
        val accepted = events.filter { it.sequence <= ack.acceptedThrough }
        val turn = accepted.lastOrNull { it.turn != null }?.turn
        val conversation = accepted.lastOrNull { it.conversation != null }?.conversation
        if (turn != null || conversation != null) transition(teamId, sessionId) { current ->
            if (current.currentEpoch != epoch || current.state.terminal) return@transition null
            val now = sessions.now()
            var next = current
            if (conversation != null) next = next.copy(resumeConversation = conversation)
            if (turn != null && current.desiredState == DesiredState.RUNNING && (current.state == READY || current.state == ACTIVE))
                next = next.copy(state = if (turn == TurnMarker.STARTED) ACTIVE else READY, lastActivityAt = now)
            if (next == current) null else CloudTransition(next.copy(updatedAt = now), CloudWrite())
        }
        return ack
    }

    /**
     * Long-poll: waits up to [waitMillis] for inputs and returns them marked delivered to this epoch. Inputs are
     * delivered only while the agent can take them (`ready` or `active`, desired running); a session that is
     * pausing receives none, so its queue survives for the next execution.
     */
    suspend fun inputs(teamId: String, sessionId: String, epoch: Int, waitMillis: Long): List<CloudInput> = poll(teamId, sessionId, epoch, waitMillis, null).inputs

    /**
     * [inputs], with the session's desired state when the wait ended. A runner that sends the desired state it [known]
     * gets an answer as soon as the session's differs: a pause (or a resume that cancels it) reaches it within
     * [POLL_MILLIS] through this already open request, instead of at its next heartbeat.
     */
    /**
     * [acceptsFiles]: the runner downloads the files a message carries (`cloud.files.v1`). A runner that does not would hand
     * the agent the text alone, so such a message is rejected for it with `files_unsupported` instead of being delivered.
     */
    suspend fun poll(teamId: String, sessionId: String, epoch: Int, waitMillis: Long, known: String?, acceptsFiles: Boolean = false): RunnerInputs {
        // Only the two states a runner acts on: a deleted session fences the call anyway.
        val knownState = known?.let { wire -> DesiredState.entries.firstOrNull { it.wire == wire && it != DesiredState.TERMINATED }
            ?: throw TeamFailure("invalid_state", 400, "Send the desired state as running or paused.") }
        val deadline = sessions.now() + waitMillis.coerceIn(0, CloudConversationRules.MAX_RUNNER_WAIT_MILLIS)
        while (true) {
            val session = live(teamId, sessionId, epoch)
            if (knownState != null && session.desiredState != knownState) return RunnerInputs(emptyList(), session.desiredState)
            if (session.desiredState == DesiredState.RUNNING && (session.state == READY || session.state == ACTIVE)) {
                val delivered = store.deliver(sessionId, epoch, DELIVERY_BATCH).let { inputs ->
                    val unsupported = if (acceptsFiles) emptyList() else inputs.filter { it.files.isNotEmpty() }
                    if (unsupported.isNotEmpty()) store.acknowledge(sessionId, epoch, unsupported.map { RunnerInputAck(it.id, false, "files_unsupported") })
                    // Checked again at delivery (audit M2): a non-owner's input accepted while the owner had no link, or
                    // while the session was shared, never reaches an agent that now holds the owner's token.
                    val held = (inputs - unsupported.toSet()).filter { CloudConversationRules.ownerOnly(session, it.authorId) && !CloudConversationRules.onlyStops(it) }
                        .takeIf { it.isNotEmpty() && githubLinked(teamId, session.ownerId) }.orEmpty()
                    if (held.isNotEmpty()) store.acknowledge(sessionId, epoch, held.map { RunnerInputAck(it.id, false, "github_owner_only") })
                    inputs - unsupported.toSet() - held.toSet()
                }
                if (delivered.isNotEmpty()) return RunnerInputs(delivered, session.desiredState)
            }
            if (sessions.now() >= deadline) return RunnerInputs(emptyList(), session.desiredState)
            sleep(POLL_MILLIS)
        }
    }

    suspend fun acknowledge(teamId: String, sessionId: String, epoch: Int, acks: List<RunnerInputAck>): List<CloudInput> {
        if (acks.size > DELIVERY_BATCH * 4 || acks.any { it.reason != null && !Regex("[a-z_]{1,64}").matches(it.reason) })
            throw TeamFailure("invalid_acks", 400, "Acknowledge at most ${DELIVERY_BATCH * 4} inputs with stable reason codes.")
        live(teamId, sessionId, epoch)
        return store.acknowledge(sessionId, epoch, acks)
    }

    private suspend fun live(teamId: String, sessionId: String, epoch: Int): CloudSession = liveExecution(sessions, teamId, sessionId, epoch)

    companion object {
        const val POLL_MILLIS = 500L
        const val DELIVERY_BATCH = 25
    }
}

/** The session of a runner call when [epoch] is its current, running execution; anything else is `409 execution_fenced`. */
internal suspend fun liveExecution(sessions: CloudSessionStore, teamId: String, sessionId: String, epoch: Int): CloudSession {
    val session = sessions.find(teamId, sessionId) ?: throw fenced()
    if (session.currentEpoch != epoch || session.state.terminal || session.desiredState == DesiredState.TERMINATED) throw fenced()
    val execution = sessions.execution(sessionId, epoch) ?: throw fenced()
    if (execution.state != ExecutionState.RUNNING) throw fenced()
    return session
}
private fun fenced() = TeamFailure("execution_fenced", 409, "This execution is no longer current. Stop the agent and exit without checkpointing.")
