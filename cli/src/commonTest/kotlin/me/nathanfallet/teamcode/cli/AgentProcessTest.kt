package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import me.nathanfallet.teamcode.cli.platform.startAgentProcess
import kotlin.test.*

class AgentProcessTest {
    @Test fun pipesLinesBoundsThemAndRemovesVariables() = runTest {
        withContext(Dispatchers.Default) {
            val script = "echo \"cwd=${'$'}(pwd) secret=${'$'}{TEAMCODE_CLOUD_TOKEN_FILE:-none}\"; while read -r line; do echo \"got:${'$'}line\"; done; exit 3"
            // Windows runners have no agent process support yet.
            val process = try { startAgentProcess("/bin/sh", listOf("-c", script), mapOf("TEAMCODE_CLOUD_TOKEN_FILE" to null), "/tmp") }
                catch (_: UnsupportedOperationException) { return@withContext }
            assertTrue(process.readLine()!!.matches(Regex("cwd=(/private)?/tmp secret=none")))
            assertTrue(process.writeLine("hello"))
            assertEquals("got:hello", process.readLine())
            assertTrue(process.writeLine("x".repeat(100) + "\n" + "next"))
            assertEquals("got:" + "x".repeat(6), process.readLine(10), "A long line is cut, never buffered whole")
            assertEquals("got:next", process.readLine(), "The cut never eats the next line")
            assertEquals(3, process.stop(5_000))
            assertNull(process.readLine())
        }
    }
}
