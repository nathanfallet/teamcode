package me.nathanfallet.teamcode.companion

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.russhwolf.settings.MapSettings
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolder
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolders
import me.nathanfallet.teamcode.companion.domain.models.PermissionMode
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateDeviceSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadDeviceFoldersUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadHostDevicesUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadResumableSessionsUseCase
import me.nathanfallet.teamcode.companion.domain.models.ResumableSession
import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsPreferencesRepository
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute
import me.nathanfallet.teamcode.companion.presentation.screens.NewSessionSheet
import me.nathanfallet.teamcode.companion.presentation.theme.TeamCodeTheme
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionViewModel
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionTarget
import kotlin.test.*

/** The new-session sheet on the desktop target: Cloud next to an online device, its folders, the permission mode. */
@OptIn(ExperimentalTestApi::class)
class NewSessionSheetTest {
    private val sessions = FakeSessionRepository().apply { profileList = listOf(Profile("p1", "Default", "claude")) }
    private val devices = FakeDeviceRepository()
    private val settings = MapSettings()

    private fun model(features: FakeFeatures): NewSessionViewModel {
        val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
        val preferences = SettingsPreferencesRepository(settings)
        return NewSessionViewModel(NewSessionRoute("a1"), LoadProfilesUseCase(sessions, features, guard), CreateSessionUseCase(sessions, features, guard),
            LoadHostDevicesUseCase(devices, features, guard), LoadDeviceFoldersUseCase(devices, guard), CreateDeviceSessionUseCase(sessions, features, preferences, guard),
            LoadResumableSessionsUseCase(devices, features, guard), preferences, SequenceIds())
    }

    @Test fun anOlderServerShowsTheCloudFormAlone() = runComposeUiTest {
        val viewModel = model(FakeFeatures())
        setContent { TeamCodeTheme { NewSessionSheet(viewModel, onDismiss = {}, onCreated = {}) } }
        waitUntil { !viewModel.uiState.value.loadingProfiles }
        onNodeWithTag("target-device").assertDoesNotExist()
        onNodeWithTag("profile-p1").assertExists()
    }

    @Test fun aSessionStartsInAFolderOfAnOnlineDeviceWithTheChosenPermissionMode() = runComposeUiTest {
        devices.devicesResult = { listOf(hostDevice("d1", "Ada's Mac")) }
        devices.foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f1", "app", "~/git/app"), DeviceFolder("f2", "web", "~/git/web"))) }
        val viewModel = model(FakeFeatures(FakeFeatures().features + Features.DEVICE_SESSIONS))
        var created = false
        setContent { TeamCodeTheme { NewSessionSheet(viewModel, onDismiss = {}, onCreated = { created = true }) } }
        waitUntil { !viewModel.uiState.value.loadingProfiles }
        onNodeWithTag("target-device").performClick()
        waitUntil { viewModel.uiState.value.folders != null }
        onNodeWithTag("device-d1").assertIsSelected()
        onNodeWithText("~/git/app").assertExists()
        // No folder chosen yet: nothing starts.
        onNodeWithTag("create").assertIsNotEnabled()
        onNodeWithTag("folder-f2").performScrollTo().performClick()
        onNodeWithText("Start on Ada's Mac").assertExists()
        onNodeWithTag("bypass-warning").assertDoesNotExist()
        onNodeWithTag("permissions-bypass").performScrollTo().performClick()
        onNodeWithTag("bypass-warning").assertExists()
        onNodeWithTag("create").assertIsEnabled().performClick()
        waitUntil { created }
        val (sent, _) = sessions.deviceCreations.single()
        assertEquals("f2", sent.folderId); assertEquals("web", sent.name); assertEquals(PermissionMode.BYPASS, sent.permissionMode)
        assertEquals(PermissionMode.BYPASS, SettingsPreferencesRepository(settings).permissionMode)
    }

    @Test fun noOnlineDeviceSaysHowToBringOneOnline() = runComposeUiTest {
        val viewModel = model(FakeFeatures(FakeFeatures().features + Features.DEVICE_SESSIONS))
        setContent { TeamCodeTheme { NewSessionSheet(viewModel, onDismiss = {}, onCreated = {}) } }
        waitUntil { !viewModel.uiState.value.loadingProfiles }
        viewModel.onTarget(SessionTarget.DEVICE)
        waitUntil { viewModel.uiState.value.devices != null }
        onNodeWithTag("no-device").assertExists()
        onNodeWithTag("create").assertIsNotEnabled()
        devices.devicesResult = { listOf(hostDevice("d1")) }
        devices.foldersResult = { DeviceFolders(true, emptyList()) }
        onNodeWithTag("refresh-devices").performClick()
        waitUntil { viewModel.uiState.value.folders != null }
        onNodeWithTag("no-folder").assertExists()
        onNodeWithTag("create").assertIsNotEnabled()
    }

