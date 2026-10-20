package me.nathanfallet.teamcode

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/** Opt-in actual native launcher acceptance; fixture credentials travel only through a private pipe. */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 3)
    val output = Path.of(args[2]); Files.createDirectories(output.parent)
    AgentRunHttpFixture().use { fixture ->
        fixture.start()
        val child = ProcessBuilder("python3", args[0], "--binary", args[1])
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            child.outputStream.bufferedWriter().use { input ->
                input.write(buildJsonObject {
                    put("origin", fixture.origins[0]); put("otherOrigin", fixture.origins[1]); put("accessToken", fixture.member.token)
                }.toString()); input.newLine()
            }
            check(child.waitFor(150, TimeUnit.SECONDS)) { "Owned native run acceptance timed out; see its private diagnostic artifact." }
            check(child.exitValue() == 0) { "Native run acceptance failed; see its diagnostic artifact." }
            check(fixture.dispatches.get() == 2) { "The native fixture must make exactly two synthetic requests." }
            println("Actual native run registry acceptance passed: Claude/Codex, quiet heartbeat, shared replica reads, streaming and nonzero exit; no providers called.")
        } finally {
            if (child.isAlive) { child.destroy(); if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) } }
        }
    }
}
