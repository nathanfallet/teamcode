package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.NewSession
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute

data class NewSessionUiState(
    val profiles: List<Profile> = emptyList(),
    val loadingProfiles: Boolean = true,
    /** The server takes a first message with the session. */
    val acceptsMessage: Boolean = false,
    val profileId: String? = null,
    val name: String = "",
    val model: String = "",
    val effort: String = "",
    val message: String = "",
    val creating: Boolean = false,
    val error: String? = null,
    val created: CloudSession? = null,
) {
    val canCreate: Boolean get() = !creating && created == null && profileId != null && name.isNotBlank()
}

/**
 * The new-session form. Its idempotency key is drawn once for the form and kept while the fields stay the same, so
 * a retry after a network failure returns the session the first attempt created; changing what is sent draws a new one
 * (the server refuses a replayed key with another body).
 */
class NewSessionViewModel(
    private val route: NewSessionRoute,
    private val loadProfiles: LoadProfilesUseCase,
    private val createSession: CreateSessionUseCase,
    private val ids: IdGenerator,
) : ViewModel() {
    private val state = MutableStateFlow(NewSessionUiState())
    val uiState: StateFlow<NewSessionUiState> = state.asStateFlow()
    private var key = ids.newId()

    init { reload() }

    fun reload() {
        // A creation in flight keeps its form and key: what it sent is what a retry sends again.
        if (state.value.creating) return
        state.update { it.copy(loadingProfiles = true, error = null) }
        viewModelScope.launch {
            try {
                val (profiles, acceptsMessage) = loadProfiles(route.accountId)
                val before = state.value
                state.update { it.copy(profiles = profiles, acceptsMessage = acceptsMessage, profileId = it.profileId ?: profiles.singleOrNull()?.id, loadingProfiles = false) }
                // What is sent changed (the first message now announced or not, a profile picked): a new creation.
                if (state.value.form() != before.form()) key = ids.newId()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(loadingProfiles = false, error = error.userMessage()) }
            }
        }
    }

    fun onProfile(id: String) = edit { it.copy(profileId = id) }
    fun onName(value: String) = edit { it.copy(name = value) }
    fun onModel(value: String) = edit { it.copy(model = value) }
    fun onEffort(value: String) = edit { it.copy(effort = value) }
    fun onMessage(value: String) = edit { it.copy(message = value) }

    private fun edit(change: (NewSessionUiState) -> NewSessionUiState) {
        if (state.value.creating) return
        val before = state.value
        val after = change(before).copy(error = null)
        if (after.form() != before.form()) key = ids.newId()
        state.value = after
    }

    fun create() {
        val current = state.value
        if (!current.canCreate) return
        state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val session = createSession(route.accountId, current.form(), key)
                state.update { it.copy(creating = false, created = session) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(creating = false, error = error.userMessage()) }
            }
        }
    }

    /** The form as sent: an edit that changes nothing sent (a trailing space) keeps the key, so a retry never creates twice. */
    private fun NewSessionUiState.form() = NewSession(name, profileId.orEmpty(), model, effort, message.takeIf { acceptsMessage }).normalized()
}
