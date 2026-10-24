package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.AddedSubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.EnrollmentAccess
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccount
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionAccountType
import me.nathanfallet.teamcode.companion.domain.models.SubscriptionSignIn
import me.nathanfallet.teamcode.companion.domain.usecases.subscriptions.CompleteSubscriptionSignInUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.subscriptions.LoadSubscriptionAccountsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.subscriptions.StartSubscriptionSignInUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.SubscriptionAccountsRoute

/** Where the "Add a subscription account" flow is. */
sealed interface AddAccountStep {
    /** The kinds of account, the unavailable ones shown disabled. */
    data object ChooseType : AddAccountStep

    /**
     * The browser sign-in: [signIn]'s link is opened once ([opened]), then the member pastes the code. [spent] is true
     * after a failure that used this attempt up: only "Start again" (a new attempt) is offered.
     */
    data class SignIn(
        val signIn: SubscriptionSignIn,
        val paste: String = "",
        val opened: Boolean = false,
        val sending: Boolean = false,
        val error: Failure? = null,
        val spent: Boolean = false,
    ) : AddAccountStep {
        val canSubmit: Boolean get() = !sending && !spent && paste.isNotBlank()
    }
}

data class SubscriptionAccountsUiState(
    val loading: Boolean = true,
    /** Null until the server answered. */
    val access: EnrollmentAccess? = null,
    /** The pool for an administrator; null for a member, whom the server does not show it. */
    val accounts: List<SubscriptionAccount>? = null,
    val types: List<SubscriptionAccountType> = emptyList(),
    val step: AddAccountStep? = null,
    /** A sign-in being started (the enrollment check asked again). */
    val starting: Boolean = false,
    val error: Failure? = null,
    /** The last account added or reconnected, for the confirmation. */
    val added: AddedSubscriptionAccount? = null,
) {
    val canAdd: Boolean get() = access == EnrollmentAccess.ALLOWED && step == null && !starting
}

/**
 * The subscription accounts of one TeamCode account's server, and adding one as `teamcode accounts add` does: the
 * enrollment check, a kind of account, the provider's page in the system browser, the pasted code sent to the server.
 * The pasted code is kept only in this state while the step is shown, and never in a message.
 */
class SubscriptionAccountsViewModel(
    private val route: SubscriptionAccountsRoute,
    private val load: LoadSubscriptionAccountsUseCase,
    private val start: StartSubscriptionSignInUseCase,
    private val complete: CompleteSubscriptionSignInUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(SubscriptionAccountsUiState(types = load.types))
    val uiState: StateFlow<SubscriptionAccountsUiState> = state.asStateFlow()

    init { refresh() }

    fun refresh() = reload(clearError = true)

    /** Reads the enrollment check and the pool again; after a refusal, [clearError] false keeps its sentence shown. */
    private fun reload(clearError: Boolean) {
        state.update { it.copy(loading = true, error = if (clearError) null else it.error) }
        viewModelScope.launch {
            try {
                val overview = load(route.accountId)
                state.update { it.copy(loading = false, access = overview.access, accounts = overview.accounts) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    fun startAdding() {
        if (!state.value.canAdd) return
        state.update { it.copy(step = AddAccountStep.ChooseType, error = null, added = null) }
    }

    fun cancelAdding() = state.update { it.copy(step = null, starting = false) }

    fun dismissAdded() = state.update { it.copy(added = null) }

    /** Starts a new sign-in attempt of [type]; also "Start again" after a failure. */
    fun choose(type: SubscriptionAccountType) {
        val current = state.value
        if (!type.available || current.starting || current.step == null) return
        state.update { it.copy(starting = true, error = null) }
        viewModelScope.launch {
            try {
                val signIn = start(route.accountId, type)
                state.update { if (it.step == null) it.copy(starting = false) else it.copy(starting = false, step = AddAccountStep.SignIn(signIn)) }
            } catch (error: CancellationException) {
                state.update { it.copy(starting = false) }
                throw error
            } catch (error: Exception) {
                // A refusal of the server (the setting changed since the screen loaded) ends the flow and reads it again.
                val refused = error is CompanionException.EnrollmentRefused
                state.update { it.copy(starting = false, step = if (refused || it.step == null) null else AddAccountStep.ChooseType, error = error.userMessage()) }
                if (refused) reload(clearError = false)
            }
        }
    }

    /** The screen opened the link in the browser: it is not opened again by itself. */
    fun browserOpened() = updateSignIn { it.copy(opened = true) }

    fun onPaste(value: String) = updateSignIn { if (it.sending) it else it.copy(paste = value, error = null) }

    fun submit() {
        val step = state.value.step as? AddAccountStep.SignIn ?: return
        if (!step.canSubmit) return
        updateSignIn { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                val added = complete(route.accountId, step.signIn, step.paste)
                state.update { it.copy(step = null, added = added) }
                reload(clearError = true)
            } catch (error: CancellationException) {
                updateSignIn { it.copy(sending = false) }
                throw error
            } catch (error: Exception) {
                // Only a paste that is not the code can be corrected; anything else used this attempt up.
                val spent = !(error is CompanionException.ProviderSignIn && !error.restart)
                val message = error.userMessage()
                updateSignIn { it.copy(sending = false, error = message, spent = spent, paste = if (spent) "" else it.paste) }
            }
        }
    }

    private fun updateSignIn(change: (AddAccountStep.SignIn) -> AddAccountStep.SignIn) = state.update { current ->
        val step = current.step as? AddAccountStep.SignIn ?: return@update current
        current.copy(step = change(step))
    }
}
