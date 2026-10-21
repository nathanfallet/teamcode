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
        override suspend fun saveRuntime(actor: Principal, runtime: CloudRuntime, expectedUpdatedAt: Long?): CloudRuntime {
            if (stored.updatedAt != expectedUpdatedAt) throw TeamFailure("cloud_runtime_conflict", 409, "Changed.")
            return runtime.copy(updatedAt = (expectedUpdatedAt ?: 0) + 1).also { stored = it }
        }
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

        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/cloud/runner/heartbeat") { contentType(ContentType.Application.Json); setBody("{}") }.status,
            "An anonymous caller learns nothing about the cloud state")
        // Off: no capability, sessions answer 404, configuration stays available to administrators.
        assertTrue(features().none { it.startsWith("cloud.") })
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/cloud/sessions") { bearerAuth("admin"); header("Idempotency-Key", "k1"); contentType(ContentType.Application.Json); setBody("{}") }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/cloud/sessions") { bearerAuth("admin") }.status, "Existing sessions stay readable and stoppable")
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/cloud/sessions").status, "The state is not disclosed before authentication")
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
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/cloud/runtime") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"enabled":true,"runnerImage":null,"runnerBinary":"/usr/local/bin/teamcode","serverUrl":null,"workspaceAccessMode":"ReadWriteOnce","updatedAt":1}""") }.status,
            "Cloud sessions cannot stay on without a runner image")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/cloud/sessions") { bearerAuth("admin") }.status)
        val view = Serialization.json.decodeFromString<CloudRuntimeView>(client.get("/api/v1/cloud/runtime") { bearerAuth("admin") }.bodyAsText())
        assertEquals("https://teamcode.example.test", view.effectiveServerUrl, "An empty server URL uses the public URL")

        // A loopback callback origin cannot be stored; turning off through the API hides everything again.
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/cloud/runtime") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"enabled":true,"runnerImage":"r:1","runnerBinary":"/usr/local/bin/teamcode","serverUrl":"https://127.0.0.1:8443","workspaceAccessMode":"ReadWriteOnce","updatedAt":1}""") }.status)
        assertEquals(HttpStatusCode.Conflict, client.put("/api/v1/cloud/runtime") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"enabled":false,"runnerImage":"r:1","runnerBinary":"/usr/local/bin/teamcode","serverUrl":null,"workspaceAccessMode":"ReadWriteOnce"}""") }.status,
            "A save based on an older read never overwrites another administrator's change")
        assertTrue(store.stored.enabled)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/cloud/runtime") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"enabled":false,"runnerImage":"r:1","runnerBinary":"/usr/local/bin/teamcode","serverUrl":null,"workspaceAccessMode":"ReadWriteOnce","updatedAt":1}""") }.status)
        assertTrue(features().none { it.startsWith("cloud.") })
        for (spelling in listOf("/api/v1/cloud/sessions", "/api/v1//cloud/sessions", "/api/v1/cloud//sessions", "/api/v1/cl%6Fud/sessions"))
            assertEquals(HttpStatusCode.NotFound, client.post(spelling) { bearerAuth("colleague"); header("Idempotency-Key", "k2"); contentType(ContentType.Application.Json); setBody("{}") }.status, spelling)
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

    /** Opaque and bound to its context, like the real cipher; not encryption, which the infrastructure tests cover. */
    private class PlainSealer : SecretSealer {
        private val base64 = java.util.Base64.getUrlEncoder().withoutPadding()
        override fun seal(plaintext: ByteArray, context: String) = base64.encodeToString("$context|${base64.encodeToString(plaintext)}".toByteArray())
        override fun open(envelope: String, context: String): ByteArray? = runCatching { String(java.util.Base64.getUrlDecoder().decode(envelope)) }.getOrNull()
            ?.takeIf { it.startsWith("$context|") }?.let { java.util.Base64.getUrlDecoder().decode(it.removePrefix("$context|")) }
    }

    @Test fun aKubeconfigPrefillsTheClusterFormAndItsSecretsNeverReachTheBrowser() = testApplication {
        val clusters = Admin()
        application {
            configureGateway(gateway, team = team(), cloudRuntime = CloudRuntimeService(Runtime(), "https://teamcode.example.test"), cloudSessions = CloudSessionService(Sessions),
                cloudAdmin = CloudAdministrationService(clusters, FakeCluster, KubeconfigImportService(PlainSealer())), publicOrigin = "https://teamcode.example.test")
        }
        val pem = "-----BEGIN CERTIFICATE-----\nMIIBimportedca\n-----END CERTIFICATE-----"
        val ca = java.util.Base64.getEncoder().encodeToString(pem.toByteArray())
        val token = "imported-service-account-token"
        fun config(vararg contexts: Triple<String, String, String>) = "current-context: ${contexts.last().first}\nclusters:\n- name: c\n  cluster: {server: 'https://k8s.example.test:6443', certificate-authority-data: $ca}\nusers:\n" +
            contexts.joinToString("") { (name, user, _) -> "- name: $name\n  user: $user\n" } + "contexts:\n" + contexts.joinToString("") { (name, _, namespace) -> "- name: $name\n  context: {cluster: c, user: $name, namespace: $namespace}\n" }
        suspend fun post(path: String, vararg fields: Pair<String, String>) = client.post(path) {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://teamcode.example.test")
            contentType(ContentType.Application.FormUrlEncoded); setBody((listOf("csrf" to "admin-session") + fields).formUrlEncode())
        }
        fun sealedOf(page: String) = Regex("name=\"kubeconfigSealed\" value=\"([^\"]+)\"").find(page)!!.groupValues[1]

        // The cluster page offers the import, with the script allowed by its CSP; the script is served to administrators only.
        val cloud = client.get("/dashboard/cloud") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertContains(cloud.bodyAsText(), "Import a kubeconfig"); assertContains(cloud.headers["Content-Security-Policy"]!!, "script-src 'self'")
        assertEquals(HttpStatusCode.OK, client.get("/dashboard/assets/kubeconfig-import.js") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/assets/kubeconfig-import.js") { header(HttpHeaders.Cookie, "teamcode_session=colleague") }.status)

        // One context: the form is prefilled at once; neither the CA nor the token is in the page.
        val single = post("/dashboard/cloud/clusters/import", "kubeconfig" to config(Triple("lab", "{token: $token}", "teamcode"))).bodyAsText()
        assertContains(single, "value=\"https://k8s.example.test:6443\""); assertContains(single, "value=\"teamcode\""); assertContains(single, "value=\"lab\"")
        assertContains(single, "from the kubeconfig")
        assertFalse(token in single || "MIIBimportedca" in single || ca in single, "Secrets stay on the server")
        assertFalse(Regex("<(input|textarea)[^>]*required").containsMatchIn(single.substringAfter("Service-account token").substringBefore("Allowed namespaces")), "Imported credentials are not asked again")

        // Saving through the same path stores the imported CA and token, like a manual entry.
        assertEquals(HttpStatusCode.Found, post("/dashboard/cloud/clusters", "kubeconfigSealed" to sealedOf(single), "kubeconfigContext" to "lab", "name" to "lab", "apiUrl" to "https://k8s.example.test:6443",
            "caCertificate" to "", "serviceAccountToken" to "", "allowedNamespaces" to "teamcode", "maxRunningPods" to "4").status)
        assertEquals(CloudClusterInput("lab", "https://k8s.example.test:6443", pem, token, listOf("teamcode"), 4, null), clusters.clusters.single().second)

        // A value typed in the form wins over the kubeconfig's.
        post("/dashboard/cloud/clusters", "kubeconfigSealed" to sealedOf(single), "kubeconfigContext" to "lab", "name" to "lab", "apiUrl" to "https://k8s.example.test:6443",
            "caCertificate" to "", "serviceAccountToken" to "typed-replacement-token", "allowedNamespaces" to "teamcode", "maxRunningPods" to "4")
        assertEquals("typed-replacement-token", clusters.clusters.last().second.serviceAccountToken); assertEquals(pem, clusters.clusters.last().second.caCertificate)

        // Several contexts: a picker with the current context selected and the unusable one disabled with its reason.
        val picker = post("/dashboard/cloud/clusters/import", "kubeconfig" to config(Triple("dev", "{token: $token-dev}", "dev-ns"), Triple("sso", "{exec: {command: aws}}", "x"), Triple("prod", "{token: $token-prod}", "prod-ns"))).bodyAsText()
        assertContains(picker, "several contexts"); assertContains(picker, "current context")
        assertTrue(Regex("value=\"prod\" checked").containsMatchIn(picker), "current-context is selected by default")
        assertTrue(Regex("value=\"sso\" disabled").containsMatchIn(picker)); assertContains(picker, "exec plugin")
        assertFalse("$token-prod" in picker || "$token-dev" in picker)
        val chosen = post("/dashboard/cloud/clusters/import/context", "kubeconfigSealed" to sealedOf(picker), "context" to "dev").bodyAsText()
        assertContains(chosen, "value=\"dev-ns\""); assertFalse("$token-dev" in chosen)
        post("/dashboard/cloud/clusters", "kubeconfigSealed" to sealedOf(chosen), "kubeconfigContext" to "dev", "name" to "dev", "apiUrl" to "https://k8s.example.test:6443", "allowedNamespaces" to "dev-ns", "maxRunningPods" to "2")
        assertEquals("$token-dev", clusters.clusters.last().second.serviceAccountToken)
        assertEquals(HttpStatusCode.UnprocessableEntity, post("/dashboard/cloud/clusters/import/context", "kubeconfigSealed" to sealedOf(picker), "context" to "sso").status, "An unusable context cannot be chosen")
        assertNotEquals(sealedOf(picker), sealedOf(chosen), "Once a context is chosen, the other contexts' secrets are dropped")
        assertEquals(HttpStatusCode.UnprocessableEntity, post("/dashboard/cloud/clusters", "kubeconfigSealed" to sealedOf(chosen), "kubeconfigContext" to "prod", "name" to "prod", "apiUrl" to "https://k8s.example.test:6443",
            "allowedNamespaces" to "prod-ns", "maxRunningPods" to "2").status, "A chosen import cannot be replayed for another context")

        // exec and auth-provider kubeconfigs are refused with a message that says why and what to do.
        for (user in listOf("{exec: {command: aws}}", "{auth-provider: {name: gcp}}")) {
            val refused = post("/dashboard/cloud/clusters/import", "kubeconfig" to config(Triple("only", user, "ns")))
            assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
            assertContains(refused.bodyAsText(), "service-account token")
        }
        // A large kubeconfig (percent-encoded, with a lot of newlines and colons) is read, or refused as a kubeconfig, never as an oversized body.
        val large = "current-context: x\n" + "#:#:#:#:#:#:#:#:#\n".repeat(6_500)
        assertTrue(large.length < Kubeconfig.MAX_CHARACTERS && listOf("kubeconfig" to large).formUrlEncode().length > 256 * 1024)
        assertEquals(HttpStatusCode.UnprocessableEntity, post("/dashboard/cloud/clusters/import", "kubeconfig" to large).status)
        val count = clusters.clusters.size
        assertEquals(HttpStatusCode.UnprocessableEntity, post("/dashboard/cloud/clusters/import", "kubeconfig" to "not: [a kubeconfig").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, post("/dashboard/cloud/clusters", "kubeconfigSealed" to "forged", "kubeconfigContext" to "lab", "name" to "x", "apiUrl" to "https://k8s.example.test",
            "allowedNamespaces" to "teamcode", "maxRunningPods" to "4").status)
        assertEquals(count, clusters.clusters.size, "A refused import creates nothing")

        // Administrators only, and the origin check applies like any dashboard form.
        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/cloud/clusters/import") {
            header(HttpHeaders.Cookie, "teamcode_session=colleague"); header(HttpHeaders.Origin, "https://teamcode.example.test")
            contentType(ContentType.Application.FormUrlEncoded); setBody(listOf("csrf" to "colleague-session", "kubeconfig" to config(Triple("lab", "{token: $token}", "x"))).formUrlEncode())
        }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/dashboard/cloud/clusters/import") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://evil.example.test")
            contentType(ContentType.Application.FormUrlEncoded); setBody(listOf("csrf" to "admin-session", "kubeconfig" to config(Triple("lab", "{token: $token}", "x"))).formUrlEncode())
        }.status)
    }

    @Test fun manualEntryStaysTheOnlyWayWithoutAKubeconfigImportService() = testApplication {
        application {
            configureGateway(gateway, team = team(), cloudRuntime = CloudRuntimeService(Runtime(), "https://teamcode.example.test"), cloudSessions = CloudSessionService(Sessions),
                cloudAdmin = CloudAdministrationService(Admin(), FakeCluster), publicOrigin = "https://teamcode.example.test")
        }
        val page = client.get("/dashboard/cloud") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertContains(page, "Connect a cluster"); assertFalse("Import a kubeconfig" in page)
        // Without the service the import route does not exist: the path falls to the cluster edit route, which refuses it.
        assertEquals(HttpStatusCode.BadRequest, client.post("/dashboard/cloud/clusters/import") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "https://teamcode.example.test")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=admin-session&kubeconfig=x")
        }.status)
    }

    @Test fun onlyNewWorkIsRefusedWhileOff() {
        for ((method, path) in listOf("POST" to "/api/v1/cloud/sessions", "POST" to "/api/v1/cloud/sessions/s1/resume", "POST" to "/api/v1/cloud/sessions/s1/messages",
            "POST" to "/api/v1/cloud/sessions/s1/attachments", "POST" to "/api/v1/cloud/sessions/s1/transfer", "POST" to "/api/v1/cloud/transfers/t1/accept",
            "POST" to "//api/v1/cloud/sessions", "POST" to "/api/v1//cloud/sessions", "POST" to "/api/v1/cl%6Fud/sessions", "POST" to "/api/v1/cloud/%ZZ",
            "POST" to "/api/v1/github/link/device", "POST" to "/api/v1/github//link/device", "POST" to "/dashboard/github/link", "POST" to "/%61pi/v1/cloud/sessions"))
            assertTrue(cloudStartsWork(method, path), "$method $path")
        for ((method, path) in listOf("GET" to "/api/v1/cloud/sessions", "GET" to "/api/v1/cloud/sessions/s1", "POST" to "/api/v1/cloud/sessions/s1/pause",
            "POST" to "/api/v1/cloud/sessions/s1/interrupt", "DELETE" to "/api/v1/cloud/sessions/s1", "GET" to "/api/v1/cloud/sessions/s1/events",
            "POST" to "/api/v1/cloud/transfers/t1/decline", "PUT" to "/api/v1/cloud/runtime", "POST" to "/api/v1/cloud/clusters", "GET" to "/api/v1/cloud/profiles",
            "POST" to "/api/v1/cloud/runner/register", "POST" to "/api/v1/cloud/runner/heartbeat", "POST" to "/api/v1/cloud/runner/inputs",
            "GET" to "/api/v1/github/link", "DELETE" to "/api/v1/github/link", "POST" to "/dashboard/github/unlink", "GET" to "/dashboard/github", "GET" to "/api/v1/capabilities", "POST" to "/api/v1/github/link/device/abc", "POST" to "/api/v1/cloud/sessions/s1/attachments/a1/renew", "GET" to GITHUB_CALLBACK_PATH, "POST" to "/v1/messages",
            // A malformed path outside cloud routes is left to routing.
            "GET" to "/dashboard/%ZZ", "POST" to "/v1/messages%zz", "GET" to "/health/%"))
            assertFalse(cloudStartsWork(method, path), "$method $path")
    }

    @Test fun runtimeValidation() {
        val origin = "https://teamcode.example.test"
        CloudRuntimeService.validate(CloudRuntime(), origin)
        CloudRuntimeService.validate(CloudRuntime(enabled = true, runnerImage = "ghcr.io/team/runner@sha256:0123"), origin)
        fun refused(runtime: CloudRuntime, public: String = origin) = assertFailsWith<TeamFailure> { CloudRuntimeService.validate(runtime, public) }
        refused(CloudRuntime(enabled = true))
        refused(CloudRuntime(enabled = true, runnerImage = "runner:1"), "http://localhost:8080")
        // Off, an administrator can always save, even while the public URL is plain HTTP.
        CloudRuntimeService.validate(CloudRuntime(enabled = false, runnerImage = "runner:1"), "http://localhost:8080")
        refused(CloudRuntime(runnerImage = "runner 1"))
        refused(CloudRuntime(runnerBinary = "teamcode"))
        refused(CloudRuntime(runnerBinary = "/opt/../teamcode"))
        refused(CloudRuntime(workspaceAccessMode = "ReadWriteMany"))
        refused(CloudRuntime(serverUrl = "http://teamcode.internal"))
        for (spelling in listOf("https://0x7f.0.0.1", "https://0177.0.0.1", "https://127.000.0.1", "https://\u0661\u0662\u0667.0.0.1", "https://[::ffff:127.0.0.1]", "https://[fe80::1%25eth0]"))
            refused(CloudRuntime(serverUrl = spelling))
        CloudRuntimeService.validate(CloudRuntime(serverUrl = "https://10.0.0.5:8443"), origin)
        CloudRuntimeService.validate(CloudRuntime(serverUrl = "https://teamcode.internal.example"), origin)
        for (loopback in listOf("https://LocalHost:8443", "https://0.0.0.0", "https://[0:0:0:0:0:0:0:1]", "https://[::]", "https://127.1", "https://2130706433", "https://api.localhost", "https://999.1.1.1", "https://256.0.0.1"))
            refused(CloudRuntime(serverUrl = loopback))
        CloudRuntimeService.validate(CloudRuntime(enabled = true, runnerImage = "runner:1", serverUrl = "https://teamcode.internal"), "http://localhost:8080")
    }
}
