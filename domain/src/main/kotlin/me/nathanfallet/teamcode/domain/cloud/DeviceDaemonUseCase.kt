package me.nathanfallet.teamcode.domain.cloud

import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.domain.team.*

/**
 * A remote session waiting for the device's daemon to host it: a session started through the daemon
 * ([CloudSessionHost.folderId] set), on its device, in phase `waiting_host` with desired state `running`. [state] is
 * `creating` for a new session, `resuming` for a woken one, `preparing` when a host was lost before it was ready.
 */
data class DaemonOrder(
    val sessionId: String, val folderId: String, val directoryName: String, val agentKind: String,
    val permissionMode: RemotePermissionMode, val state: CloudSessionState, val requestedAt: Long,
)

/** Persistence of the daemon channel: presence on the device row, published folders, and the queue read from sessions. */
interface DeviceDaemonStore {
    /** The database clock, shared by every replica. */
    suspend fun now(): Long
    /** The device of an interactive login bound to an installation; null for a service key, a browser or an older login. */
    suspend fun deviceOf(principal: Principal): String?
    /**
     * Whether [principal] is a cloud session's credential (a `tcex_` execution token, issued to one pod for one session),
     * whatever its scopes: a pod never registers, heartbeats or orders as a device.
     */
    suspend fun cloudExecution(principal: Principal): Boolean
    /** Marks the daemon seen at the database clock, unless a poll did within [DeviceDaemonRules.TOUCH_MILLIS]. */
    suspend fun touch(deviceId: String, version: String?)
    /** Marks the daemon offline at once. */
    suspend fun offline(deviceId: String)
    /** Replaces the device's folders, in their order. */
    suspend fun publish(deviceId: String, folders: List<DeviceFolder>)
    /** [deviceId] if [memberId] of [teamId] owns it, with its presence and folders; null otherwise (whoever asks). */
    suspend fun owned(teamId: String, memberId: String, deviceId: String): OwnedDevice?
    /** The device's queue, oldest request first, at most [limit]. */
    suspend fun orders(deviceId: String, limit: Int): List<DaemonOrder>
}

data class DaemonPoll(val orders: List<DaemonOrder>)

/**
 * The daemon's side of the channel. Every call is made with the owner's interactive login from the device itself
 * ([DeviceDaemonStore.deviceOf]): a service key, a browser session or another device's login never reads or answers a
 * device's queue. Accepting an order is [CloudSessionService.host]; refusing it is [CloudSessionService.refuseHost].
 */
class DeviceDaemonUseCase(
    private val store: DeviceDaemonStore,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** The caller's device, required for every daemon call. */
    suspend fun device(principal: Principal): String {
        requireNotCloudSession(store, principal)
        if (principal.serviceScopes != null) throw daemonRequiresDevice()
        return store.deviceOf(principal) ?: throw daemonRequiresDevice()
    }

    /**
     * Marks the daemon online, then returns its queue as soon as it is not empty, or empty after [waitMillis] (at most
     * [DeviceDaemonRules.MAX_WAIT_MILLIS]). The wait reads the queue every [DeviceDaemonRules.POLL_MILLIS], holding no
     * transaction between reads, and ends with the caller's cancellation.
     */
    suspend fun poll(principal: Principal, waitMillis: Long, version: String?): DaemonPoll {
        DeviceDaemonRules.validateVersion(version)
        val device = device(principal)
        store.touch(device, version)
        var waited = 0L
        val wait = waitMillis.coerceIn(0, DeviceDaemonRules.MAX_WAIT_MILLIS)
        while (true) {
            val orders = store.orders(device, DeviceDaemonRules.ORDER_BATCH)
            if (orders.isNotEmpty() || waited >= wait) return DaemonPoll(orders)
            sleep(DeviceDaemonRules.POLL_MILLIS)
            waited += DeviceDaemonRules.POLL_MILLIS
        }
    }

    suspend fun offline(principal: Principal) = store.offline(device(principal))

    suspend fun publish(principal: Principal, folders: List<DeviceFolder>): List<DeviceFolder> {
        DeviceDaemonRules.validateFolders(folders)
        store.publish(device(principal), folders)
        return folders
    }

    /** The folders of one of the caller's own devices. Another member's device, an administrator's included, is not found. */
    suspend fun owned(principal: Principal, deviceId: String): OwnedDevice {
        principal.requireInteractive()
        return store.owned(principal.teamId, principal.member.id, deviceId) ?: throw deviceNotFound()
    }

    companion object {
        /**
         * One pod runs one session, by design (Nathan, 23/10/2026): a cloud session's credential never becomes a host
         * device, never appears online and never starts a session on a device. Checked before anything else, so the
         * refusal holds even if such a credential were ever given more scopes.
         */
        suspend fun requireNotCloudSession(store: DeviceDaemonStore, principal: Principal) {
            if (store.cloudExecution(principal)) throw TeamFailure("cloud_session_credential", 403,
                "A cloud session cannot host or start remote sessions: one pod runs one session. Use teamcode login on a computer.")
        }
        fun deviceNotFound() = TeamFailure("device_not_found", 404, "Device not found among your devices.")
        private fun daemonRequiresDevice() = TeamFailure("daemon_requires_device", 422,
            "The daemon channel takes the interactive login of the device itself (teamcode login on that device); service keys and browser sessions cannot use it.")
    }
}
