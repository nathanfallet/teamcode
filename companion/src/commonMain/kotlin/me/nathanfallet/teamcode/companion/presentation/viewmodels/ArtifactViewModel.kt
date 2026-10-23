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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class ArtifactUiState(
    val artifact: Artifact? = null,
    /** The viewer page on the account's server: copied and opened in the browser. */
    val viewerUrl: String? = null,
    val versions: List<ArtifactVersion> = emptyList(),
    /** The version shown, or asked for while its ticket comes. */
    val version: Int? = null,
    val opened: OpenedArtifact? = null,
    /** Grows with each ticket, so the content view loads again even when a renewed ticket has the same URL. */
    val reload: Int = 0,
    val loading: Boolean = true,
    /** A version newer than the newest listed was published since the screen opened; shown only on request. */
    val newerVersion: Int? = null,
    val gone: Boolean = false,
    /** A link inside the content led to a page the version does not have (a `404` on a ticket still valid). */
    val missingPage: Boolean = false,
    val error: String? = null,
)

/**
 * One artifact: its versions, and a ticket for the version shown when the platform embeds the content ([embedded]).
 * While visible ([start], [stop]), the artifact is read again every 30 s to offer a newer version, never to reload it
 * by itself. An expired ticket is renewed in silence.
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
    /** When the last silent reload of a crashed page happened: at most one a minute, so a failing page never loops. */
    private var renewedAt: TimeMark? = null

    init { load() }

    fun load() {
        renewedAt = null
        state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val (artifact, versions, viewerUrl) = getArtifact(route.accountId, route.artifactId)
                val version = state.value.version ?: artifact.currentVersion
                if (versions.isNotEmpty() && versions.none { it.number == version }) throw CompanionException.NotFound()
                state.update { it.copy(artifact = artifact, viewerUrl = viewerUrl, versions = versions, version = version, loading = embedded && it.opened?.ticket?.version != version) }
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
        renewedAt = null
        state.update { it.copy(version = number, loading = embedded, error = null) }
        if (embedded) open(number)
    }

    /** The newer version: the list read again, then shown. */
    fun showNewer() {
        val newer = state.value.newerVersion ?: return
        state.update { it.copy(version = newer, newerVersion = null) }
        renewedAt = null
        load()
    }

    /**
     * The page answered `404`: a new ticket is asked for. A different one means the old one expired, and it loads; the
     * same one (the server rounds its expiry, so a ticket still valid comes back unchanged) means a link led to a file
     * the version lacks: the screen says so and offers the entry page again ([restart]). No device clock involved.
     */
    fun contentMissing() {
        val version = state.value.version ?: return
        val shown = state.value.opened?.ticket?.contentUrl ?: return
        open(version, missingIfSame = shown)
    }

    /** The page's renderer or content process died: loaded again once, in a new web view; a second time, an error. */
    fun contentCrashed() {
        if (state.value.opened == null) return
        if (recentlyRenewed()) {
            state.update { it.copy(opened = null, error = "The page stopped working.") }
            return
        }
        state.update { it.copy(reload = it.reload + 1) }
    }

    /** Back to the version's entry page. */
    fun restart() = state.update { it.copy(missingPage = false, reload = it.reload + 1) }

    /** True when a silent reload already happened within a minute (so a failing page never loops); marks this one otherwise. */
    private fun recentlyRenewed(): Boolean {
        if (renewedAt?.let { it.elapsedNow() < RENEWAL_WINDOW } == true) return true
        renewedAt = TimeSource.Monotonic.markNow()
        return false
    }

    private fun open(version: Int, missingIfSame: String? = null) {
        ticket?.cancel()
        ticket = viewModelScope.launch {
            try {
                val opened = openArtifact(route.accountId, route.artifactId, version)
                state.update {
                    when {
                        it.version != version -> it
                        opened.ticket.contentUrl == missingIfSame -> it.copy(missingPage = true)
                        else -> it.copy(opened = opened, reload = it.reload + 1, missingPage = false, loading = false)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loading = false, opened = null, gone = error is CompanionException.NotFound, error = error.userMessage()) }
            }
        }
    }

    private companion object {
        const val WATCH_MILLIS = 30_000L
        val RENEWAL_WINDOW = 1.minutes
    }
}
