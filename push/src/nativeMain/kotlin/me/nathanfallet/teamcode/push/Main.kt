package me.nathanfallet.teamcode.push

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import me.nathanfallet.flareon.core.credentials.GoogleCredentials
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String): String? = getenv(name)?.toKString()?.trim()?.takeIf { it.isNotEmpty() }

/**
 * Configuration, from the environment of the Cloud Run service: `PORT` (set by Cloud Run), `TEAMCODE_PUSH_PROJECT_ID`
 * (default: the project of the service account), `TEAMCODE_PUSH_BLOCKED_INSTANCES` (instance ids cut off, comma
 * separated) and the per-minute quotas `TEAMCODE_PUSH_LIMIT_INSTANCE`, `TEAMCODE_PUSH_LIMIT_TOKEN`, `TEAMCODE_PUSH_LIMIT_TOTAL`.
 * Credentials come from the metadata server: no key file.
 */
fun main() {
    val port = env("PORT")?.toIntOrNull() ?: 8080
    val defaults = RelayLimits()
    val limits = RelayLimits(
        env("TEAMCODE_PUSH_LIMIT_INSTANCE")?.toIntOrNull() ?: defaults.perInstance,
        env("TEAMCODE_PUSH_LIMIT_TOKEN")?.toIntOrNull() ?: defaults.perToken,
        env("TEAMCODE_PUSH_LIMIT_TOTAL")?.toIntOrNull() ?: defaults.total,
    )
    val blocked = env("TEAMCODE_PUSH_BLOCKED_INSTANCES")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    val fcm = FirebaseFcm(GoogleCredentials.fromMetadataServer(), projectId = env("TEAMCODE_PUSH_PROJECT_ID"))
    println("TeamCode Push relay on port $port, ${blocked.size} blocked instances.")
    embeddedServer(CIO, port = port) { relayModule(Relay(fcm, blocked, limits)) }.start(wait = true)
}
