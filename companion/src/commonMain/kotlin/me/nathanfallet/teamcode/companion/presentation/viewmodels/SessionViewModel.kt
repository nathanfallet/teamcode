package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.FollowTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.LoadTranscriptUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.Transcript
import me.nathanfallet.teamcode.companion.presentation.navigation.SessionRoute

enum class LiveState { CONNECTING, LIVE, RECONNECTING, ENDED, STOPPED }

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
) {
    /** A turn is running: its last `turn` event is `started`. */
    val running: Boolean get() = transcript.events.lastOrNull { it is TranscriptEvent.Turn }.let { it is TranscriptEvent.Turn && it.phase == TurnPhase.STARTED }
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
) : ViewModel() {
    private val state = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = state.asStateFlow()
    private val loading = Mutex()
    private var follow: Job? = null
    private var started = false

    fun start() {
        started = true
        // An ended session sent everything: nothing more to follow.
        if (follow?.isActive == true || state.value.gone || state.value.live == LiveState.ENDED) return
        state.update { it.copy(live = LiveState.CONNECTING, error = null) }
        follow = viewModelScope.launch {
            try {
                // The header first, then the newest page: following starts after its last sequence.
                refreshSession()
                loading.withLock {
                    if (!state.value.loaded) {
                        val page = loadTranscript(route.accountId, route.sessionId)
                        state.update { it.copy(transcript = it.transcript.withLatest(page), loaded = true) }
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
                        is LiveUpdate.Item -> state.update { it.copy(transcript = it.transcript.withLive(update.value), live = LiveState.LIVE) }
                        is LiveUpdate.Reconnecting -> { reconnected = true; state.update { it.copy(live = LiveState.RECONNECTING) } }
                        LiveUpdate.Gap -> Unit
                        LiveUpdate.Ended -> {
                            state.update { it.copy(live = LiveState.ENDED) }
                            refreshSession()
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(live = LiveState.STOPPED, error = error.userMessage(), gone = error is CompanionException.NotFound || error is CompanionException.AccessRevoked) }
            }
        }
    }

    fun stop() {
        started = false
        follow?.cancel(); follow = null
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

    private suspend fun refreshSessionQuietly() {
        try {
            refreshSession()
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionException.Unauthorized) {
            throw error
        } catch (_: Exception) {
            // The header stays as it was; the transcript goes on.
        }
    }

    private suspend fun refreshSession() {
        val session = getSession(route.accountId, route.sessionId)
        state.update { it.copy(session = session) }
    }
}
