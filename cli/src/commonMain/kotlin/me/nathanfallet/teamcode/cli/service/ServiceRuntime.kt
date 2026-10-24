package me.nathanfallet.teamcode.cli.service

import io.ktor.client.HttpClient
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.config.configStore
import me.nathanfallet.teamcode.cli.platform.*
import me.nathanfallet.teamcode.cli.proxy.proxyServer
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.time.Clock
import kotlin.time.TimeSource
import okio.FileSystem
import okio.Path.Companion.toPath

internal fun stopRequested(settings: ServiceSettings): Boolean {
    if (serviceStopRequested()) return true
    if (settings.os != "windows") return false
    val path = serviceStopPath(settings.config).toPath()
    val metadata = FileSystem.SYSTEM.metadataOrNull(path) ?: return false
    require(metadata.isRegularFile && metadata.symlinkTarget == null && (metadata.size ?: 0) <= 64)
    return FileSystem.SYSTEM.read(path) { readUtf8() } == settings.id
}

/**
 * A capability alone is insufficient: verify the installation ID and fixed upstream origin. The probe does not send the
 * local token: whoever listens on the fixed port (another local user while the service is down, say) gets a fresh
 * challenge, and only the service, which holds the token, can answer it with a proof of its installation ID and
 * origin ([serviceProof], audit L9).
 *
 * A service older than the challenge answers it with `401`. That counts as not running, except while waiting for a
 * release older than this binary ([expectedVersion]: a rollback or a recovery to an older release), where the probe
 * asks again with the token as before, since an older service has no other way to answer. A newer or unparsable
 * version never gets the token: a release from now on answers the challenge.
 */
internal suspend fun serviceResponds(settings: ServiceSettings, origin: String, backgroundWorkload: Boolean = false, expectedVersion: String? = null, runRouting: Boolean = false, mcpRelay: Boolean = false, localTokenHeader: Boolean = false, artifactRelay: Boolean = false, activityRelay: Boolean = false): Boolean = try {
    HttpClient { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 2_000; connectTimeoutMillis = 1_000 } }.use { http ->
        suspend fun identity(challenge: String?): Pair<Int, JsonObject?> =
            http.prepareGet("http://127.0.0.1:${settings.port}/_teamcode/service") {
                if (challenge != null) header(me.nathanfallet.teamcode.cli.proxy.SERVICE_CHALLENGE_HEADER, challenge) else bearerAuth(settings.localToken)
            }.execute { response ->
                if (response.status.value != 200 || (response.contentLength() ?: 0) > 4096) return@execute response.status.value to null
                // Bounded body even when the occupied port belongs to another local application.
                val channel = response.bodyAsChannel()
                val bytes = ByteArray(4097)
                var size = 0
                while (size < bytes.size) {
                    val read = channel.readAvailable(bytes, size, bytes.size - size)
                    if (read < 0) break
                    size += read
                }
                if (size > 4096) return@execute 200 to null
                200 to Json.parseToJsonElement(bytes.decodeToString(0, size)).jsonObject
            }
        val challenge = secureRandomBytes(32).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val (status, answer) = identity(challenge)
        val body = when {
            answer != null -> answer.takeIf { it["proof"]?.jsonPrimitive?.content == me.nathanfallet.teamcode.cli.proxy.serviceProof(settings.localToken, challenge, settings.id, origin) }
                // The proof stands for the installation ID and the origin.
                ?.let { JsonObject(it + mapOf("id" to JsonPrimitive(settings.id), "serverUrl" to JsonPrimitive(origin))) }
            status == 401 && expectedVersion != null && olderRelease(expectedVersion, me.nathanfallet.teamcode.cli.VERSION) -> identity(null).second
            else -> null
        } ?: return@use false
        serviceMatches(body, settings.id, origin, backgroundWorkload, runRouting, mcpRelay, localTokenHeader, artifactRelay, activityRelay) && (expectedVersion == null || body["version"]?.jsonPrimitive?.content == expectedVersion)
    }
} catch (error: CancellationException) { throw error } catch (_: Exception) { false }

/**
 * Whether [candidate] is a strictly older `major.minor.patch` release than [current]; a pre-release is older than its
 * release (`1.2.3-rc1` before `1.2.3`). `false` when either does not parse: the build enforces that format for
 * [me.nathanfallet.teamcode.cli.VERSION], and an unparsable awaited version gets no token.
 */
internal fun olderRelease(candidate: String, current: String): Boolean {
    fun parse(version: String): List<Int>? = version.removePrefix("v").substringBefore('+').substringBefore('-').split('.')
        .takeIf { it.size == 3 }?.map { part -> part.takeIf { it.isNotEmpty() && it.length <= 9 && it.all(Char::isDigit) }?.toInt() ?: return null }
    val (older, newer) = (parse(candidate) ?: return false) to (parse(current) ?: return false)
    val difference = older.zip(newer).firstOrNull { (a, b) -> a != b }
        ?: return '-' in candidate.substringBefore('+') && '-' !in current.substringBefore('+')
    return difference.first < difference.second
}

