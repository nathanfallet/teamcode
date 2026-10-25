package me.nathanfallet.teamcode.domain.cloud

import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import kotlin.test.*

/**
 * The agent's background work (subagents, background commands, monitors, scheduled wake-ups) reported by the runner's
 * heartbeats keeps a `ready` session from the idle pause, for a bounded time.
 */
class CloudBackgroundWorkTest {
    private val minute = 60_000L
    private val now = 1_000 * minute
    private val session = CloudSession(
        id = "s1", teamId = "t", ownerId = "owner", name = "Task", createdBy = Attribution("owner", "c", "owner"), profileId = "p", profileVersion = 1,
        clusterId = "k", namespace = "teamcode", imageReference = "img", imageDigest = null, agentKind = "claude", agentVersion = null, agentModel = "claude-opus-5",
        agentEffort = "high", labels = emptyMap(), state = READY, phase = null, desiredState = DesiredState.RUNNING, generation = 4, currentEpoch = 2,
        idleTimeoutMinutes = 30, lastActivityAt = now - 40 * minute, resumeWorkspace = WorkspaceResume.CONSISTENT, resumeConversation = ConversationResume.NONE,
        failure = null, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null, terminatedReason = null,
        waitingSince = null, workspace = CloudWorkspace("tc-ws-s1", "standard", 10), createdAt = 0, updatedAt = 0,
    )
    private val execution = CloudExecution("s1", 2, "tc-s1-e2", ExecutionState.RUNNING, createdAt = now - 60 * minute, lastHeartbeatAt = now - 15_000)

    private fun idle(execution: CloudExecution?, maxMinutes: Int = CloudIdleRules.DEFAULT_BACKGROUND_WORK_MINUTES) =
        now - CloudIdleRules.idleSince(session, null, CloudIdleRules.backgroundActivity(execution, maxMinutes)) >= CloudIdleRules.timeoutMillis(session)

    @Test fun runningBackgroundWorkKeepsAnIdleSessionRunning() {
        assertTrue(idle(execution), "Without background work, 40 minutes after the last turn the session is idle")
        val working = execution.copy(backgroundSince = now - 35 * minute, backgroundSeenAt = now - 15_000)
        assertFalse(idle(working), "A subagent or a background command still running is activity")
    }

    @Test fun theEndOfBackgroundWorkStartsTheIdleTimeout() {
        assertFalse(idle(execution.copy(backgroundSince = now - 60 * minute, backgroundSeenAt = now - 10 * minute)), "Ended 10 minutes ago: 20 more minutes")
        assertTrue(idle(execution.copy(backgroundSince = now - 60 * minute, backgroundSeenAt = now - 31 * minute)), "Ended 31 minutes ago")
    }

    @Test fun backgroundWorkThatNeverEndsIsCapped() {
        // Started 5 hours ago, still reported: it counts until 4 hours after its start, then the 30-minute timeout runs.
        val stuck = execution.copy(backgroundSince = now - 300 * minute, backgroundSeenAt = now - 15_000)
        assertEquals(now - 60 * minute, CloudIdleRules.backgroundActivity(stuck, 240))
        assertTrue(idle(stuck, 240))
        assertFalse(idle(execution.copy(backgroundSince = now - 260 * minute, backgroundSeenAt = now - 15_000), 240), "4 h 20 min: within cap plus timeout")
        assertNull(CloudIdleRules.backgroundActivity(stuck, 0), "0 turns the rule off")
        assertTrue(idle(execution.copy(backgroundSince = now - 5 * minute, backgroundSeenAt = now), 0))
        assertNull(CloudIdleRules.backgroundActivity(null, 240))
        assertNull(CloudIdleRules.backgroundActivity(execution, 240), "Never reported")
    }

    @Test fun heartbeatsKeepTheStartOfAnUnbrokenStretch() {
        val first = CloudIdleRules.heartbeat(execution, background = true, now = now).copy(lastHeartbeatAt = now)
        assertEquals(now to now, first.backgroundSince to first.backgroundSeenAt)
        val second = CloudIdleRules.heartbeat(first, background = true, now = now + 15_000).copy(lastHeartbeatAt = now + 15_000)
        assertEquals(now to now + 15_000, second.backgroundSince to second.backgroundSeenAt, "The stretch goes on")
        val quiet = CloudIdleRules.heartbeat(second, background = false, now = now + 30_000).copy(lastHeartbeatAt = now + 30_000)
        assertEquals(now to now + 15_000, quiet.backgroundSince to quiet.backgroundSeenAt, "The end is known from the last report")
        val again = CloudIdleRules.heartbeat(quiet, background = true, now = now + 45_000)
        assertEquals(now + 45_000 to now + 45_000, again.backgroundSince to again.backgroundSeenAt, "A gap starts a new stretch, so the cap restarts")
    }
}
