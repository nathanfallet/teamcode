package me.nathanfallet.teamcode.domain.cloud

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.domain.team.*

/**
 * A remote session waiting for the device's daemon to host it: a session started through the daemon
 * ([CloudSessionHost.folderId] set), on its device, in phase `waiting_host` with desired state `running`. [state] is
 * `creating` for a new session, `resuming` for a woken one, `preparing` when a host was lost before it was ready.
 */
data class DaemonOrder(
    val sessionId: String, val folderId: String, val directoryName: String, val agentKind: String,
    val permissionMode: RemotePermissionMode, val state: CloudSessionState, val requestedAt: Long,
    /** The device's conversation the session continues ([CloudSessionHost.resumeSessionId]). */
    val resumeSessionId: String? = null,
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
    /** Marks the daemon seen at the database clock, with its version and features, unless a poll did within [DeviceDaemonRules.TOUCH_MILLIS]. */
    suspend fun touch(deviceId: String, version: String?, features: List<String>)
    /** Marks the daemon offline at once. */
    suspend fun offline(deviceId: String)
    /** Replaces the device's folders, in their order. */
    suspend fun publish(deviceId: String, folders: List<DeviceFolder>)
    /** [deviceId] if [memberId] of [teamId] owns it, with its presence and folders; null otherwise (whoever asks). */
    suspend fun owned(teamId: String, memberId: String, deviceId: String): OwnedDevice?
    /** The device's queue, oldest request first, at most [limit]. */
    suspend fun orders(deviceId: String, limit: Int): List<DaemonOrder>

    /**
     * Asks [deviceId]'s daemon for a folder's conversations: a request that expires at [expiresAt], removing the
     * device's expired ones first. Returns its id.
     */
    suspend fun askListing(deviceId: String, folderId: String, limit: Int, before: Long?, expiresAt: Long): String
    /** The device's requests not answered nor expired, oldest first, at most [limit]. */
    suspend fun listings(deviceId: String, limit: Int): List<DaemonListing>
    /** Stores the answer to a request of [deviceId] still open; false when there is none (expired, answered, another device's). */
    suspend fun answerListing(deviceId: String, requestId: String, answer: ResumableAnswer): Boolean
    /** The answer to [requestId] once the daemon gave it; null before. */
    suspend fun listingAnswer(requestId: String): ResumableAnswer?
    /** Removes a request, answered or not, once its member stopped waiting. */
    suspend fun dropListing(requestId: String)
}

data class DaemonPoll(val orders: List<DaemonOrder>, val listings: List<DaemonListing> = emptyList())

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
    suspend fun poll(principal: Principal, waitMillis: Long, version: String?, features: List<String> = emptyList()): DaemonPoll {
        DeviceDaemonRules.validateVersion(version)
        DeviceDaemonRules.validateFeatures(features)
        val device = device(principal)
        store.touch(device, version, features)
        // An older daemon cannot answer a listing: its device's requests are never read (and never made, see resumable).
        val lists = DeviceDaemonRules.RESUME_FEATURE in features
        var waited = 0L
        val wait = waitMillis.coerceIn(0, DeviceDaemonRules.MAX_WAIT_MILLIS)
        while (true) {
            val orders = store.orders(device, DeviceDaemonRules.ORDER_BATCH)
            val listings = if (lists) store.listings(device, DeviceDaemonRules.LISTING_BATCH) else emptyList()
            if (orders.isNotEmpty() || listings.isNotEmpty() || waited >= wait) return DaemonPoll(orders, listings)
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

    /**
     * The conversations of [folderId] on one of the caller's own devices that a session can continue, the most recent
     * first (`cloud.sessions.remote.resume.v1`). The server holds no transcript: it asks the device's daemon, which reads
     * them, and waits up to [DeviceDaemonRules.LISTING_WAIT_MILLIS] for its answer, holding no transaction between reads.
     * The request is removed when the wait ends, whatever its outcome, so an answer stays in the database for seconds.
     * Refusals: `device_not_found` (another member's device, an administrator's included), `device_offline`,
     * `daemon_resume_unsupported` (a daemon older than the feature), `folder_not_published`, the daemon's own reason
     * (`folder_missing`…, `422`), and `device_no_answer` (`504`) when nothing came back in time.
     */
    suspend fun resumable(principal: Principal, deviceId: String, folderId: String, limit: Int, before: Long?): ResumableAnswer {
        DeviceDaemonRules.validateListing(limit, before)
        val device = owned(principal, deviceId)
        requireResume(device)
        if (device.folders.none { it.id == folderId }) throw folderNotPublished(device.name)
        val request = store.askListing(device.id, folderId, limit, before, store.now() + DeviceDaemonRules.LISTING_TTL_MILLIS)
        try {
            var waited = 0L
            while (waited < DeviceDaemonRules.LISTING_WAIT_MILLIS) {
                sleep(DeviceDaemonRules.LISTING_POLL_MILLIS)
                waited += DeviceDaemonRules.LISTING_POLL_MILLIS
                val answer = store.listingAnswer(request) ?: continue
                answer.refused?.let { throw TeamFailure(it, 422, "${device.name} cannot read this folder's conversations ($it).") }
                return answer
            }
            throw TeamFailure("device_no_answer", 504, "${device.name} did not answer in time. Try again.", retryable = true)
        } finally { withContext(NonCancellable) { store.dropListing(request) } }
    }

    /** The daemon's answer to a listing of its own device; `404 listing_not_found` once nobody waits for it. */
    suspend fun answer(principal: Principal, requestId: String, answer: ResumableAnswer) {
        DeviceDaemonRules.validateAnswer(answer)
        if (!store.answerListing(device(principal), requestId, answer)) throw TeamFailure("listing_not_found", 404, "Nobody waits for this listing any more.")
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
        fun folderNotPublished(device: String) = TeamFailure("folder_not_published", 422, "$device does not offer this folder. Add it on the device with teamcode folders add.")
        /** The device's daemon is online and lists conversations ([DeviceDaemonRules.RESUME_FEATURE]). */
        fun requireResume(device: OwnedDevice) {
            val daemon = device.daemon
            if (daemon?.online != true)
                throw TeamFailure("device_offline", 409, "${device.name} is offline: its TeamCode daemon is not connected. Start it on the device, or choose another one.")
            if (DeviceDaemonRules.RESUME_FEATURE !in daemon.features)
                throw TeamFailure("daemon_resume_unsupported", 409, "${device.name} runs a TeamCode version that cannot continue a conversation. Update TeamCode on it (teamcode update).")
        }
        private fun daemonRequiresDevice() = TeamFailure("daemon_requires_device", 422,
            "The daemon channel takes the interactive login of the device itself (teamcode login on that device); service keys and browser sessions cannot use it.")
    }
}
