package me.nathanfallet.teamcode.mcp

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Where the host's events go: one JSON line each (started, exited, killed, oversize, backoff, start_failed). Events
 * carry the shortened key, the launcher and the package, never arguments, environment, input, output or stderr.
 */
fun interface EventSink {
    fun emit(event: JsonObject)

    companion object {
        private val logger = LoggerFactory.getLogger("teamcode.mcp.events")
        val LOG = EventSink { logger.info(it.toString()) }
    }
}

/** Resident memory of a process tree, or null when the platform gives no reading. */
fun interface MemoryProbe {
    fun rssBytes(pids: List<Long>): Long?

    companion object {
        /** `/proc/<pid>/status` (VmRSS) on Linux, `ps -o rss=` elsewhere (development machines). */
        val SYSTEM = MemoryProbe { pids ->
            if (pids.isEmpty()) null
            else if (Files.isDirectory(Path.of("/proc/self"))) pids.sumOf { procRss(it) }
            else psRss(pids)
        }

        private fun procRss(pid: Long): Long = try {
            Files.readAllLines(Path.of("/proc/$pid/status")).firstOrNull { it.startsWith("VmRSS:") }
                ?.removePrefix("VmRSS:")?.trim()?.removeSuffix("kB")?.trim()?.toLongOrNull()?.times(1024) ?: 0
        } catch (_: java.io.IOException) { 0 }

        private fun psRss(pids: List<Long>): Long? = try {
            val process = ProcessBuilder("ps", "-o", "rss=", "-p", pids.joinToString(",")).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); null }
            else output.lines().mapNotNull { it.trim().toLongOrNull() }.sum() * 1024
        } catch (_: java.io.IOException) { null }
    }
}
