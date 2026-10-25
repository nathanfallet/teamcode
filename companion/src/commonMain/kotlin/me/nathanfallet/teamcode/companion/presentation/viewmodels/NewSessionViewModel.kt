package me.nathanfallet.teamcode.companion.presentation.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolder
import me.nathanfallet.teamcode.companion.domain.models.HostDevice
import me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession
import me.nathanfallet.teamcode.companion.domain.models.NewSession
import me.nathanfallet.teamcode.companion.domain.models.PermissionMode
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.models.ResumableSession
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository
import me.nathanfallet.teamcode.companion.domain.services.IdGenerator
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateDeviceSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadDeviceFoldersUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadHostDevicesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadResumableSessionsUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute

/** Where the new session runs: a pod from a cloud profile, or a folder of one of the member's online devices. */
enum class SessionTarget { CLOUD, DEVICE }

data class NewSessionUiState(
    val profiles: List<Profile> = emptyList(),
    val loadingProfiles: Boolean = true,
    /** The server takes a first message with the session. */
    val acceptsMessage: Boolean = false,
    /** The server starts sessions on the member's online devices (`cloud.sessions.remote.daemon.v1`): the form offers both. */
    val onDevices: Boolean = false,
    val target: SessionTarget = SessionTarget.CLOUD,
    val profileId: String? = null,
    /** The member's devices whose daemon is online; null until they were read. */
    val devices: List<HostDevice>? = null,
    val loadingDevices: Boolean = false,
    val deviceId: String? = null,
    /** The chosen device's folders; null until they were read. */
    val folders: List<DeviceFolder>? = null,
    val loadingFolders: Boolean = false,
    val folderId: String? = null,
    /** The server lets a session continue a conversation of the folder (`cloud.sessions.remote.resume.v1`). */
    val resumes: Boolean = false,
    /** The chosen device's daemon lists the folder's conversations; false for an older TeamCode there. */
    val deviceResumes: Boolean = false,
    /** The folder's conversations, read when the picker first opens; null until then. */
    val resumable: List<ResumableSession>? = null,
    /** The cursor of the next, older page; null on the last one. */
    val resumableNext: Long? = null,
    val loadingResumable: Boolean = false,
    /** Why the conversations could not be read, shown in the picker; the form can still start a new conversation. */
    val resumableError: Failure? = null,
    val resumeOpen: Boolean = false,
    /** The conversation the session continues; null, the default, starts a new one. */
    val resumeId: String? = null,
    val agent: String = "claude",
    /** The last launch's choice, until changed. */
    val permissionMode: PermissionMode = PermissionMode.ASK,
    val name: String = "",
    /** False until the member types a name: on a device, the folder's name follows the chosen folder. */
    val nameEdited: Boolean = false,
    val model: String = "",
    val effort: String = "",
    val message: String = "",
    val creating: Boolean = false,
    val error: Failure? = null,
    val created: CloudSession? = null,
) {
    /** The picker shows on a device, once a folder is chosen, for Claude Code only. */
    val showsResume: Boolean get() = target == SessionTarget.DEVICE && onDevices && resumes && folderId != null && agent == "claude"

    // Not while something loads: its answer can change what is sent, and so the key a retry must reuse.
    val canCreate: Boolean get() = !creating && !loadingProfiles && created == null && name.isNotBlank() && when (target) {
        SessionTarget.CLOUD -> profileId != null
        SessionTarget.DEVICE -> onDevices && !loadingDevices && !loadingFolders && deviceId != null && folderId != null
    }
}

/**
 * The new-session form, in the cloud or on one of the member's online devices. Its idempotency key is drawn once for
 * the form and kept while what is sent stays the same, so a retry after a network failure returns the session the first
 * attempt created; changing what is sent draws a new one (the server refuses a replayed key with another body).
 */
