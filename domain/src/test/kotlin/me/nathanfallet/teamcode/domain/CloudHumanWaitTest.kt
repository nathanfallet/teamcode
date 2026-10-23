package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import kotlin.test.*

/** A session shows that it waits for a person only while the request that set it can still be answered. */
class CloudHumanWaitTest {
    private val wait = CloudHumanWait(CloudHumanWait.Reason.QUESTION, "req-1", since = 5_000, eventSeq = 12, epoch = 2)
    private val session = CloudSession(
        id = "s1", teamId = "t", ownerId = "owner", name = "Task", createdBy = Attribution("owner", "c", "owner"), profileId = "p", profileVersion = 1,
        clusterId = "k", namespace = "teamcode", imageReference = "img", imageDigest = null, agentKind = "claude", agentVersion = null, agentModel = "claude-opus-5",
        agentEffort = "high", labels = emptyMap(), state = ACTIVE, phase = null, desiredState = DesiredState.RUNNING, generation = 4, currentEpoch = 2,
        idleTimeoutMinutes = 30, lastActivityAt = 0, resumeWorkspace = WorkspaceResume.CONSISTENT, resumeConversation = ConversationResume.NONE,
        failure = null, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null, terminatedReason = null,
        waitingSince = null, workspace = CloudWorkspace("tc-ws-s1", "standard", 10), createdAt = 0, updatedAt = 0, humanWait = wait,
    )

    @Test fun aWaitShowsWhileItsExecutionRunsAndTakesAnswers() {
        assertEquals(wait, session.waitingForHuman)
        assertEquals(wait, session.copy(state = READY).waitingForHuman)
        assertNull(session.copy(humanWait = null).waitingForHuman)
    }

    @Test fun pausingEndingOrANewExecutionEndsTheWait() {
        assertNull(session.copy(desiredState = DesiredState.PAUSED).waitingForHuman, "A pause was asked: answers are refused, the runner denies the request")
        for (state in listOf(PAUSING, PAUSED, RESUMING, TRANSFERRING, TERMINATED, FAILED)) assertNull(session.copy(state = state).waitingForHuman, "$state")
        assertNull(session.copy(desiredState = DesiredState.TERMINATED).waitingForHuman)
        assertNull(session.copy(currentEpoch = 3).waitingForHuman, "A new pod (after a pause, a transfer or a crash) never inherits a request")
    }

    @Test fun deltasAreARunnerTypeAndOnlyClaudeStreamsThem() {
        assertTrue(CloudEventType.AGENT_MESSAGE_DELTA in CloudEventType.fromRunner)
        assertEquals(CloudEventType.AGENT_MESSAGE_DELTA, CloudEventType.parse("message.agent.delta"))
    }
}
