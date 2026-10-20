package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.emptyFlow
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** Transport contract of the cloud routes over in-memory ports; MySQL behavior is covered in infrastructure. */
class CloudRoutesTest {
    private val admin = Member("admin-id", "admin", Role.ADMIN, false, false)
    private val colleague = Member("colleague-id", "colleague", Role.MEMBER, false, false)

    private inner class Store : CloudSessionStore {
        val sessions = linkedMapOf<String, CloudSession>()
        val keys = mutableMapOf<String, Pair<String, String>>()
        var clock = 1_000L
        val profile = CloudProfile("profile-id", "default", "toolchain", 1, CloudProfileSpec("cluster-id", "teamcode", "registry.example/toolchain:1"), setOf(colleague.id), 1, 1)
        override suspend fun now() = clock++
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String) = listOf(admin, colleague).find { it.id == memberId }
        override suspend fun profile(teamId: String, profileId: String) = profile.takeIf { it.id == profileId }
        override suspend fun profileVersion(teamId: String, profileId: String, version: Int) = profile.spec
        override suspend fun cluster(teamId: String, clusterId: String) = CloudCluster("cluster-id", "default", "lab", "https://k8s.example.test", listOf("teamcode"), 10, "standard", 1, 0, null, 1, 1)
        override suspend fun settings(teamId: String) = CloudSettings()
        override suspend fun insert(session: CloudSession, actor: CloudActor, idempotency: IdempotencyRequest?, retainedLimit: Int): CloudCreateOutcome {
            if (idempotency != null) keys[actor.principal.member.id + idempotency.key]?.let { (digest, id) ->
                if (digest != idempotency.requestDigest) throw TeamFailure("idempotency_conflict", 422, "conflict")
                return CloudCreateOutcome.Replayed(sessions.getValue(id))
            }
            val created = session.copy(generation = 1)
            sessions[created.id] = created
            idempotency?.let { keys[actor.principal.member.id + it.key] = it.requestDigest to created.id }
            return CloudCreateOutcome.Created(created)
        }
        override suspend fun find(teamId: String, id: String) = sessions[id]
        override suspend fun list(teamId: String, query: CloudSessionQuery) =
            CloudSessionPage(sessions.values.filter { query.owner == null || it.ownerId == query.owner }.filter { s -> query.labels.all { (k, v) -> s.labels[k] == v } }, null)
        override suspend fun executions(teamId: String, sessionId: String) = emptyList<CloudExecution>()
        val runs = mutableMapOf<Pair<String, Int>, CloudExecution>()
        override suspend fun execution(sessionId: String, epoch: Int): CloudExecution? = runs[sessionId to epoch]
        override suspend fun compareAndSet(expected: CloudSession, next: CloudSession, write: CloudWrite): CloudSession? {
            if (sessions[expected.id]?.generation != expected.generation) return null
            write.updateExecution?.let { runs[it.sessionId to it.epoch] = it }
            return next.copy(generation = expected.generation + 1).also { sessions[it.id] = it }
        }
        override suspend fun claimDue(owner: String, leaseMillis: Long, limit: Int) = emptyList<CloudSession>()
        override suspend fun release(sessionId: String, owner: String, reconcileAt: Long?) = Unit
        override suspend fun startExecution(expected: CloudSession, next: CloudSession, execution: CloudExecution, leaseOwner: String, memberRunningLimit: Int) = StartOutcome.Conflict
        override suspend fun heartbeat(sessionId: String, epoch: Int, runnerReady: Boolean): Boolean {
            val run = runs[sessionId to epoch]?.takeIf { sessions[sessionId]?.currentEpoch == epoch } ?: return false
            runs[sessionId to epoch] = run.copy(lastHeartbeatAt = clock, runnerReady = runnerReady)
            return true
        }
        override suspend fun liveObjects(clusterId: String) = LiveObjects(emptySet(), emptySet())
        override suspend fun namespaces(clusterId: String) = emptySet<String>()
        override suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int) = Unit
    }
    private class Admin : CloudAdminStore {
        var settings = CloudSettings()
        override suspend fun clusters(actor: Principal) = emptyList<CloudCluster>()
        override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput) = error("unused")
        override suspend fun profiles(actor: Principal) = emptyList<CloudProfile>()
        override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) = error("unused")
        override suspend fun settings(actor: Principal) = settings
        override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings.also { this.settings = it }
    }
    private fun team(): TeamService = object : TeamService by FakeTeam() {
        override suspend fun authenticate(token: String): Principal? = when (token) {
            "admin" -> Principal(admin, "admin-session", "default")
            "colleague" -> Principal(colleague, "colleague-session", "default")
            "service-admin" -> Principal(admin, "admin-key", "default", ServiceScope.entries.toSet())
            else -> null
        }
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) =
            receive(GenerationHead(200, "text/event-stream"), emptyFlow())
    }

    @Test fun sessionsRequireInteractiveCallersIdempotencyAndAdministratorDelegation() = testApplication {
        val store = Store()
        application { configureGateway(gateway, team = team(), cloudSessions = CloudSessionService(store), cloudAdmin = CloudAdministrationService(Admin(), FakeCluster), publicOrigin = "http://localhost") }
        val body = """{"name":"Task 1234","profileId":"profile-id","labels":{"orchestrator.task":"1234"}}"""
        suspend fun create(token: String, key: String?, onBehalf: String? = null, json: String = body) = client.post("/api/v1/cloud/sessions") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(json)
            key?.let { header(CloudHeaders.IDEMPOTENCY_KEY, it) }; onBehalf?.let { header(CloudHeaders.ON_BEHALF_OF, it) }
        }
        assertTrue("cloud.sessions.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("admin") }.bodyAsText()).features)
        assertEquals(HttpStatusCode.Forbidden, create("service-admin", "k1").status, "Service keys never reach cloud routes")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/sessions") { bearerAuth("service-admin") }.status)
        assertEquals(HttpStatusCode.BadRequest, create("admin", null).status)
        assertEquals(HttpStatusCode.BadRequest, create("admin", "has space").status)
        assertEquals(HttpStatusCode.Forbidden, create("colleague", "k1", onBehalf = "admin-id").status)
        assertTrue(store.sessions.isEmpty())
        val first = create("admin", "k1", onBehalf = "colleague-id")
        assertEquals(HttpStatusCode.Accepted, first.status)
        val view = Serialization.json.decodeFromString<CloudSessionView>(first.bodyAsText())
        assertEquals("colleague-id", view.ownerId); assertEquals(CloudAttributionView("admin-id", "admin-session", "colleague-id"), view.createdBy)
        assertEquals("creating", view.state); assertEquals("running", view.desiredState)
        val replay = create("admin", "k1", onBehalf = "colleague-id", json = """{"labels":{"orchestrator.task":"1234"},"profileId":"profile-id","name":"Task 1234"}""")
        assertEquals("true", replay.headers[CloudHeaders.IDEMPOTENCY_REPLAYED], "Field order does not change the canonical request")
        assertEquals(view.id, Serialization.json.decodeFromString<CloudSessionView>(replay.bodyAsText()).id)
        assertEquals(HttpStatusCode.UnprocessableEntity, create("admin", "k1").status, "The effective member is part of the request digest")
        assertEquals(1, store.sessions.size)
        // The owner sees it; another member does not; pause and delete are natural, idempotent operations.
        val sdk = TeamCodeClient("http://localhost", { "colleague" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            assertEquals(view.id, sdk.cloudSession(view.id).id)
            assertEquals(listOf(view.id), sdk.cloudSessions(labels = mapOf("orchestrator.task" to "1234")).sessions.map { it.id })
            store.sessions[view.id] = store.sessions.getValue(view.id).copy(state = CloudSessionState.READY)
            val paused = sdk.pauseCloudSession(view.id)
            assertEquals("paused", paused.desiredState)
            assertEquals(paused.generation, sdk.pauseCloudSession(view.id).generation)
            sdk.deleteCloudSession(view.id); sdk.deleteCloudSession(view.id)
            assertEquals("terminated", sdk.cloudSession(view.id).desiredState)
        } finally { sdk.close() }
        store.sessions["admin-only"] = store.sessions.getValue(view.id).copy(id = "admin-only", ownerId = "admin-id")
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cloud/sessions/admin-only") { bearerAuth("colleague") }.status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/cloud/sessions/admin-only/resume") { bearerAuth("colleague") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/sessions?owner=all") { bearerAuth("colleague") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/cloud/sessions?owner=all&label=orchestrator.task=1234") { bearerAuth("admin") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/cloud/sessions?state=sleeping") { bearerAuth("admin") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/cloud/sessions?limit=51") { bearerAuth("admin") }.status)
    }

    @Test fun retentionSettingsArePersistentAdministratorSettings() = testApplication {
        val settings = Admin()
        application { configureGateway(gateway, team = team(), cloudSessions = CloudSessionService(Store()), cloudAdmin = CloudAdministrationService(settings, FakeCluster), publicOrigin = "http://localhost") }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/settings") { bearerAuth("colleague") }.status)
        val put = client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"pausedRetentionDays":null,"failedWorkspaceRetentionDays":3,"maxRunningSessionsPerMember":5,"maxRetainedSessionsPerMember":20}""") }
        assertEquals(HttpStatusCode.OK, put.status)
        assertNull(settings.settings.pausedRetentionDays, "null keeps paused workspaces indefinitely")
        // A partial body must not silently reset the omitted fields (including the indefinite retention) to defaults.
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"failedWorkspaceRetentionDays":9}""") }.status)
        assertNull(settings.settings.pausedRetentionDays)
        assertEquals(5, settings.settings.maxRunningSessionsPerMember)
        assertEquals(3, settings.settings.failedWorkspaceRetentionDays)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"pausedRetentionDays":0}""") }.status)
        val page = client.get("/dashboard/cloud") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertTrue(page.bodyAsText().contains("Paused retention"))
        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/cloud") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=wrong&pausedRetentionDays=30&failedWorkspaceRetentionDays=7&maxRunning=2&maxRetained=20")
        }.status)
        assertEquals(HttpStatusCode.Found, client.post("/dashboard/cloud") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=admin-session&pausedRetentionDays=14&failedWorkspaceRetentionDays=7&maxRunning=2&maxRetained=20")
        }.status)
        assertEquals(14, settings.settings.pausedRetentionDays)
    }

    @Test fun runnerChannelAcceptsOnlyTheCurrentPodByItsProjectedToken() = testApplication {
        val store = Store()
        val reviews = mutableListOf<String>()
        val cluster = object : ClusterPort by FakeCluster {
            override suspend fun reviewToken(clusterId: String, token: String, audience: String): TokenIdentity? {
                reviews += token
                assertEquals("teamcode", audience)
                return when (token) {
                    "projected-pod-token-1" -> TokenIdentity("teamcode", "default", "tc-s1-e1", "uid-1")
                    "projected-other-namespace" -> TokenIdentity("elsewhere", "default", "tc-s1-e1", "uid-2")
                    "projected-unbound-token" -> TokenIdentity("teamcode", "default", null, null)
                    else -> null
                }
            }
        }
        val sessions = CloudSessionService(store)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), cluster),
                cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, cluster)), publicOrigin = "http://localhost")
        }
        val created = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Run", "profile-id", null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        store.sessions.clear()
        store.sessions["s1"] = created.copy(id = "s1", currentEpoch = 1, generation = 1)
        store.runs["s1" to 1] = CloudExecution("s1", 1, "tc-s1-e1", ExecutionState.PENDING, 1)
        suspend fun call(path: String, token: String?, body: String) = client.post("/api/v1/cloud/runner/$path") {
            token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody(body)
        }
        val register = """{"teamId":"default","sessionId":"s1","epoch":1}"""
        assertEquals(HttpStatusCode.Unauthorized, call("register", null, register).status)
        assertEquals(HttpStatusCode.Unauthorized, call("register", "colleague-session-token", register).status, "Member credentials are not runner credentials")
        assertEquals(HttpStatusCode.Unauthorized, call("register", "projected-other-namespace", register).status)
        assertEquals(HttpStatusCode.Unauthorized, call("register", "projected-unbound-token", register).status)
        assertEquals(HttpStatusCode.Conflict, call("register", "projected-pod-token-1", """{"teamId":"default","sessionId":"s1","epoch":2}""").status, "Another epoch is fenced")
        val runner = TeamCodeClient("http://localhost", { "projected-pod-token-1" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            assertEquals(RunnerInstructionView("running", null, 1), runner.runnerRegister(RunnerRegisterRequest("default", "s1", 1)))
            assertEquals(CloudSessionState.PREPARING, store.sessions.getValue("s1").state)
            runner.runnerHeartbeat(RunnerHeartbeatRequest("default", "s1", 1, ready = true))
            assertEquals(CloudSessionState.READY, store.sessions.getValue("s1").state)
            assertEquals(409, assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> {
                runner.runnerCheckpoint(RunnerCheckpointRequest("default", "s1", 1, completed = true))
            }.statusCode, "No checkpoint before a pause is requested")
        } finally { runner.close() }
        assertEquals(1, reviews.count { it == "projected-pod-token-1" }, "Token reviews are cached")
    }

    private object FakeCluster : ClusterPort {
        override suspend fun storageClass(clusterId: String, name: String?) = StorageClassObservation("standard", "Delete")
        override suspend fun ensureClaim(clusterId: String, request: WorkspaceRequest) = Unit
        override suspend fun claim(clusterId: String, namespace: String, name: String): ClaimObservation? = null
        override suspend fun deleteClaim(clusterId: String, namespace: String, name: String) = Unit
        override suspend fun volume(clusterId: String, name: String): VolumeObservation? = null
        override suspend fun orphanedVolumes(clusterId: String) = emptyList<VolumeObservation>()
        override suspend fun createPod(clusterId: String, request: PodRequest) = Unit
        override suspend fun pod(clusterId: String, namespace: String, name: String): PodObservation? = null
        override suspend fun deletePod(clusterId: String, namespace: String, name: String, force: Boolean) = Unit
        override suspend fun managed(clusterId: String, namespace: String) = ManagedObjects(emptyList(), emptyList())
        override suspend fun reviewToken(clusterId: String, token: String, audience: String): TokenIdentity? = null
    }
}
