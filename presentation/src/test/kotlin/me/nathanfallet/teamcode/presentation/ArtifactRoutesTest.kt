package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class ArtifactRoutesTest {
    private val id = "00000000-0000-4000-8000-0000000000aa"
    private val upload = "00000000-0000-4000-8000-0000000000bb"
    private val page = "<html>report</html>".encodeToByteArray()
    private val pageSha = ArtifactRules.sha256(page)
    private val leakContent = "<script>'ghp_abcdefghijklmnopqrstuvwxyz0123456789'</script>".encodeToByteArray()
    private val leakSha = ArtifactRules.sha256(leakContent)

    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "writer-key" -> interactive.authenticate("member")!!.copy(serviceScopes = setOf(ServiceScope.ARTIFACTS_WRITE))
                "reader-key" -> interactive.authenticate("member")!!.copy(serviceScopes = setOf(ServiceScope.ARTIFACTS_READ))
                "cloud-key" -> interactive.authenticate("admin")!!.copy(serviceScopes = setOf(ServiceScope.CLOUD_WRITE))
                else -> interactive.authenticate(token)
            }
        }
    }

    private fun artifact(relation: String = "owner") = Artifact(id, "default", "admin-id", "admin", "Report", "", "chart", ArtifactAccess.PERSONAL, true, null, 2, "active", 19,
        ArtifactOrigin(), 1, 2, 2, null, relation)
    private fun version(number: Int) = ArtifactVersion(id, number, "index.html", "d".repeat(64), 1, page.size.toLong(), "admin-id", 2, number - 1, null, null,
        listOf(ArtifactFile("index.html", pageSha, page.size.toLong(), "text/html; charset=utf-8", "page")))

    /** Records what reached persistence; answers like a store with one artifact at version 2. */
    private inner class Store : ArtifactStore {
        val calls = mutableListOf<String>()
        var inactive: String? = null
        private fun call(name: String) { inactive?.let { throw TeamFailure(it, 409, "Inactive.") }; calls += name }
        var policy = ArtifactPolicy(true, null)
        var origin: ArtifactOrigin? = null
        override suspend fun policy(actor: Principal): ArtifactPolicy { calls += "policy"; return policy }
        override suspend fun startUpload(actor: Principal, artifactId: String?, files: List<UploadFile>, origin: ArtifactOrigin): ArtifactUpload {
            call("startUpload"); this.origin = origin; return ArtifactUpload(upload, id, files.map { it.sha256 }, 9)
        }
        override suspend fun putChunk(actor: Principal, uploadId: String, sha256: String, number: Int, bytes: ByteArray): ChunkReceipt { call("putChunk"); return ChunkReceipt(true, 1, 1) }
        override suspend fun assemble(actor: Principal, uploadId: String, sha256: String) = (if (sha256 == pageSha) page else leakContent).let { UploadBlob("default", id, sha256, it.size.toLong()) to it }
        override suspend fun recordBlob(actor: Principal, uploadId: String, sha256: String) { calls += "recordBlob" }
        override suspend fun refuseBlob(actor: Principal, uploadId: String, sha256: String, code: String) { calls += "refuse:$code" }
        override suspend fun abandonBlob(blob: UploadBlob, uploadId: String) { calls += "abandon" }
        override suspend fun commit(actor: Principal, artifactId: String, plan: CommitPlan): Published {
            call("commit")
            if (plan.baseVersion != 2) throw ArtifactConflictFailure(version(2))
            return Published(artifact(), version(3), replayed = false)
        }
        override suspend fun artifact(actor: Principal, artifactId: String) = artifact().also { call("artifact") }
        override suspend fun list(actor: Principal, query: ArtifactQuery) = ArtifactPage(listOf(artifact()), null).also { call("list:${query.scope.wire}") }
        override suspend fun versions(actor: Principal, artifactId: String) = listOf(version(2), version(1)).also { call("versions") }
        override suspend fun availability(teamId: String) = ArtifactAvailability(inactive == null, inactive)
        override suspend fun publicArtifact(artifactId: String): PublicArtifact? = null
        override suspend fun viewable(artifactId: String, number: Int, memberId: String?, tokenId: String?): ViewableVersion? = null
        override suspend fun version(actor: Principal, artifactId: String, number: Int) = version(number).also { call("version") }
        override suspend fun setAccess(actor: Principal, artifactId: String, access: ArtifactAccess, followLatest: Boolean, pinnedVersion: Int?) = artifact().copy(access = access).also { call("access") }
        override suspend fun members(actor: Principal, artifactId: String) = ArtifactSharing("admin-id", emptyList()).also { call("members") }
        override suspend fun share(actor: Principal, artifactId: String, member: String, role: ArtifactMemberRole) =
            ArtifactSharing("admin-id", listOf(ArtifactMember("m", member, role, "invited", "admin-id", 3, null))).also { call("share") }
        override suspend fun unshare(actor: Principal, artifactId: String, member: String) = ArtifactSharing("admin-id", emptyList()).also { call("unshare") }
        override suspend fun delete(actor: Principal, artifactId: String) { calls += "delete" }
        override suspend fun expireDrafts(limit: Int) = 0
        override suspend fun expireRetention(limit: Int) = 0
        override suspend fun expireUploads(limit: Int) = 0
        override suspend fun collectableBlobs(limit: Int) = emptyList<CollectedBlob>()
        override suspend fun forgetBlobs(blobs: List<CollectedBlob>) = Unit
        override suspend fun deferBlobs(blobs: List<CollectedBlob>) = Unit
        override suspend fun deferDeletion(target: StorageTarget) = Unit
        override suspend fun pendingDeletions(limit: Int) = emptyList<StorageTarget>()
        override suspend fun markStorageDeleted(target: StorageTarget) = Unit
    }

    private class Admin : ArtifactAdminStore by stub() {
        override suspend fun connection(teamId: String) = ArtifactStorageConnection("https://s3.example.com", "us-east-1", "artifacts", "", true, "AKIAEXAMPLE", "synthetic-secret", ServerSideEncryption.NONE, 1)
    }
    private class Bucket : ArtifactObjectStore by stub() {
        val keys = mutableMapOf<String, ByteArray>()
        override suspend fun put(connection: ArtifactStorageConnection, key: String, bytes: ByteArray, contentType: String) { keys[key] = bytes }
        override suspend fun get(connection: ArtifactStorageConnection, key: String, range: LongRange?, maxBytes: Int) = keys[key]?.let { StoredObject(it, it.size.toLong(), null, null) }
    }

    @Test fun keysPublishWithTheirScopesButNeverChangeAccessOrDelete() = testApplication {
        val store = Store(); val bucket = Bucket()
        application { configureGateway(gateway, team = team(), publicOrigin = "https://teamcode.example.org/", artifacts = ArtifactService(store, Admin(), bucket)) }
        suspend fun call(method: HttpMethod, path: String, token: String, json: String? = null, raw: ByteArray? = null) = client.request(path) {
            this.method = method; bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
            if (raw != null) { contentType(ContentType.Application.OctetStream); setBody(raw) }
        }
        val announce = """{"files":[{"sha256":"$pageSha","size":${page.size}}]}"""
        val created = call(HttpMethod.Post, "/api/v1/artifacts/uploads", "writer-key", announce)
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals(ArtifactUploadView(upload, id, listOf(pageSha), ArtifactRules.CHUNK_BYTES, 9), Serialization.json.decodeFromString<ArtifactUploadView>(created.bodyAsText()))
        val chunk = call(HttpMethod.Put, "/api/v1/artifacts/uploads/$upload/blobs/$pageSha/0", "writer-key", raw = page)
        assertEquals(HttpStatusCode.OK, chunk.status); assertTrue(Serialization.json.decodeFromString<ArtifactChunkView>(chunk.bodyAsText()).complete)
        assertEquals(page.toList(), bucket.keys.getValue("default/artifacts/$id/blobs/$pageSha").toList(), "The checked blob reached the artifact's folder")

        val commit = """{"uploadId":"$upload","entry":"index.html","files":{"index.html":{"sha256":"$pageSha","size":${page.size}}},"baseVersion":2}"""
        val published = call(HttpMethod.Post, "/api/v1/artifacts/$id/versions", "writer-key", commit)
        assertEquals(HttpStatusCode.Created, published.status)
        val view = Serialization.json.decodeFromString<ArtifactPublishedView>(published.bodyAsText())
        assertEquals("https://teamcode.example.org/artifact/$id", view.artifact.url); assertEquals(3, view.version.number)
        assertTrue(bucket.keys.containsKey("default/artifacts/$id/versions/3.json"), "A copy of the manifest sits next to the blobs")

        // A stale base answers 409 with the live version for the publisher to merge.
        val stale = call(HttpMethod.Post, "/api/v1/artifacts/$id/versions", "writer-key", commit.replace("\"baseVersion\":2", "\"baseVersion\":1"))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        val conflict = Serialization.json.decodeFromString<ArtifactConflictView>(stale.bodyAsText())
        assertEquals("artifact_conflict", conflict.error.type); assertEquals(2, conflict.current?.number); assertEquals(listOf("index.html"), conflict.current?.files?.map { it.path })

        // A credential in a text file: 422 with the blob and the kind, never the value; nothing stored.
        val leak = call(HttpMethod.Put, "/api/v1/artifacts/uploads/$upload/blobs/$leakSha/0", "writer-key", raw = leakContent)
        assertEquals(HttpStatusCode.UnprocessableEntity, leak.status)
        val secret = Serialization.json.decodeFromString<ArtifactSecretView>(leak.bodyAsText())
        assertEquals(Triple("artifact_secret_detected", leakSha, "github_token"), Triple(secret.error.type, secret.sha256, secret.kind))
        assertFalse("ghp_" in leak.bodyAsText()); assertTrue("refuse:artifact_secret_detected" in store.calls)

        // Reads with artifacts:read; writes refused to it; access and deletion refused to every key, cloud keys reach nothing.
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/artifacts?scope=mine", "reader-key").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/artifacts/$id/versions/2", "reader-key").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/artifacts/uploads", "reader-key", announce).status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/artifacts/$id/members", "reader-key", """{"member":"bob"}""").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/v1/artifacts/$id/members", "writer-key", """{"member":"bob","role":"editor"}""").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/api/v1/artifacts/$id/members/bob", "writer-key").status)
        val before = store.calls.size
        for (token in listOf("writer-key", "reader-key")) {
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, "/api/v1/artifacts/$id/access", token, """{"access":"public"}""").status)
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "/api/v1/artifacts/$id", token).status)
        }
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/artifacts", "cloud-key").status)
        assertEquals(before, store.calls.size, "Refused calls never reach persistence")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/v1/artifacts/$id/access", "member", """{"access":"team"}""").status)
        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/v1/artifacts/$id", "member").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/api/v1/artifacts?scope=everything", "member").status)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/artifacts", "unknown").status)
    }

    @Test fun aKeyNeverReachesTheArtifactThroughADotMemberSegment() {
        val id = "00000000-0000-4000-8000-0000000000aa"
        assertEquals(CredentialAction.ARTIFACTS_WRITE, credentialAction("DELETE", "/api/v1/artifacts/$id/members/bob", emptySet()))
        for (member in listOf("..", ".", ".bob"))
            assertEquals(CredentialAction.INTERACTIVE, credentialAction("DELETE", "/api/v1/artifacts/$id/members/$member", emptySet()), member)
    }

    @Test fun policyAnswersInEveryAvailabilityStateAndUploadsCarryTheRun() = testApplication {
        val store = Store()
        application { configureGateway(gateway, team = team(), artifacts = ArtifactService(store, Admin(), Bucket())) }
        suspend fun policy(token: String) = client.get("/api/v1/artifacts/policy") { bearerAuth(token) }
        assertEquals(ArtifactPolicyView(true, null), Serialization.json.decodeFromString<ArtifactPolicyView>(policy("reader-key").bodyAsText()))
        // Every reason the store gives reaches the launcher, which then leaves the artifact tools out.
        for (reason in listOf("artifact_storage_missing", "artifact_storage_unverified", "artifacts_disabled", "artifact_publish_forbidden")) {
            store.policy = ArtifactPolicy(false, reason)
            val answer = policy("writer-key")
            assertEquals(HttpStatusCode.OK, answer.status)
            assertEquals(ArtifactPolicyView(false, reason), Serialization.json.decodeFromString<ArtifactPolicyView>(answer.bodyAsText()))
        }
        assertEquals(HttpStatusCode.Forbidden, policy("cloud-key").status, "A key without an artifacts scope does not read the policy")

        val run = "11111111-2222-4333-8444-555555555555"
        val announce = """{"files":[{"sha256":"$pageSha","size":${page.size}}]}"""
        suspend fun start(vararg runs: String) = client.post("/api/v1/artifacts/uploads") {
            bearerAuth("writer-key"); runs.forEach { header("x-teamcode-run-id", it) }; contentType(ContentType.Application.Json); setBody(announce)
        }
        assertEquals(HttpStatusCode.Created, start(run).status); assertEquals(ArtifactOrigin("local_run", run), store.origin)
        assertEquals(HttpStatusCode.Created, start().status); assertEquals(ArtifactOrigin(), store.origin)
        store.origin = null
        assertEquals(HttpStatusCode.BadRequest, start("not-a-run").status)
        assertEquals(HttpStatusCode.BadRequest, start(run, run).status)
        assertNull(store.origin, "An invalid run never reaches the store")
    }

    @Test fun filesDownloadAsAttachmentsAndInactiveArtifactsSayWhy() = testApplication {
        val store = Store(); val bucket = Bucket()
        bucket.keys["default/artifacts/$id/blobs/$pageSha"] = page
        application { configureGateway(gateway, team = team(), artifacts = ArtifactService(store, Admin(), bucket)) }
        val file = client.get("/api/v1/artifacts/$id/versions/2/file?path=index.html") { bearerAuth("member") }
        assertEquals(HttpStatusCode.OK, file.status)
        assertEquals(ContentType.Application.OctetStream, file.contentType()?.withoutParameters(), "A page is never served as HTML on the server's origin")
        assertEquals("attachment", file.headers[HttpHeaders.ContentDisposition]); assertEquals(pageSha, file.headers["X-TeamCode-Sha256"])
        assertEquals(page.toList(), file.readRawBytes().toList())
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/artifacts/$id/versions/2/file?path=other.css") { bearerAuth("member") }.status)

        store.inactive = "artifact_storage_unverified"
        val refused = client.get("/api/v1/artifacts") { bearerAuth("member") }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertEquals("artifact_storage_unverified", Serialization.json.decodeFromString<ApiError>(refused.bodyAsText()).error.type)
    }
}

/** An implementation that fails on any call: the delegating fakes above override only what the routes use. */
private inline fun <reified T : Any> stub(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
    if (method.name == "toString") "stub" else error("Unexpected call to ${method.name}.")
} as T
