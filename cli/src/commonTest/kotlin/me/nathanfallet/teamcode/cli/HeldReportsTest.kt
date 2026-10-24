package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.cli.launch.withHeldReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `teamcode run`: nothing reaches the terminal while the agent owns it; each distinct notice is printed once it exited. */
class HeldReportsTest {
    @Test fun nothingIsWrittenWhileTheAgentRunsAndTheNoticeFollowsItsExit() = runTest {
        val printed = mutableListOf<String>()
        val reported = CompletableDeferred<Unit>()
        val code = withHeldReports(printed::add, { report ->
            report("TeamCode 1.0.0 is available. Run teamcode update to install it.")
            report("Release notifications unavailable; check installation permissions.")
            report("TeamCode 1.0.0 is available. Run teamcode update to install it.")
            reported.complete(Unit)
            awaitCancellation()
        }) {
            reported.await()
            // The watcher has reported three times while the agent runs: the terminal stays untouched.
            assertTrue(printed.isEmpty())
            0
        }
        assertEquals(0, code)
        // The later warning does not hide the notice, and the repeated notice is printed once.
        assertEquals(listOf("TeamCode 1.0.0 is available. Run teamcode update to install it.", "Release notifications unavailable; check installation permissions."), printed)
    }

    @Test fun theNoticeFollowsAFailedAgentToo() = runTest {
        val printed = mutableListOf<String>()
        val reported = CompletableDeferred<Unit>()
        assertFailsWith<IllegalStateException> {
            withHeldReports(printed::add, { report -> report("TeamCode 1.0.1 is available. Run teamcode update to install it."); reported.complete(Unit); awaitCancellation() }) {
                reported.await()
                throw IllegalStateException("agent failed")
            }
        }
        assertEquals(listOf("TeamCode 1.0.1 is available. Run teamcode update to install it."), printed)
    }

    @Test fun withoutANoticeNothingIsPrinted() = runTest {
        val printed = mutableListOf<String>()
        assertEquals(7, withHeldReports(printed::add, { awaitCancellation() }) { 7 })
        assertTrue(printed.isEmpty())
    }
}
