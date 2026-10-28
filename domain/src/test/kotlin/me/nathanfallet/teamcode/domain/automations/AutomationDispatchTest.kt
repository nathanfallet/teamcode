package me.nathanfallet.teamcode.domain.automations

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.domain.automations.Fixtures.ada
import me.nathanfallet.teamcode.domain.automations.Fixtures.principal
import me.nathanfallet.teamcode.domain.cloud.CloudInputStatus
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState
import me.nathanfallet.teamcode.domain.cloud.CloudSettings
import me.nathanfallet.teamcode.domain.cloud.DesiredState
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class AutomationDispatchTest {
    private fun queued(world: Fixtures.World = Fixtures.World(), overlap: OverlapPolicy = OverlapPolicy.ALLOW, key: String = "k"): Pair<Fixtures.World, AutomationRun> = runBlocking {
        val id = world.store.automations.values.firstOrNull()?.id ?: world.service.create(principal(ada), Fixtures.cron.copy(overlap = overlap)).automation.id
        val run = (world.service.test(principal(ada), id, null, dryRun = false, idempotencyKey = key) as TestOutcome.Run).outcome.run
        world to run
    }
    private fun progress(world: Fixtures.World, run: AutomationRun, state: CloudSessionState, input: CloudInputStatus?, desired: DesiredState = DesiredState.RUNNING, turnEnd: String? = null) {
        world.store.progress["session-${run.id}"] = SessionProgress(state, desired, input, turnEnd)
    }

    @Test fun aQueuedRunCreatesItsSessionAsTheOwnerWithItsIdempotencyKey() = runBlocking {
        val (world, run) = queued()
        world.dispatcher.run()
        val (credential, prompt, key) = world.sessions.created.single()
        assertEquals("credential-${run.automationId}", credential, "the owner's automation credential, not the tester's login")
        assertTrue(prompt.startsWith("Summarize ")); assertEquals("automation-run:${run.id}", key)
        val started = world.store.runs.getValue(run.id)
        assertEquals(RunStatus.STARTING, started.status); assertEquals("session-${run.id}", started.sessionId); assertEquals(3, started.profileVersion)
        assertFalse(run.id in world.store.prompts, "the prompt lives in the session from now on")
    }

    @Test fun aRunWaitsForTheOwnersAutomationCapThenExpires() = runBlocking {
        val world = Fixtures.World()
        world.directory.settings = CloudSettings(maxRunningAutomationsPerMember = 1)
        val (_, first) = queued(world, key = "a")
        world.dispatcher.run()
        val (_, second) = queued(world, key = "b")
        world.store.clock += 20_000
        world.dispatcher.run()
        assertEquals(RunStatus.QUEUED, world.store.runs.getValue(second.id).status, "the first one holds the only place")
        assertEquals(1, world.sessions.created.size)
        world.store.clock += AutomationRules.QUEUE_TIMEOUT_MILLIS
        world.dispatcher.run()
        assertEquals(RunStatus.EXPIRED, world.store.runs.getValue(second.id).status)
        assertEquals("capacity_timeout", world.store.runs.getValue(second.id).reason)
        assertEquals(RunStatus.STARTING, world.store.runs.getValue(first.id).status)
    }

    @Test fun aRevokedOwnerFailsTheRunAndPausesTheAutomation() = runBlocking {
        val (world, run) = queued()
        world.store.revoked += run.automationId
        world.dispatcher.run()
        assertEquals(RunStatus.FAILED, world.store.runs.getValue(run.id).status)
        assertEquals("owner_unavailable", world.store.runs.getValue(run.id).reason)
        assertEquals("owner_unavailable", world.store.automations.getValue(run.automationId).disabledReason)
        assertTrue(world.sessions.created.isEmpty())
    }

    @Test fun aRefusedCreationFailsWithItsCodeAndATransientOneRetries() = runBlocking {
        val (world, run) = queued()
        world.sessions.failure = TeamFailure("cluster_unavailable", 503, "Down.")
        world.dispatcher.run()
        assertEquals(RunStatus.QUEUED, world.store.runs.getValue(run.id).status)
        world.sessions.failure = TeamFailure("profile_unassigned", 403, "No.")
        world.store.clock += DispatchAutomationRunsUseCase.ERROR_RETRY_MILLIS
        world.dispatcher.run()
        assertEquals(RunStatus.FAILED, world.store.runs.getValue(run.id).status)
        assertEquals("profile_unassigned", world.store.runs.getValue(run.id).reason)
    }

    @Test fun theRunFollowsItsSessionUntilTheTurnEnds() = runBlocking {
        val (world, run) = queued()
        world.dispatcher.run()
        progress(world, run, CloudSessionState.ACTIVE, CloudInputStatus.CONSUMED)
        world.dispatcher.run()
        assertEquals(RunStatus.RUNNING, world.store.runs.getValue(run.id).status)
        progress(world, run, CloudSessionState.READY, CloudInputStatus.CONSUMED, turnEnd = "completed")
        world.dispatcher.run()
        assertEquals(RunStatus.SUCCEEDED, world.store.runs.getValue(run.id).status)
    }

    @Test fun aConsumedPromptWhoseTurnHasNotEndedIsNotASuccess() = runBlocking {
        val (world, run) = queued()
        world.dispatcher.run()
        progress(world, run, CloudSessionState.READY, CloudInputStatus.CONSUMED)
        world.dispatcher.run()
        assertEquals(RunStatus.STARTING, world.store.runs.getValue(run.id).status, "consumed, but the turn it starts has not ended")
    }

    @Test fun aFailedOrInterruptedTurnFailsTheRun() = runBlocking {
        val (world, run) = queued()
        world.dispatcher.run()
        progress(world, run, CloudSessionState.READY, CloudInputStatus.CONSUMED, turnEnd = "interrupted")
        world.dispatcher.run()
        assertEquals(RunStatus.FAILED, world.store.runs.getValue(run.id).status)
        assertEquals("turn_interrupted", world.store.runs.getValue(run.id).reason)
    }

    @Test fun aReadySessionBeforeThePromptIsConsumedIsNotASuccess() = runBlocking {
        val (world, run) = queued()
        world.dispatcher.run()
        progress(world, run, CloudSessionState.READY, CloudInputStatus.QUEUED)
        world.dispatcher.run()
        assertEquals(RunStatus.STARTING, world.store.runs.getValue(run.id).status)
    }

    @Test fun aFailedOrDeletedSessionFailsTheRun() = runBlocking {
        val (world, run) = queued()
        world.dispatcher.run()
        progress(world, run, CloudSessionState.FAILED, CloudInputStatus.QUEUED)
        world.dispatcher.run()
        assertEquals("session_failed", world.store.runs.getValue(run.id).reason)
        val (_, other) = queued(world, key = "other")
        world.dispatcher.run()
        progress(world, other, CloudSessionState.ACTIVE, CloudInputStatus.CONSUMED, DesiredState.TERMINATED)
        world.dispatcher.run()
        assertEquals("session_deleted", world.store.runs.getValue(other.id).reason)
    }

    @Test fun aRunPastItsRuntimeTimesOutAndPausesItsSession() = runBlocking {
        val (world, run) = queued()
        world.dispatcher.run()
        progress(world, run, CloudSessionState.ACTIVE, CloudInputStatus.CONSUMED)
        world.store.clock += 61 * 60_000L
        world.dispatcher.run()
        assertEquals(RunStatus.TIMED_OUT, world.store.runs.getValue(run.id).status)
        assertEquals(listOf("session-${run.id}"), world.sessions.paused)
    }

    @Test fun cancellingAWorkingRunPausesItsSessionOrDeletesAPreparingOne() = runBlocking {
        val (world, run) = queued()
        world.dispatcher.run()
        progress(world, run, CloudSessionState.PREPARING, null)
        world.store.requestCancel(principal(ada), run.automationId, run.id)
        world.sessions.preparing = true
        world.dispatcher.run()
        assertEquals(RunStatus.CANCELLED, world.store.runs.getValue(run.id).status)
        assertEquals(listOf("session-${run.id}"), world.sessions.deleted)
    }

    @Test fun cancellingAQueuedRunEndsItAtOnce() = runBlocking {
        val (world, run) = queued()
        assertEquals(RunStatus.CANCELLED, world.service.cancel(principal(ada), run.automationId, run.id).status)
        world.dispatcher.run()
        assertTrue(world.sessions.created.isEmpty())
    }

    @Test fun anAutomationKeepsTheSessionsOfItsLatestRuns() = runBlocking {
        val world = Fixtures.World()
        repeat(AutomationRules.KEEP_SESSIONS + 2) { index ->
            val (_, run) = queued(world, key = "r$index")
            world.dispatcher.run()
            progress(world, run, CloudSessionState.READY, CloudInputStatus.CONSUMED, turnEnd = "completed")
            world.dispatcher.run()
        }
        assertEquals(2, world.sessions.deleted.size)
        assertEquals(world.store.runs.values.take(2).map { it.sessionId }, world.sessions.deleted, "the oldest ones")
    }

    @Test fun consecutiveFailuresIgnoreTests() = runBlocking {
        val world = Fixtures.World()
        val id = world.service.create(principal(ada), Fixtures.webhook.copy(overlap = OverlapPolicy.ALLOW)).automation.id
        assertFailsWith<TeamFailure> { world.service.test(principal(ada), id, buildJsonObject { put("x", 1) }, dryRun = false, idempotencyKey = "t") }
        assertEquals(RunStatus.INVALID_PAYLOAD, world.store.runs.values.single().status)
        assertEquals(0, world.store.automations.getValue(id).consecutiveFailures)
    }

    @Test fun aBrokenStopNeitherBlocksTheRunNorTheOthers() = runBlocking {
        val world = Fixtures.World()
        val (_, first) = queued(world, key = "a")
        val (_, second) = queued(world, key = "b")
        world.dispatcher.run()
        world.sessions.stopError = java.io.IOException("connection reset")
        world.store.clock += 24 * 3_600_000L
        world.dispatcher.run()
        assertEquals(RunStatus.TIMED_OUT, world.store.runs.getValue(first.id).status)
        assertEquals(RunStatus.TIMED_OUT, world.store.runs.getValue(second.id).status, "the next run is still followed")
    }

    @Test fun aRunCancelledWhileItsSessionIsCreatedDeletesThatSession() = runBlocking {
        val (world, run) = queued()
        world.sessions.duringCreate = { world.service.cancel(principal(ada), run.automationId, run.id) }
        world.dispatcher.run()
        assertEquals(RunStatus.CANCELLED, world.store.runs.getValue(run.id).status)
        assertEquals(listOf("session-${run.id}"), world.sessions.deleted)
    }

    @Test fun aQueueAutomationsNextRunWaitsForTheWorkingOne() = runBlocking {
        val world = Fixtures.World()
        val (_, first) = queued(world, overlap = OverlapPolicy.QUEUE, key = "a")
        world.dispatcher.run()
        val (_, second) = queued(world, key = "b")
        assertEquals(RunStatus.QUEUED, second.status)
        world.store.clock += 20_000
        world.dispatcher.run()
        assertEquals(RunStatus.QUEUED, world.store.runs.getValue(second.id).status, "the owner has room, but the automation is busy")
        progress(world, first, CloudSessionState.READY, CloudInputStatus.CONSUMED, turnEnd = "completed")
        world.dispatcher.run()
        world.store.clock += 20_000
        world.dispatcher.run()
        assertEquals(RunStatus.STARTING, world.store.runs.getValue(second.id).status)
    }

    @Test fun aSessionThatCouldNotBeDeletedIsReleasedWhenTheNextRunEnds() = runBlocking {
        val world = Fixtures.World()
        fun end(index: Int) = runBlocking {
            val (_, run) = queued(world, key = "r$index")
            world.dispatcher.run()
            progress(world, run, CloudSessionState.READY, CloudInputStatus.CONSUMED, turnEnd = "completed")
            world.dispatcher.run()
        }
        repeat(AutomationRules.KEEP_SESSIONS) { end(it) }
        world.sessions.stopError = java.io.IOException("connection reset")
        end(AutomationRules.KEEP_SESSIONS)
        assertTrue(world.sessions.deleted.isEmpty())
        world.sessions.stopError = null
        end(AutomationRules.KEEP_SESSIONS + 1)
        assertEquals(world.store.runs.values.take(2).map { it.sessionId }.toSet(), world.sessions.deleted.toSet(), "the one that failed, and the next")
    }
}
