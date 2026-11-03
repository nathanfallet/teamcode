package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import me.nathanfallet.teamcode.cli.platform.startAgentProcess
import okio.Path
import kotlin.test.*

class AgentProcessTest {
    @Test fun pipesLinesBoundsThemAndRemovesVariables() = runTest {
        withContext(Dispatchers.Default) {
            val script = "echo \"cwd=${'$'}(pwd) secret=${'$'}{TEAMCODE_CLOUD_TOKEN_FILE:-none}\"; while read -r line; do echo \"got:${'$'}line\"; done; exit 3"
            // Windows has no /bin/sh: its own tests below drive findstr, which echoes its input.
            if (Path.DIRECTORY_SEPARATOR == "\\") return@withContext
            val process = startAgentProcess("/bin/sh", listOf("-c", script), mapOf("TEAMCODE_CLOUD_TOKEN_FILE" to null), "/tmp")
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

    /**
     * Windows: PowerShell copies stdin to stdout line by line and flushes each one, like an agent speaking JSON lines
     * (`findstr` and `more` buffer their output when it goes to a pipe, so they cannot stand in for one).
     */
    private val windowsEcho = listOf("-NoProfile", "-NonInteractive", "-Command",
        "while((\$l=[Console]::In.ReadLine()) -ne \$null){[Console]::Out.WriteLine(\$l);[Console]::Out.Flush()}")

    @Test fun windowsPipesLinesAndEndsWhenItsInputCloses() = runTest {
        if (Path.DIRECTORY_SEPARATOR != "\\") return@runTest
        withContext(Dispatchers.Default) {
            withTimeout(45_000) {
                val process = startAgentProcess("powershell", windowsEcho, mapOf("TEAMCODE_CLOUD_TOKEN_FILE" to null), "C:\\Windows")
                assertTrue(process.writeLine("hello"))
                assertEquals("hello", process.readLine())
                assertTrue(process.writeLine("x".repeat(100)))
                assertEquals("x".repeat(10), process.readLine(10), "A long line is cut, never buffered whole")
                assertNull(process.exitCode(), "Still running while its input is open")
                process.closeInput()
                assertEquals(0, process.stop(20_000), "It finishes by itself once its input closes")
                assertNull(process.readLine())
                assertFalse(process.writeLine("late"), "Nothing is written to a stopped process")
            }
        }
    }

    @Test fun windowsStopTerminatesAProcessThatDoesNotExit() = runTest {
        if (Path.DIRECTORY_SEPARATOR != "\\") return@runTest
        withContext(Dispatchers.Default) {
            withTimeout(45_000) {
                val process = startAgentProcess("powershell", listOf("-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 300"), emptyMap(), "C:\\Windows")
                assertNull(process.exitCode())
                // A zero grace period: the input closes, the process does not care, and its job is terminated.
                assertEquals(130, process.stop(0), "Ended by the job's termination, never left running")
                assertNull(process.readLine())
            }
        }
    }
}
