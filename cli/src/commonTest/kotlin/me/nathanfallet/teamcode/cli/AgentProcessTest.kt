package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    /** Windows: `findstr "^"` copies stdin to stdout line by line, like `cat`. */
    @Test fun windowsPipesLinesAndEndsWhenItsInputCloses() = runTest {
        if (Path.DIRECTORY_SEPARATOR != "\\") return@runTest
        withContext(Dispatchers.Default) {
            val process = startAgentProcess("findstr", listOf("^"), mapOf("TEAMCODE_CLOUD_TOKEN_FILE" to null), "C:\\Windows")
            assertTrue(process.writeLine("hello"))
            assertEquals("hello", process.readLine())
            assertTrue(process.writeLine("x".repeat(100)))
            assertEquals("x".repeat(10), process.readLine(10), "A long line is cut, never buffered whole")
            assertNull(process.exitCode(), "Still running while its input is open")
            process.closeInput()
            assertEquals(0, process.stop(5_000), "It finishes by itself once its input closes")
            assertNull(process.readLine())
            assertFalse(process.writeLine("late"), "Nothing is written to a stopped process")
        }
    }

    @Test fun windowsStopTerminatesAProcessThatDoesNotExit() = runTest {
        if (Path.DIRECTORY_SEPARATOR != "\\") return@runTest
        withContext(Dispatchers.Default) {
            val process = startAgentProcess("findstr", listOf("^"), emptyMap(), "C:\\Windows")
            assertTrue(process.writeLine("ping"))
            assertEquals("ping", process.readLine())
            // The grace period ends with the input closed, so findstr would exit on its own: keep a reader blocked on a closed pipe out of it.
            val code = process.stop(0)
            assertNotNull(process.exitCode())
            assertTrue(code == 0 || code == 130, "Ended by its input closing or by the job's termination, never left running: $code")
        }
    }
}
