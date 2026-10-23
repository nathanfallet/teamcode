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
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.LoadArtifactsUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.ArtifactsRoute

data class ArtifactsUiState(
    val scope: ArtifactScope = ArtifactScope.TEAM,
    val artifacts: List<Artifact> = emptyList(),
    val nextBefore: String? = null,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val query: String = "",
    val error: String? = null,
) {
    /** The loaded artifacts whose title or description holds the search, ignoring case. */
    val shown: List<Artifact> get() = query.trim().let { q ->
        if (q.isEmpty()) artifacts else artifacts.filter { it.title.contains(q, ignoreCase = true) || it.description.contains(q, ignoreCase = true) }
    }
}

/** The gallery: the member's own artifacts or the team's they can read, newest first, a page at a time. */
class ArtifactsViewModel(private val route: ArtifactsRoute, private val loadArtifacts: LoadArtifactsUseCase) : ViewModel() {
    private val state = MutableStateFlow(ArtifactsUiState())
    val uiState: StateFlow<ArtifactsUiState> = state.asStateFlow()
    private var load: Job? = null

    init { reload() }

    fun setScope(scope: ArtifactScope) {
        if (scope == state.value.scope) return
        state.update { ArtifactsUiState(scope = scope, query = it.query) }
        reload()
    }

    fun onQuery(query: String) = state.update { it.copy(query = query) }

    fun reload() {
        load?.cancel()
        val scope = state.value.scope
        state.update { it.copy(loading = true, loadingMore = false, error = null) }
        load = viewModelScope.launch { page(scope, before = null) }
    }

    /** The next page, once: a request already running is not doubled. */
    fun loadMore() {
        val current = state.value
        val before = current.nextBefore ?: return
        if (load?.isActive == true) return
        state.update { it.copy(loadingMore = true, error = null) }
        load = viewModelScope.launch { page(current.scope, before) }
    }

    private suspend fun page(scope: ArtifactScope, before: String?) {
        try {
            val page = loadArtifacts(route.accountId, scope, route.sessionId, before)
            state.update { current ->
                if (current.scope != scope) return@update current
                val known = if (before == null) emptySet() else current.artifacts.map { it.id }.toSet()
                val rows = if (before == null) page.artifacts else current.artifacts + page.artifacts.filter { it.id !in known }
                current.copy(artifacts = rows, nextBefore = page.nextBefore, loading = false, loadingMore = false)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            state.update { it.copy(loading = false, loadingMore = false, error = error.userMessage()) }
        }
    }
}
