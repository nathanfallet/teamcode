package me.nathanfallet.teamcode.companion.presentation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.SequenceIds
import me.nathanfallet.teamcode.companion.cloudSession
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.Profile
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadProfilesUseCase
import me.nathanfallet.teamcode.companion.presentation.navigation.NewSessionRoute
import me.nathanfallet.teamcode.companion.presentation.viewmodels.NewSessionViewModel
import com.russhwolf.settings.MapSettings
import me.nathanfallet.teamcode.companion.FakeDeviceRepository
import me.nathanfallet.teamcode.companion.hostDevice
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolder
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolders
import me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession
import me.nathanfallet.teamcode.companion.domain.models.PermissionMode
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateDeviceSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadDeviceFoldersUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadHostDevicesUseCase
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsPreferencesRepository
import me.nathanfallet.teamcode.companion.presentation.viewmodels.SessionTarget
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class NewSessionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
    private val settings = MapSettings()

    private fun viewModel(sessions: FakeSessionRepository, features: FakeFeatures, devices: FakeDeviceRepository) = NewSessionViewModel(
        NewSessionRoute("a1"), LoadProfilesUseCase(sessions, features, guard), CreateSessionUseCase(sessions, features, guard),
        LoadHostDevicesUseCase(devices, features, guard), LoadDeviceFoldersUseCase(devices, guard),
        CreateDeviceSessionUseCase(sessions, features, SettingsPreferencesRepository(settings), guard), SettingsPreferencesRepository(settings), SequenceIds(),
    )
    private val onDevices = FakeFeatures(FakeFeatures().features + Features.DEVICE_SESSIONS + Features.INITIAL_MESSAGE)

    @Test fun aRetryAfterAFailureReusesTheKeyAndAnEditDrawsANewOne() = runTest(dispatcher) {
        val sessions = FakeSessionRepository().apply { profileList = listOf(Profile("p1", "Default", "claude")) }
        val features = FakeFeatures()
        val model = viewModel(sessions, features, FakeDeviceRepository())
        settle()
        // The only profile is chosen; the server without the first-message capability hides the field.
        assertEquals("p1", model.uiState.value.profileId)
        assertFalse(model.uiState.value.acceptsMessage)
        model.onName("Fix"); model.onMessage("ignored")
        // While the profiles load again, their answer may change what is sent: no creation until it is in.
        model.reload()
        assertFalse(model.uiState.value.canCreate)
        settle()
        assertTrue(model.uiState.value.canCreate)
        sessions.createResult = { throw CompanionException.Unreachable() }
        model.create(); settle()
        assertNotNull(model.uiState.value.error)
        // A trailing space changes nothing sent: the retry keeps the key, so the server returns what it may have created.
        model.onName("Fix ")
        model.create(); settle()
        var creates = sessions.calls.filter { it.startsWith("create") }
        assertEquals(2, creates.size); assertEquals(creates[0], creates[1])
        // Another name is another session: a new key.
        model.onName("Fix it")
        sessions.createResult = { cloudSession("new") }
        model.create(); settle()
        creates = sessions.calls.filter { it.startsWith("create") }
        assertEquals(3, creates.size); assertNotEquals(creates[1].substringAfterLast(' '), creates[2].substringAfterLast(' '))
        assertEquals("new", model.uiState.value.created?.id)
        assertFalse(model.uiState.value.canCreate)
    }

    @Test fun aServerWithoutDeviceSessionsOffersTheCloudOnly() = runTest(dispatcher) {
        val devices = FakeDeviceRepository()
        val model = viewModel(FakeSessionRepository().apply { profileList = listOf(Profile("p1", "Default", "claude")) }, FakeFeatures(), devices)
        settle()
        assertFalse(model.uiState.value.onDevices)
        model.onTarget(SessionTarget.DEVICE); settle()
        assertEquals(SessionTarget.CLOUD, model.uiState.value.target)
        assertTrue(devices.calls.isEmpty())
    }

    @Test fun onlyOnlineDevicesAndTheirPublishedFoldersAreOfferedAndTheFolderNamesTheSession() = runTest(dispatcher) {
        val sessions = FakeSessionRepository().apply { profileList = listOf(Profile("p1", "Default", "claude")) }
        val devices = FakeDeviceRepository().apply {
            devicesResult = { listOf(hostDevice("d1", "Ada's Mac"), hostDevice("d2", "Linux box")) }
            foldersResult = { id -> DeviceFolders(true, if (id == "d1") listOf(DeviceFolder("f1", "app", "~/git/app"), DeviceFolder("f2", "web", "~/git/web")) else listOf(DeviceFolder("f9", "infra", null))) }
        }
        val model = viewModel(sessions, onDevices, devices)
        settle()
        assertTrue(model.uiState.value.onDevices)
        assertEquals(SessionTarget.CLOUD, model.uiState.value.target)
        assertTrue(devices.calls.isEmpty())
        model.onTarget(SessionTarget.DEVICE); settle()
        assertEquals(listOf("d1", "d2"), model.uiState.value.devices?.map { it.id })
        // Two devices: none is chosen for the member, so nothing can start yet.
        assertNull(model.uiState.value.deviceId); assertFalse(model.uiState.value.canCreate)
        model.onDevice("d9"); settle()
        assertNull(model.uiState.value.deviceId)
        model.onDevice("d2"); settle()
        // Its only folder is chosen and names the session.
        assertEquals("f9", model.uiState.value.folderId); assertEquals("infra", model.uiState.value.name)
        model.onDevice("d1"); settle()
        assertNull(model.uiState.value.folderId); assertFalse(model.uiState.value.canCreate)
        model.onFolder("f2")
        assertEquals("web", model.uiState.value.name); assertTrue(model.uiState.value.canCreate)
        // A typed name is the member's: another folder no longer changes it.
        model.onName("Landing page"); model.onFolder("f1")
        assertEquals("Landing page", model.uiState.value.name)
        model.onAgent("codex"); model.onMessage("Ship it")
        model.create(); settle()
        val (sent, _) = sessions.deviceCreations.single()
        assertEquals(NewDeviceSession("Landing page", "d1", "f1", "codex", null, null, PermissionMode.ASK, "Ship it"), sent)
        assertNotNull(model.uiState.value.created)
    }

    @Test fun thePermissionModeOfTheLastLaunchComesBack() = runTest(dispatcher) {
        val devices = FakeDeviceRepository().apply {
            devicesResult = { listOf(hostDevice("d1")) }
            foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f1", "app", null))) }
        }
        val first = viewModel(FakeSessionRepository(), onDevices, devices)
        settle()
        // No cloud profile: the form opens on a device, the only one and its only folder chosen.
        assertEquals(SessionTarget.DEVICE, first.uiState.value.target)
        assertEquals("d1", first.uiState.value.deviceId); assertEquals("f1", first.uiState.value.folderId)
        assertEquals(PermissionMode.ASK, first.uiState.value.permissionMode)
        first.onPermissionMode(PermissionMode.BYPASS)
        first.create(); settle()
        assertNotNull(first.uiState.value.created)
        val next = viewModel(FakeSessionRepository(), onDevices, devices)
        settle()
        assertEquals(PermissionMode.BYPASS, next.uiState.value.permissionMode)
    }

    @Test fun aDeviceStillListedButOfflineForItsFoldersIsDroppedWithoutReadingAgainAndAgain() = runTest(dispatcher) {
        // Its presence lags: the list still names it while its folders answer that it is offline.
        val devices = FakeDeviceRepository().apply {
            devicesResult = { listOf(hostDevice("d1", "Ada's Mac")) }
            foldersResult = { DeviceFolders(false, emptyList()) }
        }
        val model = viewModel(FakeSessionRepository(), onDevices, devices)
        settle(); settle(); settle()
        assertEquals(listOf("devices", "folders d1"), devices.calls)
        val state = model.uiState.value
        assertEquals(emptyList(), state.devices); assertNull(state.deviceId); assertFalse(state.loadingDevices); assertFalse(state.loadingFolders)
        assertEquals(CompanionException.DeviceRefused("device_offline").message, state.error?.text)
        // Refresh reads it again, once.
        model.reloadDevices(); settle()
        assertEquals(listOf("devices", "folders d1", "devices", "folders d1"), devices.calls)
    }

    @Test fun aClearedNameIsTakenFromTheFolderAgainAndAnotherDeviceClearsTheFoldersName() = runTest(dispatcher) {
        val devices = FakeDeviceRepository().apply {
            devicesResult = { listOf(hostDevice("d1"), hostDevice("d2")) }
            foldersResult = { id -> DeviceFolders(true, listOf(DeviceFolder("f-$id", "app-$id", null), DeviceFolder("g-$id", "web-$id", null))) }
        }
        val model = viewModel(FakeSessionRepository(), onDevices, devices)
        settle()
        model.onDevice("d1"); settle()
        model.onFolder("f-d1")
        assertEquals("app-d1", model.uiState.value.name)
        model.onDevice("d2"); settle()
        // No folder chosen on the other device: the first device's folder no longer names the session.
        assertEquals("", model.uiState.value.name)
        model.onName("Mine"); model.onName("")
        model.onFolder("g-d2")
        assertEquals("web-d2", model.uiState.value.name)
    }

    @Test fun aDeviceGoneOfflineIsRefusedSaidAndDroppedAndARetryKeepsItsKey() = runTest(dispatcher) {
        val sessions = FakeSessionRepository()
        var online = listOf(hostDevice("d1", "Ada's Mac"))
        val devices = FakeDeviceRepository().apply {
            devicesResult = { online }
            foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f1", "app", null))) }
        }
        val model = viewModel(sessions, onDevices, devices)
        settle()
        assertTrue(model.uiState.value.canCreate)
        // A network failure: the retry sends the same key, so the server answers what it may have created.
        sessions.createOnDeviceResult = { throw CompanionException.Unreachable() }
        model.create(); settle()
        model.create(); settle()
        val keys = sessions.deviceCreations.map { it.second }
        assertEquals(2, keys.size); assertEquals(keys[0], keys[1])
        // The device went offline meanwhile: refused, said in words, and no longer offered.
        online = emptyList()
        sessions.createOnDeviceResult = { throw CompanionException.Server(409, "device_offline") }
        model.create(); settle()
        val state = model.uiState.value
        assertEquals(CompanionException.DeviceRefused("device_offline").message, state.error?.text)
        assertEquals(emptyList(), state.devices); assertNull(state.deviceId); assertFalse(state.canCreate)
        assertNull(state.created)
    }
}

/** Runs what is due within a second of virtual time; the screens' periodic refreshes never let the scheduler go idle. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.settle() { advanceTimeBy(1_000); runCurrent() }
