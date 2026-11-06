package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.waits.*
import kotlin.test.*

/** The child-session routes decode, classify the credential and encode; confinement is the domain's (OrchestrationService). */
class OrchestrationRoutesTest {
    private val owner = Member("admin-id", "admin", Role.MEMBER, false, false)
    private fun session(id: String, parent: String? = null, depth: Int = 0) = CloudSession(
        id = id, teamId = "team", ownerId = owner.id, name = "Session $id", createdBy = Attribution(owner.id, "c", owner.id), profileId = "p", profileVersion = 1,
        clusterId = "k", namespace = "teamcode", imageReference = "img", imageDigest = null, agentKind = "claude", agentVersion = null, agentModel = null,
        agentEffort = null, labels = emptyMap(), state = CloudSessionState.READY, phase = null, desiredState = DesiredState.RUNNING, generation = 1, currentEpoch = 1,
        idleTimeoutMinutes = 30, lastActivityAt = 0, resumeWorkspace = WorkspaceResume.CONSISTENT, resumeConversation = ConversationResume.NONE,
        failure = null, resumeBlocked = null, pauseRequestedAt = null, pauseForce = false, pausedAt = null, expiresAt = null, terminatedReason = null,
        waitingSince = null, workspace = CloudWorkspace("tc-ws-$id", "standard", 10), createdAt = 0, updatedAt = 0,
        parentSessionId = parent, rootSessionId = parent, depth = depth,
    )
    private val sessions = mutableMapOf("parent" to session("parent"), "child" to session("child", "parent", 1), "other" to session("other"))
    private val spec = CloudProfileSpec("k", "teamcode", "img", orchestration = CloudOrchestrationSpec(true, 3, 2))

    private inline fun <reified T : Any> fake(crossinline answer: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> answer(method.name, args ?: emptyArray()) } as T

