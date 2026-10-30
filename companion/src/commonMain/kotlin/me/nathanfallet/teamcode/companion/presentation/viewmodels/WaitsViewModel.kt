package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.FieldInput
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitInputProblem
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.waits.AnswerWaitUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.FollowWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.LoadWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.WaitAnswerOutcome
import me.nathanfallet.teamcode.companion.domain.usecases.waits.WaitsSignal

/** What the member typed for one wait, and where sending it stands. */
data class WaitDraft(
    val inputs: Map<String, FieldInput> = emptyMap(),
    val sending: Boolean = false,
    /** The field to fix, found before sending. */
    val problem: WaitInputProblem? = null,
    val notice: WaitNotice? = null,
)

/** Why an answer did not go through, besides a field to fix. */
enum class WaitNotice { REFUSED, RETRY, FAILED }

/** The lists of the waits screen: what is open (the default), what was answered, what was withdrawn. */
enum class WaitFilter(val status: WaitStatus) { OPEN(WaitStatus.OPEN), ANSWERED(WaitStatus.RESOLVED), CANCELLED(WaitStatus.CANCELLED) }

data class WaitsUiState(
    val account: Account? = null,
    /** The server has waits; the screen, its tab and the banner are hidden otherwise. */
    val available: Boolean = false,
    /** The member's open waits, newest first (the first page, then what "load more" added). */
    val waits: List<Wait> = emptyList(),
    /** The keyset cursor after [waits]; null when they are all there. */
    val nextBefore: String? = null,
    val loaded: Boolean = false,
    val reconnecting: Boolean = false,
    val drafts: Map<String, WaitDraft> = emptyMap(),
    /** Someone answered or withdrew a wait while the member was on it: shown once, then cleared by the next action. */
    val closedElsewhere: Boolean = false,
    val filter: WaitFilter = WaitFilter.OPEN,
    /** The waits of the answered or withdrawn [filter], read only; empty on [WaitFilter.OPEN]. */
    val history: List<Wait> = emptyList(),
    val historyNext: String? = null,
    val historyLoaded: Boolean = false,
    val loadingMore: Boolean = false,
    /** The last load of the shown list failed (offline, a busy server): what is shown stays, and a retry reloads. */
    val error: Boolean = false,
) {
    /** What the badge counts: the open waits read so far, a "+" more when pages remain. */
    val openCount: Int get() = waits.size
    val moreOpen: Boolean get() = nextBefore != null

    val shown: List<Wait> get() = if (filter == WaitFilter.OPEN) waits else history
    val shownNext: String? get() = if (filter == WaitFilter.OPEN) nextBefore else historyNext
    val shownLoaded: Boolean get() = if (filter == WaitFilter.OPEN) loaded else historyLoaded
}

/**
 * What waits for the active account's member ("Waiting for you"). Kept current by the app's one feed
 * ([FollowWaitsUseCase]); [start] and [stop] follow the app's lifecycle (the signed-in shell calls them, so the badge
 * counts while no waits screen is open). A failure to load keeps what is shown and sets [WaitsUiState.error].
 */
