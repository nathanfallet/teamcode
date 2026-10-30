package me.nathanfallet.teamcode.domain.cloud

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** Who may rename a session, the name rules shared with creation, and what the write carries (audit action, feed event). */
class CloudSessionRenameTest {
    private val ownerMember = Member("owner-id", "owner", Role.MEMBER, false, false)
    private val otherMember = Member("other-id", "other", Role.MEMBER, false, false)
    private val adminMember = Member("admin-id", "admin", Role.ADMIN, false, false)
    private val owner = Principal(ownerMember, "s1", "team")
    private val other = Principal(otherMember, "s2", "team")
    private val admin = Principal(adminMember, "s3", "team")

    private var session = CloudSession(
        id = "s1", teamId = "team", ownerId = ownerMember.id, name = "Task", createdBy = Attribution(ownerMember.id, "c", ownerMember.id), profileId = "p", profileVersion = 1,
        clusterId = "k", namespace = "teamcode", imageReference = "img", imageDigest = null, agentKind = "claude", agentVersion = null, agentModel = null,
        agentEffort = null, labels = emptyMap(), state = CloudSessionState.READY, phase = null, desiredState = DesiredState.RUNNING, generation = 4, currentEpoch = 1,
        idleTimeoutMinutes = 30, lastActivityAt = 0, resumeWorkspace = WorkspaceResume.CONSISTENT, resumeConversation = ConversationResume.NONE,
        failure = null, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null, terminatedReason = null,
        waitingSince = null, workspace = CloudWorkspace("tc-ws-s1", "standard", 10), createdAt = 0, updatedAt = 0,
    )
    private val writes = mutableListOf<CloudWrite>()
    private val service = CloudSessionService(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CloudSessionStore::class.java)) { _, method, args ->
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
    } as CloudSessionStore)

    private fun failure(code: String, block: suspend () -> Unit): TeamFailure {
        val error = assertFailsWith<TeamFailure> { runBlocking { block() } }
        assertEquals(code, error.code)
        return error
    }

    @Test fun theOwnerRenamesAndTheWriteIsAuditedForTheFeed() = runBlocking {
        val renamed = service.rename(owner, null, "s1", "Fix the build")
        assertEquals("Fix the build", renamed.name)
        assertEquals("cloud.session.renamed", writes.single().auditAction)
        assertEquals(listOf(TeamEventType.SESSION_RENAMED), TeamEventRules.session(CloudSessionState.READY, CloudSessionState.READY, writes.single().auditAction))
        assertNull(writes.single().reconcileAt, "A name changes nothing the reconciler acts on")
    }

    @Test fun anotherMemberCannotButAnAdministratorCan() = runBlocking {
        failure("not_found") { service.rename(other, null, "s1", "Mine") }
        assertEquals("Task", session.name)
        assertEquals("By the admin", service.rename(admin, null, "s1", "By the admin").name)
        assertEquals(1, writes.size)
    }

    @Test fun theCreationNameRulesApply() {
        for (bad in listOf("", "   ", "x".repeat(121), "two\nlines", "tab\there")) {
            val error = failure("invalid_session") { service.rename(owner, null, "s1", bad) }
            assertEquals(400, error.status)
        }
        assertEquals("Task", session.name); assertTrue(writes.isEmpty())
        runBlocking { assertEquals("x".repeat(120), service.rename(owner, null, "s1", "x".repeat(120)).name) }
    }

    @Test fun theSameNameWritesNothingAndAFinishedSessionRefuses() {
        runBlocking { assertEquals("Task", service.rename(owner, null, "s1", "Task").name) }
        assertTrue(writes.isEmpty())
        session = session.copy(desiredState = DesiredState.TERMINATED)
        failure("session_not_running") { service.rename(owner, null, "s1", "New") }
        failure("not_found") { service.rename(owner, null, "missing", "New") }
        assertTrue(writes.isEmpty())
    }

    @Test fun aReadOnlyKeyCannotRename() {
        failure("credential_scope") { service.rename(owner.copy(serviceScopes = setOf(ServiceScope.CLOUD_READ)), null, "s1", "New") }
    }
}
