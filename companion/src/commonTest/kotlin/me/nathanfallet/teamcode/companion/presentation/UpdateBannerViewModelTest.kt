package me.nathanfallet.teamcode.companion.presentation

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.domain.services.AppUpdateException
import me.nathanfallet.teamcode.companion.domain.services.AppUpdater
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.domain.usecases.updates.UpdatePolicy
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsUpdatePreferences
import me.nathanfallet.teamcode.companion.presentation.viewmodels.UpdateBannerState
import me.nathanfallet.teamcode.companion.presentation.viewmodels.UpdateBannerViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class FakeUpdater(var latest: String? = "1.3.0", var cli: String? = "1.2.0") : AppUpdater {
    override val appVersion = "1.2.0"
    var checks = 0
    var failure: Exception? = null
    var updateFailure: Exception? = null
    var startupError: String? = null
    var updates = 0
    var quits = 0
    var gate: CompletableDeferred<Unit>? = null
    override suspend fun cliVersion() = cli
    override suspend fun latestVersion(): String? {
        checks++
        failure?.let { throw it }
        return latest
    }
    override suspend fun update(output: (String) -> Unit) {
        updates++
        output("Updating the TeamCode CLI 1.2.0…")
        gate?.await()
        updateFailure?.let { throw it }
        output("Selected TeamCode 1.3.0.")
        cli = "1.3.0"
    }
    override fun quit() { quits++ }
    override fun takeStartupError() = startupError.also { startupError = null }
}

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateBannerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val settings = MapSettings()
    private val preferences = SettingsUpdatePreferences(settings)
    /** The test scheduler's virtual time, from a fixed epoch. */
    private val clock = Clock { 1_000_000_000L + dispatcher.scheduler.currentTime }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** Cleared at the end of each test: their checks repeat forever, which the end of runTest would otherwise follow. */
    private val store = ViewModelStore()
    private var created = 0
    private fun viewModel(updater: AppUpdater) = UpdateBannerViewModel(updater, preferences, clock).also { store.put("banner-${created++}", it) }
    private fun banner(block: suspend TestScope.() -> Unit): kotlinx.coroutines.test.TestResult = runTest(dispatcher) { try { block() } finally { store.clear() } }

    @Test fun aNewerVersionShowsAtStartup() = banner {
        val updater = FakeUpdater()
        val model = viewModel(updater)
        runCurrent()
        assertEquals(UpdateBannerState.Available("1.3.0"), model.uiState.value)
        assertEquals(1, updater.checks)
    }

    @Test fun nothingShowsWhenUpToDateOrWithoutACli() = banner {
        val model = viewModel(FakeUpdater(latest = "1.2.0"))
        runCurrent()
        assertEquals(UpdateBannerState.Hidden, model.uiState.value)
        settings.clear()
        val noCli = viewModel(FakeUpdater(cli = null))
        runCurrent()
        assertEquals(UpdateBannerState.Hidden, noCli.uiState.value)
    }

    @Test fun aFailedCheckIsSilent() = banner {
        val updater = FakeUpdater().apply { failure = AppUpdateException("release_rate_limited") }
        val model = viewModel(updater)
        runCurrent()
        assertEquals(UpdateBannerState.Hidden, model.uiState.value)
        // The attempt counts: the next one waits for the interval rather than retrying at each restart.
        assertEquals(1_000_000_000L, preferences.lastAttemptMillis)
        // Offline at startup: the next check comes an hour later, not six.
        updater.failure = null
        advanceTimeBy(UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS); runCurrent()
        assertEquals(UpdateBannerState.Available("1.3.0"), model.uiState.value)
    }

    @Test fun checksRepeatEverySixHoursAndAtMostHourlyAcrossRestarts() = banner {
        val updater = FakeUpdater(latest = "1.2.0")
        val first = viewModel(updater)
        runCurrent()
        assertEquals(1, updater.checks)
        // A restart within the hour reuses the last result: no request.
        updater.latest = "1.3.0"
        val restarted = viewModel(updater)
        runCurrent()
        assertEquals(1, updater.checks)
        assertEquals(UpdateBannerState.Hidden, restarted.uiState.value)
        // Once the hour is over, the restarted one checks; the first one waits for its six hours.
        advanceTimeBy(UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS); runCurrent()
        assertEquals(2, updater.checks)
        assertEquals(UpdateBannerState.Available("1.3.0"), restarted.uiState.value)
        assertEquals(UpdateBannerState.Hidden, first.uiState.value)
        advanceTimeBy(UpdatePolicy.CHECK_INTERVAL_MILLIS - UpdatePolicy.MIN_CHECK_INTERVAL_MILLIS); runCurrent()
        assertEquals(3, updater.checks)
        assertEquals(UpdateBannerState.Available("1.3.0"), first.uiState.value)
    }

    @Test fun aClosedVersionStaysClosedAndANewerOneShows() = banner {
        val updater = FakeUpdater()
        val model = viewModel(updater)
        runCurrent()
        model.dismiss()
        assertEquals(UpdateBannerState.Hidden, model.uiState.value)
        assertEquals("1.3.0", preferences.dismissedVersion)
        settings.remove("updates.lastAttempt")
        val restarted = viewModel(updater)
        runCurrent()
        assertEquals(UpdateBannerState.Hidden, restarted.uiState.value)
        updater.latest = "1.3.1"
        advanceTimeBy(UpdatePolicy.CHECK_INTERVAL_MILLIS); runCurrent()
        assertEquals(UpdateBannerState.Available("1.3.1"), restarted.uiState.value)
    }

    @Test fun updateShowsItsProgressThenQuits() = banner {
        val updater = FakeUpdater().apply { gate = CompletableDeferred() }
        val model = viewModel(updater)
        runCurrent()
        model.update()
        runCurrent()
        assertEquals(UpdateBannerState.Updating("1.3.0", listOf("Updating the TeamCode CLI 1.2.0…")), model.uiState.value)
        // A second click while it runs does nothing.
        model.update()
        updater.gate!!.complete(Unit)
        runCurrent()
        assertEquals(UpdateBannerState.Restarting("1.3.0"), model.uiState.value)
        assertEquals(0, updater.quits)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, updater.updates)
        assertEquals(1, updater.quits)
    }

    @Test fun aFailedUpdateSaysWhyAndCanBeTriedAgain() = banner {
        val updater = FakeUpdater().apply { updateFailure = AppUpdateException("This TeamCode CLI is not a managed installation (release_not_managed).") }
        val model = viewModel(updater)
        runCurrent()
        model.update(); runCurrent()
        val failed = assertIs<UpdateBannerState.Failed>(model.uiState.value)
        assertEquals("1.3.0", failed.version)
        assertEquals("This TeamCode CLI is not a managed installation (release_not_managed).", failed.message)
        assertEquals(listOf("Updating the TeamCode CLI 1.2.0…"), failed.output)
        assertEquals(0, updater.quits)
        // A periodic check leaves the failure on screen.
        advanceTimeBy(UpdatePolicy.CHECK_INTERVAL_MILLIS); runCurrent()
        assertIs<UpdateBannerState.Failed>(model.uiState.value)
        updater.updateFailure = null
        model.update()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(1, updater.quits)
    }

    @Test fun anUnexpectedFailureIsWordedAndClosingItClosesTheVersion() = banner {
        val updater = FakeUpdater().apply { updateFailure = IllegalStateException("secret detail") }
        val model = viewModel(updater)
        runCurrent()
        model.update(); runCurrent()
        assertEquals("The update failed unexpectedly. Run teamcode update in a terminal to see why.", assertIs<UpdateBannerState.Failed>(model.uiState.value).message)
        model.dismiss()
        assertEquals(UpdateBannerState.Hidden, model.uiState.value)
        assertEquals("1.3.0", preferences.dismissedVersion)
    }

    @Test fun aFailureTheAppWasReopenedWithShowsFirst() = banner {
        val updater = FakeUpdater(latest = "1.2.0").apply { startupError = "The Windows installer of TeamCode Desktop failed (desktop_msiexec_failed_1603)." }
        val model = viewModel(updater)
        runCurrent()
        assertEquals(UpdateBannerState.Failed(null, "The Windows installer of TeamCode Desktop failed (desktop_msiexec_failed_1603)."), model.uiState.value)
        model.update()
        assertIs<UpdateBannerState.Failed>(model.uiState.value)
        model.dismiss()
        assertEquals(UpdateBannerState.Hidden, model.uiState.value)
    }
}
