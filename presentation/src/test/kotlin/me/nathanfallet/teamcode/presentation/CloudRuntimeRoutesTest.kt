package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.emptyFlow
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** The stored cloud runtime: always configurable by administrators, and the only switch for cloud sessions. */
class CloudRuntimeRoutesTest {
    private val admin = Member("admin-id", "admin", Role.ADMIN, false, false)
    private val colleague = Member("colleague-id", "colleague", Role.MEMBER, false, false)

    private class Runtime : CloudRuntimeStore {
        var stored = CloudRuntime()
        override suspend fun runtime(actor: Principal) = stored
        override suspend fun saveRuntime(actor: Principal, runtime: CloudRuntime) = runtime.copy(updatedAt = 1).also { stored = it }
        override suspend fun current() = stored
        override suspend fun seed(initial: CloudRuntime) = stored.takeIf { it.updatedAt != null } ?: initial.copy(updatedAt = 1).also { stored = it }
    }
    private class Admin : CloudAdminStore {
        val clusters = mutableListOf<Pair<String?, CloudClusterInput>>()
        override suspend fun clusters(actor: Principal) = listOf(CloudCluster("cluster-id", "default", "lab", "https://k8s.example.test", listOf("teamcode"), 10, null, 3, 0, null, 1, 1))
        override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput): CloudCluster {
            clusters += id to input
            return clusters(actor).single()
        }
        override suspend fun profiles(actor: Principal) = emptyList<CloudProfile>()
        override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) = error("unused")
        override suspend fun settings(actor: Principal) = CloudSettings()
        override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings
    }
    /** Lists no session; nothing else is reached by these tests. */
    private object Sessions : CloudSessionStore {
        override suspend fun now() = 1L
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String): Member? = null
        override suspend fun profile(teamId: String, profileId: String): CloudProfile? = null
        override suspend fun profileVersion(teamId: String, profileId: String, version: Int): CloudProfileSpec? = null
        override suspend fun cluster(teamId: String, clusterId: String): CloudCluster? = null
        override suspend fun settings(teamId: String) = CloudSettings()
        override suspend fun insert(session: CloudSession, actor: CloudActor, idempotency: IdempotencyRequest?, retainedLimit: Int): CloudCreateOutcome = error("unused")
        override suspend fun find(teamId: String, id: String): CloudSession? = null
        override suspend fun list(teamId: String, query: CloudSessionQuery) = CloudSessionPage(emptyList(), null)
        override suspend fun executions(teamId: String, sessionId: String) = emptyList<CloudExecution>()
        override suspend fun execution(sessionId: String, epoch: Int): CloudExecution? = null
        override suspend fun compareAndSet(expected: CloudSession, next: CloudSession, write: CloudWrite): CloudSession? = null
        override suspend fun claimDue(owner: String, leaseMillis: Long, limit: Int) = emptyList<CloudSession>()
        override suspend fun release(sessionId: String, owner: String, reconcileAt: Long?) = Unit
        override suspend fun startExecution(expected: CloudSession, next: CloudSession, execution: CloudExecution, leaseOwner: String, memberRunningLimit: Int) = StartOutcome.Conflict
        override suspend fun heartbeat(sessionId: String, epoch: Int, runnerReady: Boolean) = false
        override suspend fun liveObjects(clusterId: String) = LiveObjects(emptySet(), emptySet())
        override suspend fun namespaces(clusterId: String) = emptySet<String>()
        override suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int) = Unit
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
    private fun team(): TeamService = object : TeamService by FakeTeam() {
        override suspend fun authenticate(token: String): Principal? = when (token) {
            "admin" -> Principal(admin, "admin-session", "default")
            "colleague" -> Principal(colleague, "colleague-session", "default")
            else -> null
        }
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) =
            receive(GenerationHead(200, "text/event-stream"), emptyFlow())
    }

    @Test fun cloudSessionsFollowTheStoredRuntimeAndStayConfigurable() = testApplication {
        val store = Runtime()
        val clusters = Admin()
        val runtime = CloudRuntimeService(store, "https://teamcode.example.test")
        application {
            configureGateway(gateway, team = team(), cloudRuntime = runtime, cloudSessions = CloudSessionService(Sessions), cloudAdmin = CloudAdministrationService(clusters, FakeCluster),
                publicOrigin = "https://teamcode.example.test")
        }
        suspend fun features() = Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("admin") }.bodyAsText()).features
        suspend fun form(path: String, body: String) = client.post(path) {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://teamcode.example.test")
            contentType(ContentType.Application.FormUrlEncoded); setBody(body)
        }

        // Off: no capability, sessions answer 404, configuration stays available to administrators.
        assertTrue(features().none { it.startsWith("cloud.") })
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cloud/sessions") { bearerAuth("admin") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/cloud/settings") { bearerAuth("admin") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/cloud/clusters") { bearerAuth("admin") }.status)
        val page = client.get("/dashboard/cloud") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertContains(page, "Runner image"); assertContains(page, "Kubernetes clusters"); assertContains(page, "Token and CA bundle defined")
        assertContains(client.get("/dashboard/settings") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText(), "/dashboard/cloud")
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/runtime") { bearerAuth("colleague") }.status)

        // Enabling without an image is refused; with one, every cloud capability appears at once.
        assertEquals(HttpStatusCode.BadRequest, form("/dashboard/cloud/runtime", "csrf=admin-session&enabled=on&runnerImage=&runnerBinary=%2Fusr%2Flocal%2Fbin%2Fteamcode&serverUrl=&workspaceAccessMode=ReadWriteOncePod").status)
        assertFalse(store.stored.enabled)
        assertEquals(HttpStatusCode.Found, form("/dashboard/cloud/runtime", "csrf=admin-session&enabled=on&runnerImage=registry.example.test%2Frunner%40sha256%3Aabc&runnerBinary=%2Fusr%2Flocal%2Fbin%2Fteamcode&serverUrl=&workspaceAccessMode=ReadWriteOnce").status)
        assertEquals(CloudRuntime(true, "registry.example.test/runner@sha256:abc", "/usr/local/bin/teamcode", null, "ReadWriteOnce", 1), store.stored)
        assertTrue("cloud.sessions.v1" in features())
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/cloud/sessions") { bearerAuth("admin") }.status)
        val view = Serialization.json.decodeFromString<CloudRuntimeView>(client.get("/api/v1/cloud/runtime") { bearerAuth("admin") }.bodyAsText())
        assertEquals("https://teamcode.example.test", view.effectiveServerUrl, "An empty server URL uses the public URL")

        // A loopback callback origin cannot be stored; turning off through the API hides everything again.
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/cloud/runtime") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"enabled":true,"runnerImage":"r:1","serverUrl":"https://127.0.0.1:8443"}""") }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/cloud/runtime") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"enabled":false,"runnerImage":"r:1"}""") }.status)
        assertTrue(features().none { it.startsWith("cloud.") })
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cloud/sessions") { bearerAuth("colleague") }.status)
    }

    @Test fun clustersAreEditedOnTheDashboardWithoutShowingTheirCredentials() = testApplication {
        val clusters = Admin()
        application {
            configureGateway(gateway, team = team(), cloudRuntime = CloudRuntimeService(Runtime(), "https://teamcode.example.test"), cloudSessions = CloudSessionService(Sessions),
                cloudAdmin = CloudAdministrationService(clusters, FakeCluster), publicOrigin = "https://teamcode.example.test")
        }
        val pem = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----"
        val created = client.post("/dashboard/cloud/clusters") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://teamcode.example.test")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("csrf" to "admin-session", "name" to "lab", "apiUrl" to "https://k8s.example.test", "caCertificate" to pem, "serviceAccountToken" to "a-service-account-token",
                "allowedNamespaces" to "teamcode, sandbox", "maxRunningPods" to "4", "defaultStorageClass" to "").formUrlEncode())
        }
        assertEquals(HttpStatusCode.Found, created.status)
        assertEquals(CloudClusterInput("lab", "https://k8s.example.test", pem, "a-service-account-token", listOf("teamcode", "sandbox"), 4, null), clusters.clusters.single().second)
        // Empty credential fields keep the stored ones.
        assertEquals(HttpStatusCode.Found, client.post("/dashboard/cloud/clusters/cluster-id") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://teamcode.example.test")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("csrf" to "admin-session", "version" to "3", "name" to "lab", "apiUrl" to "https://k8s.example.test", "caCertificate" to "", "serviceAccountToken" to "",
                "allowedNamespaces" to "teamcode", "maxRunningPods" to "4").formUrlEncode())
        }.status)
        assertEquals("cluster-id" to CloudClusterInput("lab", "https://k8s.example.test", null, null, listOf("teamcode"), 4, null), clusters.clusters.last())
        val page = client.get("/dashboard/cloud") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertFalse("a-service-account-token" in page || "MIIB" in page, "Credentials are never shown again")
        assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/cloud") { header(HttpHeaders.Cookie, "teamcode_session=colleague") }.status)
    }

    @Test fun onlyRunningAndLinkingPathsAreGated() {
        for (path in listOf("/api/v1/cloud/sessions", "/api/v1/cloud/sessions/s1/pause", "/api/v1/cloud/transfers", "/api/v1/cloud/runner/register",
            "/api/v1/cloud/settings/../sessions", "/api/v1/cloud/clusters%2F..%2Fsessions", "/api/v1/github/link", "/api/v1/github/link/device", GITHUB_CALLBACK_PATH, "/dashboard/github/link"))
            assertTrue(cloudOperationalPath(path), path)
        for (path in listOf("/api/v1/cloud/settings", "/api/v1/cloud/runtime", "/api/v1/cloud/clusters", "/api/v1/cloud/clusters/c1", "/api/v1/cloud/profiles",
            "/api/v1/github/app", "/api/v1/github/links", "/dashboard/cloud", "/dashboard/github", "/api/v1/capabilities"))
            assertFalse(cloudOperationalPath(path), path)
    }

    @Test fun runtimeValidation() {
        val origin = "https://teamcode.example.test"
        CloudRuntimeService.validate(CloudRuntime(), origin)
        CloudRuntimeService.validate(CloudRuntime(enabled = true, runnerImage = "ghcr.io/team/runner@sha256:0123"), origin)
        fun refused(runtime: CloudRuntime, public: String = origin) = assertFailsWith<TeamFailure> { CloudRuntimeService.validate(runtime, public) }
        refused(CloudRuntime(enabled = true))
        refused(CloudRuntime(enabled = true, runnerImage = "runner:1"), "http://localhost:8080")
        refused(CloudRuntime(runnerImage = "runner 1"))
        refused(CloudRuntime(runnerBinary = "teamcode"))
        refused(CloudRuntime(runnerBinary = "/opt/../teamcode"))
        refused(CloudRuntime(workspaceAccessMode = "ReadWriteMany"))
        refused(CloudRuntime(serverUrl = "http://teamcode.internal"))
        CloudRuntimeService.validate(CloudRuntime(enabled = true, runnerImage = "runner:1", serverUrl = "https://teamcode.internal"), "http://localhost:8080")
    }
}