class NewSessionViewModel(
    private val route: NewSessionRoute,
    private val loadProfiles: LoadProfilesUseCase,
    private val createSession: CreateSessionUseCase,
    private val loadDevices: LoadHostDevicesUseCase,
    private val loadFolders: LoadDeviceFoldersUseCase,
    private val createOnDevice: CreateDeviceSessionUseCase,
    private val loadResumable: LoadResumableSessionsUseCase,
    preferences: PreferencesRepository,
    private val ids: IdGenerator,
) : ViewModel() {
    private val state = MutableStateFlow(NewSessionUiState(permissionMode = preferences.permissionMode))
    val uiState: StateFlow<NewSessionUiState> = state.asStateFlow()
    private var key = ids.newId()
    private var firstLoad = true
    private var devicesJob: Job? = null
    private var foldersJob: Job? = null
    private var resumableJob: Job? = null

    init { reload() }

    fun reload() {
        // A creation in flight keeps its form and key: what it sent is what a retry sends again.
        if (state.value.creating) return
        change { it.copy(loadingProfiles = true, error = null) }
        viewModelScope.launch {
            try {
                val options = loadProfiles(route.accountId)
                val onDevices = options.onDevices
                // A team without a cloud profile starts on a device at once.
                val start = firstLoad && onDevices && options.profiles.isEmpty()
                firstLoad = false
                change {
                    it.copy(profiles = options.profiles, acceptsMessage = options.acceptsMessage, onDevices = onDevices, resumes = options.resumes,
                        target = if (start) SessionTarget.DEVICE else if (onDevices) it.target else SessionTarget.CLOUD,
                        profileId = it.profileId ?: options.profiles.singleOrNull()?.id, loadingProfiles = false)
                }
                if (state.value.target == SessionTarget.DEVICE) reloadDevices()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.userMessage()
                change { it.copy(loadingProfiles = false, error = message) }
            }
        }
    }

    fun onTarget(target: SessionTarget) {
        if (target == SessionTarget.DEVICE && !state.value.onDevices) return
        edit { it.copy(target = target) }
        // Read again at each switch: a device listed earlier may have gone offline since.
        if (target == SessionTarget.DEVICE && !state.value.loadingDevices) reloadDevices()
    }

    /**
     * Reads the online devices again (one may have come online); a device gone offline is dropped with its folder.
     * [keepError]: after a refusal, which stays shown.
     */
    fun reloadDevices() = reloadDevices(keepError = false)

    private fun reloadDevices(keepError: Boolean) {
        if (state.value.creating) return
        foldersJob?.cancel()
        devicesJob?.cancel()
        change { it.copy(loadingDevices = true, loadingFolders = false, error = if (keepError) it.error else null) }
        devicesJob = viewModelScope.launch {
            try {
                val devices = loadDevices(route.accountId)
                val kept = state.value.deviceId?.takeIf { id -> devices.any { it.id == id } } ?: devices.singleOrNull()?.id
                change { it.copy(devices = devices, loadingDevices = false, deviceId = kept, folders = if (kept == it.deviceId) it.folders else null, folderId = if (kept == it.deviceId) it.folderId else null) }
                kept?.let { loadFoldersOf(it, keepError) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.userMessage()
                change { it.copy(loadingDevices = false, error = message) }
            }
        }
    }

    fun onDevice(id: String) {
        if (state.value.creating || state.value.devices?.none { it.id == id } != false) return
        // The same device again only reads its folders again after they failed.
        if (id == state.value.deviceId && state.value.folders != null && state.value.error == null) return
        edit { it.copy(deviceId = id, folders = null, deviceResumes = false).withFolder(null) }
        loadFoldersOf(id)
    }

    /** The folders [deviceId] offers now; one folder alone is chosen. Only the answer for the device still chosen counts. */
    private fun loadFoldersOf(deviceId: String, keepError: Boolean = false) {
        foldersJob?.cancel()
        change { it.copy(loadingFolders = true, error = if (keepError) it.error else null) }
        foldersJob = viewModelScope.launch {
            try {
                val found = loadFolders.detailed(route.accountId, deviceId)
                val folders = found.folders
                if (state.value.deviceId != deviceId) return@launch
                val kept = state.value.folderId?.takeIf { id -> folders.any { it.id == id } } ?: folders.singleOrNull()?.id
                change { it.copy(folders = folders, loadingFolders = false, deviceResumes = found.resumes).withFolder(kept) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (state.value.deviceId != deviceId) return@launch
                // The device went offline or is gone: the list no longer offers it. Dropped here without reading the list
                // again, which could still name it (its presence lags) and lead back here.
                val message = error.userMessage()
                if (error.isDeviceGone()) change {
                    it.copy(devices = it.devices?.filter { device -> device.id != deviceId }, deviceId = null, folders = null, loadingFolders = false, error = message).withFolder(null)
                } else change { it.copy(loadingFolders = false, folders = it.folders ?: emptyList(), error = message) }
            }
        }
    }

    fun onFolder(id: String) {
        if (state.value.folders?.none { it.id == id } != false) return
        edit { it.withFolder(id) }
    }

    // Codex continues no Claude Code conversation: the choice goes with the agent.
    fun onAgent(value: String) = edit { if (value == it.agent) it else it.copy(agent = value).withoutResume() }

    /** Opens or closes the picker; the first opening reads the folder's conversations on the device. */
    fun onResumeOpen() {
        val current = state.value
        if (current.creating || !current.showsResume || !current.deviceResumes) return
        change { it.copy(resumeOpen = !it.resumeOpen) }
        if (state.value.resumeOpen && current.resumable == null && !current.loadingResumable) loadResumableOf(null)
    }

    /** Reads the conversations again after a failure. */
    fun onResumeRetry() { if (state.value.showsResume && state.value.deviceResumes && !state.value.loadingResumable) loadResumableOf(null) }

    /** The next, older page, added under the ones shown. */
    fun onResumeMore() { state.value.resumableNext?.let { if (!state.value.loadingResumable) loadResumableOf(it) } }

    /** [id] null: none, a new conversation. Closes the picker. */
    fun onResume(id: String?) {
        if (id != null && state.value.resumable?.none { it.id == id } != false) return
        edit { it.copy(resumeId = id, resumeOpen = false) }
    }

    /** Only the answer for the device and folder still chosen counts. */
    private fun loadResumableOf(before: Long?) {
        val deviceId = state.value.deviceId ?: return
        val folderId = state.value.folderId ?: return
        resumableJob?.cancel()
        change { it.copy(loadingResumable = true, resumableError = null) }
        resumableJob = viewModelScope.launch {
            try {
                val page = loadResumable(route.accountId, deviceId, folderId, before)
                if (state.value.deviceId != deviceId || state.value.folderId != folderId) return@launch
                change {
                    val shown = if (before == null) page.sessions else it.resumable.orEmpty() + page.sessions.filter { s -> it.resumable.orEmpty().none { k -> k.id == s.id } }
                    it.copy(resumable = shown, resumableNext = page.nextBefore, loadingResumable = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (state.value.deviceId != deviceId || state.value.folderId != folderId) return@launch
                val message = error.userMessage()
                change { it.copy(loadingResumable = false, resumableError = message) }
            }
        }
    }
    fun onPermissionMode(mode: PermissionMode) = edit { it.copy(permissionMode = mode) }
    fun onProfile(id: String) = edit { it.copy(profileId = id) }
    // A name cleared lets the folder name the session again.
    fun onName(value: String) = edit { it.copy(name = value, nameEdited = value.isNotBlank()) }
    fun onModel(value: String) = edit { it.copy(model = value) }
    fun onEffort(value: String) = edit { it.copy(effort = value) }
    fun onMessage(value: String) = edit { it.copy(message = value) }

    private fun edit(update: (NewSessionUiState) -> NewSessionUiState) {
        if (state.value.creating) return
        change { update(it).copy(error = null) }
    }

    /** Applies [change]; what is sent changed (a field, the first message now announced, a device dropped): a new creation. */
    private fun change(update: (NewSessionUiState) -> NewSessionUiState) {
        val before = state.value
        val after = update(before)
        // Another device or folder: the conversations read for the previous one no longer count.
        if (after.deviceId != before.deviceId || after.folderId != before.folderId) resumableJob?.cancel()
        if (after.sent() != before.sent()) key = ids.newId()
        state.value = after
    }

    fun create() {
        val current = state.value
        if (!current.canCreate) return
        change { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val session = when (current.target) {
                    SessionTarget.DEVICE -> createOnDevice(route.accountId, current.deviceForm(), key)
                    SessionTarget.CLOUD -> createSession(route.accountId, current.cloudForm(), key)
                }
                change { it.copy(creating = false, created = session) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.userMessage()
                change { it.copy(creating = false, error = message) }
                when {
                    error.isDeviceGone() -> reloadDevices(keepError = true)
                    error is CompanionException.DeviceRefused && error.code == FOLDER_NOT_PUBLISHED -> current.deviceId?.let { loadFoldersOf(it, keepError = true) }
                }
            }
        }
    }

    private fun NewSessionUiState.withFolder(id: String?): NewSessionUiState {
        val folder = folders?.firstOrNull { it.id == id }
        // Unless the member named it, the session takes the folder's name, and none without a folder. A conversation
        // belongs to its folder: another folder (or device, which goes through here) clears the choice and the list.
        val moved = folder?.id != folderId
        return copy(folderId = folder?.id, name = if (nameEdited) name else folder?.name.orEmpty()).let { if (moved) it.withoutResume(list = true) else it }
    }

    private fun NewSessionUiState.withoutResume(list: Boolean = false): NewSessionUiState = copy(resumeId = null, resumeOpen = false,
        resumable = if (list) null else resumable, resumableNext = if (list) null else resumableNext,
        loadingResumable = if (list) false else loadingResumable, resumableError = if (list) null else resumableError)

    /**
     * The form as sent: an edit that changes nothing sent (a trailing space) keeps the key, so a retry never creates twice.
     * The permission mode is kept by the device form only: a cloud session has none.
     */
    private fun NewSessionUiState.sent(): Any = when (target) {
        SessionTarget.CLOUD -> cloudForm()
        SessionTarget.DEVICE -> deviceForm()
    }

    private fun NewSessionUiState.cloudForm() = NewSession(name, profileId.orEmpty(), model, effort, message.takeIf { acceptsMessage }).normalized()
    private fun NewSessionUiState.deviceForm() =
        NewDeviceSession(name, deviceId.orEmpty(), folderId.orEmpty(), agent, model, effort, permissionMode, message.takeIf { acceptsMessage },
            resumeSessionId = resumeId.takeIf { showsResume }).normalized()

    private fun Exception.isDeviceGone() = this is CompanionException.DeviceRefused && (code == CompanionException.DeviceRefused.DEVICE_OFFLINE || code == DEVICE_NOT_FOUND)

    private companion object {
        const val FOLDER_NOT_PUBLISHED = "folder_not_published"
        const val DEVICE_NOT_FOUND = "device_not_found"
    }
}
