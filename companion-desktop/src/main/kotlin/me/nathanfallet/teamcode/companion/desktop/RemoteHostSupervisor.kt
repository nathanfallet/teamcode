package me.nathanfallet.teamcode.companion.desktop

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.nathanfallet.teamcode.api.models.CloudSessionKinds
import me.nathanfallet.teamcode.api.models.CloudSessionView
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.companion.domain.models.Account
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.repositories.SessionRepository
import me.nathanfallet.teamcode.companion.domain.services.Clock
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.config.configSibling

/**
 * Keeps the hosts of this device's remote sessions running while the app is open (in the window or the menu bar):
 * - a session woken by a message (paused, wanted running: `cloud.session.woken`) gets its host at once;
 * - a session wanted running whose host stopped answering for [graceMillis] (the host died) gets a new one.
 * Either way the app runs `teamcode remote resume SESSION --detach`, at most [maxStarts] times per session in
 * [windowMillis], then says so in a notification. The server stays the judge: a second host takes a new epoch and
 * fences the first. Only sessions of the CLI's device that this device recorded (`remote/<id>.json`) are touched,
 * and the app never signals a host: stopping one goes through the server's pause.
 */
class RemoteHostSupervisor(
    private val accounts: AccountRepository,
    private val clients: TeamCodeClientProvider,
    private val features: FeaturesRepository,
    private val sessions: SessionRepository,
    private val locator: CliLocator,
    private val processes: CliProcesses,
    private val clock: Clock,
    private val appVersion: String,
    private val recordsDirectory: String,
    private val pollMillis: Long = 15_000,
    private val graceMillis: Long = 30_000,
    /** A host started this long ago may still be registering (`remote resume --detach` alone waits up to 30 s). */
    private val startGraceMillis: Long = 90_000,
    private val maxStarts: Int = 3,
    private val windowMillis: Long = 600_000,
) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** What the app says when a host keeps stopping: shown as a notification of the menu bar icon. */
    val messages: SharedFlow<String> = notices.asSharedFlow()

    private val offlineSince = mutableMapOf<String, Long>()
    private val starts = mutableMapOf<String, MutableList<Long>>()
    private val reported = mutableSetOf<String>()
    private var device: Pair<String, String>? = null

    /** The epoch each session had when this app last started its host: a newer one means that host registered. */
    private val startEpochs = mutableMapOf<String, Int>()

    /**
     * A host this app just started, for a session at [epoch] (from "On this device", or a resume): not started again
     * while it registers, that is until the session reaches a newer epoch or [startGraceMillis] passes.
     */
    fun started(sessionId: String, epoch: Int) = synchronized(starts) {
        starts.getOrPut(sessionId) { mutableListOf() }.add(clock.nowMillis())
        startEpochs[sessionId] = epoch
    }

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        launch { followFeed() }
        while (isActive) {
            try { reconcile() } catch (error: CancellationException) { throw error } catch (_: Exception) { /* Offline: the next tick tries again. */ }
            withTimeoutOrNull(pollMillis) { wake.receive() }
        }
    }

    /** A change of a remote session in the feed checks at once instead of at the next tick. */
    private suspend fun followFeed() {
        while (true) {
            val account = accounts.accounts.value.firstOrNull { it.usedByCli && it.signedIn }
            if (account == null) { delay(pollMillis); continue }
            try {
                sessions.sessionChanges(account.id, setOf(SessionKind.REMOTE)).collect { update ->
                    if (update is LiveUpdate.Item || update is LiveUpdate.Gap) wake.trySend(Unit)
                }
            } catch (error: CancellationException) { throw error } catch (_: Exception) { }
            delay(pollMillis)
        }
    }

    /** One pass: which sessions of this device need a host, and starting it for each. */
    suspend fun reconcile() {
        val account = accounts.accounts.value.firstOrNull { it.usedByCli && it.signedIn } ?: return
        if (Features.REMOTE_SESSIONS !in features.features(account.id)) return
        val client = clients.forAccount(Account.CLI_ACCOUNT_ID, account.origin)
        val deviceId = deviceId(client, account) ?: return
        val listed = client.cloudSessions(states = WANTED_STATES, limit = 50, kinds = listOf(CloudSessionKinds.REMOTE)).sessions
        val now = clock.nowMillis()
        val due = synchronized(starts) { hostsToStart(listed, deviceId, ::recorded, now) }
        if (due.isEmpty()) return
        val cli = locator.find()?.takeIf { CliLocator.compatible(it.version, appVersion) } ?: return
        for (session in due) {
            started(session.id, session.currentEpoch)
            val result = processes.run(cli.executable, listOf("remote", "resume", session.id, "--detach"), RESUME_TIMEOUT_MILLIS)
            if (result.exitCode != 0 && result.exitCode != null) notices.tryEmit("${session.name}: ${DesktopLocalHost.explanation(result)}")
        }
    }

    /**
     * The sessions to start a host for now; also forgets what no longer applies. A session whose host this app started
     * [maxStarts] times in [windowMillis] is reported once and left alone until the window passes.
     */
    internal fun hostsToStart(listed: List<CloudSessionView>, deviceId: String, recorded: (String) -> Boolean, now: Long): List<CloudSessionView> {
        val candidates = listed.filter {
            it.kind == CloudSessionKinds.REMOTE && it.host?.deviceId == deviceId && it.desiredState == "running" && it.host?.online != true &&
                it.state !in ENDED_STATES && recorded(it.id)
        }
        offlineSince.keys.retainAll(candidates.map { it.id }.toSet())
        starts.values.forEach { times -> times.removeAll { now - it >= windowMillis } }
        starts.entries.removeAll { it.value.isEmpty() }
        reported.retainAll(starts.keys)
        startEpochs.keys.retainAll(starts.keys)
        return candidates.filter { session ->
            val times = starts[session.id].orEmpty()
            // A host started a moment ago that has not taken the session yet (same epoch) is still registering.
            val registering = (startEpochs[session.id]?.let { session.currentEpoch <= it } ?: false) && times.any { now - it < startGraceMillis }
            if (registering) return@filter false
            if (times.size >= maxStarts) {
                if (reported.add(session.id)) notices.tryEmit("${session.name}: its host stopped $maxStarts times in ${windowMillis / 60_000} minutes. Resume it with teamcode remote resume ${session.id}.")
                return@filter false
            }
            // Woken (paused, wanted running): nobody hosts it, start at once. Running without its host: after the grace.
            if (session.state == "paused") true
            else now - offlineSince.getOrPut(session.id) { now } >= graceMillis
        }
    }

    private fun recorded(sessionId: String) = Regex("[a-zA-Z0-9-]{1,36}").matches(sessionId) && File(recordsDirectory, "$sessionId.json").isFile

    /** The CLI login's device, read once per server and member. */
    private suspend fun deviceId(client: TeamCodeClient, account: Account): String? {
        val key = "${account.origin} ${account.memberId}"
        device?.takeIf { it.first == key }?.let { return it.second }
        val id = client.devices().firstOrNull { it.current }?.id ?: return null
        device = key to id
        return id
    }

    companion object {
        private val ENDED_STATES = setOf("terminated", "failed")
        private val WANTED_STATES = listOf("creating", "preparing", "ready", "active", "pausing", "resuming", "paused")
        private const val RESUME_TIMEOUT_MILLIS = 90_000L

        /** Where the CLI records this device's remote sessions: `remote/` beside `config.json`. */
        fun recordsDirectory(configPath: String) = configSibling(configPath, "remote")
    }
}
