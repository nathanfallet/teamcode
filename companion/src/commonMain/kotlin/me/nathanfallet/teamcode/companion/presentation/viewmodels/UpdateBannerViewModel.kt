package me.nathanfallet.teamcode.companion.presentation.viewmodels

import me.nathanfallet.teamcode.companion.resources.*
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
import me.nathanfallet.teamcode.companion.domain.services.UpdateOutcome
import me.nathanfallet.teamcode.companion.domain.usecases.updates.UpdatePolicy

/** What the update banner shows. */
sealed interface UpdateBannerState {
    data object Hidden : UpdateBannerState
    /** [version] is available for the CLI and the app. */
    data class Available(val version: String) : UpdateBannerState
    /** The update runs; [output] holds the CLI's last lines, [progress] the share of the app's download once it started. */
    data class Updating(val version: String, val output: List<String> = emptyList(), val progress: Float? = null) : UpdateBannerState
    /** The new app is ready: this one quits now and the new one opens ([installsAfterQuit]: once Windows' installer has put it in place). */
    data class Restarting(val version: String, val installsAfterQuit: Boolean = false) : UpdateBannerState
    /** The update failed; [version] is offered again by "Try again" (null after a failure reported at startup). */
    /** [message] is the updater's own explanation; null when the failure was not one it explained (the banner words it). */
    data class Failed(val version: String?, val message: String?, val output: List<String> = emptyList()) : UpdateBannerState
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
            while (true) delay(refresh())
        }
    }

    /**
     * One check: the network one when due, the last result otherwise (within the hour of the last attempt: another
     * window, a restart); silent on failure. Returns how long to wait before the next one: [UpdatePolicy.CHECK_INTERVAL_MILLIS]
     * after a check that worked, an hour after one that failed (offline at startup), the rest of the hour otherwise.
     */
    internal suspend fun refresh(): Long {
        val now = clock.nowMillis()
        val wait = UpdatePolicy.nextCheckDelay(now, preferences.lastAttemptMillis)
        var next = wait
        val latest = if (wait > 0) preferences.latestVersion else {
            preferences.lastAttemptMillis = now
            try {
                updater.latestVersion().also { preferences.latestVersion = it; next = UpdatePolicy.CHECK_INTERVAL_MILLIS }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                next = UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS
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
        return next
    }

    /** Updates the CLI and gets the new app ready, then quits so that it opens; stays open when only the CLI was behind. */
    fun update() {
        val version = when (val current = state.value) {
            is UpdateBannerState.Available -> current.version
            is UpdateBannerState.Failed -> current.version ?: return
            else -> return
        }
        state.value = UpdateBannerState.Updating(version)
        viewModelScope.launch {
            try {
                val outcome = updater.update(
                    output = { line -> state.update { if (it is UpdateBannerState.Updating) it.copy(output = (it.output + line).takeLast(MAX_OUTPUT_LINES)) else it } },
                    progress = { share -> state.update { if (it is UpdateBannerState.Updating) it.copy(progress = share.coerceIn(0f, 1f)) else it } },
                )
                if (outcome == UpdateOutcome.CURRENT) { state.value = UpdateBannerState.Hidden; return@launch }
                state.value = UpdateBannerState.Restarting(version, updater.installsAfterQuit)
                // Just long enough to read that it worked: the new app is ready and opens as soon as this one quits.
                delay(RESTART_DELAY_MILLIS)
                updater.quit()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val output = (state.value as? UpdateBannerState.Updating)?.output.orEmpty()
                val message = (error as? AppUpdateException)?.message
                state.value = UpdateBannerState.Failed(version, message, output)
            }
        }
    }

    /** "Not now", or closing a failure: the banner of this version does not show again; a newer version does. */
    fun dismiss() {
        when (val current = state.value) {
            is UpdateBannerState.Available -> preferences.dismissedVersion = current.version
            is UpdateBannerState.Failed -> current.version?.let { preferences.dismissedVersion = it }
            else -> return
        }
        state.value = UpdateBannerState.Hidden
    }

    private companion object {
        const val MAX_OUTPUT_LINES = 6
        const val RESTART_DELAY_MILLIS = 600L
    }
}
