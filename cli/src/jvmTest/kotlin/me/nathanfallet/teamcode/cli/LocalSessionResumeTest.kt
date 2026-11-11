package me.nathanfallet.teamcode.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.nathanfallet.teamcode.api.models.RunnerInstructionView
import me.nathanfallet.teamcode.cli.cloud.RunnerCalls
import me.nathanfallet.teamcode.cli.launch.LocalSession
import me.nathanfallet.teamcode.client.TeamCodeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalSessionResumeTest {
    private fun fenced() = TeamCodeException(409, "fenced", "execution_fenced")

    /** Epoch 1 is the execution the server ended; only the epoch the launch adopted afterwards heartbeats. */
    private class Server(val beating: CompletableDeferred<Int> = CompletableDeferred()) : RunnerCalls {
        @Volatile var epoch = 1
        val seen = mutableListOf<Int>()
        override suspend fun register() = RunnerInstructionView("running", epoch = epoch)
        override suspend fun heartbeat(ready: Boolean): RunnerInstructionView {
            synchronized(seen) { seen += epoch }
            if (epoch == 1) throw TeamCodeException(409, "fenced", "execution_fenced")
            beating.complete(epoch)
            return RunnerInstructionView("running", epoch = epoch)
        }
        override suspend fun checkpoint(completed: Boolean) = RunnerInstructionView("running", epoch = epoch)
    }

    @Test fun aLaunchThatWasOfflineResumesItsSessionAndHeartbeatsOnTheNewEpoch() = runBlocking {
        val server = Server()
        val reports = mutableListOf<String>()
        val adopted = mutableListOf<Int>()
        val job = async { LocalSession.keepHosted(server, takeEpoch = { 2 }, report = { reports += it }) { adopted += it; server.epoch = it } }
        assertEquals(2, withTimeout(10_000) { server.beating.await() })
        job.cancelAndJoin()
        assertEquals(listOf(2), adopted)
        assertEquals(listOf(1, 2), server.seen.take(2), "The fenced epoch is tried once, then the new one")
        assertTrue(reports.any { "resuming" in it } && reports.any { "back" in it }, reports.toString())
    }

    @Test fun aSessionTheServerDeletedIsNotResumedAndTheLoopStops() = runBlocking {
        val server = Server()
        val reports = mutableListOf<String>()
        withTimeout(10_000) { LocalSession.keepHosted(server, takeEpoch = { null }, report = { reports += it }) { error("nothing to adopt") } }
        assertEquals(listOf(1), server.seen)
        assertTrue(reports.single().contains("offline"), reports.toString())
    }
}
