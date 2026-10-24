package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.repositories.UpdatePreferences
import me.nathanfallet.teamcode.companion.domain.services.AppUpdateException
import me.nathanfallet.teamcode.companion.domain.services.AppUpdater
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.usecases.updates.UpdatePolicy

/** What the update banner shows. */
sealed interface UpdateBannerState {
    data object Hidden : UpdateBannerState
    /** [version] is available for the CLI and the app. */
    data class Available(val version: String) : UpdateBannerState
    /** The update runs; [output] holds the CLI's last lines. */
    data class Updating(val version: String, val output: List<String> = emptyList()) : UpdateBannerState
    /** The update is installed: the app quits and the new version opens. */
    data class Restarting(val version: String) : UpdateBannerState
    /** The update failed; [version] is offered again by "Try again" (null after a failure reported at startup). */
    data class Failed(val version: String?, val message: String, val output: List<String> = emptyList()) : UpdateBannerState
}

/**
 * The update banner: a check at startup, then every [UpdatePolicy.CHECK_INTERVAL_MILLIS], never more than once per
 * [UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS] across restarts (the last result is kept meanwhile). A failed check is
 * silent. A version the member closed does not show again; a newer one does.
 */
class UpdateBannerViewModel(
    private val updater: AppUpdater,
    private val preferences: UpdatePreferences,
    private val clock: Clock,
) : ViewModel() {
    private val state = MutableStateFlow<UpdateBannerState>(
        updater.takeStartupError()?.let { UpdateBannerState.Failed(null, it) } ?: UpdateBannerState.Hidden,
    )
    val uiState: StateFlow<UpdateBannerState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                // Within the hour of the last attempt (another window, a restart) the last result is shown again.
                refresh()
                delay(UpdatePolicy.CHECK_INTERVAL_MILLIS)
            }
        }
    }

    /** One check: the network one when due, the last result otherwise; silent on failure. */
    internal suspend fun refresh() {
        val now = clock.nowMillis()
        val latest = if (UpdatePolicy.nextCheckDelay(now, preferences.lastAttemptMillis) > 0) preferences.latestVersion else {
            preferences.lastAttemptMillis = now
            try {
                updater.latestVersion().also { preferences.latestVersion = it }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                preferences.latestVersion
            }
        }
        val cli = try { updater.cliVersion() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        val target = UpdatePolicy.target(updater.appVersion, cli, latest)?.takeIf { it != preferences.dismissedVersion }
        state.update { current ->
            when (current) {
                // An update in progress, or its failure, stays on screen until the member acts on it.
                is UpdateBannerState.Updating, is UpdateBannerState.Restarting, is UpdateBannerState.Failed -> current
                else -> target?.let { UpdateBannerState.Available(it) } ?: UpdateBannerState.Hidden
            }
        }
    }

    /** Updates the CLI and the app, then quits so that the new version opens. */
    fun update() {
        val version = when (val current = state.value) {
            is UpdateBannerState.Available -> current.version
            is UpdateBannerState.Failed -> current.version ?: return
            else -> return
        }
        state.value = UpdateBannerState.Updating(version)
        viewModelScope.launch {
            try {
                updater.update { line ->
                    state.update { if (it is UpdateBannerState.Updating) it.copy(output = (it.output + line).takeLast(MAX_OUTPUT_LINES)) else it }
                }
                state.value = UpdateBannerState.Restarting(version)
                // A moment to read that it worked, then the app quits and the new version opens.
                delay(RESTART_DELAY_MILLIS)
                updater.quit()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val output = (state.value as? UpdateBannerState.Updating)?.output.orEmpty()
                val message = (error as? AppUpdateException)?.message ?: "The update failed unexpectedly. Run teamcode update in a terminal to see why."
                state.value = UpdateBannerState.Failed(version, message, output)
            }
        }
    }

    /** "Not now": the banner of this version does not show again. A failure is only closed: the next check offers it again. */
    fun dismiss() {
        when (val current = state.value) {
            is UpdateBannerState.Available -> preferences.dismissedVersion = current.version
            is UpdateBannerState.Failed -> Unit
            else -> return
        }
        state.value = UpdateBannerState.Hidden
    }

    private companion object {
        const val MAX_OUTPUT_LINES = 6
        const val RESTART_DELAY_MILLIS = 1_500L
    }
}
