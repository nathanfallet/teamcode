package me.nathanfallet.teamcode.companion.domain

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeDeviceRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolder
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolders
import me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession
import me.nathanfallet.teamcode.companion.domain.models.PermissionMode
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.CreateDeviceSessionUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadDeviceFoldersUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadHostDevicesUseCase
import me.nathanfallet.teamcode.companion.hostDevice
import me.nathanfallet.teamcode.companion.infrastructure.settings.SettingsPreferencesRepository
import kotlin.test.*

/** Sessions started on one of the member's online devices: which devices, which folders, what is sent and kept. */
class DeviceSessionUseCasesTest {
    private val guard = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
    private val devices = FakeDeviceRepository()
    private val sessions = FakeSessionRepository()
    private val features = FakeFeatures(FakeFeatures().features + Features.DEVICE_SESSIONS + Features.INITIAL_MESSAGE)
    private val settings = MapSettings()
    private val preferences = SettingsPreferencesRepository(settings)
    private val form = NewDeviceSession(" Fix ", "d1", "f1", "claude", " ", null, PermissionMode.BYPASS, "  ")

    @Test fun aServerWithoutDeviceSessionsOffersNoDevice() = runTest {
        val load = LoadHostDevicesUseCase(devices, FakeFeatures(), guard)
        assertEquals(Features.DEVICE_SESSIONS, assertFailsWith<CompanionException.Unsupported> { load("a1") }.feature)
        assertTrue(devices.calls.isEmpty())
        val create = CreateDeviceSessionUseCase(sessions, FakeFeatures(), preferences, guard)
        assertFailsWith<CompanionException.Unsupported> { create("a1", form, "k") }
        assertTrue(sessions.deviceCreations.isEmpty())
    }

    @Test fun thisDeviceComesFirstThenTheOthersByName() = runTest {
        devices.devicesResult = { listOf(hostDevice("d3", "zeta"), hostDevice("d1", "Alpha"), hostDevice("d2", "mine", current = true)) }
        assertEquals(listOf("d2", "d1", "d3"), LoadHostDevicesUseCase(devices, features, guard)("a1").map { it.id })
    }

    @Test fun aDeviceThatWentOfflineIsRefusedWithItsFoldersAndAnotherMembersIsWorded() = runTest {
        val load = LoadDeviceFoldersUseCase(devices, guard)
        devices.foldersResult = { DeviceFolders(true, listOf(DeviceFolder("f2", "web", "~/git/web"), DeviceFolder("f1", "app", "~/git/app"))) }
        assertEquals(listOf("f1", "f2"), load("a1", "d1").map { it.id })
        devices.foldersResult = { DeviceFolders(false, listOf(DeviceFolder("f1", "app", null))) }
        assertEquals("device_offline", assertFailsWith<CompanionException.DeviceRefused> { load("a1", "d1") }.code)
        devices.foldersResult = { throw CompanionException.Server(404, "device_not_found") }
        val gone = assertFailsWith<CompanionException.DeviceRefused> { load("a1", "d9") }
        assertEquals("device_not_found", gone.code); assertFalse(gone.message!!.contains("HTTP"))
    }

    @Test fun theSessionIsSentTrimmedAndThePermissionModeKeptForTheNextLaunch() = runTest {
        assertEquals(PermissionMode.ASK, preferences.permissionMode)
        val create = CreateDeviceSessionUseCase(sessions, features, preferences, guard)
        create("a1", form, "key-1")
        val (sent, key) = sessions.deviceCreations.single()
        assertEquals(NewDeviceSession("Fix", "d1", "f1", "claude", null, null, PermissionMode.BYPASS, null), sent)
        assertEquals("key-1", key)
        // Kept across a restart of the app.
        assertEquals(PermissionMode.BYPASS, SettingsPreferencesRepository(settings).permissionMode)
    }

    @Test fun theServersRefusalsAboutTheDeviceAreWordedAndTheOthersKept() = runTest {
        val create = CreateDeviceSessionUseCase(sessions, features, preferences, guard)
        for (code in listOf("device_offline", "folder_not_published", "device_not_found", "remote_requires_login")) {
            sessions.createOnDeviceResult = { throw CompanionException.Server(409, code) }
            val refused = assertFailsWith<CompanionException.DeviceRefused> { create("a1", form, "k") }
            assertEquals(code, refused.code); assertFalse(refused.message!!.contains("HTTP"), refused.message)
        }
        sessions.createOnDeviceResult = { throw CompanionException.Server(500, "internal") }
        assertFailsWith<CompanionException.Server> { create("a1", form, "k") }
    }

    @Test fun aFormWithoutDeviceFolderOrKnownAgentIsNeverSent() = runTest {
        val create = CreateDeviceSessionUseCase(sessions, features, preferences, guard)
        assertFailsWith<IllegalArgumentException> { create("a1", form.copy(deviceId = ""), "k") }
        assertFailsWith<IllegalArgumentException> { create("a1", form.copy(folderId = " "), "k") }
        assertFailsWith<IllegalArgumentException> { create("a1", form.copy(agent = "gemini"), "k") }
        assertFailsWith<IllegalArgumentException> { create("a1", form.copy(name = "  "), "k") }
        // A first message to a server that does not take one with the session would be dropped silently.
        val older = CreateDeviceSessionUseCase(sessions, FakeFeatures(FakeFeatures().features + Features.DEVICE_SESSIONS), preferences, guard)
        assertFailsWith<CompanionException.Unsupported> { older("a1", form.copy(initialMessage = "Go"), "k") }
        assertTrue(sessions.deviceCreations.isEmpty())
        // A refused form keeps the last launch's mode.
        assertEquals(PermissionMode.ASK, preferences.permissionMode)
    }
}
