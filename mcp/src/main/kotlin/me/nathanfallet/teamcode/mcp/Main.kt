package me.nathanfallet.teamcode.mcp

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlin.system.exitProcess

fun main() {
    val config = try { HostConfig.fromEnvironment(System.getenv()) } catch (error: IllegalArgumentException) {
        System.err.println("teamcode-mcp: ${error.message}")
        exitProcess(2)
    }
    val supervisor = Supervisor(config)
    embeddedServer(Netty, port = config.port, host = config.host) { mcpHost(config, supervisor) }.start(wait = true)
}
