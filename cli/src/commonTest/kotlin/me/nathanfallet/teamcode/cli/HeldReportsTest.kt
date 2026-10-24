package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.cli.launch.HeldTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `teamcode run`: nothing reaches the terminal while the agent owns it; what was reported is printed once it exited. */
class HeldReportsTest {
    @Test fun nothingIsWrittenWhileTheAgentRunsAndTheLinesFollowItsExit() = runTest {
        val printed = mutableListOf<String>()
        val terminal = HeldTerminal(printed::add)
        terminal.report("Running claude through TeamCode.")
        val code = terminal.holding {
            // The release watcher, the proxy, the heartbeat and the environment file all report while the agent runs.
            terminal.report("TeamCode 1.0.0 is available. Run teamcode update to install it.")
            terminal.report("TeamCode local proxy request failed. Check server connectivity and authentication.")
            terminal.report("TeamCode launch heartbeat is temporarily unavailable.")
            terminal.report("Release notifications unavailable; check installation permissions.")
            terminal.report("TeamCode local proxy request failed. Check server connectivity and authentication.")
            terminal.report("The environment file could not be written; the agent keeps the variables it starts with.")
            assertEquals(listOf("Running claude through TeamCode."), printed)
            0
        }
        assertEquals(0, code)
        // Each distinct line once, in order: the later warning does not hide the release notice.
        assertEquals(listOf(
            "Running claude through TeamCode.",
            "TeamCode 1.0.0 is available. Run teamcode update to install it.",
            "TeamCode local proxy request failed. Check server connectivity and authentication.",
            "TeamCode launch heartbeat is temporarily unavailable.",
            "Release notifications unavailable; check installation permissions.",
            "The environment file could not be written; the agent keeps the variables it starts with.",
        ), printed)
        // After the exit, a line is printed at once again.
        terminal.report("TeamCode could not confirm launch completion; its heartbeat will become stale.")
        assertEquals("TeamCode could not confirm launch completion; its heartbeat will become stale.", printed.last())
    }

    @Test fun theLinesFollowAFailedAgentToo() = runTest {
        val printed = mutableListOf<String>()
        val terminal = HeldTerminal(printed::add)
        assertFailsWith<IllegalStateException> {
            terminal.holding {
                terminal.report("TeamCode 1.0.1 is available. Run teamcode update to install it.")
                assertTrue(printed.isEmpty())
                throw IllegalStateException("agent failed")
            }
        }
        assertEquals(listOf("TeamCode 1.0.1 is available. Run teamcode update to install it."), printed)
    }

    @Test fun aLongRunKeepsABoundedNumberOfLines() = runTest {
        val printed = mutableListOf<String>()
        val terminal = HeldTerminal(printed::add, limit = 3)
        terminal.holding { repeat(100) { terminal.report("TeamCode answered HTTP 429 (rate_limited_$it): slow down.") } }
        assertEquals(3, printed.size)
    }

    @Test fun linesReportedFromOtherThreadsAreAllKept() = runTest {
        val printed = mutableListOf<String>()
        val terminal = HeldTerminal(printed::add, limit = 1_000)
        val started = CompletableDeferred<Unit>()
        terminal.holding {
            started.complete(Unit)
            // The proxy reports from its own threads.
            withContext(Dispatchers.Default) { (0 until 200).map { async { terminal.report("line $it") } }.awaitAll() }
            assertTrue(printed.isEmpty())
        }
        assertEquals((0 until 200).map { "line $it" }.toSet(), printed.toSet())
        assertEquals(200, printed.size)
    }

    @Test fun withoutALineNothingIsPrinted() = runTest {
        val printed = mutableListOf<String>()
        assertEquals(7, HeldTerminal(printed::add).holding { 7 })
        assertTrue(printed.isEmpty())
    }
}
