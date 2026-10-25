package me.nathanfallet.teamcode.companion.infrastructure.repositories

import me.nathanfallet.teamcode.companion.domain.models.DeviceFolder
import me.nathanfallet.teamcode.companion.domain.models.DeviceFolders
import me.nathanfallet.teamcode.companion.domain.models.HostDevice
import me.nathanfallet.teamcode.companion.domain.models.ResumableSession
import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions
import me.nathanfallet.teamcode.api.models.DaemonFeatures
import me.nathanfallet.teamcode.companion.domain.repositories.DeviceRepository

class RemoteDeviceRepository(private val calls: AccountCalls) : DeviceRepository {
    override suspend fun onlineDevices(accountId: String): List<HostDevice> = calls.withAccount(accountId) { client ->
        // `GET /devices` lists the caller's own devices only: another member's computer is never offered.
        client.devices().filter { it.daemon?.online == true }.map { HostDevice(it.id, it.name, it.platform, it.osVersion, it.current) }
    }

    override suspend fun folders(accountId: String, deviceId: String): DeviceFolders = calls.withAccount(accountId) { client ->
        val view = client.deviceFolders(deviceId)
        DeviceFolders(view.daemon?.online == true, view.folders.map { DeviceFolder(it.id, it.name, it.label) }, DaemonFeatures.RESUME in view.daemon?.features.orEmpty())
    }

    override suspend fun resumable(accountId: String, deviceId: String, folderId: String, limit: Int, before: Long?): ResumableSessions = calls.withAccount(accountId) { client ->
        val page = client.resumableSessions(deviceId, folderId, limit, before)
        ResumableSessions(page.sessions.map { ResumableSession(it.id, it.title, it.lastActiveAt, it.turns) }, page.nextBefore)
    }
}