    private val sessionStore = fake<CloudSessionStore> { name, args ->
        when (name) {
            "now" -> 100L; "revalidate" -> Unit; "find" -> sessions[args[1]]; "member" -> owner; "profileVersion" -> spec
            "profile" -> CloudProfile("p", "team", "Profile", 1, spec, setOf(owner.id), 0, 0)
            "cluster" -> CloudCluster("k", "team", "Cluster", "https://k", listOf("teamcode"), 10, null, 1, 0, null, 0, 0)
            "settings" -> CloudSettings()
            "insertChild" -> CloudCreateOutcome.Created((args[0] as CloudSession).also { sessions[it.id] = it })
            else -> error("unexpected $name")
        }
    }
    private val conversationStore = fake<CloudConversationStore> { name, _ ->
        when (name) {
            "now" -> 100L; "accessPolicy" -> CloudAccessPolicy.DEFAULT
            "events" -> CloudEventPage(listOf(CloudEvent("child", 7, CloudEventType.AGENT_MESSAGE, 1, 1, """{"text":"done"}""")), 7, 7, null, null)
            else -> error("unexpected $name")
        }
    }
    private val waitStore = fake<WaitStore> { name, args ->
        when (name) {
            "now" -> 100L; "revalidate" -> Unit; "member" -> owner
            "session" -> sessions[args[1]]?.let { WaitSession(it.id, it.teamId, it.ownerId, it.name) }
            "createChildWait" -> WaitChange(args[1] as Wait, true)
            else -> error("unexpected $name")
        }
    }
    private val access = CloudAccessService(conversationStore, sessionStore)
    private val conversation = CloudConversationService(conversationStore, sessionStore, access, { _, _ -> false })
    private val orchestration = OrchestrationService(object : OrchestrationStore {
        override suspend fun executionSession(tokenId: String) = "parent".takeIf { tokenId == "exec" }
        override suspend fun children(teamId: String, parentId: String, beforeId: String?, limit: Int) = CloudSessionPage(sessions.values.filter { it.parentSessionId == parentId }, null)
    }, sessionStore, CloudSessionService(sessionStore, access = access), conversation, WaitService(waitStore))

    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        suspend fun key(token: String, vararg scopes: ServiceScope) = interactive.authenticate("admin")!!.copy(member = owner, tokenId = token, serviceScopes = scopes.toSet())
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "writer" -> key(token, ServiceScope.CLOUD_WRITE)
                "exec" -> key(token, ServiceScope.INFERENCE, ServiceScope.WAITS_SESSION, ServiceScope.SESSIONS_CHILDREN)
                "exec-plain" -> key(token, ServiceScope.INFERENCE, ServiceScope.WAITS_SESSION)
                else -> interactive.authenticate(token)
            }
        }
    }

    @Test fun theChildRoutesServeOnlyTheScopedCredential() = testApplication {
        application { configureGateway(gateway, team = team(), orchestration = orchestration) }
        suspend fun call(method: HttpMethod, path: String, token: String, json: String? = null, key: String? = null) = client.request(path) {
            this.method = method; bearerAuth(token); key?.let { header(CloudHeaders.IDEMPOTENCY_KEY, it) }
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type
        assertTrue(Orchestration.CAPABILITY in Serialization.json.decodeFromString<Capabilities>(call(HttpMethod.Get, "/api/v1/capabilities", "admin").bodyAsText()).features)

        for (token in listOf("admin", "writer", "exec-plain")) {
            val refused = call(HttpMethod.Get, "/api/v1/cloud/children", token)
            assertEquals(HttpStatusCode.Forbidden, refused.status, token); assertEquals("credential_scope", code(refused))
        }
        val page = Serialization.json.decodeFromString<CloudSessionPageView>(call(HttpMethod.Get, "/api/v1/cloud/children", "exec").bodyAsText())
        assertEquals(listOf("child"), page.sessions.map { it.id })
        val child = Serialization.json.decodeFromString<CloudSessionView>(call(HttpMethod.Get, "/api/v1/cloud/children/child", "exec").bodyAsText())
        assertEquals("parent", child.parentSessionId); assertEquals("parent", child.rootSessionId); assertEquals(1, child.depth)
        assertEquals("not_found", code(call(HttpMethod.Get, "/api/v1/cloud/children/other", "exec")))
        assertEquals("not_found", code(call(HttpMethod.Get, "/api/v1/cloud/children/parent", "exec")))
        // The ordinary session view shows the relation to its owner too; a top-level session is its own root.
        assertEquals("parent", sessions.getValue("parent").view().rootSessionId)

        assertEquals("idempotency_key_required", code(call(HttpMethod.Post, "/api/v1/cloud/children", "exec", """{"name":"Fix","initialMessage":"Fix it"}""")))
        assertEquals("invalid_session", code(call(HttpMethod.Post, "/api/v1/cloud/children", "exec", """{"name":"Fix","agent":{"kind":"codex"}}""", "k1")))
        val spawned = call(HttpMethod.Post, "/api/v1/cloud/children", "exec", """{"name":"Fix","labels":{"task":"1"}}""", "k1")
        assertEquals(HttpStatusCode.Accepted, spawned.status, spawned.bodyAsText())
        val created = Serialization.json.decodeFromString<CloudSessionView>(spawned.bodyAsText())
        assertEquals(1, created.depth); assertEquals("parent", created.labels[Orchestration.PARENT_LABEL])

        val events = Serialization.json.decodeFromString<CloudEventPageView>(call(HttpMethod.Get, "/api/v1/cloud/children/child/events?limit=10", "exec").bodyAsText())
        assertEquals(listOf(7L), events.events.map { it.seq })
        assertEquals("invalid_limit", code(call(HttpMethod.Get, "/api/v1/cloud/children/child/events?limit=51", "exec")))

        val waited = call(HttpMethod.Post, "/api/v1/cloud/children/child/wait", "exec", """{"timeoutSeconds":600}""")
        assertEquals(HttpStatusCode.Created, waited.status, waited.bodyAsText())
        val wait = Serialization.json.decodeFromString<WaitView>(waited.bodyAsText())
        assertEquals("child", wait.childSessionId); assertEquals("child", wait.eventSource); assertEquals("parent", wait.sessionId)
        assertEquals("invalid_wait", code(call(HttpMethod.Post, "/api/v1/cloud/children/child/wait", "exec", """{"onTimeout":"never"}""")))
    }

    @Test fun theRoutesAreClassifiedAndGated() {
        for ((method, path) in listOf("GET" to "/api/v1/cloud/children", "POST" to "/api/v1/cloud/children", "GET" to "/api/v1/cloud/children/abc",
            "POST" to "/api/v1/cloud/children/abc/messages", "GET" to "/api/v1/cloud/children/abc/events", "POST" to "/api/v1/cloud/children/abc/interrupt",
            "POST" to "/api/v1/cloud/children/abc/terminate", "POST" to "/api/v1/cloud/children/abc/wait"))
            assertEquals(CredentialAction.CHILDREN, credentialAction(method, path, emptySet()), "$method $path")
        assertEquals(CredentialAction.INTERACTIVE, credentialAction("DELETE", "/api/v1/cloud/children/abc", emptySet()))
        // While cloud sessions are off, starting and messaging children stop; reading, waiting, interrupting and terminating stay open.
        assertTrue(cloudStartsWork("POST", "/api/v1/cloud/children")); assertTrue(cloudStartsWork("POST", "/api/v1/cloud/children/abc/messages"))
        for (path in listOf("/api/v1/cloud/children/abc/wait", "/api/v1/cloud/children/abc/terminate", "/api/v1/cloud/children/abc/interrupt"))
            assertFalse(cloudStartsWork("POST", path), path)
        assertTrue(ServiceScope.SESSIONS_CHILDREN in SESSION_BOUND_SCOPES)
    }
}