    @Test fun aSessionResumesAConversationChosenInThePickerWhichAnotherFolderClears() = runComposeUiTest {
        val id = "0000000a-aaaa-4bbb-8ccc-00000000000a"
        devices.devicesResult = { listOf(hostDevice("d1", "Ada's Mac")) }
        devices.foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f1", "app", "~/git/app"), DeviceFolder("f2", "web", "~/git/web")), resumes = true) }
        devices.resumableResult = { _, folder, _ -> if (folder == "f1") ResumableSessions(listOf(ResumableSession(id, "Fix the login bug", 0, 4))) else ResumableSessions(emptyList()) }
        val viewModel = model(FakeFeatures(FakeFeatures().features + Features.DEVICE_SESSIONS + Features.RESUME_SESSIONS))
        var created = false
        setContent { TeamCodeTheme { NewSessionSheet(viewModel, onDismiss = {}, onCreated = { created = true }) } }
        waitUntil { !viewModel.uiState.value.loadingProfiles }
        onNodeWithTag("target-device").performClick()
        waitUntil { viewModel.uiState.value.folders != null }
        onNodeWithTag("resume").assertDoesNotExist()
        onNodeWithTag("folder-f1").performScrollTo().performClick()
        // Closed, on None, until opened.
        onNodeWithTag("resume-picker").performScrollTo().assertExists()
        onNodeWithText("None").assertExists()
        onNodeWithTag("resume-$id").assertDoesNotExist()
        onNodeWithTag("resume-picker").performClick()
        waitUntil { viewModel.uiState.value.resumable != null }
        onNodeWithTag("resume-none").assertIsSelected()
        onNodeWithTag("resume-$id").performScrollTo().performClick()
        onNodeWithText("Fix the login bug").assertExists()
        onNodeWithTag("resume-none").assertDoesNotExist()
        // Another folder: the choice is gone, and its own list is empty.
        onNodeWithTag("folder-f2").performScrollTo().performClick()
        onNodeWithText("Fix the login bug").assertDoesNotExist()
        onNodeWithTag("resume-picker").performScrollTo().performClick()
        waitUntil { viewModel.uiState.value.resumable != null }
        onNodeWithTag("resume-empty").assertExists()
        onNodeWithTag("folder-f1").performScrollTo().performClick()
        onNodeWithTag("resume-picker").performScrollTo().performClick()
        waitUntil { viewModel.uiState.value.resumable != null }
        onNodeWithTag("resume-$id").performScrollTo().performClick()
        // Codex hides it.
        onNodeWithTag("agent-codex").performScrollTo().performClick()
        onNodeWithTag("resume").assertDoesNotExist()
        onNodeWithTag("agent-claude").performScrollTo().performClick()
        onNodeWithTag("resume-picker").performScrollTo().performClick()
        waitUntil { viewModel.uiState.value.resumable != null }
        onNodeWithTag("resume-$id").performScrollTo().performClick()
        onNodeWithTag("create").assertIsEnabled().performClick()
        waitUntil { created }
        assertEquals(id, sessions.deviceCreations.single().first.resumeSessionId)
    }

    @Test fun anOlderDaemonExplainsWhyThereIsNothingToResume() = runComposeUiTest {
        devices.devicesResult = { listOf(hostDevice("d1", "Ada's Mac")) }
        devices.foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f1", "app", "~/git/app")), resumes = false) }
        val viewModel = model(FakeFeatures(FakeFeatures().features + Features.DEVICE_SESSIONS + Features.RESUME_SESSIONS))
        setContent { TeamCodeTheme { NewSessionSheet(viewModel, onDismiss = {}, onCreated = {}) } }
        waitUntil { !viewModel.uiState.value.loadingProfiles }
        onNodeWithTag("target-device").performClick()
        waitUntil { viewModel.uiState.value.folderId != null }
        onNodeWithTag("resume-old-daemon").performScrollTo().assertExists()
        onNodeWithTag("resume-picker").assertDoesNotExist()
        onNodeWithTag("create").assertIsEnabled()
    }
}
