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
import me.nathanfallet.teamcode.cli.config.ConfigStore
import me.nathanfallet.teamcode.cli.platform.*
import me.nathanfallet.teamcode.cli.proxy.proxyServer
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.time.Clock
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

/** A capability alone is insufficient: verify the installation ID and fixed upstream origin. */
internal suspend fun serviceResponds(settings: ServiceSettings, origin: String, backgroundWorkload: Boolean = false, expectedVersion: String? = null, runRouting: Boolean = false, mcpRelay: Boolean = false, localTokenHeader: Boolean = false, artifactRelay: Boolean = false, activityRelay: Boolean = false): Boolean = try {
    HttpClient { followRedirects = false; install(HttpTimeout) { requestTimeoutMillis = 2_000; connectTimeoutMillis = 1_000 } }.use { http ->
        http.prepareGet("http://127.0.0.1:${settings.port}/_teamcode/service") { bearerAuth(settings.localToken) }.execute { response ->
            if (response.status.value != 200 || (response.contentLength() ?: 0) > 4096) return@execute false
            // Bounded body even when the occupied port belongs to another local application.
            val channel = response.bodyAsChannel()
            val bytes = ByteArray(4097)
            var size = 0
            while (size < bytes.size) {
                val read = channel.readAvailable(bytes, size, bytes.size - size)
                if (read < 0) break
                size += read
            }
            if (size > 4096) return@execute false
            val body = Json.parseToJsonElement(bytes.decodeToString(0, size)).jsonObject
            serviceMatches(body, settings.id, origin, backgroundWorkload, runRouting, mcpRelay, localTokenHeader, artifactRelay, activityRelay) && (expectedVersion == null || body["version"]?.jsonPrimitive?.content == expectedVersion)
        }
    }
} catch (error: CancellationException) { throw error } catch (_: Exception) { false }

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

internal suspend fun serve(settings: ServiceSettings) = coroutineScope {
    installServiceSignalHandlers()
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
        val store = ConfigStore(settings.config)
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
        try {
            server.start(wait = false)
            // Ktor's Native startup registers its own signal handler. The service owns both
            // the proxy lifetime and the outer command loop, so install our handler last.
            installServiceSignalHandlers()
            report("Local proxy is listening. Upstream connectivity and authentication are checked on each request.")
            updates = launch { me.nathanfallet.teamcode.cli.update.watchReleaseUpdates(::report) }
            while (!stopRequested(settings)) delay(250)
            report("Stopping: draining existing requests for up to ten minutes.")
        } finally {
            withContext(NonCancellable) { updates?.cancelAndJoin(); server.stop(600_000, 610_000); client.close() }
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
