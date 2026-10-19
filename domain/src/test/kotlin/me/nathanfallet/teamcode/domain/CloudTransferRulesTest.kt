package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.policy.ModelPolicy
import me.nathanfallet.teamcode.domain.policy.ModelPolicyStore
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class CloudTransferRulesTest {
    private val session = CloudSession(
        id = "s1", teamId = "t", ownerId = "owner", name = "Task", createdBy = Attribution("owner", "c", "owner"), profileId = "p", profileVersion = 1,
        clusterId = "k", namespace = "teamcode", imageReference = "img", imageDigest = null, agentKind = "claude", agentVersion = null, agentModel = "claude-opus-5",
        agentEffort = "high", labels = emptyMap(), state = READY, phase = null, desiredState = DesiredState.RUNNING, generation = 4, currentEpoch = 1,
        idleTimeoutMinutes = 30, lastActivityAt = 0, resumeWorkspace = WorkspaceResume.CONSISTENT, resumeConversation = ConversationResume.NONE,
        failure = null, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null, terminatedReason = null,
        waitingSince = null, workspace = CloudWorkspace("tc-ws-s1", "standard", 10), createdAt = 0, updatedAt = 0,
    )
    private val transfer = CloudTransfer("tr", "t", "s1", "owner", "recipient", Attribution("admin", "c", "admin"), CloudTransferState.ACCEPTED,
        resume = false, force = true, pendingInputs = TransferPendingInputs.CANCEL, version = 1, createdAt = 0, updatedAt = 0)

    @Test fun ownersChangeOnlyAtThePausedBoundary() {
        assertTrue(CloudStateMachine.permits(PAUSED, TRANSFERRING))
        for (state in listOf(CREATING, PREPARING, READY, ACTIVE, PAUSING, RESUMING, FAILED, TERMINATED))
            assertFalse(CloudStateMachine.permits(state, TRANSFERRING), "$state cannot start a transfer: it may still have a pod")
        assertTrue(CloudStateMachine.permits(TRANSFERRING, PAUSED)); assertTrue(CloudStateMachine.permits(TRANSFERRING, RESUMING))
        assertFalse(CloudStateMachine.permits(TRANSFERRING, READY), "A transferred session runs again only through a new execution")
        // The owner field changes only through a completed transfer, never through an ordinary compare-and-set.
        assertFailsWith<IllegalArgumentException> { SessionChange(session, session.copy(ownerId = "recipient")) }
        assertFailsWith<TeamFailure> { SessionChange(session, session.copy(state = TRANSFERRING)) }
        // Never reached: the use case refuses before any store call.
        val unused = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CloudSessionStore::class.java)) { _, _, _ -> error("unused") } as CloudSessionStore
        assertFailsWith<IllegalArgumentException> { runBlocking { TransitionCloudSessionUseCase(unused).commit(session, CloudTransition(session.copy(ownerId = "recipient"))) } }
    }

    @Test fun acceptanceMovesTheSessionTowardAPause() {
        val running = CloudTransferService.toward(session, transfer, now = 50)
        assertEquals(DesiredState.PAUSED, running.desiredState); assertEquals("tr", running.transferId)
        assertEquals(50L to true, running.pauseRequestedAt to running.pauseForce, "A forced transfer does not wait for the running turn")
        assertEquals("owner", running.ownerId)
        val paused = session.copy(state = PAUSED, desiredState = DesiredState.PAUSED, pauseRequestedAt = null, pausedAt = 10)
        assertEquals(null to false, CloudTransferService.toward(paused, transfer, 50).let { it.pauseRequestedAt to it.pauseForce }, "An already paused session is not paused again")
        val resuming = session.copy(state = PAUSED, desiredState = DesiredState.RUNNING)
        assertEquals(DesiredState.PAUSED, CloudTransferService.toward(resuming, transfer, 50).desiredState, "A pending resume is cancelled")

        fun refused(s: CloudSession) = assertFailsWith<TeamFailure> { CloudTransferService.transferable(s) }.code
        assertEquals("session_preparing", refused(session.copy(state = CREATING)))
        assertEquals("session_preparing", refused(session.copy(state = PREPARING)))
        assertEquals("session_not_running", refused(session.copy(desiredState = DesiredState.TERMINATED)))
        assertEquals("session_not_running", refused(session.copy(state = FAILED)))
        assertEquals("transfer_pending", refused(session.copy(transferId = "other")))
        assertEquals("transfer_pending", refused(session.copy(state = TRANSFERRING)))
        CloudTransferService.transferable(session.copy(transferId = "tr"), allowing = "tr")
    }

    @Test fun recipientChecksFollowModelPolicyAndQuota() = runBlocking {
        val recipient = Member("recipient", "r", Role.MEMBER, false, false)
        var policy = ModelPolicy()
        val store = object : ModelPolicyStore {
            override suspend fun get(teamId: String) = policy
            override suspend fun save(actor: Principal, policy: ModelPolicy) = policy
        }
        val models = ModelPolicyRecipientCheck(store)
        assertEquals(emptyList(), models.problems(session, recipient))
        policy = ModelPolicy(deniedModels = listOf("*opus*"))
        assertEquals(listOf("model_not_allowed"), models.problems(session, recipient).map { it.code })
        policy = ModelPolicy(allowedEfforts = listOf("low"))
        assertEquals(listOf("effort_not_allowed"), models.problems(session, recipient).map { it.code })
        assertEquals(listOf("effort_not_allowed"), models.problems(session.copy(agentModel = null), recipient).map { it.code })
        assertEquals(emptyList(), models.problems(session.copy(agentModel = null, agentEffort = null), recipient))

        var allowance = 0L
        val quota = QuotaRecipientCheck { _, _ -> allowance }
        assertEquals(listOf("recipient_no_quota"), quota.problems(session, recipient).map { it.code })
        allowance = 1
        assertEquals(emptyList(), quota.problems(session, recipient))
    }

    @Test fun transferSettingsAreBounded() {
        CloudAdministrationService.validate(CloudSettings(transferOfferDays = 30, memberTransfers = MemberTransfers.DISABLED, transferPendingInputs = TransferPendingInputs.KEEP))
        for (days in listOf(0, 31)) assertEquals("invalid_cloud_settings", assertFailsWith<TeamFailure> { CloudAdministrationService.validate(CloudSettings(transferOfferDays = days)) }.code)
        assertNull(MemberTransfers.parse("always")); assertNull(TransferPendingInputs.parse("drop"))
        assertFalse(CloudEventType.TRANSFER in CloudEventType.fromRunner, "Only the server writes transfer events")
    }
}
