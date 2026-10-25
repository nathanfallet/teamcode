package me.nathanfallet.teamcode.companion.domain.repositories

import me.nathanfallet.teamcode.companion.domain.models.DeviceFolders
import me.nathanfallet.teamcode.companion.domain.models.HostDevice
import me.nathanfallet.teamcode.companion.domain.models.ResumableSessions

/** The member's own devices that can host a session (`cloud.sessions.remote.daemon.v1`). */
interface DeviceRepository {
    /** The member's own devices whose daemon is online now; the others are never offered. */
    suspend fun onlineDevices(accountId: String): List<HostDevice>
    /** The folders [deviceId] published, owner only; a device of another member is [me.nathanfallet.teamcode.companion.domain.models.CompanionException.DeviceRefused]. */
    suspend fun folders(accountId: String, deviceId: String): DeviceFolders
    /** The conversations of [folderId] on [deviceId] a session can continue, read by its daemon now (`cloud.sessions.remote.resume.v1`). */
    suspend fun resumable(accountId: String, deviceId: String, folderId: String, limit: Int, before: Long?): ResumableSessions
}
