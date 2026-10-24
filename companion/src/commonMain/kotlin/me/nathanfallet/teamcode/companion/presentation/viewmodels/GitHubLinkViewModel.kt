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
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.GitHubDeviceLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubLink
import me.nathanfallet.teamcode.companion.domain.models.GitHubLinkState
import me.nathanfallet.teamcode.companion.domain.models.GitHubPoll
import me.nathanfallet.teamcode.companion.domain.usecases.github.LoadGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.PollGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.StartGitHubLinkUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.github.UnlinkGitHubUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.GitHubLinkRoute

/** Why a device flow ended without a link. */
enum class GitHubLinkEnd { EXPIRED, DENIED }

/**
 * A device flow in progress: the member enters [code] at [verificationUri]. The screen copies the code and opens the
 * page once ([opened]); the view model polls the server until GitHub answers.
 */
data class GitHubDeviceStep(val code: String, val verificationUri: String, val opened: Boolean = false)

data class GitHubLinkUiState(
    val loading: Boolean = true,
    /** Null until the server answered, and when it does not offer GitHub links ([offered] false). */
    val link: GitHubLink? = null,
    val offered: Boolean = true,
    val starting: Boolean = false,
    val step: GitHubDeviceStep? = null,
    val unlinking: Boolean = false,
    val error: Failure? = null,
    /** How the last device flow ended without a link. */
    val ended: GitHubLinkEnd? = null,
    /** The login just linked, for the confirmation. */
    val justLinked: String? = null,
) {
    val canLink: Boolean get() = offered && link?.appConfigured == true && step == null && !starting && !unlinking
    val canUnlink: Boolean get() = link != null && link.state != GitHubLinkState.NOT_LINKED && step == null && !starting && !unlinking
}

/**
 * The member's GitHub link on one TeamCode account's server, linked as `teamcode github link` does: the server starts
 * GitHub's device flow, the app copies the code, opens GitHub's page and polls at the interval GitHub asked for. The
 * token exchange stays on the server; nothing secret is held here.
 */
class GitHubLinkViewModel(
    private val route: GitHubLinkRoute,
    private val load: LoadGitHubLinkUseCase,
    private val start: StartGitHubLinkUseCase,
    private val poll: PollGitHubLinkUseCase,
    private val unlink: UnlinkGitHubUseCase,
) : ViewModel() {
    private val state = MutableStateFlow(GitHubLinkUiState())
    val uiState: StateFlow<GitHubLinkUiState> = state.asStateFlow()
    private var polling: Job? = null

    init { refresh() }

    fun refresh() = reload(clearError = true)

    /** Reads the link again; after a refusal, [clearError] false keeps its sentence shown. */
    private fun reload(clearError: Boolean) {
        state.update { it.copy(loading = true, error = if (clearError) null else it.error) }
        viewModelScope.launch {
            try {
                val link = load(route.accountId)
                state.update { it.copy(loading = false, link = link, offered = link != null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, error = error.userMessage()) }
            }
        }
    }

    /** Starts a device flow (also "Start again" after it ended) and polls until GitHub answers. */
    fun startLinking() {
        if (!state.value.canLink) return
        polling?.cancel()
        state.update { it.copy(starting = true, error = null, ended = null, justLinked = null) }
        polling = viewModelScope.launch {
            try {
                val device = start(route.accountId)
                state.update { it.copy(starting = false, step = GitHubDeviceStep(device.userCode, device.verificationUri)) }
                follow(device)
            } catch (error: CancellationException) {
                state.update { it.copy(starting = false) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(starting = false, step = null, error = error.userMessage()) }
                // A refusal of the server (App removed, device flow turned off) is read again.
                if (error is CompanionException.GitHubRefused) reload(clearError = false)
            }
        }
    }

    /** The screen copied the code and opened GitHub: neither is done again by itself. */
    fun browserOpened() = state.update { current -> current.copy(step = current.step?.copy(opened = true)) }

    fun cancelLinking() {
        polling?.cancel()
        state.update { it.copy(step = null, starting = false) }
    }

    fun dismissLinked() = state.update { it.copy(justLinked = null) }

    fun unlinkAccount() {
        if (!state.value.canUnlink) return
        state.update { it.copy(unlinking = true, error = null, justLinked = null) }
        viewModelScope.launch {
            try {
                unlink(route.accountId)
                state.update { it.copy(unlinking = false) }
                reload(clearError = true)
            } catch (error: CancellationException) {
                state.update { it.copy(unlinking = false) }
                throw error
            } catch (error: Exception) {
                state.update { it.copy(unlinking = false, error = error.userMessage()) }
            }
        }
    }

    /** Polls every interval (never faster than GitHub asked); a few network failures in a row are waited out. */
    private suspend fun follow(device: GitHubDeviceLink) {
        var interval = device.interval
        var failures = 0
        while (true) {
            delay(interval.coerceIn(MIN_INTERVAL, MAX_INTERVAL) * 1000L)
            val answer = try {
                poll(route.accountId, device.id).also { failures = 0 }
            } catch (error: CancellationException) {
                throw error
            } catch (error: CompanionException.Unreachable) {
                if (++failures >= MAX_FAILURES) {
                    state.update { it.copy(step = null, error = error.userMessage()) }
                    return
                }
                continue
            } catch (error: Exception) {
                state.update { it.copy(step = null, error = error.userMessage()) }
                return
            }
            when (answer) {
                is GitHubPoll.Pending -> interval = answer.interval ?: interval
                is GitHubPoll.Linked -> {
                    state.update { it.copy(step = null, link = answer.link, justLinked = answer.link.login) }
                    return
                }
                GitHubPoll.Expired -> { state.update { it.copy(step = null, ended = GitHubLinkEnd.EXPIRED) }; return }
                GitHubPoll.Denied -> { state.update { it.copy(step = null, ended = GitHubLinkEnd.DENIED) }; return }
            }
        }
    }

    private companion object {
        const val MIN_INTERVAL = 1
        const val MAX_INTERVAL = 60
        const val MAX_FAILURES = 3
    }
}
