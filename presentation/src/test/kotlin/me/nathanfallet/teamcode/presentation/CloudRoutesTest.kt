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
        /** A profile assigned to nobody: only administrators may use it. */
        val reserved = CloudProfile("reserved-id", "default", "reserved", 1, profile.spec, emptySet(), 1, 1)
        override suspend fun now() = clock++
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String) = listOf(admin, colleague).find { it.id == memberId }
        override suspend fun profile(teamId: String, profileId: String) = listOf(profile, reserved).find { it.id == profileId }
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
        override suspend fun heartbeat(sessionId: String, epoch: Int, runnerReady: Boolean): Boolean {
            val run = runs[sessionId to epoch]?.takeIf { sessions[sessionId]?.currentEpoch == epoch } ?: return false
            runs[sessionId to epoch] = run.copy(lastHeartbeatAt = clock, runnerReady = runnerReady)
            return true
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
            "service-admin" -> Principal(admin, "admin-key", "default", ServiceScope.entries.toSet() - setOf(ServiceScope.CLOUD_READ, ServiceScope.CLOUD_WRITE, ServiceScope.ADMIN_CLOUD, ServiceScope.TEAM_READ))
            "colleague-read" -> Principal(colleague, "colleague-read-key", "default", setOf(ServiceScope.CLOUD_READ))
            "colleague-write" -> Principal(colleague, "colleague-write-key", "default", setOf(ServiceScope.CLOUD_WRITE))
            "admin-write" -> Principal(admin, "admin-write-key", "default", setOf(ServiceScope.CLOUD_WRITE))
            "admin-cloud" -> Principal(admin, "admin-cloud-key", "default", setOf(ServiceScope.ADMIN_CLOUD))
            "admin-read" -> Principal(admin, "admin-read-key", "default", setOf(ServiceScope.TEAM_READ))
            "admin-usage" -> Principal(admin, "admin-usage-key", "default", setOf(ServiceScope.USAGE_READ, ServiceScope.CLOUD_READ))
            "admin-read-write" -> Principal(admin, "admin-read-write-key", "default", setOf(ServiceScope.TEAM_READ, ServiceScope.CLOUD_WRITE))
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
        assertEquals(CloudSessionHostView("device-1", "Work laptop", "macos", "15.1", "teamcode"), remote.host)
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
            assertEquals(409, assertFailsWith<me.nathanfallet.teamcode.client.TeamCodeException> {
                runner.runnerCheckpoint(RunnerCheckpointRequest("default", "s1", 1, completed = true))
            }.statusCode, "No checkpoint before a pause is requested")
        } finally { runner.close() }
        assertEquals(1, reviews.count { it == "projected-pod-token-1" }, "Token reviews are cached")
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
        assertTrue("cloud.environment.v1" in Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("admin") }.bodyAsText()).features)
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
