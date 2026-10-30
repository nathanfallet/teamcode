package me.nathanfallet.teamcode.presentation

import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
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
        var profile = CloudProfile("profile-id", "default", "toolchain", 1, CloudProfileSpec("cluster-id", "teamcode", "registry.example/toolchain:1"), setOf(colleague.id), 1, 1)
        /** A profile assigned to nobody: only administrators may use it. */
        val reserved = CloudProfile("reserved-id", "default", "reserved", 1, profile.spec, emptySet(), 1, 1)
        override suspend fun now() = clock++
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String) = listOf(admin, colleague).find { it.id == memberId }
        override suspend fun profile(teamId: String, profileId: String) = listOf(profile, reserved).find { it.id == profileId }
        override suspend fun profileVersion(teamId: String, profileId: String, version: Int) = profile.spec
        override suspend fun cluster(teamId: String, clusterId: String) = CloudCluster("cluster-id", "default", "lab", "https://k8s.example.test", listOf("teamcode"), 10, "standard", 1, 0, null, 1, 1)
        var cloudSettings = CloudSettings()
        override suspend fun settings(teamId: String) = cloudSettings
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
            CloudSessionPage(sessions.values.filter { query.owner == null || it.ownerId == query.owner }.filter { s -> query.labels.all { (k, v) -> s.labels[k] == v } }
                .filter { query.kinds.isEmpty() || it.kind in query.kinds }, null)
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
        override suspend fun heartbeat(sessionId: String, epoch: Int, runnerReady: Boolean, background: Boolean): Boolean {
            val run = runs[sessionId to epoch]?.takeIf { sessions[sessionId]?.currentEpoch == epoch } ?: return false
            runs[sessionId to epoch] = CloudIdleRules.heartbeat(run, background, clock).copy(lastHeartbeatAt = clock, runnerReady = runnerReady)
            return true
        }
        /** Login sessions with an installation, by token id: two devices of the colleague's. */
        val devices = mapOf("colleague-session" to "laptop-id", "colleague-desktop-session" to "desktop-id", "admin-session" to "admin-device")
        override suspend fun deviceOf(principal: Principal) = if (principal.serviceScopes != null) null else devices[principal.tokenId]
        override suspend fun startHostExecution(expected: CloudSession, next: CloudSession, execution: CloudExecution, ended: CloudExecution?, actor: CloudActor): CloudSession? {
            if (sessions[expected.id]?.generation != expected.generation) return null
            ended?.let { runs[it.sessionId to it.epoch] = it }
            runs[execution.sessionId to execution.epoch] = execution
            return next.copy(generation = expected.generation + 1).also { sessions[it.id] = it }
        }
        override suspend fun liveObjects(clusterId: String) = LiveObjects(emptySet(), emptySet())
        override suspend fun namespaces(clusterId: String) = emptySet<String>()
        override suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int) = Unit
    }
    private inner class Admin(val profiles: List<CloudProfile> = emptyList()) : CloudAdminStore {
        var settings = CloudSettings()
        override suspend fun clusters(actor: Principal) = listOf(CloudCluster("cluster-id", "default", "lab", "https://k8s.example.test", listOf("teamcode"), 10, "standard", 1, 0, null, 1, 1))
        override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput) = error("unused")
        /** As the SQL store: every profile for an administrator, the assigned ones for a member. */
        override suspend fun profiles(actor: Principal) = profiles.filter { actor.member.role.isAdministrator || actor.member.id in it.assignedMemberIds }
        override suspend fun member(actor: Principal, memberId: String) = listOf(admin, colleague).find { it.id == memberId }
        override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) = error("unused")
        override suspend fun settings(actor: Principal) = settings
        override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings.also { this.settings = it }
    }
    private fun team(): TeamService = object : TeamService by FakeTeam() {
        override suspend fun authenticate(token: String): Principal? = when (token) {
            "admin" -> Principal(admin, "admin-session", "default")
            "colleague" -> Principal(colleague, "colleague-session", "default")
            "colleague-desktop" -> Principal(colleague, "colleague-desktop-session", "default")
            // Login tokens of realistic length, for the runner channel.
            "colleague-laptop-login-0123456789" -> Principal(colleague, "colleague-session", "default")
            "colleague-desktop-login-0123456789" -> Principal(colleague, "colleague-desktop-session", "default")
            "admin-login-token-0123456789" -> Principal(admin, "admin-session", "default")
            "service-admin" -> Principal(admin, "admin-key", "default", ServiceScope.entries.toSet() - setOf(ServiceScope.CLOUD_READ, ServiceScope.CLOUD_WRITE, ServiceScope.ADMIN_CLOUD, ServiceScope.TEAM_READ))
            "colleague-read" -> Principal(colleague, "colleague-read-key", "default", setOf(ServiceScope.CLOUD_READ))
            "colleague-write" -> Principal(colleague, "colleague-write-key", "default", setOf(ServiceScope.CLOUD_WRITE))
            "admin-write" -> Principal(admin, "admin-write-key", "default", setOf(ServiceScope.CLOUD_WRITE))
            "admin-cloud" -> Principal(admin, "admin-cloud-key", "default", setOf(ServiceScope.ADMIN_CLOUD))
            "admin-read" -> Principal(admin, "admin-read-key", "default", setOf(ServiceScope.TEAM_READ))
            "admin-usage" -> Principal(admin, "admin-usage-key", "default", setOf(ServiceScope.USAGE_READ, ServiceScope.CLOUD_READ))
            "admin-read-write" -> Principal(admin, "admin-read-write-key", "default", setOf(ServiceScope.TEAM_READ, ServiceScope.CLOUD_WRITE))
            // A cloud session's execution credential, given every scope to show the refusal does not depend on them.
            "pod" -> Principal(colleague, "pod-execution", "default", ServiceScope.entries.toSet())
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
        assertEquals(HttpStatusCode.Forbidden, create("service-admin", "k1").status, "Keys without a cloud scope never reach cloud routes")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/sessions") { bearerAuth("service-admin") }.status)
        assertEquals(HttpStatusCode.BadRequest, create("admin", null).status)
        assertEquals(HttpStatusCode.BadRequest, create("admin", "has space").status)
        assertEquals(HttpStatusCode.Forbidden, create("colleague", "k1", onBehalf = "admin-id").status)
        assertFalse("cloud.sessions.initial-message.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("admin") }.bodyAsText()).features)
        val unsupported = create("colleague", "k0", json = """{"name":"Task","profileId":"profile-id","initialMessage":"Start"}""")
        assertEquals(HttpStatusCode.BadRequest, unsupported.status)
        assertTrue("initial_message_unsupported" in unsupported.bodyAsText(), "Never dropped silently by a server without conversations")
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
        kindsAndHosts(client, store)
    }

    /** A list without `kind` keeps to cloud sessions, so a client from before remote sessions never receives one. */
    private suspend fun kindsAndHosts(client: io.ktor.client.HttpClient, store: Store) {
        val cloud = store.sessions.getValue("admin-only")
        store.sessions["remote-one"] = cloud.copy(id = "remote-one", kind = CloudSessionKind.REMOTE,
            host = CloudSessionHost("device-1", "teamcode", HostDevice("Work laptop", "macos", "15.1")))
        suspend fun ids(query: String) = Serialization.json.decodeFromString<CloudSessionPageView>(
            client.get("/api/v1/cloud/sessions?owner=all$query") { bearerAuth("admin") }.bodyAsText()).sessions
        assertFalse(ids("").any { it.id == "remote-one" })
        assertTrue(ids("").all { it.kind == "cloud" && it.host == null })
        val remote = ids("&kind=remote").single()
        assertEquals("remote-one", remote.id)
        assertEquals(CloudSessionHostView("device-1", "Work laptop", "macos", "15.1", "teamcode", permissionMode = "ask"), remote.host)
        assertEquals("remote" to "remote", remote.profile.id to remote.image.reference, "Reserved placeholders where a remote session has no profile or image")
        assertEquals("not_applicable", remote.workspaceDeletion.state)
        assertEquals(setOf("admin-only", "remote-one"), ids("&kind=cloud&kind=remote").map { it.id }.filter { it in setOf("admin-only", "remote-one") }.toSet())
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/cloud/sessions?kind=laptop") { bearerAuth("admin") }.status)
        store.sessions.remove("remote-one")
    }

    @Test fun profilesListWhatTheMemberCanUseAndCreationTellsAMissingProfileFromAnUnassignedOne() = testApplication {
        val store = Store()
        application {
            configureGateway(gateway, team = team(), cloudSessions = CloudSessionService(store), cloudAdmin = CloudAdministrationService(Admin(listOf(store.profile, store.reserved)), FakeCluster),
                publicOrigin = "http://localhost")
        }
        suspend fun profiles(token: String, query: String = "") = client.get("/api/v1/cloud/profiles$query") { bearerAuth(token) }
        suspend fun ids(token: String, query: String = "") = Serialization.json.decodeFromString<List<CloudProfileView>>(profiles(token, query).bodyAsText()).map { it.id }
        assertTrue("cloud.profiles.member.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features)
        assertEquals(listOf("profile-id"), ids("colleague"), "A member lists the profiles assigned to them")
        assertEquals(listOf("profile-id"), ids("colleague-read"))
        assertEquals(listOf("profile-id", "reserved-id"), ids("admin"))
        assertEquals("lab", Serialization.json.decodeFromString<List<CloudProfileView>>(profiles("colleague").bodyAsText()).single().clusterName, "The cluster's name, not only its id")
        // An administrator lists what another member can use, as when creating on their behalf.
        assertEquals(listOf("profile-id"), ids("admin", "?member=colleague-id"))
        assertEquals(listOf("profile-id"), ids("admin-cloud", "?member=colleague-id"))
        assertEquals(listOf("profile-id", "reserved-id"), ids("admin", "?member=admin-id"), "An administrator member can use every profile")
        assertEquals(listOf("profile-id"), ids("colleague", "?member=colleague-id"))
        assertEquals(HttpStatusCode.Forbidden, profiles("colleague", "?member=admin-id").status)
        assertEquals(HttpStatusCode.Forbidden, profiles("admin-write", "?member=colleague-id").status, "A key without admin:cloud is only its owner")
        assertTrue("invalid_effective_member" in profiles("admin", "?member=unknown-id").bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, profiles("admin", "?member=colleague-id&member=admin-id").status)
        // Creation: an id that exists but is not assigned is told apart from an id that does not exist.
        suspend fun create(token: String, profileId: String, onBehalf: String? = null) = client.post("/api/v1/cloud/sessions") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"name":"Task","profileId":"$profileId"}""")
            header(CloudHeaders.IDEMPOTENCY_KEY, "k-$token-$profileId"); onBehalf?.let { header(CloudHeaders.ON_BEHALF_OF, it) }
        }
        val missing = create("colleague", "unknown-id")
        assertEquals(HttpStatusCode.NotFound, missing.status); assertTrue("profile_not_found" in missing.bodyAsText())
        val unassigned = create("colleague", "reserved-id")
        assertEquals(HttpStatusCode.Forbidden, unassigned.status); assertTrue("profile_unassigned" in unassigned.bodyAsText())
        val delegated = create("admin", "reserved-id", onBehalf = "colleague-id")
        assertEquals(HttpStatusCode.Forbidden, delegated.status); assertTrue("profile_unassigned" in delegated.bodyAsText())
        assertTrue(store.sessions.isEmpty())
        assertEquals(HttpStatusCode.Accepted, create("admin", "reserved-id").status, "An administrator uses every profile")
    }

    @Test fun renamingASessionIsForItsOwnerAndCloudAdministratorsUnderTheCreationNameRules() = testApplication {
        val store = Store()
        application { configureGateway(gateway, team = team(), cloudSessions = CloudSessionService(store), cloudAdmin = CloudAdministrationService(Admin(), FakeCluster), publicOrigin = "http://localhost") }
        assertTrue("cloud.sessions.rename.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features)
        val created = client.post("/api/v1/cloud/sessions") {
            bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"name":"Task","profileId":"profile-id"}""")
            header(CloudHeaders.IDEMPOTENCY_KEY, "rename-1"); header(CloudHeaders.ON_BEHALF_OF, "colleague-id")
        }
        val view = Serialization.json.decodeFromString<CloudSessionView>(created.bodyAsText())
        suspend fun rename(token: String, name: String, id: String = view.id) = client.patch("/api/v1/cloud/sessions/$id") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(Serialization.json.encodeToString(RenameCloudSessionRequest(name)))
        }
        val owner = rename("colleague", "Fix the build")
        assertEquals(HttpStatusCode.OK, owner.status)
        assertEquals("Fix the build", Serialization.json.decodeFromString<CloudSessionView>(owner.bodyAsText()).name)
        assertEquals("Fix the build", store.sessions.getValue(view.id).name)
        assertEquals(HttpStatusCode.OK, rename("colleague-write", "Fix the build 2").status, "cloud:write is enough")
        assertEquals(HttpStatusCode.Forbidden, rename("colleague-read", "Nope").status, "cloud:read never writes")
        assertEquals(HttpStatusCode.NotFound, rename("admin-write", "Nope").status, "An administrator's key without admin:cloud is only its owner")
        assertEquals(HttpStatusCode.OK, rename("admin", "By the admin").status, "A cloud administrator renames any session")
        for (bad in listOf("", "   ", "x".repeat(121), "line\nbreak")) {
            val refused = rename("colleague", bad)
            assertEquals(HttpStatusCode.BadRequest, refused.status, bad); assertTrue("invalid_session" in refused.bodyAsText())
        }
        assertEquals("By the admin", store.sessions.getValue(view.id).name)
        store.sessions["admin-only"] = store.sessions.getValue(view.id).copy(id = "admin-only", ownerId = "admin-id")
        assertEquals(HttpStatusCode.NotFound, rename("colleague", "Mine now", "admin-only").status)
        store.sessions[view.id] = store.sessions.getValue(view.id).copy(state = CloudSessionState.TERMINATED)
        assertEquals(HttpStatusCode.Conflict, rename("colleague", "Too late").status)
        assertEquals(CredentialAction.CLOUD_WRITE, credentialAction("PATCH", "/api/v1/cloud/sessions/${view.id}", emptySet()))
        assertEquals(CredentialAction.INTERACTIVE, credentialAction("PATCH", "/api/v1/cloud/sessions/${view.id}/x", emptySet()))
    }

    @Test fun cloudKeysActForTheirOwnerAndOnlyAdminCloudKeysDelegate() = testApplication {
        val store = Store()
        val sessions = CloudSessionService(store)
        val transfers = CloudTransferService(store, Transfers(store), sessions, CloudRecipientPolicy(store))
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), FakeCluster),
                cloudTransfers = transfers, publicOrigin = "http://localhost")
        }
        assertTrue("cloud.keys.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague-read") }.bodyAsText()).features)
        suspend fun create(token: String, key: String, onBehalf: String? = null) = client.post("/api/v1/cloud/sessions") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"name":"Task","profileId":"profile-id"}""")
            header(CloudHeaders.IDEMPOTENCY_KEY, key); onBehalf?.let { header(CloudHeaders.ON_BEHALF_OF, it) }
        }
        suspend fun get(token: String, path: String) = client.get(path) { bearerAuth(token) }
        // cloud:read reads, never writes.
        val readOnly = create("colleague-read", "r1")
        assertEquals(HttpStatusCode.Forbidden, readOnly.status); assertTrue("credential_scope" in readOnly.bodyAsText())
        assertEquals(HttpStatusCode.OK, get("colleague-read", "/api/v1/cloud/sessions").status)
        assertEquals(HttpStatusCode.OK, get("colleague-read", "/api/v1/cloud/profiles").status)
        assertEquals(HttpStatusCode.OK, get("colleague-read", "/api/v1/cloud/transfers").status)
        // Cluster, profile and settings administration stays interactive, also with admin:cloud.
        for (path in listOf("/api/v1/cloud/settings", "/api/v1/cloud/clusters")) assertEquals(HttpStatusCode.Forbidden, get("admin-cloud", path).status, path)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/cloud/profiles") { bearerAuth("admin-cloud"); contentType(ContentType.Application.Json); setBody("{}") }.status)
        // cloud:write creates and manages the owner's own sessions.
        val ownResponse = create("colleague-write", "w1")
        assertEquals(HttpStatusCode.Accepted, ownResponse.status, ownResponse.bodyAsText())
        val own = Serialization.json.decodeFromString<CloudSessionView>(ownResponse.bodyAsText())
        assertEquals(CloudAttributionView("colleague-id", "colleague-write-key", "colleague-id"), own.createdBy)
        assertTrue("on_behalf_forbidden" in create("colleague-write", "w2", onBehalf = "admin-id").bodyAsText())
        assertEquals(HttpStatusCode.OK, get("colleague-read", "/api/v1/cloud/sessions/${own.id}").status)
        store.sessions[own.id] = store.sessions.getValue(own.id).copy(state = CloudSessionState.READY)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/cloud/sessions/${own.id}/pause") { bearerAuth("colleague-read") }.status)
        // An administrator's key without admin:cloud is only its owner: no delegation, no other member's session.
        assertTrue("on_behalf_forbidden" in create("admin-write", "a1", onBehalf = "colleague-id").bodyAsText())
        assertEquals(HttpStatusCode.NotFound, get("admin-write", "/api/v1/cloud/sessions/${own.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/cloud/sessions/${own.id}") { bearerAuth("admin-write") }.status)
        assertEquals(HttpStatusCode.Forbidden, get("admin-write", "/api/v1/cloud/sessions?owner=all").status)
        assertEquals(HttpStatusCode.Forbidden, get("admin-write", "/api/v1/cloud/transfers?scope=team").status)
        // admin:cloud: an orchestrator creating and driving sessions on behalf of a member, attributed to its key.
        val delegatedResponse = create("admin-cloud", "o1", onBehalf = "colleague-id")
        assertEquals(HttpStatusCode.Accepted, delegatedResponse.status, delegatedResponse.bodyAsText())
        val delegated = Serialization.json.decodeFromString<CloudSessionView>(delegatedResponse.bodyAsText())
        assertEquals("colleague-id", delegated.ownerId)
        assertEquals(CloudAttributionView("admin-id", "admin-cloud-key", "colleague-id"), delegated.createdBy)
        assertEquals(HttpStatusCode.OK, get("admin-cloud", "/api/v1/cloud/transfers?scope=team").status)
        // team:read: an administrator's key that reads every member's sessions and transfers and writes nothing.
        val readable = Serialization.json.decodeFromString<CloudSessionPageView>(get("admin-read", "/api/v1/cloud/sessions?owner=all").bodyAsText())
        assertEquals(setOf(own.id, delegated.id), readable.sessions.map { it.id }.toSet())
        assertEquals(HttpStatusCode.OK, get("admin-read", "/api/v1/cloud/sessions/${own.id}").status)
        assertEquals(HttpStatusCode.OK, get("admin-read", "/api/v1/cloud/sessions/${own.id}/executions").status)
        assertEquals(HttpStatusCode.OK, get("admin-read", "/api/v1/cloud/transfers?scope=team").status)
        assertEquals(HttpStatusCode.Forbidden, get("admin-read", "/api/v1/cloud/settings").status)
        val refused = create("admin-read", "t1", onBehalf = "colleague-id")
        assertEquals(HttpStatusCode.Forbidden, refused.status); assertTrue("credential_scope" in refused.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden, create("admin-read", "t2").status)
        for (action in listOf("pause", "resume", "transfer")) assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/cloud/sessions/${own.id}/$action") { bearerAuth("admin-read"); contentType(ContentType.Application.Json); setBody("{}") }.status, action)
        assertEquals(HttpStatusCode.Forbidden, client.delete("/api/v1/cloud/sessions/${own.id}") { bearerAuth("admin-read") }.status)
        // team:read does not widen a write: combined with cloud:write the key is still only its owner for writes.
        assertEquals(HttpStatusCode.OK, get("admin-read-write", "/api/v1/cloud/sessions/${own.id}").status)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/cloud/sessions/${own.id}/pause") { bearerAuth("admin-read-write") }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/cloud/sessions/${own.id}") { bearerAuth("admin-read-write") }.status)
        assertTrue("on_behalf_forbidden" in create("admin-read-write", "t3", onBehalf = "colleague-id").bodyAsText())
        // usage:read and cloud:read on an administrator's key stay the owner's own view.
        assertEquals(HttpStatusCode.Forbidden, get("admin-usage", "/api/v1/cloud/sessions?owner=all").status)
        assertEquals(HttpStatusCode.Forbidden, get("admin-usage", "/api/v1/cloud/transfers?scope=team").status)
        assertEquals(HttpStatusCode.NotFound, get("admin-usage", "/api/v1/cloud/sessions/${own.id}").status)
        val orchestrator = TeamCodeClient("http://localhost", { "admin-cloud" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            assertEquals(setOf(own.id, delegated.id), orchestrator.cloudSessions(owner = "all").sessions.map { it.id }.toSet())
            assertEquals("paused", orchestrator.pauseCloudSession(own.id, onBehalfOf = "colleague-id").desiredState)
            store.sessions[delegated.id] = store.sessions.getValue(delegated.id).copy(state = CloudSessionState.READY)
            val moved = orchestrator.transferCloudSession(delegated.id, CreateCloudTransferRequest("admin-id", resume = false, force = true), "o2", onBehalfOf = "colleague-id")
            assertEquals("accepted", moved.state, "An admin:cloud transfer is immediate, like an interactive administrator's")
            assertEquals(CloudAttributionView("admin-id", "admin-cloud-key", "colleague-id"), moved.initiatedBy)
        } finally { orchestrator.close() }
    }

    /** Transfers in memory: the version compare-and-set and the session write of the real store, without SQL. */
    private class Transfers(val store: Store) : CloudTransferStore {
        val rows = linkedMapOf<String, CloudTransfer>()
        override suspend fun find(teamId: String, id: String) = rows[id]
        override suspend fun byKey(teamId: String, initiatorId: String, key: String) = rows.values.find { it.initiatedBy.initiatorId == initiatorId && it.idempotencyKey == key }
        override suspend fun list(teamId: String, memberId: String, query: CloudTransferQuery) =
            CloudTransferPage(rows.values.filter { query.team || it.fromMemberId == memberId || it.toMemberId == memberId }
                .filter { query.direction != CloudTransferDirection.OUTGOING || it.fromMemberId == memberId }
                .filter { query.direction != CloudTransferDirection.INCOMING || it.toMemberId == memberId }.reversed(), null)
        override suspend fun open(transfer: CloudTransfer, actor: CloudActor, auditAction: String, session: SessionChange?): CloudTransferOutcome {
            byKey(transfer.teamId, transfer.initiatedBy.initiatorId, transfer.idempotencyKey!!)?.let { return CloudTransferOutcome.Replayed(it) }
            if (rows.values.any { it.sessionId == transfer.sessionId && it.state.open }) throw TeamFailure("transfer_pending", 409, "pending")
            session?.let { store.compareAndSet(it.expected, it.next, CloudWrite()) ?: throw TeamFailure("session_busy", 409, "busy") }
            rows[transfer.id] = transfer
            return CloudTransferOutcome.Created(transfer)
        }
        override suspend fun update(expected: CloudTransfer, next: CloudTransfer, actor: CloudActor?, auditAction: String?, session: SessionChange?): CloudTransfer? {
            if (rows[expected.id]?.version != expected.version) return null
            session?.let { store.compareAndSet(it.expected, it.next, CloudWrite()) ?: return null }
            return next.also { rows[it.id] = it }
        }
        override suspend fun complete(expected: CloudSession, next: CloudSession, transfer: CloudTransfer, leaseOwner: String, retainedLimit: Int) = TransferCompletion.Conflict
        override suspend fun expireOffers(limit: Int) = 0
        override suspend fun maintain() = Unit
        val recipientLimits = mutableListOf<Int>()
        override suspend fun recipients(teamId: String, limit: Int): List<CloudTransferRecipient> {
            recipientLimits += limit
            return listOf(store.member(teamId, "admin-id")!!, store.member(teamId, "colleague-id")!!).map { CloudTransferRecipient(it.id, it.username, it.role.isAdministrator) }
        }
    }

    @Test fun recipientsAreListedToWhoeverMayRequestATransfer() = testApplication {
        val store = Store()
        val sessions = CloudSessionService(store)
        val transferStore = Transfers(store)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), FakeCluster),
                cloudTransfers = CloudTransferService(store, transferStore, sessions, CloudRecipientPolicy(store)), publicOrigin = "http://localhost")
        }
        assertTrue("cloud.transfer.recipients.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features)
        val member = TeamCodeClient("http://localhost", { "colleague" }) { config -> createClient { config(this) } }
        try {
            // Only what a picker needs: no role name, no state, nothing else of the member.
            assertEquals(listOf(CloudTransferRecipientView("admin-id", "admin", true), CloudTransferRecipientView("colleague-id", "colleague", false)), member.cloudTransferRecipients())
            val body = client.get("/api/v1/cloud/transfer-recipients") { bearerAuth("colleague") }.bodyAsText()
            assertEquals("""{"recipients":[{"id":"admin-id","username":"admin","administrator":true},{"id":"colleague-id","username":"colleague","administrator":false}]}""", body)
            assertEquals(listOf(CloudTransferService.MAX_RECIPIENTS), transferStore.recipientLimits.distinct())
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/transfer-recipients") { bearerAuth("service-admin") }.status, "A key without a cloud write scope lists nobody")
            // A team that keeps transfers to administrators does not show members its directory.
            store.cloudSettings = CloudSettings(memberTransfers = MemberTransfers.DISABLED)
            assertEquals("transfer_disabled", assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> { member.cloudTransferRecipients() }.code)
            assertEquals(2, Serialization.json.decodeFromString<CloudTransferRecipientsView>(client.get("/api/v1/cloud/transfer-recipients") { bearerAuth("admin") }.bodyAsText()).recipients.size)
        } finally { member.close() }
    }

    @Test fun transfersAreOffersForMembersAndImmediateForAdministrators() = testApplication {
        val store = Store()
        val settings = Admin()
        val sessions = CloudSessionService(store)
        val transfers = CloudTransferService(store, Transfers(store), sessions, CloudRecipientPolicy(store))
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(settings, FakeCluster),
                cloudTransfers = transfers, publicOrigin = "http://localhost")
        }
        assertTrue("cloud.transfer.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("admin") }.bodyAsText()).features)
        val created = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Run", "profile-id", null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        store.sessions[created.id] = created.copy(state = CloudSessionState.READY)
        suspend fun transfer(token: String, key: String?, body: String = """{"toMemberId":"admin-id"}""", onBehalf: String? = null) = client.post("/api/v1/cloud/sessions/${created.id}/transfer") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
            key?.let { header(CloudHeaders.IDEMPOTENCY_KEY, it) }; onBehalf?.let { header(CloudHeaders.ON_BEHALF_OF, it) }
        }
        assertEquals(HttpStatusCode.Forbidden, transfer("service-admin", "t1").status, "Keys without a cloud scope never transfer sessions")
        assertEquals(HttpStatusCode.BadRequest, transfer("colleague", null).status, "An Idempotency-Key is required")
        assertEquals(HttpStatusCode.Forbidden, transfer("colleague", "t1", onBehalf = "admin-id").status)
        val offered = transfer("colleague", "t1")
        assertEquals(HttpStatusCode.Accepted, offered.status)
        val offer = Serialization.json.decodeFromString<CloudTransferView>(offered.bodyAsText())
        assertEquals(listOf("offered", "colleague-id", "admin-id"), listOf(offer.state, offer.fromMemberId, offer.toMemberId))
        assertEquals(CloudAttributionView("colleague-id", "colleague-session", "colleague-id"), offer.initiatedBy)
        val replay = transfer("colleague", "t1", """{ "toMemberId" : "admin-id" }""")
        assertEquals("true", replay.headers[CloudHeaders.IDEMPOTENCY_REPLAYED]); assertEquals(offer.id, Serialization.json.decodeFromString<CloudTransferView>(replay.bodyAsText()).id)
        assertEquals(HttpStatusCode.UnprocessableEntity, transfer("colleague", "t1", """{"toMemberId":"admin-id","resume":true}""").status, "Same key, another request")
        assertNull(store.sessions.getValue(created.id).transferId, "An offer does not touch the session")

        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/transfers?scope=team") { bearerAuth("colleague") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/cloud/transfers?direction=sideways") { bearerAuth("colleague") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/cloud/transfers?state=pending") { bearerAuth("colleague") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/cloud/transfers/${offer.id}/accept") { bearerAuth("colleague") }.status, "The owner cannot accept their own offer")
        val owner = TeamCodeClient("http://localhost", { "colleague" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        val recipient = TeamCodeClient("http://localhost", { "admin" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            assertEquals(listOf(offer.id), owner.cloudTransfers(direction = "outgoing").transfers.map { it.id })
            assertEquals(emptyList(), owner.cloudTransfers(direction = "incoming").transfers)
            assertEquals(listOf(offer.id), recipient.cloudTransfers(team = true, states = listOf("offered")).transfers.map { it.id })
            val accepted = recipient.acceptCloudTransfer(offer.id, resume = true)
            assertEquals("accepted" to true, accepted.state to accepted.resume)
            assertEquals(CloudAttributionView("admin-id", "admin-session", "admin-id"), accepted.decidedBy)
            val session = owner.cloudSession(created.id)
            assertEquals(offer.id to "paused", session.transferId to session.desiredState, "Acceptance pauses the session toward the transfer")
            assertEquals(409, assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> { owner.resumeCloudSession(created.id) }.statusCode, "No resume while a transfer is accepted")
            assertEquals("transfer_forbidden", assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> { owner.cancelCloudTransfer(offer.id) }.code)
            assertEquals("cancelled", recipient.cancelCloudTransfer(offer.id).state, "An administrator stops it before the owner change")
            assertNull(owner.cloudSession(created.id).transferId)
            // An administrator transfers at once, with the delegated member recorded.
            val direct = recipient.transferCloudSession(created.id, CreateCloudTransferRequest("admin-id", resume = false, force = true), "t2", onBehalfOf = "colleague-id")
            assertEquals("accepted", direct.state); assertEquals("colleague-id", direct.initiatedBy.effectiveMemberId)
            assertEquals(true, store.sessions.getValue(created.id).pauseForce)
            assertEquals(direct.id, recipient.cloudTransfer(direct.id).id)
        } finally { owner.close(); recipient.close() }

        // Settings: the transfer policy is part of the persistent cloud settings and the dashboard form.
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"pausedRetentionDays":30,"failedWorkspaceRetentionDays":7,"maxRunningSessionsPerMember":2,"maxRetainedSessionsPerMember":20,"transcriptRetentionDays":30,"memberTransfers":"always"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"pausedRetentionDays":30,"failedWorkspaceRetentionDays":7,"maxRunningSessionsPerMember":2,"maxRetainedSessionsPerMember":20,"transcriptRetentionDays":30,"transferOfferDays":31}""") }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json)
            setBody("""{"pausedRetentionDays":30,"failedWorkspaceRetentionDays":7,"maxRunningSessionsPerMember":2,"maxRetainedSessionsPerMember":20,"transcriptRetentionDays":30,"memberTransfers":"disabled","transferOfferDays":3,"transferPendingInputs":"keep"}""") }.status)
        assertEquals(CloudSettings(memberTransfers = MemberTransfers.DISABLED, transferOfferDays = 3, transferPendingInputs = TransferPendingInputs.KEEP), settings.settings)
        // A client that predates transfers omits the fields: the stored transfer policy is kept.
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"pausedRetentionDays":30,"failedWorkspaceRetentionDays":7,"maxRunningSessionsPerMember":3,"maxRetainedSessionsPerMember":20,"transcriptRetentionDays":30}""") }.status)
        assertEquals(CloudSettings(maxRunningSessionsPerMember = 3, memberTransfers = MemberTransfers.DISABLED, transferOfferDays = 3, transferPendingInputs = TransferPendingInputs.KEEP), settings.settings)
        assertTrue(client.get("/dashboard/cloud") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText().contains("Members may offer their own sessions"))
        assertEquals(HttpStatusCode.Found, client.post("/dashboard/cloud") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=admin-session&pausedRetentionDays=30&failedWorkspaceRetentionDays=7&maxRunning=2&maxRetained=20&transcriptRetentionDays=30&memberTransfers=offer&transferOfferDays=5&transferPendingInputs=cancel")
        }.status)
        assertEquals(Triple(MemberTransfers.OFFER, 5, TransferPendingInputs.CANCEL), settings.settings.let { Triple(it.memberTransfers, it.transferOfferDays, it.transferPendingInputs) })

        // The permission request deadline: none by default, set and cleared through the API and the form, kept by a client that omits it.
        val base = """"pausedRetentionDays":30,"failedWorkspaceRetentionDays":7,"maxRunningSessionsPerMember":2,"maxRetainedSessionsPerMember":20,"transcriptRetentionDays":30"""
        assertEquals(0, Serialization.json.decodeFromString<CloudSettingsView>(client.get("/api/v1/cloud/settings") { bearerAuth("admin") }.bodyAsText()).permissionDeadlineMinutes)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{$base,"permissionDeadlineMinutes":1441}""") }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{$base,"permissionDeadlineMinutes":30}""") }.status)
        assertEquals(30, settings.settings.permissionDeadlineMinutes)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{$base}""") }.status)
        assertEquals(30, settings.settings.permissionDeadlineMinutes, "An absent deadline keeps the stored one")
        assertTrue(client.get("/dashboard/cloud") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText().contains("""name="permissionDeadlineMinutes" min="0" max="1440" value="30""""))
        suspend fun form(extra: String) = client.post("/dashboard/cloud") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=admin-session&pausedRetentionDays=30&failedWorkspaceRetentionDays=7&maxRunning=2&maxRetained=20&transcriptRetentionDays=30$extra")
        }
        assertEquals(HttpStatusCode.Found, form("").status)
        assertEquals(30, settings.settings.permissionDeadlineMinutes, "A form from before the deadline existed keeps it")
        assertEquals(HttpStatusCode.Found, form("&permissionDeadlineMinutes=").status)
        assertEquals(0, settings.settings.permissionDeadlineMinutes, "An empty field means no deadline")
    }

    @Test fun retentionSettingsArePersistentAdministratorSettings() = testApplication {
        val settings = Admin()
        application { configureGateway(gateway, team = team(), cloudSessions = CloudSessionService(Store()), cloudAdmin = CloudAdministrationService(settings, FakeCluster), publicOrigin = "http://localhost") }
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/settings") { bearerAuth("colleague") }.status)
        val put = client.put("/api/v1/cloud/settings") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"pausedRetentionDays":null,"failedWorkspaceRetentionDays":3,"maxRunningSessionsPerMember":5,"maxRetainedSessionsPerMember":20,"transcriptRetentionDays":null}""") }
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
            assertNull(store.runs.getValue("s1" to 1).backgroundSeenAt, "No background work reported yet")
            runner.runnerHeartbeat(RunnerHeartbeatRequest("default", "s1", 1, ready = true, background = true))
            assertNotNull(store.runs.getValue("s1" to 1).backgroundSeenAt, "The runner's background work reaches the store")
            assertEquals(409, assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> {
                runner.runnerCheckpoint(RunnerCheckpointRequest("default", "s1", 1, completed = true))
            }.statusCode, "No checkpoint before a pause is requested")
        } finally { runner.close() }
        assertEquals(1, reviews.count { it == "projected-pod-token-1" }, "Token reviews are cached")
    }

    @Test fun remoteSessionsAreCreatedOnTheCallersDeviceAndHostedWithItsOwnLogin() = testApplication {
        val store = Store()
        val sessions = CloudSessionService(store)
        val logins = team()
        application {
            configureGateway(gateway, team = logins, cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), FakeCluster),
                cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, FakeCluster, logins = { logins.authenticate(it) }), remoteHosts = true),
                publicOrigin = "http://localhost")
        }
        assertTrue("cloud.sessions.remote.v1" !in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features,
            "Advertised only with the conversation its host relays")
        suspend fun create(token: String, json: String, key: String = json.hashCode().toString()) = client.post("/api/v1/cloud/sessions") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(json); header(CloudHeaders.IDEMPOTENCY_KEY, key)
        }
        val body = """{"name":"Refactor","kind":"remote","agent":{"kind":"codex","model":"gpt-5"},"directoryName":"teamcode","permissionMode":"bypass"}"""
        suspend fun refused(code: String, response: HttpResponse, status: HttpStatusCode) = runCatchingAssert(code, response, status)
        refused("remote_requires_device", create("colleague-write", body), HttpStatusCode.UnprocessableEntity)
        refused("invalid_session", create("colleague", """{"name":"Refactor","kind":"remote","profileId":"profile-id","agent":{"kind":"codex"},"directoryName":"teamcode"}"""), HttpStatusCode.BadRequest)
        refused("invalid_session", create("colleague", """{"name":"Task","profileId":"profile-id","directoryName":"teamcode"}"""), HttpStatusCode.BadRequest)
        refused("invalid_agent", create("colleague", """{"name":"Refactor","kind":"remote","directoryName":"teamcode"}"""), HttpStatusCode.BadRequest)
        refused("invalid_directory_name", create("colleague", """{"name":"Refactor","kind":"remote","agent":{"kind":"claude"},"directoryName":"/Users/someone/teamcode"}"""), HttpStatusCode.BadRequest)
        refused("invalid_permission_mode", create("colleague", """{"name":"Refactor","kind":"remote","agent":{"kind":"claude"},"directoryName":"teamcode","permissionMode":"yolo"}"""), HttpStatusCode.BadRequest)
        refused("invalid_kind", create("colleague", """{"name":"Refactor","kind":"laptop"}"""), HttpStatusCode.BadRequest)
        refused("on_behalf_forbidden", client.post("/api/v1/cloud/sessions") {
            bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(body); header(CloudHeaders.IDEMPOTENCY_KEY, "behalf"); header(CloudHeaders.ON_BEHALF_OF, "colleague-id")
        }, HttpStatusCode.Forbidden)
        assertTrue(store.sessions.isEmpty())
        val created = create("colleague", body)
        assertEquals(HttpStatusCode.Accepted, created.status)
        val view = Serialization.json.decodeFromString<CloudSessionView>(created.bodyAsText())
        assertEquals("remote" to "creating", view.kind to view.state)
        assertEquals(CloudAgentView("codex", null, "gpt-5"), view.agent)
        assertEquals(CloudSessionHostView("laptop-id", directoryName = "teamcode", permissionMode = "bypass"), view.host)
        assertEquals(60, view.activity.idleTimeoutMinutes)

        // The host starts it from the same device only, then uses the runner channel with that login.
        refused("remote_host_mismatch", client.post("/api/v1/cloud/sessions/${view.id}/host") { bearerAuth("colleague-desktop") }, HttpStatusCode.Conflict)
        refused("credential_scope", client.post("/api/v1/cloud/sessions/${view.id}/host") { bearerAuth("colleague-write") }, HttpStatusCode.Forbidden)
        refused("remote_host_mismatch", client.post("/api/v1/cloud/sessions/${view.id}/resume") { bearerAuth("colleague-desktop") }, HttpStatusCode.Conflict)
        val hosted = Serialization.json.decodeFromString<CloudHostView>(client.post("/api/v1/cloud/sessions/${view.id}/host") { bearerAuth("colleague") }.bodyAsText())
        assertEquals("default" to 1, hosted.teamId to hosted.epoch)
        suspend fun runner(path: String, token: String, epoch: Int = 1) = client.post("/api/v1/cloud/runner/$path") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"teamId":"default","sessionId":"${view.id}","epoch":$epoch}""")
        }
        val laptop = "colleague-laptop-login-0123456789"
        assertEquals(HttpStatusCode.Unauthorized, runner("register", "colleague-desktop-login-0123456789").status, "Another device of the owner is not the host")
        assertEquals(HttpStatusCode.Unauthorized, runner("register", "admin-login-token-0123456789").status)
        assertEquals(HttpStatusCode.Conflict, runner("register", laptop, epoch = 2).status)
        assertEquals(HttpStatusCode.OK, runner("register", laptop).status)
        assertEquals(CloudSessionState.PREPARING, store.sessions.getValue(view.id).state)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/cloud/runner/heartbeat") {
            bearerAuth(laptop); contentType(ContentType.Application.Json); setBody("""{"teamId":"default","sessionId":"${view.id}","epoch":1,"ready":true}""")
        }.status)
        assertEquals(CloudSessionState.READY, store.sessions.getValue(view.id).state)
        // A second host fences the first.
        client.post("/api/v1/cloud/sessions/${view.id}/host") { bearerAuth("colleague") }
        refused("execution_fenced", runner("register", laptop), HttpStatusCode.Conflict)
    }

    /** In memory, as the SQL store: presence by device, folders by device, the queue read from the sessions. */
    private class Daemons(val store: Store) : DeviceDaemonStore {
        val seen = mutableMapOf<String, Pair<Long, String?>>()
        val features = mutableMapOf<String, List<String>>()
        val folders = mutableMapOf<String, List<DeviceFolder>>()
        /** Requests by id: device, listing, and the answer once given. */
        val listings = java.util.concurrent.ConcurrentHashMap<String, Triple<String, DaemonListing, ResumableAnswer?>>()
        val owners = mapOf("laptop-id" to "colleague-id", "desktop-id" to "colleague-id", "admin-device" to "admin-id")
        override suspend fun now() = store.clock
        override suspend fun deviceOf(principal: Principal) = store.deviceOf(principal)
        override suspend fun cloudExecution(principal: Principal) = principal.tokenId == "pod-execution"
        override suspend fun touch(deviceId: String, version: String?, features: List<String>) { seen[deviceId] = store.clock to version; this.features[deviceId] = features }
        override suspend fun offline(deviceId: String) { seen.remove(deviceId) }
        override suspend fun publish(deviceId: String, folders: List<DeviceFolder>) { this.folders[deviceId] = folders }
        override suspend fun owned(teamId: String, memberId: String, deviceId: String) = deviceId.takeIf { owners[it] == memberId }?.let {
            OwnedDevice(it, it, seen[it]?.let { (at, version) -> DaemonPresence.of(at, version, store.clock, features[it].orEmpty()) }, folders[it].orEmpty())
        }
        override suspend fun orders(deviceId: String, limit: Int) = store.sessions.values.filter {
            it.host?.deviceId == deviceId && it.host?.folderId != null && it.phase == CloudSessionService.WAITING_HOST && it.desiredState == DesiredState.RUNNING
        }.take(limit).map { DaemonOrder(it.id, it.host!!.folderId!!, it.host!!.directoryName!!, it.agentKind, it.host!!.permissionMode, it.state, it.updatedAt, it.host!!.resumeSessionId) }
        override suspend fun askListing(deviceId: String, folderId: String, limit: Int, before: Long?, expiresAt: Long) =
            java.util.UUID.randomUUID().toString().also { listings[it] = Triple(deviceId, DaemonListing(it, folderId, limit, before), null) }
        override suspend fun listings(deviceId: String, limit: Int) = listings.values.filter { it.first == deviceId && it.third == null }.map { it.second }.take(limit)
        override suspend fun answerListing(deviceId: String, requestId: String, answer: ResumableAnswer): Boolean {
            val found = listings[requestId]?.takeIf { it.first == deviceId && it.third == null } ?: return false
            listings[requestId] = found.copy(third = answer)
            return true
        }
        override suspend fun listingAnswer(requestId: String) = listings[requestId]?.third
        override suspend fun dropListing(requestId: String) { listings.remove(requestId) }
    }

    @Test fun aFoldersConversationsAreListedByTheDaemonAndASessionContinuesOne() = testApplication {
        val store = Store()
        val daemons = Daemons(store)
        val sessions = CloudSessionService(store, daemons = daemons)
        val logins = team()
        val conversations = java.lang.reflect.Proxy.newProxyInstance(CloudConversationStore::class.java.classLoader, arrayOf(CloudConversationStore::class.java)) { _, method, _ ->
            if (method.name == "toString") "stub" else error("unused: ${method.name}")
        } as CloudConversationStore
        val access = CloudAccessService(conversations, store)
        application {
            configureGateway(gateway, team = logins, cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), FakeCluster),
                cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, FakeCluster, logins = { logins.authenticate(it) }), remoteHosts = true),
                cloudConversation = CloudConversation(CloudConversationService(conversations, store, access, { _, _ -> false }), access, RunnerConversationUseCase(store, conversations, { _, _ -> false })),
                deviceDaemons = DeviceDaemonChannel(DeviceDaemonUseCase(daemons, sleep = { kotlinx.coroutines.delay(5) }), sessions), publicOrigin = "http://localhost")
        }
        suspend fun json(method: HttpMethod, path: String, token: String, body: String) = client.request(path) {
            this.method = method; bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        suspend fun create(token: String, body: String) = client.post("/api/v1/cloud/sessions") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body); header(CloudHeaders.IDEMPOTENCY_KEY, body.hashCode().toString() + token)
        }
        assertTrue(ResumableSessions.CAPABILITY in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features)
        val device = TeamCodeClient("http://localhost", { "colleague" }) { config -> createClient { config(this) } }
        val app = TeamCodeClient("http://localhost", { "colleague-desktop" }) { config -> createClient { config(this) } }
        val id = "0000000a-aaaa-4bbb-8ccc-00000000000a"
        try {
            device.publishDeviceFolders(listOf(DeviceFolderView("api", "api")))
            // An older daemon announces nothing: the app is told, and nothing is asked of it.
            device.pollDeviceDaemon(0, "1.0.0")
            assertEquals(emptyList(), app.deviceFolders("laptop-id").daemon!!.features)
            assertEquals("daemon_resume_unsupported", assertFailsWith<TeamCodeException> { app.resumableSessions("laptop-id", "api") }.code)
            assertEquals(emptyMap(), daemons.listings)

            device.stopDeviceDaemon()
            assertEquals(emptyList(), device.pollDeviceDaemon(0, "1.1.0", listOf(DaemonFeatures.RESUME)).listings)
            assertEquals(listOf(DaemonFeatures.RESUME), app.deviceFolders("laptop-id").daemon!!.features)
            assertEquals("folder_not_published", assertFailsWith<TeamCodeException> { app.resumableSessions("laptop-id", "web") }.code)
            runCatchingAssert("device_not_found", client.get("/api/v1/devices/laptop-id/folders/api/resumable") { bearerAuth("admin") }, HttpStatusCode.NotFound)
            runCatchingAssert("invalid_limit", client.get("/api/v1/devices/laptop-id/folders/api/resumable?limit=x") { bearerAuth("colleague-desktop") }, HttpStatusCode.BadRequest)
            runCatchingAssert("invalid_limit", client.get("/api/v1/devices/laptop-id/folders/api/resumable?limit=51") { bearerAuth("colleague-desktop") }, HttpStatusCode.BadRequest)

            // The app waits while the daemon, at its next poll, reads the folder and answers.
            val page = ResumableSessionsView(listOf(ResumableSessionView(id, "Fix the build", 1_000, 3)), nextBefore = 1_000)
            val listed = kotlinx.coroutines.coroutineScope {
                val waiting = async { app.resumableSessions("laptop-id", "api", limit = 5, before = 9_000) }
                var listing: DaemonListingView? = null
                while (listing == null) listing = device.pollDeviceDaemon(0, "1.1.0", listOf(DaemonFeatures.RESUME)).listings.singleOrNull()
                assertEquals(DaemonListingView(listing.requestId, "api", 5, 9_000), listing)
                runCatchingAssert("invalid_listing", json(HttpMethod.Post, "/api/v1/devices/current/daemon/listings/${listing.requestId}", "colleague",
                    """{"sessions":[{"id":"$id","title":"","lastActiveAt":1,"turns":1}]}"""), HttpStatusCode.BadRequest)
                runCatchingAssert("listing_not_found", json(HttpMethod.Post, "/api/v1/devices/current/daemon/listings/${listing.requestId}", "colleague-desktop", "{}"), HttpStatusCode.NotFound)
                device.answerDeviceDaemonListing(listing.requestId, DaemonListingAnswerRequest(page.sessions, page.nextBefore))
                waiting.await()
            }
            assertEquals(page, listed)
            assertEquals(emptyMap(), daemons.listings, "Gone once read")
            // A refusal of the daemon reaches the app as its reason.
            val refused = kotlinx.coroutines.coroutineScope {
                val waiting = async { assertFailsWith<TeamCodeException> { app.resumableSessions("laptop-id", "api") } }
                var listing: DaemonListingView? = null
                while (listing == null) listing = device.pollDeviceDaemon(0, "1.1.0", listOf(DaemonFeatures.RESUME)).listings.singleOrNull()
                device.answerDeviceDaemonListing(listing.requestId, DaemonListingAnswerRequest(refused = "folder_missing"))
                waiting.await()
            }
            assertEquals("folder_missing" to 422, refused.code to refused.statusCode)

            // A session continues one: the order carries it to the daemon.
            fun start(agent: String, resume: String) = CreateCloudSessionRequest("Go on", agent = CloudAgentRequest(kind = agent), kind = "remote", deviceId = "laptop-id", folderId = "api", resumeSessionId = resume)
            assertEquals("invalid_resume", assertFailsWith<TeamCodeException> { app.createCloudSession(start("claude", "../etc")) }.code)
            assertEquals("resume_unsupported", assertFailsWith<TeamCodeException> { app.createCloudSession(start("codex", id)) }.code)
            runCatchingAssert("invalid_session", create("colleague", """{"name":"Task","profileId":"profile-id","resumeSessionId":"$id"}"""), HttpStatusCode.BadRequest)
            val session = app.createCloudSession(start("claude", id))
            assertEquals(id, device.pollDeviceDaemon(0, "1.1.0", listOf(DaemonFeatures.RESUME)).orders.single { it.sessionId == session.id }.resumeSessionId)
        } finally { device.close(); app.close() }
    }

    @Test fun remoteSessionsStartOnTheOwnersOnlineDeviceThroughItsDaemon() = testApplication {
        val store = Store()
        val daemons = Daemons(store)
        val sessions = CloudSessionService(store, daemons = daemons)
        val logins = team()
        // Never called here: the conversation is mounted only because the capability needs it.
        val conversations = java.lang.reflect.Proxy.newProxyInstance(CloudConversationStore::class.java.classLoader, arrayOf(CloudConversationStore::class.java)) { _, method, _ ->
            if (method.name == "toString") "stub" else error("unused: ${method.name}")
        } as CloudConversationStore
        val access = CloudAccessService(conversations, store)
        application {
            configureGateway(gateway, team = logins, cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), FakeCluster),
                cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, FakeCluster, logins = { logins.authenticate(it) }), remoteHosts = true),
                cloudConversation = CloudConversation(CloudConversationService(conversations, store, access, { _, _ -> false }), access, RunnerConversationUseCase(store, conversations, { _, _ -> false })),
                deviceDaemons = DeviceDaemonChannel(DeviceDaemonUseCase(daemons, sleep = {}), sessions), publicOrigin = "http://localhost")
        }
        assertTrue("cloud.sessions.remote.daemon.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features)
        suspend fun json(method: HttpMethod, path: String, token: String, body: String) = client.request(path) {
            this.method = method; bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
        }
        suspend fun create(token: String, body: String) = client.post("/api/v1/cloud/sessions") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(body); header(CloudHeaders.IDEMPOTENCY_KEY, body.hashCode().toString() + token)
        }
        val start = """{"name":"From the phone","kind":"remote","agent":{"kind":"claude"},"deviceId":"laptop-id","folderId":"api","permissionMode":"bypass"}"""

        // The daemon channel is interactive and bound to a device.
        runCatchingAssert("credential_scope", json(HttpMethod.Post, "/api/v1/devices/current/daemon/poll", "colleague-write", "{}"), HttpStatusCode.Forbidden)
        runCatchingAssert("device_offline", create("colleague-desktop", start), HttpStatusCode.Conflict)
        // A cloud pod never becomes a device and never orders one: one pod runs one session.
        runCatchingAssert("cloud_session_credential", create("pod", start), HttpStatusCode.Forbidden)
        val published = json(HttpMethod.Put, "/api/v1/devices/current/folders", "colleague", """{"folders":[{"id":"api","name":"api","label":"~/work/api"}]}""")
        assertEquals(listOf(DeviceFolderView("api", "api", "~/work/api")), Serialization.json.decodeFromString<List<DeviceFolderView>>(published.bodyAsText()))
        runCatchingAssert("invalid_folders", json(HttpMethod.Put, "/api/v1/devices/current/folders", "colleague", """{"folders":[{"id":"x","name":"/home/x"}]}"""), HttpStatusCode.BadRequest)
        val empty = Serialization.json.decodeFromString<DaemonPollView>(json(HttpMethod.Post, "/api/v1/devices/current/daemon/poll", "colleague", """{"waitMillis":0,"version":"1.0.0"}""").bodyAsText())
        assertEquals(DaemonPollView(emptyList(), DeviceDaemonRules.ONLINE_TTL_MILLIS, DeviceDaemonRules.MAX_WAIT_MILLIS), empty)

        // Only the owner reads the folders and starts a session there; the device's folder is checked.
        val folders = Serialization.json.decodeFromString<DeviceFoldersView>(client.get("/api/v1/devices/laptop-id/folders") { bearerAuth("colleague-desktop") }.bodyAsText())
        assertEquals("laptop-id" to true, folders.deviceId to folders.daemon!!.online)
        runCatchingAssert("device_not_found", client.get("/api/v1/devices/laptop-id/folders") { bearerAuth("admin") }, HttpStatusCode.NotFound)
        runCatchingAssert("device_not_found", create("admin", start), HttpStatusCode.NotFound)
        runCatchingAssert("remote_requires_login", create("colleague-write", start), HttpStatusCode.UnprocessableEntity)
        runCatchingAssert("folder_not_published", create("colleague-desktop", start.replace("\"api\"", "\"web\"")), HttpStatusCode.UnprocessableEntity)
        runCatchingAssert("invalid_session", create("colleague", """{"name":"Task","profileId":"profile-id","deviceId":"laptop-id"}"""), HttpStatusCode.BadRequest)
        val created = create("colleague-desktop", start)
        assertEquals(HttpStatusCode.Accepted, created.status)
        val view = Serialization.json.decodeFromString<CloudSessionView>(created.bodyAsText())
        assertEquals(CloudSessionHostView("laptop-id", directoryName = "api", permissionMode = "bypass", folderId = "api"), view.host)

        val poll = Serialization.json.decodeFromString<DaemonPollView>(json(HttpMethod.Post, "/api/v1/devices/current/daemon/poll", "colleague", """{"waitMillis":20000}""").bodyAsText())
        assertEquals(listOf(DaemonOrderView(view.id, "api", "api", "claude", "bypass", "creating", poll.orders.single().requestedAt)), poll.orders)
        runCatchingAssert("remote_host_mismatch", json(HttpMethod.Post, "/api/v1/devices/current/daemon/sessions/${view.id}/refuse", "colleague-desktop", """{"reason":"folder_missing"}"""), HttpStatusCode.Conflict)
        val refused = Serialization.json.decodeFromString<CloudSessionView>(json(HttpMethod.Post, "/api/v1/devices/current/daemon/sessions/${view.id}/refuse", "colleague", """{"reason":"folder_missing"}""").bodyAsText())
        assertEquals("host_refused", refused.failure?.code)
        assertEquals(emptyList(), Serialization.json.decodeFromString<DaemonPollView>(json(HttpMethod.Post, "/api/v1/devices/current/daemon/poll", "colleague", "{}").bodyAsText()).orders)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/devices/current/daemon") { bearerAuth("colleague") }.status)
        runCatchingAssert("device_offline", create("colleague-desktop", start.replace("From the phone", "Again")), HttpStatusCode.Conflict)

        // The same flow through the client SDK: the daemon with the laptop's login, the app with another device's.
        val device = TeamCodeClient("http://localhost", { "colleague" }) { config -> createClient { config(this) } }
        val app = TeamCodeClient("http://localhost", { "colleague-desktop" }) { config -> createClient { config(this) } }
        try {
            assertEquals(listOf(DeviceFolderView("web", "web")), device.publishDeviceFolders(listOf(DeviceFolderView("web", "web"))))
            assertEquals(emptyList(), device.pollDeviceDaemon(0, "1.0.0").orders)
            assertEquals(listOf("web"), app.deviceFolders("laptop-id").folders.map { it.id })
            val session = app.createCloudSession(CreateCloudSessionRequest("SDK", agent = CloudAgentRequest(kind = "codex"), kind = "remote", deviceId = "laptop-id", folderId = "web"))
            assertEquals("ask" to "web", session.host?.permissionMode to session.host?.directoryName)
            assertEquals(listOf(session.id), device.pollDeviceDaemon(0).orders.map { it.sessionId })
            assertEquals("host_refused", device.refuseDeviceDaemonSession(session.id, "agent_missing").failure?.code)
            device.stopDeviceDaemon()
            assertNull(app.deviceFolders("laptop-id").daemon)
        } finally { device.close(); app.close() }
    }

    private suspend fun runCatchingAssert(code: String, response: HttpResponse, status: HttpStatusCode) {
        val text = response.bodyAsText()
        assertEquals(status, response.status, text)
        assertTrue("\"$code\"" in text, text)
    }

    @Test fun runnerReceivesAnInferenceCredentialForItsOwnExecutionOnly() = testApplication {
        val store = Store()
        val cluster = object : ClusterPort by FakeCluster {
            override suspend fun reviewToken(clusterId: String, token: String, audience: String): TokenIdentity? =
                if (token == "projected-pod-token-1") TokenIdentity("teamcode", "default", "tc-s1-e1", "uid-1") else null
        }
        val issued = mutableListOf<Triple<String, Int, Long>>()
        val credentials = object : CloudInferenceCredentialStore {
            override suspend fun issue(teamId: String, sessionId: String, epoch: Int, ttlMillis: Long, keep: Int): IssuedInferenceCredential {
                issued += Triple(sessionId, epoch, ttlMillis)
                return IssuedInferenceCredential("tcex_synthetic-inference-credential-${issued.size}", 1_000 + ttlMillis, ttlMillis)
            }
            override suspend fun purge(limit: Int) = 0
        }
        val sessions = CloudSessionService(store)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), cluster),
                cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, cluster), RunnerInferenceUseCase(store, credentials)),
                publicOrigin = "http://localhost")
        }
        val created = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Run", "profile-id", null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        store.sessions.clear()
        store.sessions["s1"] = created.copy(id = "s1", currentEpoch = 1, generation = 1)
        store.runs["s1" to 1] = CloudExecution("s1", 1, "tc-s1-e1", ExecutionState.PENDING, 1)
        assertTrue("cloud.inference.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features)
        suspend fun call(token: String?, epoch: Int = 1) = client.post("/api/v1/cloud/runner/inference") {
            token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody("""{"teamId":"default","sessionId":"s1","epoch":$epoch}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, call(null).status)
        assertEquals(HttpStatusCode.Unauthorized, call("colleague").status, "A member credential cannot mint inference credentials")
        assertEquals(HttpStatusCode.Conflict, call("projected-pod-token-1", epoch = 2).status, "Another epoch is fenced")
        assertEquals(HttpStatusCode.Conflict, call("projected-pod-token-1").status, "Not before the runner registered")
        val runner = TeamCodeClient("http://localhost", { "projected-pod-token-1" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            runner.runnerRegister(RunnerRegisterRequest("default", "s1", 1))
            runner.runnerHeartbeat(RunnerHeartbeatRequest("default", "s1", 1, ready = true))
            val response = call("projected-pod-token-1")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store, no-transform", response.headers[HttpHeaders.CacheControl])
            val credential = runner.runnerInference(RunnerInferenceRequest("default", "s1", 1))
            assertTrue(credential.token.startsWith("tcex_")); assertFalse(credential.token in credential.toString())
            assertEquals(listOf(Triple("s1", 1, RunnerInferenceUseCase.TTL_MILLIS), Triple("s1", 1, RunnerInferenceUseCase.TTL_MILLIS)), issued)
            store.sessions["s1"] = store.sessions.getValue("s1").copy(state = CloudSessionState.PAUSED)
            assertEquals("inference_unavailable", assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> {
                runner.runnerInference(RunnerInferenceRequest("default", "s1", 1))
            }.code)
        } finally { runner.close() }
    }

    @Test fun runnerRenewsTheGitHubTokenOfTheExecutionOwnerOnly() = testApplication {
        val store = Store()
        val cluster = object : ClusterPort by FakeCluster {
            override suspend fun reviewToken(clusterId: String, token: String, audience: String): TokenIdentity? =
                if (token == "projected-pod-token-1") TokenIdentity("teamcode", "default", "tc-s1-e1", "uid-1") else null
        }
        val github = GitHubAccessSource { _, member ->
            if (member == colleague.id) GitHubAccess.Linked(GitHubUserToken("ghu_colleague_token", 99, "octo", 42)) else GitHubAccess.NotLinked
        }
        val sessions = CloudSessionService(store)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), cluster),
                cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, cluster), github = RunnerGitHubUseCase(store, github)),
                publicOrigin = "http://localhost")
        }
        val created = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Run", "profile-id", null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        store.sessions.clear()
        store.sessions["s1"] = created.copy(id = "s1", currentEpoch = 1, generation = 1, state = CloudSessionState.ACTIVE)
        store.runs["s1" to 1] = CloudExecution("s1", 1, "tc-s1-e1", ExecutionState.PENDING, 1, ownerId = colleague.id)
        assertTrue("cloud.github.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features)
        suspend fun call(token: String?, epoch: Int = 1) = client.post("/api/v1/cloud/runner/github") {
            token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody("""{"teamId":"default","sessionId":"s1","epoch":$epoch}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, call(null).status)
        assertEquals(HttpStatusCode.Unauthorized, call("colleague").status, "A member credential cannot read the runner's GitHub token")
        assertEquals(HttpStatusCode.Conflict, call("projected-pod-token-1", epoch = 2).status, "Another epoch is fenced")
        assertEquals(HttpStatusCode.Conflict, call("projected-pod-token-1").status, "Not before the execution runs")
        store.runs["s1" to 1] = store.runs.getValue("s1" to 1).copy(state = ExecutionState.RUNNING)
        val runner = TeamCodeClient("http://localhost", { "projected-pod-token-1" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            val response = call("projected-pod-token-1")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store, no-transform", response.headers[HttpHeaders.CacheControl])
            val view = runner.runnerGitHub(RunnerGitHubRequest("default", "s1", 1))
            assertEquals("linked", view.status); assertEquals("ghu_colleague_token", view.token); assertEquals(99, view.expiresAt)
            assertEquals("octo", view.login, "The linked account's login, for the agent's gh configuration")
            assertFalse("ghu_colleague_token" in view.toString())
            // After a transfer the execution runs for the recipient: the former owner's token is never served again.
            store.runs["s1" to 1] = store.runs.getValue("s1" to 1).copy(ownerId = admin.id)
            val transferred = runner.runnerGitHub(RunnerGitHubRequest("default", "s1", 1))
            assertEquals("not_linked", transferred.status); assertNull(transferred.token); assertNull(transferred.login)
            store.sessions["s1"] = store.sessions.getValue("s1").copy(state = CloudSessionState.PAUSED)
            assertEquals("github_unavailable", assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> {
                runner.runnerGitHub(RunnerGitHubRequest("default", "s1", 1))
            }.code)
        } finally { runner.close() }
    }

    /**
     * A session created on someone's behalf runs the creator's instructions: it gets no GitHub token until its owner
     * turns GitHub access on (audit M2). Only the owner changes the access, and turning it off waits for a pause,
     * because the runner keeps a token it already received until that token expires.
     */
    @Test fun aSessionCreatedOnSomeonesBehalfGetsNoGitHubTokenUntilItsOwnerAllowsIt() = testApplication {
        val store = Store()
        val cluster = object : ClusterPort by FakeCluster {
            override suspend fun reviewToken(clusterId: String, token: String, audience: String): TokenIdentity? =
                if (token == "projected-pod-token-1") TokenIdentity("teamcode", "default", "tc-s1-e1", "uid-1") else null
        }
        val github = GitHubAccessSource { _, member ->
            if (member == colleague.id) GitHubAccess.Linked(GitHubUserToken("ghu_colleague_token", 99, "octo", 42)) else GitHubAccess.NotLinked
        }
        val sessions = CloudSessionService(store)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), cluster),
                cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, cluster), github = RunnerGitHubUseCase(store, github)),
                publicOrigin = "http://localhost")
        }
        val created = (sessions.create(Principal(admin, "admin-session", "default"), colleague.id, CloudSessionDraft("Run", "profile-id", null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        assertEquals(colleague.id, created.ownerId)
        store.sessions.clear()
        store.sessions["s1"] = created.copy(id = "s1", currentEpoch = 1, generation = 1, state = CloudSessionState.ACTIVE)
        store.runs["s1" to 1] = CloudExecution("s1", 1, "tc-s1-e1", ExecutionState.RUNNING, 1, ownerId = colleague.id)
        assertTrue("cloud.sessions.github-access.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features)
        assertTrue("\"githubAccess\":\"none\"" in client.get("/api/v1/cloud/sessions/s1") { bearerAuth("colleague") }.bodyAsText())
        val runner = TeamCodeClient("http://localhost", { "projected-pod-token-1" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        suspend fun access(token: String, value: String, onBehalf: String? = null) = client.put("/api/v1/cloud/sessions/s1/github") {
            bearerAuth(token); onBehalf?.let { header("TeamCode-On-Behalf-Of", it) }; contentType(ContentType.Application.Json); setBody("""{"access":"$value"}""")
        }
        try {
            val refused = runner.runnerGitHub(RunnerGitHubRequest("default", "s1", 1))
            assertEquals("not_linked", refused.status, "The creator's instructions never run with the owner's token"); assertNull(refused.token)
            assertEquals(HttpStatusCode.NotFound, access("admin", "owner").status, "Only the owner turns GitHub access on")
            assertEquals(HttpStatusCode.Forbidden, access("admin", "owner", onBehalf = colleague.id).status, "Not even on the owner's behalf")
            assertEquals(HttpStatusCode.BadRequest, access("colleague", "everyone").status)
            val granted = access("colleague", "owner")
            assertEquals(HttpStatusCode.OK, granted.status); assertTrue("\"githubAccess\":\"owner\"" in granted.bodyAsText())
            assertEquals("ghu_colleague_token", runner.runnerGitHub(RunnerGitHubRequest("default", "s1", 1)).token)
            val running = access("colleague", "none")
            assertEquals(HttpStatusCode.Conflict, running.status, "A running agent keeps a token it received: turning access off waits for a pause")
            assertTrue("session_not_paused" in running.bodyAsText())
            store.sessions["s1"] = store.sessions.getValue("s1").copy(state = CloudSessionState.PAUSED, desiredState = DesiredState.PAUSED)
            assertEquals(HttpStatusCode.OK, access("colleague", "none").status)
            store.sessions["s1"] = store.sessions.getValue("s1").copy(githubAccess = CloudGitHubAccess.OWNER, environment = CloudEnvironmentSpec(requireGitHub = true))
            assertEquals(HttpStatusCode.UnprocessableEntity, access("colleague", "none").status, "A profile that requires GitHub cannot run without it")
        } finally { runner.close() }
        // A session the owner created stays as before: the owner's token, with nothing to turn on.
        val own = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Own", "profile-id", null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        assertEquals(CloudGitHubAccess.OWNER, own.githubAccess)
        // A profile that requires GitHub cannot work without the owner's consent: it is refused up front, not failed later.
        store.profile = store.profile.copy(spec = store.profile.spec.copy(environment = CloudEnvironmentSpec(requireGitHub = true)))
        assertEquals("github_owner_consent", assertFailsWith<TeamFailure> {
            sessions.create(Principal(admin, "admin-session", "default"), colleague.id, CloudSessionDraft("Run", "profile-id", null, null, null, emptyMap(), null), null)
        }.code)
    }

    @Test fun runnerReadsTheVariablesOfTheExecutionOwnerOnlyWhileTheSessionRuns() = testApplication {
        val store = Store()
        val cluster = object : ClusterPort by FakeCluster {
            override suspend fun reviewToken(clusterId: String, token: String, audience: String): TokenIdentity? =
                if (token == "projected-pod-token-1") TokenIdentity("teamcode", "default", "tc-s1-e1", "uid-1") else null
        }
        val variables = CloudVariableSource { _, member ->
            CloudVariables(if (member == colleague.id) mapOf("NPM_TOKEN" to "synthetic-colleague-value") else mapOf("NPM_TOKEN" to "synthetic-admin-value"))
        }
        val sessions = CloudSessionService(store)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), cluster),
                cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, cluster), variables = RunnerVariablesUseCase(store, variables)),
                publicOrigin = "http://localhost")
        }
        val created = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Run", "profile-id", null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        store.sessions.clear()
        store.sessions["s1"] = created.copy(id = "s1", currentEpoch = 1, generation = 1, state = CloudSessionState.ACTIVE)
        store.runs["s1" to 1] = CloudExecution("s1", 1, "tc-s1-e1", ExecutionState.PENDING, 1, ownerId = colleague.id)
        assertTrue("cloud.variables.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features)
        suspend fun call(token: String?, epoch: Int = 1) = client.post("/api/v1/cloud/runner/variables") {
            token?.let { bearerAuth(it) }; contentType(ContentType.Application.Json); setBody("""{"teamId":"default","sessionId":"s1","epoch":$epoch}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, call(null).status)
        assertEquals(HttpStatusCode.Unauthorized, call("colleague").status, "A member credential cannot read the runner's variables")
        assertEquals(HttpStatusCode.Conflict, call("projected-pod-token-1", epoch = 2).status, "Another epoch is fenced")
        assertEquals(HttpStatusCode.Conflict, call("projected-pod-token-1").status, "Not before the execution runs")
        store.runs["s1" to 1] = store.runs.getValue("s1" to 1).copy(state = ExecutionState.RUNNING)
        val runner = TeamCodeClient("http://localhost", { "projected-pod-token-1" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            val view = runner.runnerVariables(RunnerVariablesRequest("default", "s1", 1))
            assertEquals(mapOf("NPM_TOKEN" to "synthetic-colleague-value"), view.variables)
            assertFalse("synthetic-colleague-value" in view.toString())
            // After a transfer the execution runs for the recipient: the former owner's values are never served again.
            store.runs["s1" to 1] = store.runs.getValue("s1" to 1).copy(ownerId = admin.id)
            assertEquals(mapOf("NPM_TOKEN" to "synthetic-admin-value"), runner.runnerVariables(RunnerVariablesRequest("default", "s1", 1)).variables)
            store.sessions["s1"] = store.sessions.getValue("s1").copy(state = CloudSessionState.PAUSED)
            assertEquals("variables_unavailable", assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> {
                runner.runnerVariables(RunnerVariablesRequest("default", "s1", 1))
            }.code)
        } finally { runner.close() }
    }

    @Test fun runnerReadsItsEnvironmentAndReportsPreparationThroughTheTypedClient() = testApplication {
        val store = Store()
        val cluster = object : ClusterPort by FakeCluster {
            override suspend fun reviewToken(clusterId: String, token: String, audience: String) =
                if (token == "projected-pod-token-1") TokenIdentity("teamcode", "default", "tc-s1-e1", "uid-1") else null
        }
        val variables = CloudVariableSource { _, member -> CloudVariables(if (member == colleague.id) mapOf("NPM_TOKEN" to "npm_synthetic_value") else emptyMap()) }
        // Only the session owner's link exists; another member's token must never reach this runner.
        val github = GitHubAccessSource { _, member -> if (member == colleague.id) GitHubAccess.Linked(GitHubUserToken("ghu_synthetic_user_token", 99, "octo", 42)) else GitHubAccess.Linked(GitHubUserToken("ghu_admin_token", 99, "boss", 1)) }
        val sessions = CloudSessionService(store)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(Admin(), cluster),
                cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store, variables, github), AuthenticateRunnerUseCase(store, cluster)), publicOrigin = "http://localhost")
        }
        val features = Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("admin") }.bodyAsText()).features
        assertTrue("cloud.environment.v1" in features)
        assertTrue("cloud.sessions.background-work.v1" in features, "Runners report background work and the idle pause counts it")
        val override = CloudEnvironmentRequest(listOf(CloudRepositoryView("https://github.com/example/api.git", "api", "main", "task-1")), "api")
        val response = client.post("/api/v1/cloud/sessions") {
            bearerAuth("colleague"); header(CloudHeaders.IDEMPOTENCY_KEY, "env-1"); contentType(ContentType.Application.Json)
            setBody(Serialization.json.encodeToString(CreateCloudSessionRequest("Env", "profile-id", environment = override)))
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val view = Serialization.json.decodeFromString<CloudSessionView>(response.bodyAsText())
        assertEquals(override.repositories, view.environment.repositories); assertEquals("api", view.environment.workingDirectory)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/cloud/sessions") {
            bearerAuth("colleague"); header(CloudHeaders.IDEMPOTENCY_KEY, "env-2"); contentType(ContentType.Application.Json)
            setBody(Serialization.json.encodeToString(CreateCloudSessionRequest("Env", "profile-id", environment = CloudEnvironmentRequest(listOf(CloudRepositoryView("https://u:p@github.com/x/y", "y"))))))
        }.status, "Credentials in a clone URL are refused")
        val created = store.sessions.getValue(view.id)
        store.sessions.clear()
        store.sessions["s1"] = created.copy(id = "s1", currentEpoch = 1, generation = 1)
        store.runs["s1" to 1] = CloudExecution("s1", 1, "tc-s1-e1", ExecutionState.PENDING, 1)
        val runner = TeamCodeClient("http://localhost", { "projected-pod-token-1" }) { config -> createClient { config(this); install(HttpTimeout) { socketTimeoutMillis = null } } }
        try {
            assertEquals(409, assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> { runner.runnerEnvironment(RunnerEnvironmentRequest("default", "s1", 1)) }.statusCode,
                "Only a registered runner reads the environment")
            runner.runnerRegister(RunnerRegisterRequest("default", "s1", 1))
            val raw = client.post("/api/v1/cloud/runner/environment") {
                bearerAuth("projected-pod-token-1"); contentType(ContentType.Application.Json); setBody("""{"teamId":"default","sessionId":"s1","epoch":1}""")
            }
            assertEquals("no-store, no-transform", raw.headers[HttpHeaders.CacheControl])
            val prepared = runner.runnerEnvironment(RunnerEnvironmentRequest("default", "s1", 1))
            assertEquals(mapOf("NPM_TOKEN" to "npm_synthetic_value"), prepared.variables, "The owner's variables, resolved for the owner")
            assertEquals("ghu_synthetic_user_token", prepared.github.token, "The owner's token, not the caller's or anyone else's"); assertEquals("linked", prepared.github.status)
            assertEquals(RunnerGitIdentityView("octo", "42+octo@users.noreply.github.com"), prepared.git)
            assertEquals("task-1", prepared.environment.repositories.single().branch)
            assertFalse(prepared.toString().contains("npm_synthetic_value") || prepared.toString().contains("ghu_synthetic"))
            runner.runnerPreparation(RunnerPreparationRequest("default", "s1", 1, "cloning_repositories"))
            assertEquals("cloning_repositories", store.sessions.getValue("s1").phase)
            assertEquals(400, assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> { runner.runnerPreparation(RunnerPreparationRequest("default", "s1", 1, "rm -rf /")) }.statusCode)
            assertEquals(400, assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> {
                runner.runnerPreparation(RunnerPreparationRequest("default", "s1", 1, "cloning_repositories", RunnerPreparationFailureView("clone", 5, 128)))
            }.statusCode, "A step index outside the recipe is refused")
            runner.runnerPreparation(RunnerPreparationRequest("default", "s1", 1, "cloning_repositories", RunnerPreparationFailureView("clone", 0, 128)))
            val failed = store.sessions.getValue("s1")
            assertEquals(CloudEnvironmentRules.PREPARATION_FAILED, failed.failure?.code)
            assertTrue(failed.failure!!.message.contains("exit code 128"))
            runner.runnerHeartbeat(RunnerHeartbeatRequest("default", "s1", 1, ready = true))
            assertEquals(CloudSessionState.PREPARING, store.sessions.getValue("s1").state, "A failed preparation is never reported ready")
            assertEquals(409, assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> { runner.runnerEnvironment(RunnerEnvironmentRequest("default", "s1", 1)) }.statusCode)
        } finally { runner.close() }
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
