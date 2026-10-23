package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.SessionTransfer
import me.nathanfallet.teamcode.companion.domain.models.TeamMember
import me.nathanfallet.teamcode.companion.domain.models.TransferDirection
import me.nathanfallet.teamcode.companion.domain.models.TransferRequest
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.LoadRecipientsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferAction
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferRules
import me.nathanfallet.teamcode.companion.domain.usecases.transfers.TransferSessionUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.TransferRoute
import me.nathanfallet.teamcode.companion.presentation.navigation.TransfersRoute

/** A failure of a transfer screen: the server's refusal, worded by the screen from its code, or the domain's sentence. */
sealed interface TransferError {
    data class Refused(val code: String, val problems: List<String>) : TransferError
    data class Text(val text: String) : TransferError
}

internal fun Throwable.transferError(): TransferError =
    if (this is CompanionException.TransferRefused) TransferError.Refused(code, problems) else TransferError.Text(userMessage())

data class TransferUiState(
    val administrator: Boolean = false,
    val loading: Boolean = true,
    /** The team's members for an administrator; null for a member, who names the recipient by member ID. */
    val members: List<TeamMember>? = null,
    val query: String = "",
    /** The member picked in the list (administrators) or typed (members). */
    val recipientId: String = "",
    val resume: Boolean = false,
    val force: Boolean = false,
    val sending: Boolean = false,
    val error: TransferError? = null,
    /** The transfer the server made: `accepted` for an administrator, `offered` for a member. */
    val done: SessionTransfer? = null,
) {
    /** The members matching the search, by username or ID. */
    val matches: List<TeamMember> get() {
        val list = members ?: return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return list
        return list.filter { it.username.contains(q, ignoreCase = true) || it.id.startsWith(q, ignoreCase = true) }
    }

    val recipient: TeamMember? get() = members?.firstOrNull { it.id == recipientId }

    val canSend: Boolean get() = !sending && !loading && done == null && TransferRules.validId(recipientId.trim()) && (members == null || recipient?.available == true)
}

/**
 * The transfer form of one session. Its idempotency key is drawn once and kept while what is sent stays the same, so a
 * retry after a network failure replays the first transfer; another recipient or option draws a new one.
 */
class TransferViewModel(
    private val route: TransferRoute,
    private val loadRecipients: LoadRecipientsUseCase,
    private val transferSession: TransferSessionUseCase,
    private val accounts: AccountRepository,
    private val ids: IdGenerator,
) : ViewModel() {
    private val state = MutableStateFlow(TransferUiState(administrator = account()?.isAdministrator == true))
    val uiState: StateFlow<TransferUiState> = state.asStateFlow()
    private var key = ids.newId()

    init { reload() }

    private fun account() = accounts.accounts.value.firstOrNull { it.id == route.accountId }

    fun reload() {
        if (state.value.sending) return
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val members = loadRecipients(route.accountId, route.ownerId)
                state.update { it.copy(members = members, loading = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.transferError()) }
            }
        }
    }

    fun onQuery(value: String) = state.update { it.copy(query = value) }
    fun onRecipient(id: String) = edit { it.copy(recipientId = id) }
    fun onResume(value: Boolean) = edit { it.copy(resume = value) }
    fun onForce(value: Boolean) = edit { it.copy(force = value) }

    private fun edit(change: (TransferUiState) -> TransferUiState) {
        val before = state.value
        if (before.sending || before.done != null) return
        val after = change(before).copy(error = null)
        if (after.request() != before.request()) key = ids.newId()
        state.value = after
    }

    fun send() {
        val current = state.value
        if (!current.canSend) return
        state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                val transfer = transferSession(route.accountId, route.sessionId, route.ownerId, current.request(), key)
                state.update { it.copy(sending = false, done = transfer) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(sending = false, error = error.transferError()) }
            }
        }
    }

    /** As sent: a member's offer never carries the administrator's options, so toggling them keeps the key. */
    private fun TransferUiState.request(): TransferRequest {
        val request = TransferRequest(recipientId, resume, force)
        val account = account() ?: return request
        return TransferSessionUseCase.sent(account, request)
    }
}

data class TransferListState(
    val transfers: List<SessionTransfer> = emptyList(),
    val nextBefore: String? = null,
    val loaded: Boolean = false,
)

