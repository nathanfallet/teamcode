package me.nathanfallet.teamcode.domain.cloud

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** Who may change a session's effort, what the write carries and what a running agent is told. */
class CloudSessionEffortTest {
    private val ownerMember = Member("owner-id", "owner", Role.MEMBER, false, false)
    private val otherMember = Member("other-id", "other", Role.MEMBER, false, false)
    private val adminMember = Member("admin-id", "admin", Role.ADMIN, false, false)
    private val owner = Principal(ownerMember, "s1", "team")
    private val other = Principal(otherMember, "s2", "team")
    private val admin = Principal(adminMember, "s3", "team")

    private var session = CloudSession(
        id = "s1", teamId = "team", ownerId = ownerMember.id, name = "Task", createdBy = Attribution(ownerMember.id, "c", ownerMember.id), profileId = "p", profileVersion = 1,
        clusterId = "k", namespace = "teamcode", imageReference = "img", imageDigest = null, agentKind = "claude", agentVersion = null, agentModel = "claude-opus-5-5",
        agentEffort = "high", labels = emptyMap(), state = CloudSessionState.READY, phase = null, desiredState = DesiredState.RUNNING, generation = 4, currentEpoch = 1,
        idleTimeoutMinutes = 30, lastActivityAt = 0, resumeWorkspace = WorkspaceResume.CONSISTENT, resumeConversation = ConversationResume.NONE,
        failure = null, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null, terminatedReason = null,
        waitingSince = null, workspace = CloudWorkspace("tc-ws-s1", "standard", 10), createdAt = 0, updatedAt = 0,
    )
    private val writes = mutableListOf<CloudWrite>()
    private val announced = mutableListOf<String>()
    private val store = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CloudSessionStore::class.java)) { _, method, args ->
        when (method.name) {
            "find" -> session.takeIf { it.id == args[1] }
            "now" -> 100L
            "compareAndSet" -> {
                val next = args[1] as CloudSession
                writes += args[2] as CloudWrite
                next.copy(generation = session.generation + 1).also { session = it }
            }
            else -> error("unexpected ${method.name}")
        }
    } as CloudSessionStore
    private val service = CloudSessionService(store, effortChanges = { _, changed, effort -> announced += "${changed.id}:$effort" })

    private fun failure(code: String, block: suspend () -> Unit): TeamFailure {
        val error = assertFailsWith<TeamFailure> { runBlocking { block() } }
        assertEquals(code, error.code)
        return error
    }

    @Test fun theOwnerSwitchesTheEffortAndTheRunningAgentIsTold() = runBlocking {
        val changed = service.setEffort(owner, null, "s1", "max")
        assertEquals("max", changed.agentEffort)
        assertEquals("claude-opus-5-5", changed.agentModel, "the model is untouched")
        assertEquals("cloud.session.effort_changed", writes.single().auditAction)
        assertNull(writes.single().reconcileAt, "An effort changes nothing the reconciler acts on")
        assertEquals(listOf("s1:max"), announced)
        assertTrue(service.changesEfforts)
    }

    @Test fun noOtherMemberChangesTheEffortAnAdministratorIncluded() {
        failure("not_found") { service.setEffort(other, null, "s1", "max") }
        failure("not_found") { service.setEffort(admin, null, "s1", "max") }
        assertEquals("high", session.agentEffort)
        assertTrue(writes.isEmpty() && announced.isEmpty())
    }

    @Test fun theCreationEffortRulesApply() {
        for (bad in listOf("", "two words", "line\nbreak", "x".repeat(121), "semi;colon")) {
            assertEquals(400, failure("invalid_agent") { service.setEffort(owner, null, "s1", bad) }.status)
        }
        assertTrue(writes.isEmpty() && announced.isEmpty())
    }

    @Test fun theSameEffortWritesNothingAndAFinishedSessionRefuses() {
        // The announcement is idempotent per row write in the conversation store, so repeating the choice queues nothing there.
        runBlocking { assertEquals("high", service.setEffort(owner, null, "s1", "high").agentEffort) }
        assertTrue(writes.isEmpty())
        session = session.copy(desiredState = DesiredState.TERMINATED)
        failure("session_not_running") { service.setEffort(owner, null, "s1", "max") }
        failure("not_found") { service.setEffort(owner, null, "missing", "max") }
        assertTrue(writes.isEmpty())
    }

    @Test fun aSessionWithoutAnEffortTakesOne() = runBlocking {
        session = session.copy(agentEffort = null)
        assertEquals("low", service.setEffort(owner, null, "s1", "low").agentEffort)
    }

    @Test fun aReadOnlyKeyCannotChangeTheEffort() {
        failure("credential_scope") { service.setEffort(owner.copy(serviceScopes = setOf(ServiceScope.CLOUD_READ)), null, "s1", "max") }
    }

    @Test fun withoutConversationsTheServerDoesNotAdvertiseIt() {
        assertFalse(CloudSessionService(store).changesEfforts)
    }
}
