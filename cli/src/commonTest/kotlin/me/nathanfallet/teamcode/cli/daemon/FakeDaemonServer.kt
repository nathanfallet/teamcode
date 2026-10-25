package me.nathanfallet.teamcode.cli.daemon

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.platform.DetachedProcess
import me.nathanfallet.teamcode.client.TeamCodeClient
import okio.FileSystem
import okio.Path
import kotlin.random.Random

/**
 * The server side of the daemon channel, in memory: sessions with their phase, desired state, epoch and host presence,
 * the device's published folders, and the daemon's presence. Runs on the test's dispatcher, so the daemon, its fake
 * hosts and this server share one thread.
 */
internal class FakeDaemonServer(private val dispatcher: CoroutineDispatcher, private val daemonCapability: Boolean = true) {
    class Session(
        val id: String, val folderId: String?, val directoryName: String, val agent: String = "claude", val permissionMode: String = "ask",
        var state: String = "creating", var phase: String? = "waiting_host", var desiredState: String = "running",
        var epoch: Int = 0, var online: Boolean = false, var failure: CloudProblemView? = null, val resume: String? = null,
    )

    val sessions = linkedMapOf<String, Session>()
    val events = mutableListOf<String>()
    var folders: List<DeviceFolderView> = emptyList()
    var daemonOnline = false
    val versions = mutableListOf<String?>()
    var createdRequests = mutableListOf<CreateCloudSessionRequest>()
    val features = mutableListOf<List<String>>()
    /** What the device list says the daemon announced, instead of its last poll's (an older daemon: an empty list). */
    var deviceFeatures: List<String>? = null
    /** Members waiting for a folder's conversations, handed to the next poll; [answers] holds what the daemon sent back. */
    val listings = mutableListOf<DaemonListingView>()
    val answers = linkedMapOf<String, DaemonListingAnswerRequest>()

    fun queue(id: String, folderId: String, directoryName: String, agent: String = "claude", permissionMode: String = "ask", resume: String? = null) {
        sessions[id] = Session(id, folderId, directoryName, agent, permissionMode, resume = resume)
    }

    private fun queued() = sessions.values.filter { it.folderId != null && it.phase == "waiting_host" && it.desiredState == "running" && it.state != "terminated" }

    fun view(session: Session) = CloudSessionView(
        id = session.id, name = session.directoryName, ownerId = "m1", createdBy = CloudAttributionView("m1", "c1", "m1"), state = session.state,
        phase = session.phase, desiredState = session.desiredState, generation = 1, currentEpoch = session.epoch, profile = CloudProfileRef("remote", 0),
        image = CloudImageView("remote"), agent = CloudAgentView(session.agent), activity = CloudActivityView(0, 60), resume = CloudResumeView("kept", "kept"),
        workspaceDeletion = CloudWorkspaceDeletionView("not_applicable"), createdAt = 0, updatedAt = 0, kind = CloudSessionKinds.REMOTE,
        failure = session.failure,
        host = CloudSessionHostView("d1", "Laptop", "macos", "15.1", session.directoryName, session.online, permissionMode = session.permissionMode, folderId = session.folderId),
    )

    /** What a host does once its session should pause: it checkpoints and exits. */
    fun checkpoint(id: String) {
        val session = sessions.getValue(id)
        session.state = "paused"; session.phase = null; session.online = false
        events += "checkpoint $id"
    }

    /** What the server does 90 s after a ready session's host went silent. */
    fun loseHost(id: String) {
        val session = sessions.getValue(id)
        session.state = "paused"; session.desiredState = "paused"; session.phase = null; session.online = false
        session.failure = CloudProblemView("host_offline", "The host stopped answering.", true)
    }

