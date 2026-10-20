package me.nathanfallet.teamcode

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.presentation.ForecastLivePolicy

/** Opt-in browser fixture entry point, never included in the server or native CLI distribution. */
fun main(args: Array<String>) = runBlocking {
    val script = Path.of(args[0]).toAbsolutePath()
    val screenshot = Path.of(args[1]).toAbsolutePath()
    Files.createDirectories(screenshot.parent)
    val log = screenshot.parent.resolve("forecast-browser.log")
    withForecastLiveFixture(ForecastLivePolicy(100, 1200)) { fixture ->
        val process = ProcessBuilder("node", script.toString(), screenshot.toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        try {
            // A private pipe, not process arguments, carries this ephemeral fixture-only session.
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(buildJsonObject {
                    put("originA", fixture.originA); put("originB", fixture.originB)
                    put("token", fixture.session.token); put("accountId", fixture.accountId)
                }.toString())
            }
            check(process.waitFor(90, TimeUnit.SECONDS)) { "Owned browser acceptance timed out; see $log" }
            check(process.exitValue() == 0) { "Browser acceptance failed; see $log" }
            check(Files.size(screenshot) > 1000)
            println("Real browser live-forecast acceptance passed; screenshot: $screenshot")
        } finally {
            if (process.isAlive) {
                process.destroy() // Exact owned Node process; its signal handler closes its own Chrome child.
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
    }
}
