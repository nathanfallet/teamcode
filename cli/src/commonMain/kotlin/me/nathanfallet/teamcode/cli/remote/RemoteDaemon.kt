package me.nathanfallet.teamcode.cli.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.uuid.Uuid
import me.nathanfallet.teamcode.api.models.CloudSessionView
import me.nathanfallet.teamcode.api.models.CreateCloudSessionRequest
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.daemon.DAEMON_CAPABILITY
import me.nathanfallet.teamcode.cli.daemon.FolderStore
import me.nathanfallet.teamcode.cli.daemon.publishFolders
import me.nathanfallet.teamcode.cli.daemon.refuseFolder
import me.nathanfallet.teamcode.client.TeamCodeClient
import okio.Path

/**
 * This device's id when its daemon is online and the server starts sessions on devices (decision 8: `teamcode remote
 * start` then goes through the daemon); null otherwise, and the command hosts the session itself as before.
 */
internal suspend fun daemonDevice(client: TeamCodeClient, features: List<String>, windows: Boolean): String? {
    if (windows || DAEMON_CAPABILITY !in features) return null
    return client.devices().firstOrNull { it.current }?.takeIf { it.daemon?.online == true }?.id
}

/**
 * Starts [request] in [directory] on [device] through its daemon: the directory becomes one of the device's folders if
 * it was not (an explicit choice of its owner, in a terminal of the device, under the same rules as `teamcode folders
 * add`), the folders are published, and the session is created with the device and the folder. The daemon then hosts
 * it from its queue; its local record is written here as well, as `remote start` always did.
 */
internal suspend fun startThroughDaemon(client: TeamCodeClient, device: String, directory: Path, request: CreateCloudSessionRequest, agent: String,
    home: Path?, windows: Boolean, searchPath: String?, report: (String) -> Unit,
    folders: FolderStore = FolderStore(), sessions: RemoteSessions = RemoteSessions(), idempotencyKey: String = Uuid.random().toString()): CloudSessionView {
    refuseFolder(directory, home, windows)
    val (folder, added) = folders.add(directory, searchPath)
    if (added) report("Added $directory to this device's folders, where your other devices may start sessions too (teamcode folders remove ${folder.id} removes it).")
    publishFolders(client, folders.read(), home)
    val session = client.createCloudSession(request.copy(directoryName = null, deviceId = device, folderId = folder.id), idempotencyKey)
    sessions.save(RemoteSessionRecord(session.id, directory.toString(), agent))
    return session
}

/**
 * Waits until the daemon hosts [sessionId] (an epoch after [previousEpoch], online), at most [timeoutMillis]. A session
 * the daemon refused is an error carrying the server's reason; one not taken in time is reported as still starting.
 */
internal suspend fun awaitDaemonHost(client: TeamCodeClient, sessionId: String, previousEpoch: Int, report: (String) -> Unit,
    pollMillis: Long = 1_000, timeoutMillis: Long = 30_000) {
    var waited = 0L
    while (waited < timeoutMillis) {
        val session = try { client.cloudSession(sessionId) } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        if (session != null && hosted(session, previousEpoch)) { report("This device's daemon hosts it."); return }
        if (session != null && (session.state == "terminated" || session.desiredState == "terminated"))
            throw CliUserException("This device's daemon did not start session $sessionId" + (session.failure?.message?.let { ": $it" } ?: "."))
        delay(pollMillis); waited += pollMillis
    }
    report("This device's daemon has not taken the session yet; it hosts it as soon as it does (teamcode remote list).")
}
