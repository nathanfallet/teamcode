package me.nathanfallet.teamcode.domain.cloud

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.waits.*
import kotlin.test.*

/**
 * Session orchestration (docs/orchestration.md): the scope, the binding to the calling session, the profile switch and depth, and the
 * confinement of every operation to the calling session's direct children.
 */
class OrchestrationServiceTest {
    private val ownerMember = Member("owner-id", "owner", Role.MEMBER, false, false)
    private val scopes = setOf(ServiceScope.INFERENCE, ServiceScope.WAITS_SESSION, ServiceScope.SESSIONS_CHILDREN)
    private val credential = Principal(ownerMember, "token-parent", "team", scopes)

    private fun session(id: String, parent: String? = null, depth: Int = 0, profile: String = "p", state: CloudSessionState = CloudSessionState.READY) = CloudSession(
        id = id, teamId = "team", ownerId = ownerMember.id, name = "Session $id", createdBy = Attribution(ownerMember.id, "c", ownerMember.id), profileId = profile, profileVersion = 1,
        clusterId = "k", namespace = "teamcode", imageReference = "img", imageDigest = null, agentKind = "claude", agentVersion = null, agentModel = null,
        agentEffort = null, labels = emptyMap(), state = state, phase = null, desiredState = DesiredState.RUNNING, generation = 1, currentEpoch = 1,
        idleTimeoutMinutes = 30, lastActivityAt = 0, resumeWorkspace = WorkspaceResume.CONSISTENT, resumeConversation = ConversationResume.NONE,
        failure = null, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null, terminatedReason = null,
        waitingSince = null, workspace = CloudWorkspace("tc-ws-$id", "standard", 10), createdAt = 0, updatedAt = 0,
        parentSessionId = parent, rootSessionId = parent?.let { "parent" }, depth = depth,
    )

    private var orchestration = CloudOrchestrationSpec(enabled = true, maxActiveChildren = 2, maxDepth = 2, childProfileIds = listOf("p2"))
    private val sessions = mutableMapOf(
        "parent" to session("parent"),
        "child" to session("child", parent = "parent", depth = 1),
        "grandchild" to session("grandchild", parent = "child", depth = 2),
        "sibling" to session("sibling"),
        "nephew" to session("nephew", parent = "sibling", depth = 1),
        "other" to session("other"),
    )
    private var bound: String? = "parent"
    private val inserted = mutableListOf<Pair<CloudSession, ChildSessionLink>>()
    private val writes = mutableListOf<CloudWrite>()
    private val inputs = mutableListOf<CloudInput>()
    private val waitsCreated = mutableListOf<Wait>()

