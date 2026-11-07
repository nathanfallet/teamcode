package me.nathanfallet.teamcode.domain.cloud

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.policy.ModelPolicy
import me.nathanfallet.teamcode.domain.policy.ModelPolicyStore
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** Who may change a session's model, the team's model policy it must pass, what the write carries and what a running agent is told. */
class CloudSessionModelTest {
    private val ownerMember = Member("owner-id", "owner", Role.MEMBER, false, false)
    private val otherMember = Member("other-id", "other", Role.MEMBER, false, false)
    private val adminMember = Member("admin-id", "admin", Role.ADMIN, false, false)
    private val owner = Principal(ownerMember, "s1", "team")
    private val other = Principal(otherMember, "s2", "team")
    private val admin = Principal(adminMember, "s3", "team")

    private var session = CloudSession(
        id = "s1", teamId = "team", ownerId = ownerMember.id, name = "Task", createdBy = Attribution(ownerMember.id, "c", ownerMember.id), profileId = "p", profileVersion = 1,
        clusterId = "k", namespace = "teamcode", imageReference = "img", imageDigest = null, agentKind = "claude", agentVersion = null, agentModel = "claude-opus-5-5",
        agentEffort = null, labels = emptyMap(), state = CloudSessionState.READY, phase = null, desiredState = DesiredState.RUNNING, generation = 4, currentEpoch = 1,
        idleTimeoutMinutes = 30, lastActivityAt = 0, resumeWorkspace = WorkspaceResume.CONSISTENT, resumeConversation = ConversationResume.NONE,
        failure = null, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null, terminatedReason = null,
        waitingSince = null, workspace = CloudWorkspace("tc-ws-s1", "standard", 10), createdAt = 0, updatedAt = 0,
    )
    private val writes = mutableListOf<CloudWrite>()
    private val announced = mutableListOf<String>()
    private var policy = ModelPolicy()
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
    private val policies = object : ModelPolicyStore {
        override suspend fun get(teamId: String) = policy
        override suspend fun save(actor: Principal, policy: ModelPolicy) = policy
    }
    private val service = CloudSessionService(store, modelPolicies = policies, modelChanges = { _, changed, model -> announced += "${changed.id}:$model" })

    private fun failure(code: String, block: suspend () -> Unit): TeamFailure {
        val error = assertFailsWith<TeamFailure> { runBlocking { block() } }
        assertEquals(code, error.code)
        return error
    }

    @Test fun theOwnerSwitchesTheModelAndTheRunningAgentIsTold() = runBlocking {
        val changed = service.setModel(owner, null, "s1", "claude-sonnet-5-5")
        assertEquals("claude-sonnet-5-5", changed.agentModel)
        assertEquals("cloud.session.model_changed", writes.single().auditAction)
        assertNull(writes.single().reconcileAt, "A model changes nothing the reconciler acts on")
        assertEquals(listOf("s1:claude-sonnet-5-5"), announced)
    }

    @Test fun noOtherMemberChangesTheModelAnAdministratorIncluded() {
        failure("not_found") { service.setModel(other, null, "s1", "claude-sonnet-5-5") }
        failure("not_found") { service.setModel(admin, null, "s1", "claude-sonnet-5-5") }
        assertEquals("claude-opus-5-5", session.agentModel)
        assertTrue(writes.isEmpty() && announced.isEmpty())
    }

    @Test fun theTeamsModelPolicyDecidesAliasesIncluded() = runBlocking {
        policy = ModelPolicy(allowedModels = listOf("claude-sonnet*"), deniedModels = listOf("*haiku*"))
        failure("model_not_allowed") { service.setModel(owner, null, "s1", "claude-opus-5-5") }
        failure("model_not_allowed") { service.setModel(owner, null, "s1", "gpt-6-sol") }
        failure("model_not_allowed") { service.setModel(owner, null, "s1", "opus") }
        assertEquals("claude-sonnet-5-5", service.setModel(owner, null, "s1", "claude-sonnet-5-5").agentModel)
        assertEquals("sonnet", service.setModel(owner, null, "s1", "sonnet").agentModel)
        assertEquals(2, writes.size)
    }

    @Test fun theCreationModelRulesApply() {
        for (bad in listOf("", "two words", "line\nbreak", "x".repeat(121), "semi;colon")) {
            assertEquals(400, failure("invalid_agent") { service.setModel(owner, null, "s1", bad) }.status)
        }
        assertTrue(writes.isEmpty() && announced.isEmpty())
    }

    @Test fun theSameModelWritesNothingAndAFinishedSessionRefuses() {
        runBlocking { assertEquals("claude-opus-5-5", service.setModel(owner, null, "s1", "claude-opus-5-5").agentModel) }
        assertTrue(writes.isEmpty())
        session = session.copy(desiredState = DesiredState.TERMINATED)
        failure("session_not_running") { service.setModel(owner, null, "s1", "claude-sonnet-5-5") }
        failure("not_found") { service.setModel(owner, null, "missing", "claude-sonnet-5-5") }
        assertTrue(writes.isEmpty())
    }

    @Test fun aReadOnlyKeyCannotChangeTheModel() {
        failure("credential_scope") { service.setModel(owner.copy(serviceScopes = setOf(ServiceScope.CLOUD_READ)), null, "s1", "claude-sonnet-5-5") }
    }

    @Test fun theCatalogOffersWhatThePolicyAllowsPerAgent() {
        val defaults = CloudModelCatalog.options("claude", ModelPolicy()).map { it.id }
        assertTrue("claude-opus-5-5" in defaults && "claude-sonnet-5-5" in defaults && "claude-haiku-5-5" in defaults)
        assertEquals(listOf("claude-sonnet-5-5"), CloudModelCatalog.options("claude", ModelPolicy(allowedModels = listOf("claude-sonnet-5-5"))).map { it.id })
        assertTrue(CloudModelCatalog.options("claude", ModelPolicy(allowedModels = listOf("claude-*"), deniedModels = listOf("claude-*"))).isEmpty())
        val codexIds = CloudModelCatalog.options("codex", ModelPolicy()).map { it.id }
        assertEquals(listOf("gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-astra", "gpt-6-sol", "gpt-6-luna", "gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna"), codexIds)
        assertTrue("gpt-reserve" !in codexIds && "codex-auto-review" !in codexIds, "Hidden entries of Codex's cache are not offered")
        assertTrue(CloudModelCatalog.options("unknown", ModelPolicy()).isEmpty())
        assertEquals("claude-opus", CloudModelCatalog.resolved("Opus"))
    }
}
