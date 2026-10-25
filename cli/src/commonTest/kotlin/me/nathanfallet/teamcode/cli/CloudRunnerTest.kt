package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.api.models.RunnerInstructionView
import me.nathanfallet.teamcode.cli.cloud.RunnerCalls
import me.nathanfallet.teamcode.cli.cloud.RunnerLoop
import me.nathanfallet.teamcode.cli.cloud.agent.RunnerAgent
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

    @Test fun reportsReadyCheckpointsOncePausedAndExitsOnceCheckpointed() = runTest {
        val script = Script(ArrayDeque(listOf(running, paused, paused, fenced)))
        script.registerFailures = 2
        val sleeps = mutableListOf<Long>()
        assertEquals(0, RunnerLoop(script, sleep = { sleeps += it }).run())
        assertEquals(listOf("register", "register", "register", "heartbeat:true", "heartbeat:true", "checkpoint:true"), script.calls,
            "Nothing is left to do after the checkpoint: the runner exits, so the pod stops without its grace period")
        assertEquals(listOf(1_000L, 2_000L), sleeps.take(2), "Network failures back off")
    }

    @Test fun readyIsReportedAsSoonAsThePreparationEnds() = runTest {
        val beats = mutableListOf<Pair<Long, Boolean>>()
        val calls = object : RunnerCalls {
            override suspend fun register() = RunnerInstructionView("running", null, 1)
            override suspend fun heartbeat(ready: Boolean): RunnerInstructionView {
                beats += testScheduler.currentTime to ready
                if (ready) throw TeamCodeException(409, "fenced", "execution_fenced")
                return RunnerInstructionView("running", null, 1)
            }
            override suspend fun checkpoint(completed: Boolean) = error("unused")
        }
        // The environment is ready 400 ms after registration, between two heartbeats.
        assertEquals(0, RunnerLoop(calls, prepare = { delay(400); true }).run())
        assertEquals(listOf(0L to false, 400L to true), beats, "Ready is sent when the preparation ends, not at the next 15-second tick")
    }

    @Test fun aWakeStartsTheNextHeartbeatAtOnce() = runTest {
        val beats = mutableListOf<Long>()
        lateinit var loop: RunnerLoop
        val calls = object : RunnerCalls {
            override suspend fun register() = RunnerInstructionView("running", null, 1)
            override suspend fun heartbeat(ready: Boolean): RunnerInstructionView {
                beats += testScheduler.currentTime
                if (beats.size == 3) throw TeamCodeException(409, "fenced", "execution_fenced")
                return RunnerInstructionView("running", null, 1)
            }
            override suspend fun checkpoint(completed: Boolean) = error("unused")
        }
        loop = RunnerLoop(calls)
        val waker = launch { delay(20_000); loop.wake() }
        assertEquals(0, loop.run())
        waker.join()
        assertEquals(listOf(0L, 15_000L, 20_000L), beats, "The input poll's wake-up is not held until the next tick")
    }

    @Test fun theCheckpointIsSentAsSoonAsTheDrainEnds() = runTest {
        var checkpointAt = -1L
        val agent = object : RunnerAgent {
            override val fenced = false
            override suspend fun run() = awaitCancellation()
            override suspend fun drain(deadline: Long?): Boolean { delay(3_000); return true }
            override fun resumeAfterDrain() = Unit
            override suspend fun stop() = Unit
        }
        val calls = object : RunnerCalls {
            override suspend fun register() = RunnerInstructionView("running", null, 1)
            override suspend fun heartbeat(ready: Boolean) = RunnerInstructionView("paused", 600_000, 1)
            override suspend fun checkpoint(completed: Boolean): RunnerInstructionView { checkpointAt = testScheduler.currentTime; return RunnerInstructionView("paused", 600_000, 1) }
        }
        assertEquals(0, RunnerLoop(calls, agent = agent).run())
        assertEquals(3_000L, checkpointAt, "Checkpointed when the 3-second drain ended, not at the next 15-second heartbeat")
    }

    @Test fun heartbeatsReportTheAgentsBackgroundWork() = runTest {
        var background = false
        val agent = object : RunnerAgent {
            override val fenced = false
            override val backgroundWork get() = background
            override suspend fun run() = awaitCancellation()
            override suspend fun drain(deadline: Long?) = true
            override fun resumeAfterDrain() = Unit
            override suspend fun stop() = Unit
        }
        val beats = mutableListOf<Pair<Boolean, Boolean>>()
        val calls = object : RunnerCalls {
            override suspend fun register() = RunnerInstructionView("running", null, 1)
            override suspend fun heartbeat(ready: Boolean) = heartbeat(ready, background = false)
            override suspend fun heartbeat(ready: Boolean, background: Boolean): RunnerInstructionView {
                beats += ready to background
                if (beats.size == 3) throw TeamCodeException(409, "fenced", "execution_fenced")
                return RunnerInstructionView("running", null, 1)
            }
            override suspend fun checkpoint(completed: Boolean) = RunnerInstructionView("paused", 0, 1)
        }
        var prepared = false
        launch { delay(20_000); background = true }
        assertEquals(0, RunnerLoop(calls, agent = agent, prepare = { delay(10_000); prepared = true; true }).run())
        assertEquals(listOf(false to false, true to false, true to true), beats.take(3),
            "Unready heartbeats report none; a subagent or background command started later is reported on the next heartbeat")
        assertTrue(prepared)
    }

    @Test fun aTerminationSignalStopsTheRunnerAndRunsItsCleanup() = runTest {
        var signalled = false
        var cleaned = false
        val started = testScheduler.currentTime
        launch { delay(1_000); signalled = true }
        val code = me.nathanfallet.teamcode.cli.cloud.untilTerminated({}, { signalled }) {
            try { awaitCancellation() } finally { cleaned = true }
        }
        assertEquals(0, code)
        assertTrue(cleaned, "The runner's finally blocks ran")
        assertTrue(testScheduler.currentTime - started < 1_500, "Stopped within the signal poll, not at a grace period")
        assertEquals(7, me.nathanfallet.teamcode.cli.cloud.untilTerminated({}, { false }) { 7 }, "Without a signal the work's own code is returned")
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