data class TransfersUiState(
    val direction: TransferDirection = TransferDirection.INCOMING,
    val lists: Map<TransferDirection, TransferListState> = emptyMap(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    /** Member names by ID (administrators); a member sees IDs. */
    val names: Map<String, String> = emptyMap(),
    val memberId: String = "",
    /** Transfers with a decision in flight. */
    val acting: Set<String> = emptySet(),
    val error: TransferError? = null,
) {
    val list: TransferListState get() = lists[direction] ?: TransferListState()
}

/** The member's incoming and outgoing transfers, and accepting, declining or withdrawing them. */
class TransfersViewModel(
    private val route: TransfersRoute,
    private val transfers: TransferListUseCase,
    private val accounts: AccountRepository,
) : ViewModel() {
    private val state = MutableStateFlow(TransfersUiState(memberId = account()?.memberId.orEmpty()))
    val uiState: StateFlow<TransfersUiState> = state.asStateFlow()
    private var namesLoaded = false

    init { refresh() }

    private fun account() = accounts.accounts.value.firstOrNull { it.id == route.accountId }

    fun setDirection(direction: TransferDirection) {
        state.update { it.copy(direction = direction, error = null) }
        if (state.value.lists[direction]?.loaded != true) refresh()
    }

    /** The newest page of the shown direction again: a decision made elsewhere shows. */
    fun refresh() = load(clearError = true)

    private fun load(clearError: Boolean) {
        val direction = state.value.direction
        state.update { it.copy(loading = true, error = if (clearError) null else it.error) }
        viewModelScope.launch {
            try {
                if (!namesLoaded) {
                    // Names are a comfort: the list shows IDs without them.
                    try { val names = transfers.names(route.accountId); namesLoaded = true; state.update { it.copy(names = names) } } catch (error: CancellationException) { throw error } catch (_: Exception) { }
                }
                val page = transfers.page(route.accountId, direction)
                state.update { it.copy(loading = false, lists = it.lists + (direction to TransferListState(page.transfers, page.nextBefore, loaded = true))) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.transferError()) }
            }
        }
    }

    fun loadMore() {
        val current = state.value
        val direction = current.direction
        val before = current.list.nextBefore ?: return
        if (current.loading || current.loadingMore) return
        state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val page = transfers.page(route.accountId, direction, before)
                state.update { s ->
                    val list = s.lists[direction] ?: TransferListState()
                    // A refresh meanwhile replaced the list: this page belongs to the old one.
                    if (list.nextBefore != before) return@update s.copy(loadingMore = false)
                    val known = list.transfers.map { it.id }.toSet()
                    s.copy(loadingMore = false, lists = s.lists + (direction to list.copy(transfers = list.transfers + page.transfers.filterNot { it.id in known }, nextBefore = page.nextBefore)))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loadingMore = false, error = error.transferError()) }
            }
        }
    }

    /** What the member may do with [transfer]. */
    fun actions(transfer: SessionTransfer): Set<TransferAction> = account()?.let { TransferRules.actions(it, transfer) } ?: emptySet()

    fun accept(transfer: SessionTransfer) = decide(transfer, TransferAction.ACCEPT) { transfers.accept(route.accountId, transfer.id) }
    fun decline(transfer: SessionTransfer) = decide(transfer, TransferAction.DECLINE) { transfers.decline(route.accountId, transfer.id) }
    fun cancel(transfer: SessionTransfer) = decide(transfer, TransferAction.CANCEL) { transfers.cancel(route.accountId, transfer.id) }

    private fun decide(transfer: SessionTransfer, action: TransferAction, call: suspend () -> SessionTransfer) {
        if (transfer.id in state.value.acting || action !in actions(transfer)) return
        state.update { it.copy(acting = it.acting + transfer.id, error = null) }
        viewModelScope.launch {
            try {
                val updated = call()
                state.update { s -> s.copy(lists = s.lists.mapValues { (_, list) -> list.copy(transfers = list.transfers.map { if (it.id == updated.id) updated else it }) }) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(error = error.transferError()) }
                // Refused because it changed meanwhile (decided, expired): show what it is now, under the refusal.
                if (error is CompanionException.TransferRefused) load(clearError = false)
            } finally {
                state.update { it.copy(acting = it.acting - transfer.id) }
            }
        }
    }
}