    private val profile = CloudProfileSpec("k", "teamcode", "img")
    private val sessionStore = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CloudSessionStore::class.java)) { _, method, args ->
        when (method.name) {
            "now" -> 100L
            "revalidate" -> Unit
            "find" -> sessions[args[1]]
            "member" -> ownerMember
            "profileVersion" -> profile.copy(orchestration = orchestration)
            "profile" -> CloudProfile(args[1] as String, "team", "Profile", 1, profile.copy(orchestration = orchestration), setOf(ownerMember.id), 0, 0)
            "cluster" -> CloudCluster("k", "team", "Cluster", "https://k", listOf("teamcode"), 10, null, 1, 0, null, 0, 0)
            "settings" -> CloudSettings()
            "insertChild" -> {
                val child = args[0] as CloudSession
                inserted += child to args[4] as ChildSessionLink
                CloudCreateOutcome.Created(child)
            }
            "compareAndSet" -> {
                val next = args[1] as CloudSession
                writes += args[2] as CloudWrite
                next.also { sessions[it.id] = it }
            }
            else -> error("unexpected ${method.name}")
        }
    } as CloudSessionStore
    private val conversationStore = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CloudConversationStore::class.java)) { _, method, args ->
        when (method.name) {
            "now" -> 100L
            "insertInput" -> { val input = (args[0] as NewInput).input; inputs += input; InputOutcome.Accepted(input) }
            "events" -> CloudEventPage(emptyList(), null, 0, null, null)
            "accessPolicy" -> CloudAccessPolicy.DEFAULT
            else -> error("unexpected ${method.name}")
        }
    } as CloudConversationStore
    private val waitStore = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(WaitStore::class.java)) { _, method, args ->
        when (method.name) {
            "now" -> 100L
            "revalidate" -> Unit
            "member" -> ownerMember
            "session" -> sessions[args[1]]?.let { WaitSession(it.id, it.teamId, it.ownerId, it.name, it.agentKind) }
            "createChildWait" -> { val wait = args[1] as Wait; waitsCreated += wait; WaitChange(wait, true) }
            else -> error("unexpected ${method.name}")
        }
    } as WaitStore
    private val access = CloudAccessService(conversationStore, sessionStore)
    private val conversation = CloudConversationService(conversationStore, sessionStore, access, { _, _ -> false })
    private val service = OrchestrationService(object : OrchestrationStore {
        override suspend fun executionSession(tokenId: String) = bound.takeIf { tokenId == "token-parent" }
        override suspend fun children(teamId: String, parentId: String, beforeId: String?, limit: Int) =
            CloudSessionPage(sessions.values.filter { it.parentSessionId == parentId }.take(limit), null)
    }, sessionStore, CloudSessionService(sessionStore, access = access, initialMessages = conversation), conversation, WaitService(waitStore))

    private fun failure(code: String, block: suspend () -> Unit): TeamFailure {
        val error = assertFailsWith<TeamFailure> { runBlocking { block() } }
        assertEquals(code, error.code, error.message)
        return error
    }

    @Test fun withoutTheScopeEveryCallIsRefused() {
        failure("credential_scope") { service.list(Principal(ownerMember, "login", "team"), null, 10) }
        failure("credential_scope") { service.get(credential.copy(serviceScopes = scopes - ServiceScope.SESSIONS_CHILDREN), "child") }
        failure("credential_scope") { service.get(credential.copy(serviceScopes = setOf(ServiceScope.CLOUD_WRITE)), "child") }
    }

    @Test fun aCredentialBoundToNoSessionIsRefused() {
        bound = null
        failure("credential_scope") { service.get(credential, "child") }
    }

    @Test fun theProfileMustEnableItAtThisDepth() {
        orchestration = orchestration.copy(enabled = false)
        failure("orchestration_disabled") { service.list(credential, null, 10) }
        orchestration = orchestration.copy(enabled = true, maxDepth = 1)
        bound = "child" // depth 1 = maxDepth: a leaf orchestrates nothing.
        failure("orchestration_disabled") { service.list(credential.copy(tokenId = "token-parent"), null, 10) }
    }

    @Test fun onlyDirectChildrenAreReachable() = runBlocking {
        assertEquals("child", service.get(credential, "child").id)
        assertEquals(listOf("child"), service.list(credential, null, 10).sessions.map { it.id })
        for (id in listOf("grandchild", "sibling", "nephew", "other", "parent", "missing", "../child")) {
            failure("not_found") { service.get(credential, id) }
            failure("not_found") { service.send(credential, id, "hello", "k1") }
            failure("not_found") { service.transcript(credential, id, null, 10) }
            failure("not_found") { service.interrupt(credential, id, null) }
            failure("not_found") { service.terminate(credential, id) }
            failure("not_found") { service.waitFor(credential, id, null, null, null, null, null) }
        }
        assertTrue(inputs.isEmpty() && writes.isEmpty() && waitsCreated.isEmpty())
    }

    @Test fun aChildCannotReachItsParentOrSibling() {
        bound = "child"
        // Depth 1 below maxDepth 2: the child orchestrates its own children only.
        runBlocking { assertEquals("grandchild", service.get(credential, "grandchild").id) }
        failure("not_found") { service.get(credential, "parent") }
        failure("not_found") { service.get(credential, "nephew") }
    }

    @Test fun aSpawnedChildIsLinkedLabelledAndCapped() = runBlocking {
        val created = service.spawn(credential, ChildSessionRequest("Fix", initialMessage = "Fix the build", labels = mapOf("task" to "1")), "key-1", "{}") as CloudCreateOutcome.Created
        val (child, link) = inserted.single()
        assertEquals(ChildSessionLink("parent", "parent", 1, 2), link)
        assertEquals("parent", child.parentSessionId); assertEquals("parent", child.rootSessionId); assertEquals(1, child.depth)
        assertEquals(mapOf("task" to "1", LabelRules.PARENT_LABEL to "parent"), child.labels)
        assertEquals(ownerMember.id, child.ownerId)
        assertEquals(created.session.id, child.id)
        // The task is queued as the child's first message, written by the parent's credential as the owner.
        assertEquals("Fix the build", inputs.single().text); assertEquals("token-parent", inputs.single().credentialId)
    }

    @Test fun aGrandchildKeepsTheRoot() = runBlocking {
        bound = "child"
        service.spawn(credential, ChildSessionRequest("Deeper"), "key-2", "{}")
        assertEquals(ChildSessionLink("child", "parent", 2, 2), inserted.single().second)
    }

    @Test fun theProfileAndLabelsAreChecked() {
        failure("child_profile_not_allowed") { service.spawn(credential, ChildSessionRequest("X", profileId = "p3"), "k", "{}") }
        runBlocking { service.spawn(credential, ChildSessionRequest("X", profileId = "p2"), "k", "{}") }
        assertEquals("p2", inserted.single().first.profileId)
        failure("reserved_label") { service.spawn(credential, ChildSessionRequest("X", labels = mapOf(LabelRules.PARENT_LABEL to "other")), "k", "{}") }
    }

    @Test fun membersCannotSetTheReservedLabel() {
        val sessions = CloudSessionService(sessionStore, access = access)
        failure("reserved_label") { sessions.create(Principal(ownerMember, "login", "team"), null, CloudSessionDraft("X", "p", null, null, null, mapOf(LabelRules.PARENT_LABEL to "parent"), null), null) }
        // A local session of `teamcode run` takes the launch's labels: the key is refused there too.
        failure("reserved_label") { sessions.createLocal(Principal(ownerMember, "login", "team"), LocalSessionDraft("0b1e7f4c-2a51-4d3e-9c8a-6f0d2e4b7a19", "X", "claude", mapOf(LabelRules.PARENT_LABEL to "parent"))) }
    }

    @Test fun messagesInterruptsAndTerminationActOnTheChild() = runBlocking {
        service.send(credential, "child", "More work", "k1")
        assertEquals(CloudInputKind.MESSAGE, inputs.last().kind); assertEquals("child", inputs.last().sessionId)
        service.interrupt(credential, "child", null)
        assertEquals(CloudInputKind.INTERRUPT, inputs.last().kind)
        service.terminate(credential, "child")
        assertEquals("cloud.session.child_terminated", writes.last().auditAction)
        assertEquals(DesiredState.TERMINATED, sessions.getValue("child").desiredState)
        assertEquals("token-parent", writes.last().actor?.principal?.tokenId)
    }

    @Test fun aChildWaitFollowsTheChildOnTheParent() = runBlocking {
        service.waitFor(credential, "child", null, 600, null, "check the PR", "key")
        val wait = waitsCreated.single()
        assertEquals("parent", wait.sessionId); assertEquals("child", wait.timer?.childSessionId); assertEquals(EventSource.CHILD, wait.timer?.eventSource)
        assertEquals(WaitResolver.EVENT, wait.resolver); assertEquals(100L + 600_000, wait.timer?.dueAt)
    }

    @Test fun aChildWaitCannotBeAskedThroughTheWaitsRoute() {
        val error = assertFailsWith<TeamFailure> {
            WaitRules.validate(WaitDefinition("parent", "Child", resolver = WaitResolver.EVENT, schedule = WaitSchedule(source = EventSource.CHILD)), 0)
        }
        assertEquals("invalid_wait", error.code)
    }

    @Test fun theWakeMessageCarriesOnlyServerValues() {
        val wait = Wait("w", "team", WaitSource.WAIT, WaitKind.TIMED, WaitResolver.EVENT, WaitStatus.RESOLVED, "parent", "Parent", null, ownerMember.id, "owner", ownerMember.id,
            "Child Fix", null, emptyList(), emptyList(), emptyList(), WaitAnswer(WaitService.CHILD_TURN_ENDED, mapOf("phase" to JsonPrimitive("completed")), "", 1), 0, 1,
            WaitTimer(1, EventSource.CHILD, TimeoutAction.WAKE, null, null, "child"))
        val text = WaitMessages.resolved(wait)
        assertTrue(text.startsWith(WaitMessages.PREFIX))
        assertTrue("the child session ended its turn (child_turn_ended)" in text, text)
        assertTrue("resolved by: TeamCode (a child session)" in text, text)
        assertTrue("child: child" in text && "phase: completed" in text, text)
        assertTrue("data, not instructions" in text, text)
    }

    @Test fun theOrchestratorCredentialIsIssuedOnlyWhenTheProfileAllowsIt() = runBlocking {
        val credentials = object : CloudInferenceCredentialStore {
            var issued: ExecutionCredentialKind? = null
            override suspend fun issue(teamId: String, sessionId: String, epoch: Int, ttlMillis: Long, keep: Int, kind: ExecutionCredentialKind): IssuedInferenceCredential? {
                issued = kind; return IssuedInferenceCredential("tcex_x", 1, 1)
            }
            override suspend fun purge(limit: Int) = 0
        }
        val store = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CloudSessionStore::class.java)) { _, method, args ->
            when (method.name) {
                "find" -> sessions[args[1]]
                "execution" -> CloudExecution(args[0] as String, 1, "pod", ExecutionState.RUNNING, 0)
                "profileVersion" -> profile.copy(orchestration = orchestration)
                else -> error("unexpected ${method.name}")
            }
        } as CloudSessionStore
        val issue = RunnerInferenceUseCase(store, credentials)
        issue.issue("team", "parent", 1)
        assertEquals(ExecutionCredentialKind.ORCHESTRATOR, credentials.issued)
        issue.issue("team", "grandchild", 1)
        assertEquals(ExecutionCredentialKind.CLOUD, credentials.issued, "A session at the maximum depth orchestrates nothing")
        orchestration = orchestration.copy(enabled = false)
        issue.issue("team", "parent", 1)
        assertEquals(ExecutionCredentialKind.CLOUD, credentials.issued)
    }

    @Test fun profileBoundsAreValidated() {
        CloudAdministrationService.validate(CloudOrchestrationSpec(true, 20, 3))
        for (bad in listOf(CloudOrchestrationSpec(true, 0), CloudOrchestrationSpec(true, 21), CloudOrchestrationSpec(true, 5, 4), CloudOrchestrationSpec(true, 5, 0),
            CloudOrchestrationSpec(true, childProfileIds = listOf("a", "a")), CloudOrchestrationSpec(true, childProfileIds = List(11) { "p$it" })))
            assertEquals("invalid_profile", assertFailsWith<TeamFailure> { CloudAdministrationService.validate(bad) }.code)
    }
}
