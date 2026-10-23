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
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.ArtifactVersion
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.GetArtifactUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.OpenArtifactUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.OpenedArtifact
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactRoute

data class ArtifactUiState(
    val artifact: Artifact? = null,
    val versions: List<ArtifactVersion> = emptyList(),
    /** The version shown, or asked for while its ticket comes. */
    val version: Int? = null,
    val opened: OpenedArtifact? = null,
    val loading: Boolean = true,
    /** A version newer than the newest listed was published since the screen opened; shown only on request. */
    val newerVersion: Int? = null,
    val gone: Boolean = false,
    val error: String? = null,
)

/**
 * One artifact: its versions, and a ticket for the version shown when the platform embeds the content ([embedded]).
 * While visible ([start], [stop]), the artifact is read again every 30 s to offer a newer version, never to reload it
 * by itself. A page the server no longer serves (an expired ticket) gets one new ticket in silence.
 */
class ArtifactViewModel(
    private val route: ArtifactRoute,
    private val getArtifact: GetArtifactUseCase,
    private val openArtifact: OpenArtifactUseCase,
    private val embedded: Boolean,
) : ViewModel() {
    private val state = MutableStateFlow(ArtifactUiState(version = route.version))
    val uiState: StateFlow<ArtifactUiState> = state.asStateFlow()
    private var ticket: Job? = null
    private var watch: Job? = null
    /** The shown version already had its silent renewal. */
    private var renewed = false

    init { load() }

    fun load() {
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val (artifact, versions) = getArtifact(route.accountId, route.artifactId)
                val version = state.value.version ?: artifact.currentVersion
                if (versions.isNotEmpty() && versions.none { it.number == version }) throw CompanionException.NotFound()
                state.update { it.copy(artifact = artifact, versions = versions, version = version, loading = embedded && it.opened?.ticket?.version != version) }
                if (embedded && state.value.opened?.ticket?.version != version) open(version)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, gone = error is CompanionException.NotFound, error = error.userMessage()) }
            }
        }
    }

    fun start() {
        if (watch?.isActive == true) return
        watch = viewModelScope.launch {
            while (true) {
                delay(WATCH_MILLIS)
                val current = state.value
                if (current.gone || current.artifact == null) continue
                try {
                    val latest = getArtifact.current(route.accountId, route.artifactId)
                    val known = current.versions.maxOfOrNull { it.number } ?: current.artifact.currentVersion
                    state.update { it.copy(newerVersion = latest.currentVersion.takeIf { n -> n > known }) }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: CompanionException.NotFound) {
                    state.update { it.copy(gone = true, opened = null, error = error.userMessage()) }
                } catch (_: Exception) {
                    // Offline for a moment: the next round asks again.
                }
            }
        }
    }

    fun stop() { watch?.cancel(); watch = null }

    /** Shows [number], with a ticket fixed on it. */
    fun selectVersion(number: Int) {
        if (number == state.value.version && state.value.opened != null) return
        renewed = false
        state.update { it.copy(version = number, loading = embedded, error = null) }
        if (embedded) open(number)
    }

    /** The newer version: the list read again, then shown. */
    fun showNewer() {
        val newer = state.value.newerVersion ?: return
        state.update { it.copy(version = newer, newerVersion = null) }
        renewed = false
        load()
    }

    /** The page answered `404`: once per version, a new ticket (it expired); after that, the version is gone. */
    fun contentMissing() {
        val version = state.value.version ?: return
        if (renewed) {
            state.update { it.copy(opened = null, error = CompanionException.NotFound().userMessage()) }
            return
        }
        renewed = true
        open(version)
    }

    private fun open(version: Int) {
        ticket?.cancel()
        ticket = viewModelScope.launch {
            try {
                val opened = openArtifact(route.accountId, route.artifactId, version)
                state.update { if (it.version == version) it.copy(opened = opened, loading = false) else it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, opened = null, gone = error is CompanionException.NotFound, error = error.userMessage()) }
            }
        }
    }

    private companion object {
        const val WATCH_MILLIS = 30_000L
    }
}
