package me.nathanfallet.teamcode.cli.proxy

import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.logging.Logger
import me.nathanfallet.teamcode.client.TeamCodeClient

/** Agent stdout belongs to the agent. Framework exceptions must not print credentials or response bodies. */
internal fun proxyServer(client: TeamCodeClient, token: String, port: Int, serviceId: String? = null, serverUrl: String? = null,
    upstreamReady: (suspend () -> Boolean)? = null, report: (String) -> Unit) = embeddedServer(
    CIO,
    environment = applicationEnvironment {
        log = object : Logger by KtorSimpleLogger("TeamCode proxy") {
            override fun error(message: String) = report("TeamCode local proxy request failed. Check server connectivity and authentication.")
            override fun error(message: String, cause: Throwable) = error(message)
            override fun warn(message: String) = report("TeamCode local proxy warning. Check server connectivity.")
            override fun warn(message: String, cause: Throwable) = warn(message)
            override fun info(message: String) = Unit
            override fun info(message: String, cause: Throwable) = Unit
            override fun debug(message: String) = Unit
            override fun debug(message: String, cause: Throwable) = Unit
            override fun trace(message: String) = Unit
            override fun trace(message: String, cause: Throwable) = Unit
        }
    },
    configure = {
        reuseAddress = true // Rebind after a drained restart while old TCP connections leave TIME_WAIT.
        connectors.add(EngineConnectorBuilder().also { it.host = "127.0.0.1"; it.port = port })
    },
) { localProxy(client, token, serviceId = serviceId, serverUrl = serverUrl, upstreamReady = upstreamReady) }
