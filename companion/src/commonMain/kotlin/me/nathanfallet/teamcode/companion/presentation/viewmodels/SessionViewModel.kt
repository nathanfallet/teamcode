package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TurnPhase
import me.nathanfallet.teamcode.companion.domain.models.PendingMessage
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SendMessageUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.reconciled
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.FollowTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.Transcript
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute

enum class LiveState { CONNECTING, LIVE, RECONNECTING, ENDED, STOPPED }

/** A short message under the composer; the fixed ones are worded by the screen, from its string resources. */
sealed interface SessionNotice {
    data class Text(val text: String) : SessionNotice
    data object NothingRunning : SessionNotice
}

data class SessionUiState(
    val session: CloudSession? = null,
    val transcript: Transcript = Transcript(),
    val loaded: Boolean = false,
    val loadingOlder: Boolean = false,
    val live: LiveState = LiveState.STOPPED,
    /** A failure that ended the screen's stream or its first load. */
    val error: String? = null,
    /** True when the session is gone or no longer visible. */
    val gone: Boolean = false,
    /** Messages sent from here that the transcript does not show yet, or that failed. */
    val pending: List<PendingMessage> = emptyList(),
    val draft: String = "",
    /** The member may pause and resume (owner or administrator). */
    val canControl: Boolean = false,
    val acting: Boolean = false,
    /** A short result of an action (woken, nothing to interrupt) or its failure. */
    val notice: SessionNotice? = null,
) {
    /** A turn is running: its last `turn` event is `started` and the session is working (not paused or ended since). */
    val running: Boolean get() = (session == null || session.state == SessionState.ACTIVE) &&
        transcript.events.lastOrNull { it is TranscriptEvent.Turn }.let { it is TranscriptEvent.Turn && it.phase == TurnPhase.STARTED }
}

/**
 * One session: its header, the newest page of history, older pages on demand, and the live stream after the newest
 * event while the screen is visible ([start], [stop]). Events are merged by sequence, so nothing shows twice.
 */