class WaitsViewModel(
    private val accounts: AccountRepository,
    private val loadWaits: LoadWaitsUseCase,
    private val answerWait: AnswerWaitUseCase,
    private val followWaits: FollowWaitsUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(WaitsUiState())
    val uiState: StateFlow<WaitsUiState> = state.asStateFlow()
    private var started = false
    private var follow: Job? = null
    private var paging: Job? = null

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.signedIn == new?.signedIn && old?.memberId == new?.memberId && old?.origin == new?.origin }
                .collect { account -> paging?.cancel(); paging = null; state.value = WaitsUiState(account = account); restart() }
        }
    }

    fun start() { started = true; restart() }

    fun stop() {
        started = false
        follow?.cancel(); follow = null
        state.update { it.copy(reconnecting = false) }
    }

    fun input(waitId: String, fieldId: String, value: FieldInput) = state.update { current ->
        val draft = current.drafts[waitId] ?: WaitDraft()
        current.copy(drafts = current.drafts + (waitId to draft.copy(inputs = draft.inputs + (fieldId to value), problem = null, notice = null)))
    }

    fun answer(wait: Wait, actionId: String) {
        val account = state.value.account ?: return
        val draft = state.value.drafts[wait.id] ?: WaitDraft()
        if (draft.sending) return
        setDraft(wait.id) { it.copy(sending = true, problem = null, notice = null) }
        viewModelScope.launch {
            val outcome = try {
                answerWait(account.id, wait, actionId, draft.inputs)
            } catch (error: CancellationException) {
                setDraft(wait.id) { it.copy(sending = false) }
                throw error
            }
            when (outcome) {
                is WaitAnswerOutcome.Answered -> remove(wait.id, closedElsewhere = false)
                WaitAnswerOutcome.Closed -> remove(wait.id, closedElsewhere = true)
                is WaitAnswerOutcome.Problem -> setDraft(wait.id) { it.copy(sending = false, problem = outcome.problem) }
                WaitAnswerOutcome.Refused -> setDraft(wait.id) { it.copy(sending = false, notice = WaitNotice.REFUSED) }
                WaitAnswerOutcome.Retry -> setDraft(wait.id) { it.copy(sending = false, notice = WaitNotice.RETRY) }
                is WaitAnswerOutcome.Failed -> setDraft(wait.id) { it.copy(sending = false, notice = WaitNotice.FAILED) }
            }
        }
    }

    /** Switches the list: the open waits are already live; answered and withdrawn ones are read from the first page. */
    fun setFilter(filter: WaitFilter) {
        val account = state.value.account ?: return
        if (state.value.filter == filter) return
        paging?.cancel(); paging = null
        state.update { it.copy(filter = filter, history = emptyList(), historyNext = null, historyLoaded = false, loadingMore = false, error = false) }
        if (filter != WaitFilter.OPEN) viewModelScope.launch { loadHistory(account.id, filter) }
    }

    /** The next page of the shown list, from the cursor the last page gave. */
    fun loadMore() {
        val current = state.value
        val account = current.account ?: return
        val token = current.shownNext ?: return
        if (current.loadingMore) return
        val filter = current.filter
        state.update { it.copy(loadingMore = true) }
        paging = viewModelScope.launch {
            try {
                val page = loadWaits(account.id, filter.status, token)
                state.update { now ->
                    // The list moved on meanwhile (a reload from the feed, another filter): this page no longer follows it.
                    if (now.filter != filter || now.shownNext != token) return@update now.copy(loadingMore = false)
                    if (filter == WaitFilter.OPEN) now.copy(waits = now.waits.withMore(page.waits), nextBefore = page.nextBefore, loadingMore = false, error = false)
                    else now.copy(history = now.history.withMore(page.waits), historyNext = page.nextBefore, loadingMore = false, error = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loadingMore = false, error = true) }
            }
        }
    }

    /** Loads the shown list again from its first page (the error state's button). */
    fun retry() {
        val account = state.value.account ?: return
        state.update { it.copy(error = false) }
        viewModelScope.launch { try { reload(account.id) } catch (error: CompanionException.Unauthorized) { state.update { it.copy(error = true) } } }
    }

    private fun List<Wait>.withMore(more: List<Wait>): List<Wait> {
        val seen = mapTo(HashSet()) { it.id }
        return this + more.filter { seen.add(it.id) }
    }

    fun dismissClosed() = state.update { it.copy(closedElsewhere = false) }

    private fun setDraft(waitId: String, change: (WaitDraft) -> WaitDraft) = state.update { current ->
        current.copy(drafts = current.drafts + (waitId to change(current.drafts[waitId] ?: WaitDraft())))
    }

    private fun remove(waitId: String, closedElsewhere: Boolean) = state.update { current ->
        current.copy(waits = current.waits.filterNot { it.id == waitId }, drafts = current.drafts - waitId, closedElsewhere = closedElsewhere)
    }

    private fun restart() {
        follow?.cancel(); follow = null
        val account = state.value.account
        if (!started || account == null || !account.signedIn) return
        follow = viewModelScope.launch {
            try {
                followWaits(account.id, account.memberId).collect { signal ->
                    when (signal) {
                        WaitsSignal.Reload -> reload(account.id)
                        is WaitsSignal.Connection -> state.update { it.copy(reconnecting = signal.reconnecting) }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // The feed ended (a refusal, a signed-out account): what is shown stays; the sessions list says why.
                state.update { it.copy(reconnecting = false) }
            }
        }
    }

    private suspend fun reload(accountId: String) {
        try {
            val page = loadWaits(accountId)
            state.update { current ->
                // A draft whose wait is gone is dropped; the others keep what was typed.
                val ids = page.waits.mapTo(HashSet()) { it.id }
                current.copy(available = true, waits = page.waits, nextBefore = page.nextBefore, loaded = true, drafts = current.drafts.filterKeys { it in ids },
                    error = if (current.filter == WaitFilter.OPEN) false else current.error)
            }
            state.value.filter.takeIf { it != WaitFilter.OPEN }?.let { loadHistory(accountId, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionException.Unsupported) {
            state.update { it.copy(available = false, waits = emptyList(), nextBefore = null, loaded = true, history = emptyList(), historyNext = null) }
        } catch (error: CompanionException.Unauthorized) {
            throw error
        } catch (error: Exception) {
            // Offline or a busy server: keep what is shown; the next event or reconnection loads again.
            state.update { it.copy(error = true) }
        }
    }

    /** The first page of an answered or withdrawn list; a failure keeps what is shown and marks the error. */
    private suspend fun loadHistory(accountId: String, filter: WaitFilter) {
        try {
            val page = loadWaits(accountId, filter.status)
            state.update { if (it.filter != filter) it else it.copy(history = page.waits, historyNext = page.nextBefore, historyLoaded = true, error = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionException.Unsupported) {
            state.update { it.copy(available = false) }
        } catch (error: Exception) {
            state.update { if (it.filter != filter) it else it.copy(error = true) }
        }
    }
}
