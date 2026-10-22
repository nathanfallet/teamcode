package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.emptyFlow
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** Profiles created, edited and published on the dashboard, with choices read from the chosen cluster. */
class CloudProfileRoutesTest {
    private val admin = Member("admin-id", "admin", Role.ADMIN, false, false)
    private val colleague = Member("colleague-id", "colleague", Role.MEMBER, false, false)
    private val former = Member("former-id", "former", Role.MEMBER, true, false)
    private val origin = "https://teamcode.example.test"

    private class Admin : CloudAdminStore {
        val clusters = listOf(
            CloudCluster("lab-id", "default", "lab", "https://lab.example.test", listOf("teamcode", "sandbox", "gone"), 10, null, 1, 0, null, 1, 1),
            CloudCluster("prod-id", "default", "prod", "https://prod.example.test", listOf("agents"), 10, "fast", 1, 0, null, 1, 1),
        )
        val profiles = mutableListOf<CloudProfile>()
        val saves = mutableListOf<Triple<String?, Int, CloudProfileSpec>>()
        override suspend fun clusters(actor: Principal) = clusters
        override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput) = error("unused")
        override suspend fun profiles(actor: Principal) = profiles.toList()
        override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>): CloudProfile {
            val current = profiles.find { it.id == id }
            if ((current?.version ?: 0) != expectedVersion) throw TeamFailure("profile_conflict", 409, "Changed.")
            saves += Triple(id, expectedVersion, spec)
            val saved = CloudProfile(id ?: "profile-id", actor.teamId, name, expectedVersion + 1, spec, assignedMemberIds, 1, 2)
            profiles.removeAll { it.id == saved.id }; profiles += saved
            return saved
        }
        override suspend fun settings(actor: Principal) = CloudSettings()
        override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings
    }

    /** Storage classes, namespaces and secret names per cluster; the secrets read can be refused like a missing RBAC right. */
    private class Cluster : ClusterPort by unused(), ClusterDiscoveryPort {
        var secretsForbidden = false
        val secretReads = mutableListOf<Pair<String, String>>()
        private val classes = mapOf(
            "lab-id" to listOf(StorageClassChoice("standard", "Delete", true, "rancher.io/local-path"), StorageClassChoice("keep", "Retain", false, "csi.example")),
            "prod-id" to listOf(StorageClassChoice("fast", "Delete", false, "csi.example")),
        )
        override suspend fun storageClasses(clusterId: String) = classes.getValue(clusterId)
        override suspend fun namespaceExists(clusterId: String, namespace: String) = namespace != "gone"
        override suspend fun imagePullSecrets(clusterId: String, namespace: String): List<String> {
            secretReads += clusterId to namespace
            if (secretsForbidden) throw ClusterAccessDeniedException()
            return if (clusterId == "lab-id") listOf("registry", "mirror") else listOf("prod-registry")
        }
        override suspend fun storageClass(clusterId: String, name: String?) =
            classes.getValue(clusterId).firstOrNull { it.name == (name ?: classes.getValue(clusterId).first { c -> c.isDefault }.name) }?.let { StorageClassObservation(it.name, it.reclaimPolicy) }
    }

    private fun team(): TeamService = object : TeamService by FakeTeam() {
        override suspend fun authenticate(token: String): Principal? = when (token) {
            "admin" -> Principal(admin, "admin-session", "default")
            "colleague" -> Principal(colleague, "colleague-session", "default")
            else -> null
        }
        override suspend fun members(principal: Principal) = listOf(admin, colleague, former)
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) =
            receive(GenerationHead(200, "text/event-stream"), emptyFlow())
    }

    private fun ApplicationTestBuilder.server(store: Admin, cluster: Cluster) = application {
        configureGateway(gateway, team = team(), cloudSessions = CloudSessionService(unused()), cloudAdmin = CloudAdministrationService(store, cluster), publicOrigin = origin)
    }
    private suspend fun ApplicationTestBuilder.page(path: String, as_: String = "admin") = client.get(path) { header(HttpHeaders.Cookie, "teamcode_session=$as_") }
    private suspend fun ApplicationTestBuilder.draft(vararg fields: Pair<String, String>, csrf: String = "admin-session") = client.post("/dashboard/cloud/profiles/draft") {
        header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
        contentType(ContentType.Application.FormUrlEncoded); setBody((listOf("csrf" to csrf) + fields).formUrlEncode())
    }
    private fun valid(vararg overrides: Pair<String, String>): Array<Pair<String, String>> {
        val base = linkedMapOf("name" to "toolchain", "version" to "0", "clusterId" to "lab-id", "namespace" to "teamcode", "image" to "registry.example/toolchain:1",
            "cpuMillis" to "2000", "memoryMebibytes" to "4096", "workspaceSizeGibibytes" to "20", "storageClass" to "", "agentKind" to "claude", "agentVersion" to "",
            "idleTimeoutMinutes" to "30", "minIdleTimeoutMinutes" to "5", "maxIdleTimeoutMinutes" to "1440", "repositoryHosts" to "github.com")
        overrides.forEach { (key, value) -> base[key] = value }
        return base.map { it.key to it.value }.toTypedArray()
    }

    @Test fun theApiReturnsTheChoicesOfAClusterToAdministratorsOnly() = testApplication {
        val cluster = Cluster()
        server(Admin(), cluster)
        val response = client.get("/api/v1/cloud/clusters/lab-id/choices?namespace=sandbox") { bearerAuth("admin") }
        assertEquals(HttpStatusCode.OK, response.status)
        val choices = Serialization.json.decodeFromString<CloudClusterChoicesView>(response.bodyAsText())
        assertEquals("sandbox", choices.namespace)
        assertEquals(listOf(CloudNamespaceChoiceView("teamcode", "found"), CloudNamespaceChoiceView("sandbox", "found"), CloudNamespaceChoiceView("gone", "missing")), choices.namespaces)
        assertEquals(CloudStorageClassChoicesView("listed", listOf(CloudStorageClassChoiceView("standard", "Delete", true, false, "rancher.io/local-path"),
            CloudStorageClassChoiceView("keep", "Retain", false, true, "csi.example"))), choices.storageClasses)
        assertEquals(CloudImagePullSecretChoicesView("listed", listOf("mirror", "registry")), choices.imagePullSecrets)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/clusters/lab-id/choices") { bearerAuth("colleague") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cloud/clusters/other/choices") { bearerAuth("admin") }.status)
        cluster.secretsForbidden = true
        assertEquals(CloudImagePullSecretChoicesView("forbidden"), Serialization.json.decodeFromString<CloudClusterChoicesView>(
            client.get("/api/v1/cloud/clusters/lab-id/choices") { bearerAuth("admin") }.bodyAsText()).imagePullSecrets, "A missing right is not an error")
    }

    @Test fun theFormOffersWhatTheClusterReturns() = testApplication {
        server(Admin(), Cluster())
        val cloud = page("/dashboard/cloud").bodyAsText()
        assertContains(cloud, "href=\"/dashboard/cloud/profiles/new\"")
        val response = page("/dashboard/cloud/profiles/new")
        assertEquals(HttpStatusCode.OK, response.status)
        assertContains(response.headers["Content-Security-Policy"]!!, "script-src 'self'")
        val form = response.bodyAsText()
        assertContains(form, """<option value="lab-id" selected>lab</option>""")
        assertContains(form, """<option value="prod-id">prod</option>""")
        assertContains(form, """<option value="teamcode" selected>teamcode</option>""")
        assertContains(form, """<option value="gone" disabled>gone (not found on the cluster)</option>""")
        assertContains(form, "standard (default) · reclaimPolicy Delete")
        assertContains(form, "Kubernetes default: standard")
        assertContains(form, """data-storage-warning="keep" hidden""", message = "The Retain warning shows only when that class is chosen")
        assertContains(form, "TeamCode never deletes a PersistentVolume")
        assertContains(form, """name="imagePullSecret" value="mirror"""")
        assertContains(form, """name="imagePullSecret" value="registry"""")
        assertContains(form, """name="member" value="colleague-id"""")
        assertFalse("former-id" in form, "Disabled members are not offered")
        assertContains(form, "Publish version 1")
        assertTrue(form.indexOf("""name="intent" value="check"""") < form.indexOf("""name="intent" value="reload""""), "Enter in a field checks the draft, it does not reload")
        assertEquals(HttpStatusCode.OK, page("/dashboard/assets/profile-editor.js").status)
        assertEquals(HttpStatusCode.Forbidden, page("/dashboard/assets/profile-editor.js", "colleague").status)
        assertEquals(HttpStatusCode.Forbidden, page("/dashboard/cloud/profiles/new", "colleague").status)
    }

    @Test fun withoutTheRightOnSecretsTheFormAsksForTheirNames() = testApplication {
        val store = Admin()
        server(store, Cluster().apply { secretsForbidden = true })
        val form = page("/dashboard/cloud/profiles/new")
        assertEquals(HttpStatusCode.OK, form.status)
        val body = form.bodyAsText()
        assertFalse("name=\"imagePullSecret\"" in body)
        assertContains(body, """<label>Image pull secrets (comma-separated) <input name="imagePullSecretsOther"""")
        assertContains(body, "may not list secrets")
        assertEquals(HttpStatusCode.Found, draft(*valid("intent" to "publish", "imagePullSecretsOther" to "registry, mirror")).status)
        assertEquals(listOf("registry", "mirror"), store.saves.single().third.imagePullSecrets)
    }

    @Test fun changingTheClusterReloadsItsChoicesAndKeepsTheDraft() = testApplication {
        val store = Admin(); val cluster = Cluster()
        server(store, cluster)
        // The namespace of the previous cluster is not allowed on the new one: the new cluster's first namespace is read.
        val reloaded = draft(*valid("intent" to "reload", "clusterId" to "prod-id", "namespace" to "teamcode", "image" to "registry.example/kept:2",
            "choicesCluster" to "lab-id", "storageClass" to "keep", "imagePullSecret" to "registry"))
        assertEquals(HttpStatusCode.OK, reloaded.status)
        val form = reloaded.bodyAsText()
        assertContains(form, """<option value="prod-id" selected>prod</option>""")
        assertContains(form, """<option value="agents" selected>agents</option>""")
        assertContains(form, "Cluster default: fast")
        assertContains(form, """name="imagePullSecret" value="prod-registry"""")
        assertContains(form, """value="registry.example/kept:2"""")
        assertFalse("keep" in form || "value=\"registry\"" in form, "The previous cluster's storage class and secrets are not carried over")
        assertContains(form, """<input type="hidden" name="choicesCluster" value="prod-id">""")
        assertEquals("prod-id" to "agents", cluster.secretReads.last())
        assertTrue(store.saves.isEmpty())
        // Changing the namespace reads that namespace's secrets.
        draft(*valid("intent" to "reload", "namespace" to "sandbox"))
        assertEquals("lab-id" to "sandbox", cluster.secretReads.last())
    }

    @Test fun aDraftIsCheckedThenPublishedAsANewVersion() = testApplication {
        val store = Admin()
        server(store, Cluster())
        val refused = draft(*valid("intent" to "check", "storageClass" to "keep", "image" to "registry.example/typed:3"))
        assertEquals(HttpStatusCode.UnprocessableEntity, refused.status)
        val refusedForm = refused.bodyAsText()
        assertContains(refusedForm, "reclaimPolicy Retain")
        assertContains(refusedForm, """value="registry.example/typed:3"""", message = "A refused draft keeps what was typed")
        assertContains(refusedForm, """data-storage-warning="keep"><strong>""", message = "The warning shows for the chosen class")
        val checked = draft(*valid("intent" to "check"))
        assertEquals(HttpStatusCode.OK, checked.status)
        assertContains(checked.bodyAsText(), "The draft passes the profile checks (cluster, namespace, storage class, bounds, recipe). Publishing creates version 1.")
        assertTrue(store.saves.isEmpty(), "Checking never publishes")
        assertEquals(HttpStatusCode.Forbidden, draft(*valid("intent" to "publish"), csrf = "wrong").status)

        val published = draft(*valid("intent" to "publish", "imagePullSecret" to "registry", "imagePullSecretsOther" to "extra", "member" to "colleague-id",
            "allowedImageOverrides" to "registry.example/toolchain:2\r\nregistry.example/toolchain:3\r\n", "agentKind" to "codex", "agentVersion" to "1.2.3",
            "requiredVariables" to "NPM_TOKEN, API_KEY", "requireGitHub" to "on", "gitEmailDomain" to "example.com", "workingDirectory" to "app",
            "repositoryHosts" to "github.com, gitlab.example.com"),
            "member" to "departed-id",
            "repositoryUrl" to "https://github.com/org/app.git", "repositoryPath" to "app", "repositoryRef" to "main", "repositoryBranch" to "",
            "repositoryUrl" to "", "repositoryPath" to "", "repositoryRef" to "", "repositoryBranch" to "",
            "repositoryUrl" to "https://gitlab.example.com/org/lib.git", "repositoryPath" to "lib", "repositoryRef" to "", "repositoryBranch" to "task-1",
            "setupCreate" to "npm ci\r\nnpm run build", "setupCreate" to "", "setupStart" to "npm start")
        assertEquals(HttpStatusCode.Found, published.status)
        assertEquals("/dashboard/cloud?published=1#profiles", published.headers[HttpHeaders.Location])
        val (id, expected, spec) = store.saves.single()
        assertNull(id); assertEquals(0, expected)
        assertEquals(CloudProfileSpec("lab-id", "teamcode", "registry.example/toolchain:1", listOf("registry.example/toolchain:2", "registry.example/toolchain:3"),
            listOf("registry", "extra"), CloudResources(2000, 4096), 20, null, "codex", "1.2.3", 30, 5, 1440,
            CloudEnvironmentSpec(listOf(CloudRepository("https://github.com/org/app.git", "app", "main"), CloudRepository("https://gitlab.example.com/org/lib.git", "lib", null, "task-1")),
                "app", CloudSetup(listOf("npm ci\nnpm run build"), listOf("npm start")), listOf("NPM_TOKEN", "API_KEY"), true, "example.com", listOf("github.com", "gitlab.example.com"))), spec)
        assertEquals(setOf("colleague-id", "departed-id"), store.profiles.single().assignedMemberIds)
        assertContains(page("/dashboard/cloud?published=1").bodyAsText(), "Version 1 published.")

        // Editing starts a draft of version 2 from the published version, prefilled.
        val edit = page("/dashboard/cloud/profiles/profile-id").bodyAsText()
        assertContains(edit, "A draft of version 2, started from version 1")
        assertContains(edit, """name="version" value="1"""")
        assertContains(edit, """<option value="codex" selected>Codex</option>""")
        assertContains(edit, """name="imagePullSecret" value="extra" checked""")
        assertContains(edit, "not found in teamcode")
        assertContains(edit, "npm ci\nnpm run build</textarea>")
        assertContains(edit, """name="member" value="colleague-id" checked""")
        assertContains(edit, """<input type="hidden" name="member" value="departed-id">""", message = "An assignment the list cannot show is kept")
        assertContains(edit, "Publish version 2")
        assertEquals(HttpStatusCode.Found, draft(*valid("intent" to "publish", "id" to "profile-id", "version" to "1", "workspaceSizeGibibytes" to "50")).status)
        assertEquals(2, store.profiles.single().version)
        // A draft started from a version that is no longer current is refused, and shown again with the reason.
        val stale = draft(*valid("intent" to "publish", "id" to "profile-id", "version" to "1"))
        assertEquals(HttpStatusCode.UnprocessableEntity, stale.status)
        assertEquals(2, store.saves.size)
        assertEquals(HttpStatusCode.NotFound, page("/dashboard/cloud/profiles/unknown").status)
    }

    private companion object {
        inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            if (method.name == "toString") "unused" else error("Not reached by these tests: ${method.name}")
        } as T
    }
}
