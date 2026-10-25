package me.nathanfallet.teamcode.cli.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.uuid.Uuid
import me.nathanfallet.teamcode.api.models.CloudSessionView
import me.nathanfallet.teamcode.api.models.CreateCloudSessionRequest
import me.nathanfallet.teamcode.api.models.DaemonFeatures
import me.nathanfallet.teamcode.api.models.LocalResumableSessionsView
import me.nathanfallet.teamcode.api.models.LocalResumeReasons
import me.nathanfallet.teamcode.api.models.ResumableSessions
import me.nathanfallet.teamcode.cli.CliUserException
import me.nathanfallet.teamcode.cli.daemon.ClaudeTranscripts
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

/** Whether this device can start a session that continues one of its own conversations: only through its daemon. */
internal sealed interface LocalResume {
    data class Ready(val deviceId: String) : LocalResume
    /** [reason]: a [LocalResumeReasons] code. */
    data class Unavailable(val reason: String) : LocalResume
}

/**
 * A session continuing a conversation of this device goes through its daemon, never through a host the command starts
 * itself: the daemon checks the conversation against the folder and has its host fork it, the code of
 * `cloud.sessions.remote.resume.v1`. So the server must announce it, the daemon must host sessions here (not on
 * Windows), be online, and have announced [DaemonFeatures.RESUME].
 */
internal suspend fun localResumeTarget(client: TeamCodeClient, features: List<String>, windows: Boolean): LocalResume {
    if (DAEMON_CAPABILITY !in features || ResumableSessions.CAPABILITY !in features) return LocalResume.Unavailable(LocalResumeReasons.SERVER_UNSUPPORTED)
    if (windows) return LocalResume.Unavailable(LocalResumeReasons.PLATFORM_UNSUPPORTED)
    val device = client.devices().firstOrNull { it.current }
    val daemon = device?.daemon?.takeIf { it.online } ?: return LocalResume.Unavailable(LocalResumeReasons.DAEMON_OFFLINE)
    if (DaemonFeatures.RESUME !in daemon.features) return LocalResume.Unavailable(LocalResumeReasons.DAEMON_TOO_OLD)
    return LocalResume.Ready(device.id)
}

/**
 * `teamcode remote resumable`: [directory]'s Claude Code conversations, read here by the daemon's own [ClaudeTranscripts]
 * (same bounds, titles and `cwd` check) and printed for the desktop app; nothing of them goes to the server. Nothing is
 * read when [target] says this device could not continue one anyway.
 */
internal fun localResumables(transcripts: ClaudeTranscripts, target: LocalResume, directory: Path, limit: Int, before: Long?): LocalResumableSessionsView =
    when (target) {
        is LocalResume.Unavailable -> LocalResumableSessionsView(unavailable = target.reason)
        is LocalResume.Ready -> transcripts.list(directory.toString(), limit, before).let { LocalResumableSessionsView(it.sessions, it.nextBefore) }
    }

/** The sentence `teamcode remote start --resume` and `remote resumable` give for a [LocalResumeReasons] code. */
internal fun localResumeRefusal(reason: String): String = when (reason) {
    LocalResumeReasons.SERVER_UNSUPPORTED -> "This server does not continue a conversation of a device (cloud.sessions.remote.resume.v1): update it."
    LocalResumeReasons.PLATFORM_UNSUPPORTED -> "Continuing a conversation goes through this device's daemon, which does not host sessions on Windows."
    LocalResumeReasons.DAEMON_OFFLINE -> "Continuing a conversation goes through this device's daemon, which is not running: start it with teamcode service install."
    LocalResumeReasons.DAEMON_TOO_OLD -> "This device's daemon is too old to continue a conversation: run teamcode update."
    else -> "This device cannot continue a conversation ($reason)."
}

/**
 * Starts [request] in [directory] on [device] through its daemon: the directory becomes one of the device's folders if
 * it was not (an explicit choice of its owner, in a terminal of the device, under the same rules as `teamcode folders
 * add`), the folders are published, and the session is created with the device and the folder. The daemon then hosts
 * it from its queue; its local record is written here as well, as `remote start` always did.
 */
internal suspend fun startThroughDaemon(client: TeamCodeClient, device: String, directory: Path, request: CreateCloudSessionRequest, agent: String,
    home: Path?, windows: Boolean, searchPath: String?, report: (String) -> Unit,
    folders: FolderStore = FolderStore(), sessions: RemoteSessions = RemoteSessions(), idempotencyKey: String = Uuid.random().toString(),
    resumeSessionId: String? = null, transcripts: ClaudeTranscripts = ClaudeTranscripts.local()): CloudSessionView {
    refuseFolder(directory, home, windows)
    // Checked here first, with the daemon's own reading, so a wrong id adds no folder and creates nothing; the daemon
    // checks it again before it hosts the session.
    if (resumeSessionId != null && !transcripts.belongs(directory.toString(), resumeSessionId))
        throw CliUserException("$resumeSessionId is not one of the Claude Code conversations of $directory on this device (teamcode remote resumable lists them).")
    val (folder, added) = folders.add(directory, searchPath)
    if (added) report("Added $directory to this device's folders, where your other devices may start sessions too (teamcode folders remove ${folder.id} removes it).")
    publishFolders(client, folders.read(), home)
    val session = client.createCloudSession(request.copy(directoryName = null, deviceId = device, folderId = folder.id, resumeSessionId = resumeSessionId), idempotencyKey)
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