internal fun serviceMatches(body: JsonObject, id: String, origin: String, backgroundWorkload: Boolean, runRouting: Boolean = false, mcpRelay: Boolean = false, localTokenHeader: Boolean = false, artifactRelay: Boolean = false, activityRelay: Boolean = false): Boolean {
    val capabilities = body["capabilities"] as? JsonArray
    return body["id"]?.jsonPrimitive?.content == id && body["serverUrl"]?.jsonPrimitive?.content == origin &&
        (!backgroundWorkload || capabilities?.contains(JsonPrimitive("workload-routing-v1")) == true) &&
        (!runRouting || capabilities?.contains(JsonPrimitive("run-routing-v1")) == true) &&
        (!mcpRelay || capabilities?.contains(JsonPrimitive("mcp-relay-v1")) == true) &&
        (!localTokenHeader || capabilities?.contains(JsonPrimitive("local-token-header-v1")) == true) &&
        (!artifactRelay || capabilities?.contains(JsonPrimitive("artifact-relay-v1")) == true) &&
        (!activityRelay || capabilities?.contains(JsonPrimitive("activity-relay-v1")) == true)
}

/**
 * The local service: the loopback proxy and the device's daemon ([runDaemon]). One per configuration: `daemon.lock` is
 * held for the whole run, and a second instance exits before writing anything (not even the log, which it would replace).
 * On stop, the daemon pauses the sessions it hosts and goes offline first, then the proxy drains its requests.
 */
internal suspend fun serve(settings: ServiceSettings) = coroutineScope {
    installServiceSignalHandlers()
    val instance = holdExclusiveLock(me.nathanfallet.teamcode.cli.daemon.daemonLockPath(settings.config))
        ?: throw com.github.ajalt.clikt.core.CliktError("Another TeamCode service already runs for this configuration.")
    try { serveLocked(settings) } finally { instance.close() }
}

private suspend fun serveLocked(settings: ServiceSettings) = coroutineScope {
    val logs = Channel<String>(64)
    val writer = launch {
        val lines = ArrayDeque<String>()
        for (message in logs) {
            lines.addLast("${Clock.System.now()} ${message.take(240)}")
            while (lines.size > 128) lines.removeFirst()
            writePrivateFile(serviceLogPath(settings.config), lines.joinToString("\n", postfix = "\n"))
        }
    }
    fun report(message: String) { logs.trySend(message) }
    try {
        val store = configStore(settings.config)
        var config = store.load()
        if (config == null) report("Waiting for saved authentication. Run teamcode login; no browser is opened by the service.")
        while (config == null && !stopRequested(settings)) { delay(1_000); config = store.load() }
        if (config == null || stopRequested(settings)) return@coroutineScope
        val origin = config.serverUrl
        val client = TeamCodeClient(origin, {
            store.load()?.let { latest ->
                check(latest.serverUrl == origin) { "Restart the service after changing the server URL." }
                latest.accessToken
            }
        }) { configure -> HttpClient { configure(this) } }
        val server = proxyServer(client, settings.localToken, settings.port, settings.id, origin, report = ::report)
        var updates: Job? = null
        var daemon: Job? = null
        var drainMillis = 600_000L
        try {
            server.start(wait = false)
            // Ktor's Native startup registers its own signal handler. The service owns both
            // the proxy lifetime and the outer command loop, so install our handler last.
            installServiceSignalHandlers()
            report("Local proxy is listening. Upstream connectivity and authentication are checked on each request.")
            updates = launch { me.nathanfallet.teamcode.cli.update.watchReleaseUpdates(::report) }
            daemon = launch {
                try { me.nathanfallet.teamcode.cli.daemon.runDaemon(client, settings.config, settings.executable, ::report) { stopRequested(settings) } }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { report("The daemon stopped after an unexpected error; the local proxy keeps running.") }
            }
            while (!stopRequested(settings)) delay(250)
            // The daemon pauses its sessions and goes offline first (bounded), then the proxy drains in the time left.
            val stopping = TimeSource.Monotonic.markNow()
            daemon.join()
            drainMillis = (600_000L - stopping.elapsedNow().inWholeMilliseconds).coerceAtLeast(10_000L)
            report("Stopping: draining existing requests for up to ${drainMillis / 1_000} seconds.")
        } finally {
            withContext(NonCancellable) { daemon?.cancelAndJoin(); updates?.cancelAndJoin(); server.stop(drainMillis, drainMillis + 10_000); client.close() }
        }
        report("Local proxy stopped.")
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        report("Service failed. Check the local port, saved configuration and file permissions.")
        throw error
    } finally {
        logs.close()
        withContext(NonCancellable) { writer.join() }
    }
}
