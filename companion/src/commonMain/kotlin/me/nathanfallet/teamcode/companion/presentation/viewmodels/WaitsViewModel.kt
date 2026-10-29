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

data class WaitsUiState(
    val account: Account? = null,
    /** The server has waits; the section is hidden otherwise. */
    val available: Boolean = false,
    val waits: List<Wait> = emptyList(),
    val loaded: Boolean = false,
    val reconnecting: Boolean = false,
    val drafts: Map<String, WaitDraft> = emptyMap(),
    /** Someone answered or withdrew a wait while the member was on it: shown once, then cleared by the next action. */
    val closedElsewhere: Boolean = false,
)

/**
 * What waits for the active account's member ("Waiting for you"). Kept current by the app's one feed
 * ([FollowWaitsUseCase]); [start] and [stop] follow the screen's lifecycle like the sessions list. A failure to load
 * keeps what is shown: the section is a shortcut, the sessions list carries the error banner.
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

    init {
        viewModelScope.launch {
            combine(accounts.accounts, accounts.activeAccountId) { list, id -> list.firstOrNull { it.id == id } }
                .distinctUntilChanged { old, new -> old?.id == new?.id && old?.signedIn == new?.signedIn && old?.memberId == new?.memberId && old?.origin == new?.origin }
                .collect { account -> state.value = WaitsUiState(account = account); restart() }
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
                current.copy(available = true, waits = page.waits, loaded = true, drafts = current.drafts.filterKeys { it in ids })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: CompanionException.Unsupported) {
            state.update { it.copy(available = false, waits = emptyList(), loaded = true) }
        } catch (error: CompanionException.Unauthorized) {
            throw error
        } catch (error: Exception) {
            // Offline or a busy server: keep what is shown; the next event or reconnection loads again.
        }
    }
}