    fun client(): TeamCodeClient = TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
        HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = this@FakeDaemonServer.dispatcher; addHandler { request ->
            val path = request.url.encodedPath
            val body = request.body.toByteArray().decodeToString()
            val (status, text) = handle(request.method, path, body)
            respond(text, status, headersOf(HttpHeaders.ContentType, "application/json"))
        } })) { configure(this) }
    }

    private inline fun <reified T> ok(value: T) = HttpStatusCode.OK to Serialization.json.encodeToString(value)
    private fun error(status: HttpStatusCode, type: String) = status to """{"error":{"type":"$type","message":"$type"}}"""

    private suspend fun handle(method: HttpMethod, path: String, body: String): Pair<HttpStatusCode, String> {
        val sessionPath = Regex("/api/v1/cloud/sessions/([a-z0-9-]+)(/[a-z]+)?").matchEntire(path)
        return when {
            path == "/api/v1/capabilities" -> ok(Capabilities(serverVersion = "1.0.0", mode = "team", features = listOf("cloud.sessions.remote.v1") + if (daemonCapability) listOf(DAEMON_CAPABILITY) else emptyList()))
            path == "/api/v1/devices" -> ok(listOf(DeviceView("d1", "m1", "Laptop", 0, 0, 0, current = true,
                daemon = if (daemonOnline) DaemonPresenceView(true, 0, versions.lastOrNull(), deviceFeatures ?: features.lastOrNull().orEmpty()) else null)))
            path == "/api/v1/devices/current/daemon/poll" -> {
                val request = Serialization.json.decodeFromString<DaemonPollRequest>(body)
                versions += request.version
                features += request.features
                daemonOnline = true
                if (queued().isEmpty() && listings.isEmpty()) delay(minOf(request.waitMillis, 50))
                val orders = queued().map { DaemonOrderView(it.id, it.folderId!!, it.directoryName, it.agent, it.permissionMode, it.state, 0, it.resume) }
                val asked = listings.toList().also { listings.clear() }
                ok(DaemonPollView(orders, 60_000, 20_000, asked))
            }
            path.startsWith("/api/v1/devices/current/daemon/listings/") -> {
                answers[path.removePrefix("/api/v1/devices/current/daemon/listings/")] = Serialization.json.decodeFromString<DaemonListingAnswerRequest>(body)
                HttpStatusCode.NoContent to ""
            }
            path == "/api/v1/devices/current/daemon" && method == HttpMethod.Delete -> { daemonOnline = false; events += "offline"; HttpStatusCode.NoContent to "" }
            path == "/api/v1/devices/current/folders" -> {
                folders = Serialization.json.decodeFromString<PublishDeviceFoldersRequest>(body).folders
                events += "publish ${folders.joinToString(",") { it.id }}"
                ok(folders)
            }
            path.startsWith("/api/v1/devices/current/daemon/sessions/") && path.endsWith("/refuse") -> {
                val id = path.removePrefix("/api/v1/devices/current/daemon/sessions/").removeSuffix("/refuse")
                val reason = Serialization.json.decodeFromString<RefuseDaemonOrderRequest>(body).reason
                val session = sessions.getValue(id)
                session.state = "terminated"; session.desiredState = "terminated"; session.phase = null
                session.failure = CloudProblemView("host_refused", "The device refused to start this session ($reason).")
                events += "refuse $id $reason"
                ok(view(session))
            }
            path == "/api/v1/cloud/sessions" && method == HttpMethod.Post -> {
                val request = Serialization.json.decodeFromString<CreateCloudSessionRequest>(body)
                createdRequests += request
                val folder = folders.firstOrNull { it.id == request.folderId } ?: return error(HttpStatusCode.UnprocessableEntity, "folder_not_published")
                val id = "5e55i0n-" + Random.nextInt(1000, 9999)
                queue(id, folder.id, folder.name, request.agent?.kind ?: "claude", request.permissionMode ?: "ask", resume = request.resumeSessionId)
                events += "create $id ${request.deviceId} ${request.folderId}"
                ok(view(sessions.getValue(id)))
            }
            sessionPath != null -> {
                val session = sessions[sessionPath.groupValues[1]] ?: return error(HttpStatusCode.NotFound, "session_not_found")
                when (sessionPath.groupValues[2]) {
                    "" -> ok(view(session))
                    "/host" -> {
                        session.epoch++; session.phase = "starting_host"
                        events += "host ${session.id} ${session.epoch}"
                        ok(CloudHostView("t1", session.epoch, view(session)))
                    }
                    "/pause" -> { session.desiredState = "paused"; events += "pause ${session.id}"; ok(view(session)) }
                    "/resume" -> {
                        session.desiredState = "running"; session.state = "resuming"; session.phase = "waiting_host"; session.failure = null
                        events += "resume ${session.id}"
                        ok(view(session))
                    }
                    else -> error(HttpStatusCode.NotFound, "not_found")
                }
            }
            else -> error(HttpStatusCode.NotFound, "not_found")
        }
    }

    /**
     * Hosts that behave like `teamcode remote host`: online once started, they checkpoint and exit 0 when their session
     * should pause, exit 1 when [crash] names them, and hang when [hang] does. [started] lists what the daemon started.
     */
    inner class Hosts : HostLauncher {
        val started = mutableListOf<Triple<String, Int, String>>()
        val crash = mutableSetOf<String>()
        /** Hosts that stop answering without exiting: alive until a newer epoch fences them. */
        val hang = mutableSetOf<String>()
        override fun start(sessionId: String, epoch: Int, teamId: String, log: String): DetachedProcess {
            started += Triple(sessionId, epoch, teamId)
            val session = sessions.getValue(sessionId)
            session.online = true; session.state = "ready"; session.phase = null
            return object : DetachedProcess {
                var exit: Int? = null
                override fun exitCode(): Int? {
                    exit?.let { return it }
                    if (session.epoch != epoch) return 0.also { exit = it }
                    if (sessionId in hang) return null
                    if (sessionId in crash) { crash -= sessionId; session.online = false; return 1.also { exit = it } }
                    if (session.desiredState != "running") { checkpoint(sessionId); return 0.also { exit = it } }
                    return null
                }
            }
        }
    }
}

/** A private scratch directory with a fake home inside, removed afterwards. Paths are canonical (macOS's /private/var). */
internal inline fun <T> scratch(block: (root: Path, home: Path) -> T): T {
    val base = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "teamcode-daemon-${Random.nextLong().toULong()}"
    FileSystem.SYSTEM.createDirectories(base / "home")
    try {
        val root = FileSystem.SYSTEM.canonicalize(base)
        return block(root, root / "home")
    } finally { FileSystem.SYSTEM.deleteRecursively(base, mustExist = false) }
}
