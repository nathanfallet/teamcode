package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.models.RunnerInstructionView
import me.nathanfallet.teamcode.cli.cloud.RunnerCalls
import me.nathanfallet.teamcode.cli.cloud.RunnerLoop
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.*

class CloudRunnerTest {
    private class Script(val heartbeats: ArrayDeque<() -> RunnerInstructionView>) : RunnerCalls {
        val calls = mutableListOf<String>()
        var registerFailures = 0
        override suspend fun register(): RunnerInstructionView {
            calls += "register"
            if (registerFailures-- > 0) throw IllegalStateException("unreachable")
            return RunnerInstructionView("running", null, 1)
        }
        override suspend fun heartbeat(ready: Boolean): RunnerInstructionView { calls += "heartbeat:$ready"; return heartbeats.removeFirst()() }
        override suspend fun checkpoint(completed: Boolean): RunnerInstructionView { calls += "checkpoint:$completed"; return RunnerInstructionView("paused", 0, 1) }
    }
    private val running = { RunnerInstructionView("running", null, 1) }
    private val paused = { RunnerInstructionView("paused", 10, 1) }
    private val fenced: () -> RunnerInstructionView = { throw TeamCodeException(409, "fenced", "execution_fenced") }

    @Test fun reportsReadyCheckpointsOncePausedAndExitsWhenFenced() = runTest {
        val script = Script(ArrayDeque(listOf(running, paused, paused, fenced)))
        script.registerFailures = 2
        val sleeps = mutableListOf<Long>()
        assertEquals(0, RunnerLoop(script, sleep = { sleeps += it }).run())
        assertEquals(listOf("register", "register", "register", "heartbeat:true", "heartbeat:true", "checkpoint:true", "heartbeat:true", "heartbeat:true"), script.calls)
        assertEquals(listOf(1_000L, 2_000L), sleeps.take(2), "Network failures back off")
    }

    @Test fun otherConflictsAreNotFencing() = runTest {
        var checkpoints = 0
        val script = object : RunnerCalls {
            val heartbeats = ArrayDeque(listOf(paused, { throw TeamCodeException(409, "busy", "session_busy") }, running, fenced))
            override suspend fun register() = RunnerInstructionView("running", null, 1)
            override suspend fun heartbeat(ready: Boolean) = heartbeats.removeFirst()()
            override suspend fun checkpoint(completed: Boolean): RunnerInstructionView { checkpoints++; throw TeamCodeException(409, "not pausing", "not_pausing") }
        }
        assertEquals(0, RunnerLoop(script, sleep = {}).run())
        assertEquals(1, checkpoints, "A refused checkpoint (the pause was cancelled) does not stop the runner")
        assertTrue(script.heartbeats.isEmpty(), "The runner kept heartbeating until it was really fenced")
    }

    @Test fun refusedTokenStopsWithoutRetrying() = runTest {
        val script = Script(ArrayDeque(listOf({ throw TeamCodeException(401, "refused") })))
        assertEquals(401, assertFailsWith<RunnerLoop.RunnerRefused> { RunnerLoop(script, sleep = {}).run() }.status)
    }

    @Test fun deletionEndsTheRunner() = runTest {
        val script = Script(ArrayDeque(listOf(running, { RunnerInstructionView("terminated", null, 1) })))
        assertEquals(0, RunnerLoop(script, sleep = {}).run())
        assertFalse("checkpoint:true" in script.calls, "A deleted session is never checkpointed")
    }
}
