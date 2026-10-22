package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.SessionGroup
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.FollowSessionListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.GetSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadSessionsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionList
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionListSignal

data class SessionsUiState(
    val account: Account? = null,
    val list: SessionList? = null,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** True while the feed (or polling) is running; false in the background, after a refusal, or signed out. */
    val following: Boolean = false,
    val reconnecting: Boolean = false,
    val error: String? = null,
) {
    val scope: SessionScope get() = list?.scope ?: SessionScope.MINE
    val group: SessionGroup get() = list?.group ?: SessionGroup.ACTIVE
}

/**
 * The active account's sessions. Every change goes through one sequential loop: the feed's signals, a filter change
 * and "load more" are handled one at a time, so an event received while the list loads is applied after it, never
 * overwritten by it. [start] and [stop] follow the screen's lifecycle: nothing stays open in the background.
 */
class SessionsViewModel(
    private val accounts: AccountRepository,
    private val loadSessions: LoadSessionsUseCase,
    private val getSession: GetSessionUseCase,
    private val followList: FollowSessionListUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(SessionsUiState())
    val uiState: StateFlow<SessionsUiState> = state.asStateFlow()

    private sealed interface Command {
        data object Reload : Command
        data object LoadMore : Command
        data object Settle : Command
    }
    private val commands = Channel<Command>(capacity = 16)
    private var started = false
    private var follow: Job? = null
    /** Where the next settling round starts in the unsettled rows. */
    private var settleTurn = 0

    init {
        viewModelScope.launch {
            // The active account, or its signed-in flag, changed: a new list for it.
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new ->
                    old?.id == new?.id && old?.signedIn == new?.signedIn && old?.memberId == new?.memberId && old?.role == new?.role && old?.origin == new?.origin
                }
                .collect { account ->
                    val scope = if (account?.isAdministrator == true) state.value.scope else SessionScope.MINE
                    state.value = SessionsUiState(account = account, list = account?.let { SessionList(scope, state.value.group, it.memberId) })
                    restart()
                }
        }
    }

    fun start() { started = true; restart() }

    fun stop() {
        started = false
        follow?.cancel(); follow = null
        state.update { it.copy(following = false, reconnecting = false, loading = false, loadingMore = false) }
    }

    fun retry() = restart()

    fun setGroup(group: SessionGroup) = changeFilter(state.value.scope, group)
    fun setScope(scope: SessionScope) = changeFilter(scope, state.value.group)
    fun loadMore() { if (state.value.list?.nextBefore != null && follow?.isActive == true) commands.trySend(Command.LoadMore) }
    fun opened(sessionId: String) = state.update { it.copy(list = it.list?.opened(sessionId)) }

    private fun changeFilter(scope: SessionScope, group: SessionGroup) {
        val current = state.value
        val account = current.account ?: return
        if (scope == current.scope && group == current.group) return
        if (scope == SessionScope.TEAM && !account.isAdministrator) return
        // Loading until the queued reload runs: an empty new list never reads as "no sessions" meanwhile.
        val running = follow?.isActive == true
        state.update { it.copy(list = SessionList(scope, group, account.memberId, finishedTurns = it.list?.finishedTurns.orEmpty()), loading = running, error = null) }
        // The loop reads the command; stopped after an error, it is started again, and the feed's opening loads the list.
        if (running) commands.trySend(Command.Reload) else restart()
    }

    private fun restart() {
        follow?.cancel(); follow = null
        val account = state.value.account
        if (!started || account == null || !account.signedIn) return
        state.update { it.copy(following = true, reconnecting = false, error = null) }
        follow = viewModelScope.launch {
            try {
                val ticks = flow { while (true) { delay(SETTLE_MILLIS); emit(Command.Settle) } }
                merge(followList(account.id), commands.receiveAsFlow(), ticks).collect { handle(account.id, it) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(following = false, reconnecting = false, loading = false, loadingMore = false, error = error.userMessage()) }
            }
        }
    }

    private suspend fun handle(accountId: String, signal: Any) {
        when (signal) {
            Command.Reload, SessionListSignal.Reload -> reload(accountId)
            Command.LoadMore -> more(accountId)
            // Read again the rows whose state changes without an event (at most a few per tick).
            Command.Settle -> {
                // In turns, so a few rows that never settle do not hide the others.
                val unsettled = state.value.list?.unsettled.orEmpty()
                if (unsettled.isEmpty()) return
                val first = settleTurn % unsettled.size
                settleTurn = first + MAX_SETTLE
                (unsettled.drop(first) + unsettled.take(first)).take(MAX_SETTLE).forEach { fetchOne(accountId, it) }
            }
            is SessionListSignal.Connection -> state.update { it.copy(reconnecting = signal.reconnecting) }
            is SessionListSignal.Changed -> {
                val list = state.value.list ?: return
                val (applied, fetch) = list.applied(signal.change)
                state.update { it.copy(list = applied) }
                if (fetch != null) fetchOne(accountId, fetch)
            }
        }
    }

    private suspend fun reload(accountId: String) {
        val list = state.value.list ?: return
        // The spinner only for a list with nothing to show yet: a refresh of a shown list stays quiet.
        state.update { it.copy(loading = list.sessions.isEmpty()) }
        // Cancelled by a restart (retry, account change): the flag never stays on.
        val outcome = try { attempt { loadSessions(accountId, list.scope, list.group) } } finally { state.update { it.copy(loading = false) } }
        state.update { current ->
            // A filter changed while loading: that change queued its own reload.
            val target = current.list?.takeIf { it.scope == list.scope && it.group == list.group }
            current.copy(loading = false, list = outcome.getOrNull()?.let { page -> target?.refreshed(page) } ?: current.list, error = outcome.exceptionOrNull()?.userMessage())
        }
    }

    private suspend fun more(accountId: String) {
        val list = state.value.list ?: return
        val before = list.nextBefore ?: return
        state.update { it.copy(loadingMore = true) }
        val outcome = try { attempt { loadSessions(accountId, list.scope, list.group, before) } } finally { state.update { it.copy(loadingMore = false) } }
        state.update { current ->
            val target = current.list?.takeIf { it.scope == list.scope && it.group == list.group && it.nextBefore == before }
            current.copy(loadingMore = false, list = outcome.getOrNull()?.let { page -> target?.appended(page) } ?: current.list, error = outcome.exceptionOrNull()?.userMessage())
        }
    }

    private suspend fun fetchOne(accountId: String, sessionId: String) {
        val outcome = attempt { getSession(accountId, sessionId) }
        val session = outcome.getOrNull()
        when {
            session != null -> state.update { it.copy(list = it.list?.upserted(session)) }
            // Gone or hidden (a transfer to someone else): it leaves the list.
            outcome.exceptionOrNull() is CompanionException.NotFound -> state.update { it.copy(list = it.list?.forgotten(sessionId)) }
            // Unreachable for a moment: the next event or reload brings it.
        }
    }

    private companion object {
        const val SETTLE_MILLIS = 15_000L
        const val MAX_SETTLE = 5
    }

    /** Runs [call], keeping its failure as a value; cancellation and a signed-out account still end the loop. */
    private suspend fun <T> attempt(call: suspend () -> T): Result<T> = try {
        Result.success(call())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (error is CompanionException.Unauthorized) throw error
        Result.failure(error)
    }
}