class SessionViewModel(
    private val route: SessionRoute,
    private val getSession: GetSessionUseCase,
    private val loadTranscript: LoadTranscriptUseCase,
    private val followTranscript: FollowTranscriptUseCase,
    private val sendMessage: SendMessageUseCase,
    private val control: ControlSessionUseCase,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val state = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = state.asStateFlow()
    private val loading = Mutex()
    private var follow: Job? = null
    private var header: Job? = null
    private var started = false

    fun start() {
        started = true
        // The transcript does not carry pauses, resumes or a failed pod: the header is read again while visible.
        if (header?.isActive != true) header = viewModelScope.launch {
            while (true) {
                delay(HEADER_MILLIS)
                // An ended session no longer changes: nothing to read.
                val current = state.value
                if (current.gone || current.live == LiveState.ENDED || current.session?.state?.group == SessionGroup.ENDED) continue
                refreshSessionQuietly()
            }
        }
        // An ended session sent everything: nothing more to follow.
        if (follow?.isActive == true || state.value.gone || state.value.live == LiveState.ENDED) return
        state.update { it.copy(live = LiveState.CONNECTING, error = null) }
        follow = viewModelScope.launch {
            try {
                // The header first, then the newest page: following starts after its last sequence. A header that cannot be
                // read now (offline, a 5xx) is read again by the header loop; only a session that is gone stops here.
                try { refreshSession() } catch (error: CancellationException) { throw error } catch (error: CompanionException.NotFound) { throw error } catch (_: Exception) { }
                loading.withLock {
                    if (!state.value.loaded) {
                        val page = loadTranscript(route.accountId, route.sessionId)
                        state.update { it.withTranscript(it.transcript.withLatest(page)).copy(loaded = true) }
                    }
                }
                var reconnected = false
                followTranscript(route.accountId, route.sessionId, state.value.transcript.newestSeq).collect { update ->
                    when (update) {
                        is LiveUpdate.Opened -> {
                            state.update { it.copy(live = LiveState.LIVE) }
                            // Back after a cut: the session may have been paused or resumed meanwhile.
                            if (reconnected) { reconnected = false; refreshSessionQuietly() }
                        }
                        is LiveUpdate.Item -> state.update { it.withTranscript(it.transcript.withLive(update.value)).withTurn(update.value).copy(live = LiveState.LIVE) }
                        is LiveUpdate.Reconnecting -> { reconnected = true; state.update { it.copy(live = LiveState.RECONNECTING) } }
                        LiveUpdate.Gap -> Unit
                        LiveUpdate.Ended -> {
                            state.update { it.copy(live = LiveState.ENDED) }
                            // The final state stays whatever the header read does.
                            refreshSessionQuietly()
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Withdrawn access may come back (or was an expired token, which the guard signed out): retry stays offered.
                state.update { it.copy(live = LiveState.STOPPED, error = error.userMessage(), gone = error is CompanionException.NotFound) }
            }
        }
    }

    fun stop() {
        started = false
        follow?.cancel(); follow = null
        header?.cancel(); header = null
        state.update { if (it.live == LiveState.ENDED) it else it.copy(live = LiveState.STOPPED) }
    }

    fun retry() { if (started) { state.update { it.copy(error = null) }; start() } }

    fun loadOlder() {
        val before = state.value.transcript.nextBefore ?: return
        if (state.value.loadingOlder) return
        state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            try {
                loading.withLock {
                    // Another page may have arrived meanwhile.
                    if (state.value.transcript.nextBefore != before) return@withLock
                    val page = loadTranscript(route.accountId, route.sessionId, before)
                    state.update { it.copy(transcript = it.transcript.withOlder(page)) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(error = error.userMessage()) }
            } finally {
                state.update { it.copy(loadingOlder = false) }
            }
        }
    }

    fun onDraftChange(text: String) = state.update { it.copy(draft = text) }
    fun dismissNotice() = state.update { it.copy(notice = null) }

    /** Shows the message at once, then sends it; the draft is cleared only once it is queued for sending. */
    fun send() {
        val message = try {
            sendMessage.draft(state.value.draft.trim())
        } catch (error: IllegalArgumentException) {
            state.update { it.copy(notice = SessionNotice.Text(error.userMessage())) }
            return
        }
        state.update { it.copy(draft = "", pending = it.pending + message) }
        deliver(message)
    }

    /** Sends a failed message again with the same id: the server never queues it twice. */
    fun retry(clientMessageId: String) {
        val message = state.value.pending.firstOrNull { it.clientMessageId == clientMessageId && it.state == PendingMessage.State.FAILED } ?: return
        val sending = message.copy(state = PendingMessage.State.SENDING, reason = null)
        state.update { current -> current.copy(pending = current.pending.map { if (it.clientMessageId == clientMessageId) sending else it }) }
        deliver(sending)
    }

    fun discard(clientMessageId: String) = state.update { current ->
        current.copy(pending = current.pending.filterNot { it.clientMessageId == clientMessageId && it.state != PendingMessage.State.SENDING })
    }

    private fun deliver(message: PendingMessage) {
        viewModelScope.launch {
            val result = sendMessage(route.accountId, route.sessionId, message)
            state.update { current ->
                current.copy(pending = current.pending.map { if (it.clientMessageId == message.clientMessageId) result else it }.reconciled(current.transcript.events))
            }
            // A paused session woke for it (the pending bubble says so): the header changes state.
            if (result.woke) refreshSessionQuietly()
        }
    }

    /** Kept from a tap until the server answers: tapping again after a lost answer repeats the same interruption. */
    private var interruptKey: String? = null

    fun interrupt() = act {
        val key = interruptKey ?: control.interruptKey().also { interruptKey = it }
        val interrupted = try { control.interrupt(route.accountId, route.sessionId, key) } catch (error: CompanionException) {
            // Answered with a refusal: that interruption is over. Unreachable or a timeout: it may still be queued.
            if (error !is CompanionException.Unreachable && !(error is CompanionException.Server && (error.statusCode >= 500 || error.statusCode == 408))) interruptKey = null
            throw error
        }
        interruptKey = null
        if (!interrupted) state.update { it.copy(notice = SessionNotice.NothingRunning) }
    }
    fun pause() = act { val session = control.pause(route.accountId, route.sessionId); state.update { it.copy(session = session) } }
    fun resume() = act { val session = control.resume(route.accountId, route.sessionId); state.update { it.copy(session = session) } }

    private fun act(action: suspend () -> Unit) {
        if (state.value.acting) return
        state.update { it.copy(acting = true, notice = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(notice = SessionNotice.Text(error.userMessage())) }
            } finally {
                state.update { it.copy(acting = false) }
            }
        }
    }

    /** A live `turn` moves a ready session to working and back, as the server does. */
    private fun SessionUiState.withTurn(event: TranscriptEvent): SessionUiState {
        val session = session ?: return this
        if (event !is TranscriptEvent.Turn) return this
        val next = when {
            event.phase == TurnPhase.STARTED && session.state == SessionState.READY -> SessionState.ACTIVE
            event.phase != TurnPhase.STARTED && session.state == SessionState.ACTIVE -> SessionState.READY
            else -> return this
        }
        return copy(session = session.copy(state = next))
    }

    private fun SessionUiState.withTranscript(transcript: Transcript) = copy(transcript = transcript, pending = pending.reconciled(transcript.events))

    private suspend fun refreshSessionQuietly() {
        try {
            refreshSession()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // The header stays as it was; the transcript goes on. An expired login was already signed out by the guard,
            // which takes the app back to sign-in: nothing here may throw, since its callers are plain launches.
        }
    }

    private companion object {
        const val HEADER_MILLIS = 30_000L
    }

    private suspend fun refreshSession() {
        val session = getSession(route.accountId, route.sessionId)
        val account = accounts.accounts.value.firstOrNull { it.id == route.accountId }
        state.update { it.copy(session = session, canControl = account != null && ControlSessionUseCase.canControl(account, session)) }
    }
}
