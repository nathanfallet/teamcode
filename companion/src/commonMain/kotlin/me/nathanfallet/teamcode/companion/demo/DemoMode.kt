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
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
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

    fun newId(prefix: String) = "demo-$prefix-${next()}"

    private fun announce(sessionId: String, state: SessionState?, turnPhase: String?) {
        feed.tryEmit(SessionChange(next(), if (turnPhase != null) "cloud.turn" else "cloud.session.state", sessionId, DemoContent.MEMBER_ID, now(), state, turnPhase))
    }
}
