package me.nathanfallet.teamcode.companion.domain.usecases.sessions

import me.nathanfallet.teamcode.companion.domain.models.InvalidInput
import me.nathanfallet.teamcode.companion.domain.models.requireInput
import me.nathanfallet.teamcode.companion.domain.models.CloudSession
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolder
import me.nathanfallet.teamcode.companion.domain.models.HostDevice
import me.nathanfallet.teamcode.companion.domain.models.NewDeviceSession
import me.nathanfallet.teamcode.companion.domain.repositories.DeviceRepository
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.PreferencesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/**
 * The devices a session can be started on: the member's own, and only those whose TeamCode daemon is online now (an
 * offline device is never offered, the server would refuse it). This device first, then by name.
 */
class LoadHostDevicesUseCase(private val devices: DeviceRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String): List<HostDevice> = guard(accountId) {
        if (Features.DEVICE_SESSIONS !in features.features(accountId)) throw CompanionException.Unsupported(Features.DEVICE_SESSIONS)
        devices.onlineDevices(accountId).sortedWith(compareBy<HostDevice> { !it.current }.thenBy { it.name.lowercase() }.thenBy { it.id })
    }
}

/**
 * The folders a device offers, by how its owner recognizes them. Never a path typed here: the daemon publishes the
 * folders its owner added (`teamcode folders add`). A device whose daemon went offline meanwhile is refused.
 */
class LoadDeviceFoldersUseCase(private val devices: DeviceRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, deviceId: String): List<DeviceFolder> = guard(accountId) {
        val found = deviceRefusals { devices.folders(accountId, deviceId) }
        if (!found.online) throw CompanionException.DeviceRefused(CompanionException.DeviceRefused.DEVICE_OFFLINE)
        found.folders.sortedWith(compareBy<DeviceFolder> { (it.label ?: it.name).lowercase() }.thenBy { it.id })
    }
}

/**
 * Starts a session on one of the member's devices, in one of its folders, with the permission mode chosen now, which is
 * kept for the next launch once the server created the session. The caller keeps [idempotencyKey] for the form, as for a cloud session. The server's
 * refusals about the device (offline, folder no longer offered, ...) are [CompanionException.DeviceRefused].
 */
class CreateDeviceSessionUseCase(
    private val sessions: SessionRepository,
    private val features: FeaturesRepository,
    private val preferences: PreferencesRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String, form: NewDeviceSession, idempotencyKey: String): CloudSession = guard(accountId) {
        val sent = form.normalized()
        requireInput(sent.deviceId.isNotBlank(), InvalidInput.Problem.CHOOSE_DEVICE, message = "Choose one of your online devices.")
        requireInput(sent.folderId.isNotBlank(), InvalidInput.Problem.CHOOSE_FOLDER, message = "Choose one of the folders the device offers.")
        requireInput(sent.agent in StartLocalSessionUseCase.AGENTS, InvalidInput.Problem.CHOOSE_AGENT, message = "Choose Claude Code or Codex.")
        requireInput(sent.name.isNotEmpty() && sent.name.length <= MAX_NAME && sent.name.none(Char::isISOControl), InvalidInput.Problem.SESSION_NAME, MAX_NAME, "Name the session (up to $MAX_NAME characters).")
        val announced = features.features(accountId)
        if (Features.DEVICE_SESSIONS !in announced) throw CompanionException.Unsupported(Features.DEVICE_SESSIONS)
        val message = sent.initialMessage
        if (message != null) {
            requireInput(MessageRules.valid(message), InvalidInput.Problem.FIRST_MESSAGE_LENGTH, MessageRules.MAX_CHARS, "Write a first message of up to ${MessageRules.MAX_CHARS} characters.")
            if (Features.INITIAL_MESSAGE !in announced) throw CompanionException.Unsupported(Features.INITIAL_MESSAGE)
        }
        val session = deviceRefusals { sessions.createOnDevice(accountId, sent, idempotencyKey) }
        // Kept once a session started with it: a refused launch leaves the previous choice.
        preferences.setPermissionMode(sent.permissionMode)
        session
    }

    private companion object {
        const val MAX_NAME = 120
    }
}

/** The server's refusals about a device, worded; any other failure stays as it is. */
private inline fun <T> deviceRefusals(call: () -> T): T = try {
    call()
} catch (error: CompanionException.Server) {
    val code = error.code
    if (code != null && code in CompanionException.DeviceRefused.CODES) throw CompanionException.DeviceRefused(code)
    throw error
}
