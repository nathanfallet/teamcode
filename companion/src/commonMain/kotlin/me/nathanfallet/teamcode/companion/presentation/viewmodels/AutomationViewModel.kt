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
import me.nathanfallet.teamcode.companion.domain.models.Automation
import me.nathanfallet.teamcode.companion.domain.models.AutomationRun
import me.nathanfallet.teamcode.companion.domain.models.TriggerKind
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.automations.AutomationOwned
import me.nathanfallet.teamcode.companion.domain.usecases.automations.AutomationSecrets
import me.nathanfallet.teamcode.companion.domain.usecases.automations.CancelAutomationRunUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.DeleteAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.GetAutomationUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.LoadAutomationRunsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.RotateAutomationSecretUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.SetAutomationEnabledUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.automations.WebhookLink
import me.nathanfallet.teamcode.companion.domain.usecases.automations.WebhookProof
import me.nathanfallet.teamcode.companion.domain.usecases.automations.WebhookVersion
import me.nathanfallet.teamcode.companion.domain.usecases.automations.webhookLink
import me.nathanfallet.teamcode.companion.presentation.navigation.AutomationRoute

data class AutomationUiState(
    val automation: Automation? = null,
    val loading: Boolean = true,
    val error: Failure? = null,
    /** The failure of the last action (arm, pause, rotate, cancel, delete), apart from the page's. */
    val actionError: Failure? = null,
    val busy: Boolean = false,
    val runs: List<AutomationRun> = emptyList(),
    val runsNext: String? = null,
    val runsLoading: Boolean = true,
    val runsError: Failure? = null,
    /** The history without the test runs. */
    val hideTests: Boolean = false,
    val proof: WebhookProof = WebhookProof.SIGNATURE,
    val version: WebhookVersion = WebhookVersion.LATEST,
    /** The webhook secret, held in memory from a creation or a rotation until the member stored it. */
    val secret: String? = null,
    val origin: String = "",
    val memberId: String = "",
    val administrator: Boolean = false,
    val deleted: Boolean = false,
    /** The name of the action's profile, when the member can list it (the owner's own). */
    val profileName: String? = null,
) {
    private val owner: Boolean get() = automation?.ownedBy(memberId) == true
    /** The owner arms, changes, rotates and tests: it runs with their quota, accounts and GitHub link. */
    val canEdit: Boolean get() = owner
    val canTest: Boolean get() = owner
    val canEnable: Boolean get() = owner && automation?.enabled == false
    val canPause: Boolean get() = (owner || administrator) && automation?.enabled == true
    val canRotate: Boolean get() = owner && automation?.trigger?.kind == TriggerKind.WEBHOOK
    val canDelete: Boolean get() = owner || administrator
    /** An administrator reading a member's automation: preview only. */
    val readOnly: Boolean get() = automation != null && !owner

    /** The URL the copy button copies for the chosen proof and version; null for a cron automation. */
    val link: WebhookLink? get() = automation?.webhook?.let { webhookLink(origin, it, proof, version, secret) }
}

/** One automation: its definition, its webhook URL (proof and version chosen at the copy), its history, and the actions on it. */
class AutomationViewModel(
    private val route: AutomationRoute,
    private val get: GetAutomationUseCase,
    private val setEnabled: SetAutomationEnabledUseCase,
    private val delete: DeleteAutomationUseCase,
    private val rotate: RotateAutomationSecretUseCase,
    private val loadRuns: LoadAutomationRunsUseCase,
    private val cancel: CancelAutomationRunUseCase,
    private val secrets: AutomationSecrets,
    private val profiles: LoadProfilesUseCase,
    accounts: AccountRepository,
) : ViewModel() {
    private val account = accounts.accounts.value.firstOrNull { it.id == route.accountId }
    private val state = MutableStateFlow(
        AutomationUiState(secret = secrets.secret(route.automationId), origin = account?.origin.orEmpty(), memberId = account?.memberId.orEmpty(), administrator = account?.isAdministrator == true),
    )
    val uiState: StateFlow<AutomationUiState> = state.asStateFlow()
    private var reading: Job? = null
    private var history: Job? = null

    init { reload() }

    fun reload() {
        reading?.cancel()
        state.update { it.copy(loading = true, error = null) }
        reading = viewModelScope.launch {
            try {
                val automation = get(route.accountId, route.automationId)
                state.update { it.copy(automation = automation, loading = false) }
                if (automation.ownedBy(state.value.memberId)) profileName(automation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
        reloadRuns()
    }

    /** The profile's name, read from the member's own profiles; a failure leaves the page without it. */
    private suspend fun profileName(automation: Automation) {
        try {
            val name = profiles(route.accountId).profiles.firstOrNull { it.id == automation.definition.action.profileId }?.name
            state.update { it.copy(profileName = name) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }

    fun reloadRuns() {
        history?.cancel()
        state.update { it.copy(runsLoading = true, runsError = null) }
        history = viewModelScope.launch { runsPage(before = null) }
    }

    fun loadMoreRuns() {
        val before = state.value.runsNext ?: return
        if (history?.isActive == true) return
        state.update { it.copy(runsLoading = true, runsError = null) }
        history = viewModelScope.launch { runsPage(before) }
    }

    private suspend fun runsPage(before: String?) {
        val hideTests = state.value.hideTests
        try {
            val page = loadRuns(route.accountId, route.automationId, hideTests, before)
            state.update { current ->
                if (current.hideTests != hideTests) return@update current
                val known = if (before == null) emptySet() else current.runs.map { it.id }.toSet()
                current.copy(runs = if (before == null) page.runs else current.runs + page.runs.filter { it.id !in known }, runsNext = page.nextBefore, runsLoading = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.update { it.copy(runsLoading = false, runsError = error.userMessage()) }
        }
    }

    fun setHideTests(hide: Boolean) {
        if (hide == state.value.hideTests) return
        state.update { it.copy(hideTests = hide, runs = emptyList(), runsNext = null) }
        reloadRuns()
    }

    fun setProof(proof: WebhookProof) = state.update { it.copy(proof = proof) }
    fun setVersion(version: WebhookVersion) = state.update { it.copy(version = version) }

    /** The member stored the secret: it is forgotten here, and cannot be read again. */
    fun secretStored() {
        secrets.forget(route.automationId)
        state.update { it.copy(secret = null) }
    }

    fun toggle() {
        val automation = state.value.automation ?: return
        act { setEnabled(route.accountId, AutomationOwned(automation.id, automation.ownerId), enabled = !automation.enabled).let { updated -> state.update { it.copy(automation = updated) } } }
    }

    fun rotateSecret() {
        val automation = state.value.automation ?: return
        act {
            val created = rotate(route.accountId, automation)
            created.secret?.let { secrets.hold(automation.id, it) }
            state.update { it.copy(automation = created.automation, secret = created.secret) }
        }
    }

    fun remove() = act {
        delete(route.accountId, route.automationId)
        secrets.forget(route.automationId)
        state.update { it.copy(deleted = true) }
    }

    /** Withdraws a waiting run or pauses the session of a working one; the history row takes the answer. */
    fun cancelRun(run: AutomationRun) = act {
        val updated = cancel(route.accountId, run)
        state.update { current -> current.copy(runs = current.runs.map { if (it.id == updated.id) updated else it }) }
    }

    private fun act(block: suspend () -> Unit) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                block()
                state.update { it.copy(busy = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(busy = false, actionError = error.userMessage()) }
            }
        }
    }
}
