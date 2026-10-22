package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

/** Publication, sharing, deletion and maintenance of artifacts on real MySQL, with two pools standing for two replicas. */
class ArtifactPublicationPersistenceTest {
    private val cipher = CredentialCipher(ByteArray(32) { it.toByte() })

    /** The bucket, in memory: what the service and the maintenance put, read, list and delete. */
    private class Bucket : ArtifactObjectStore {
        val objects = ConcurrentHashMap<String, ByteArray>()
        override suspend fun put(connection: ArtifactStorageConnection, key: String, bytes: ByteArray, contentType: String) { objects[key] = bytes }
        override suspend fun get(connection: ArtifactStorageConnection, key: String, range: LongRange?, maxBytes: Int) = objects[key]?.let { StoredObject(it, it.size.toLong(), null, null) }
        override suspend fun head(connection: ArtifactStorageConnection, key: String) = objects[key]?.let { ObjectHead(it.size.toLong(), null) }
        override suspend fun list(connection: ArtifactStorageConnection, prefix: String, continuationToken: String?, maxKeys: Int): ObjectListing {
            val keys = objects.keys.filter { it.startsWith(prefix) }.sorted()
            val start = continuationToken?.toInt() ?: 0
            val page = keys.drop(start).take(maxKeys)
            return ObjectListing(page.map { ListedObject(it, objects[it]?.size?.toLong() ?: 0) }, if (start + page.size < keys.size) (start + page.size).toString() else null)
        }
        override suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String> { keys.forEach(objects::remove); return emptyList() }
        fun under(artifact: String) = objects.keys.filter { "/artifacts/$artifact/" in it }
    }

    private class Replica(database: Database, cipher: CredentialCipher, bucket: Bucket) {
        val admin = SqlArtifactAdminStore(database, cipher)
        val store = SqlArtifactStore(database)
        val service = ArtifactService(store, admin, bucket)
        val maintenance = ArtifactMaintenance(store, admin, bucket)
    }

    private fun bytes(text: String) = text.encodeToByteArray()
    private fun ref(content: ByteArray) = UploadFile(ArtifactRules.sha256(content), content.size.toLong())

    /** Announces, sends every missing blob in chunks, and commits, as a client does. */
    private suspend fun publish(service: ArtifactService, actor: Principal, artifactId: String?, files: Map<String, ByteArray>, base: Int, entry: String = "index.html",
        title: String? = null, access: String? = null): Published {
        val upload = service.startUpload(actor, artifactId, files.values.map(::ref).distinctBy { it.sha256 })
        send(service, actor, upload, files.values)
        return service.commit(actor, upload.artifactId, upload.uploadId, entry, files.mapValues { ref(it.value) }, base, title, null, null, access, null)
    }

    private suspend fun send(service: ArtifactService, actor: Principal, upload: ArtifactUpload, contents: Collection<ByteArray>) {
        for (sha in upload.missing) {
            val content = contents.first { ArtifactRules.sha256(it) == sha }
            val chunks = ArtifactRules.chunks(content.size.toLong())
            for (n in 0 until chunks) {
                val receipt = service.putChunk(actor, upload.uploadId, sha, n, content.copyOfRange(n * ArtifactRules.CHUNK_BYTES, minOf(content.size, (n + 1) * ArtifactRules.CHUNK_BYTES)))
                assertEquals(n == chunks - 1, receipt.complete)
            }
        }
    }

    @Test @Timeout(240)
    fun versionsAreCommittedOnceWithOptimisticConcurrencyAndOnlyTrustedBlobs() = runBlocking {
        fixture { f ->
            val bucket = Bucket()
            val a = Replica(f.a, cipher, bucket); val b = Replica(f.b, cipher, bucket)
            val owner = f.member.principal

            // Inactive: every member call says why; nothing is reserved.
            assertEquals("artifact_storage_missing", assertFailsWith<TeamFailure> { a.service.startUpload(owner, null, listOf(ref(bytes("x")))) }.code)
            activate(a, f)
            a.admin.saveSettings(f.admin.principal, a.admin.settings(f.admin.principal).let { it.copy(enabled = false) })
            assertEquals("artifacts_disabled", assertFailsWith<TeamFailure> { b.service.list(owner, ArtifactQuery()) }.code)
            a.admin.saveSettings(f.admin.principal, a.admin.settings(f.admin.principal).copy(enabled = true))

            // A new artifact: a draft id reserved by the upload, a multi-chunk file, then version 1.
            val large = ByteArray(ArtifactRules.CHUNK_BYTES * 2 + 17) { (it % 251).toByte() }
            val page = bytes("<html><title>Report</title><img src=img/a.bin></html>")
            val v1 = publish(a.service, owner, null, mapOf("index.html" to page, "img/a.bin" to large, "copy.bin" to large), 0, title = "Report")
            val id = v1.artifact.id
            assertEquals(1, v1.version.number); assertEquals(3, v1.version.fileCount); assertEquals("active", v1.artifact.state); assertEquals("owner", v1.artifact.relation)
            assertEquals((page.size + large.size).toLong(), v1.artifact.storedBytes, "Two paths with the same content are stored and counted once")
            assertEquals(setOf("blobs/${ArtifactRules.sha256(page)}", "blobs/${ArtifactRules.sha256(large)}", "versions/1.json"), bucket.under(id).map { it.substringAfter("/artifacts/$id/") }.toSet())
            assertEquals(0, f.a.query { ArtifactUploadChunks.selectAll().count() }, "Chunks leave MySQL once the blob is stored")

            // A republication sends only what changed; the other files are reused.
            val page2 = bytes("<html><title>Report</title><p>v2</p></html>")
            val upload = b.service.startUpload(owner, id, listOf(ref(page2), ref(large)))
            assertEquals(listOf(ArtifactRules.sha256(page2)), upload.missing)
            send(b.service, owner, upload, listOf(page2))
            val commit = suspend { b.service.commit(owner, id, upload.uploadId, "index.html", mapOf("index.html" to ref(page2), "img/a.bin" to ref(large)), 1, null, null, null, null, null) }
            val v2 = commit()
            assertEquals(2, v2.version.number); assertFalse(v2.replayed)
            // The same commit replayed (MCP has no idempotency key) is the same version, even from the other replica.
            val replay = a.service.commit(owner, id, upload.uploadId, "index.html", mapOf("index.html" to ref(page2), "img/a.bin" to ref(large)), 1, null, null, null, null, null)
            assertTrue(replay.replayed); assertEquals(2, replay.version.number)

            // A stale base is refused with the live manifest.
            val stale = a.service.startUpload(owner, id, listOf(ref(page)))
            val conflict = assertFailsWith<ArtifactConflictFailure> { a.service.commit(owner, id, stale.uploadId, "index.html", mapOf("index.html" to ref(page)), 1, null, null, null, null, null) }
            assertEquals(2, conflict.current?.number); assertEquals(listOf("img/a.bin", "index.html"), conflict.current?.files?.map { it.path })

            // Two replicas commit on the same base at once: exactly one wins, the other gets the conflict.
            val left = bytes("<html>left</html>"); val right = bytes("<html>right</html>")
            val ups = listOf(a to left, b to right).map { (replica, content) -> replica to replica.service.startUpload(owner, id, listOf(ref(content))).also { send(replica.service, owner, it, listOf(content)) } to content }
            val outcomes = coroutineScope { ups.map { (pair, content) -> val (replica, up) = pair; async(Dispatchers.IO) {
                runCatching { replica.service.commit(owner, id, up.uploadId, "index.html", mapOf("index.html" to ref(content)), 2, null, null, null, null, null) }
            } }.awaitAll() }
            assertEquals(1, outcomes.count { it.isSuccess }); assertTrue(outcomes.single { it.isFailure }.exceptionOrNull() is ArtifactConflictFailure)
            assertEquals(3, a.service.artifact(owner, id).currentVersion)

            // Only blobs received in this upload by this credential, or already in the artifact, are trusted.
            val unsent = bytes("never sent")
            val partial = a.service.startUpload(owner, id, listOf(ref(unsent)))
            assertEquals("artifact_blob_missing", assertFailsWith<TeamFailure> { a.service.commit(owner, id, partial.uploadId, "index.html", mapOf("index.html" to ref(unsent)), 3, null, null, null, null, null) }.code)
            val otherDevice = f.team.login("developer", "artifact member replacement password", "second-device").principal
            send(a.service, owner, partial, listOf(unsent))
            assertEquals("artifact_upload_not_found", assertFailsWith<TeamFailure> { a.service.commit(otherDevice, id, partial.uploadId, "index.html", mapOf("index.html" to ref(unsent)), 3, null, null, null, null, null) }.code)
            assertEquals("artifact_upload_not_found", assertFailsWith<TeamFailure> { a.service.putChunk(otherDevice, partial.uploadId, ArtifactRules.sha256(unsent), 0, unsent) }.code)

            // A chunk of the wrong size, an unannounced file and a file whose bytes do not match are refused.
            val check = a.service.startUpload(owner, id, listOf(UploadFile(ArtifactRules.sha256(bytes("abc")), 4)))
            assertEquals("invalid_artifact_chunk", assertFailsWith<TeamFailure> { a.service.putChunk(owner, check.uploadId, ArtifactRules.sha256(bytes("abc")), 0, bytes("abc")) }.code)
            assertEquals("invalid_artifact_upload", assertFailsWith<TeamFailure> { a.service.putChunk(owner, check.uploadId, ArtifactRules.sha256(bytes("zzz")), 0, bytes("zzz")) }.code)
            assertEquals("artifact_blob_mismatch", assertFailsWith<TeamFailure> { a.service.putChunk(owner, check.uploadId, ArtifactRules.sha256(bytes("abc")), 0, bytes("abcd")) }.code)

            // A credential in a text file refuses it before anything reaches the bucket, and the refusal is audited by code only.
            val leak = bytes("<script>const k = 'ghp_abcdefghijklmnopqrstuvwxyz0123456789'</script>")
            val leaking = a.service.startUpload(owner, id, listOf(ref(leak)))
            val secret = assertFailsWith<ArtifactSecretFailure> { a.service.putChunk(owner, leaking.uploadId, ArtifactRules.sha256(leak), 0, leak) }
            assertEquals("github_token", secret.kind); assertEquals(ArtifactRules.sha256(leak), secret.sha256)
            assertFalse(bucket.objects.keys.any { it.endsWith(ArtifactRules.sha256(leak)) })
            val refusals = f.a.query { AuditEvents.selectAll().where { AuditEvents.action eq "artifact.publish_refused" }.map { it[AuditEvents.subjectId] } }
            assertEquals(setOf("$id:artifact_secret_detected", "$id:artifact_blob_mismatch"), refusals.toSet())
            // The refusal ended that upload at once; a mismatch did not end its own.
            assertEquals("artifact_upload_not_found", assertFailsWith<TeamFailure> { a.service.putChunk(owner, leaking.uploadId, ArtifactRules.sha256(leak), 0, leak) }.code)
            assertEquals("artifact_blob_mismatch", assertFailsWith<TeamFailure> { a.service.putChunk(owner, check.uploadId, ArtifactRules.sha256(bytes("abc")), 0, bytes("abc").copyOf(4)) }.code)
            // A new artifact refused for a secret leaves no draft behind: retries never use up the drafts a member may hold.
            val fresh = a.service.startUpload(owner, null, listOf(ref(leak)))
            assertFailsWith<ArtifactSecretFailure> { b.service.putChunk(owner, fresh.uploadId, ArtifactRules.sha256(leak), 0, leak) }
            assertEquals("deleted", f.a.query { Artifacts.selectAll().where { Artifacts.id eq fresh.artifactId }.single()[Artifacts.state] })

            // The last two chunks of a file sent at once from two replicas: exactly one of them completes and stores it.
            val split = ByteArray(ArtifactRules.CHUNK_BYTES + 5) { (it % 7).toByte() }
            val parallel = a.service.startUpload(owner, id, listOf(ref(split)))
            val receipts = coroutineScope { listOf(a to 0, b to 1).map { (replica, n) -> async(Dispatchers.IO) {
                replica.service.putChunk(owner, parallel.uploadId, ArtifactRules.sha256(split), n, split.copyOfRange(n * ArtifactRules.CHUNK_BYTES, minOf(split.size, (n + 1) * ArtifactRules.CHUNK_BYTES)))
            } }.awaitAll() }
            assertEquals(1, receipts.count { it.complete }, "The chunk counted last sees the other one")
            assertTrue(bucket.objects.keys.any { it.endsWith(ArtifactRules.sha256(split)) })

            // A blob put in the bucket after its upload vanished is marked for collection, never left uncounted.
            val orphan = bytes("orphaned after the put")
            a.store.abandonBlob(UploadBlob(f.member.principal.teamId, id, ArtifactRules.sha256(orphan), orphan.size.toLong()), "00000000-0000-4000-8000-0000000000ff")
            assertNotNull(f.a.query { ArtifactBlobs.selectAll().where { (ArtifactBlobs.artifactId eq id) and (ArtifactBlobs.sha256 eq ArtifactRules.sha256(orphan)) }.single()[ArtifactBlobs.collectingSince] })
            // While its own upload is live (a failed or cancelled request), it is recorded for that upload: a retry finds it stored.
            val unrecorded = bytes("stored, then the request failed")
            val live = a.service.startUpload(owner, id, listOf(ref(unrecorded)))
            a.store.abandonBlob(UploadBlob(f.member.principal.teamId, id, ArtifactRules.sha256(unrecorded), unrecorded.size.toLong()), live.uploadId)
            assertTrue(a.service.putChunk(owner, live.uploadId, ArtifactRules.sha256(unrecorded), 0, unrecorded).stored)

            val actions = f.a.query { AuditEvents.selectAll().where { AuditEvents.action like "artifact.%" }.map { it[AuditEvents.action] to it[AuditEvents.subjectId] } }
            assertEquals(1, actions.count { it.first == "artifact.created" })
            assertEquals(listOf("$id/1", "$id/2", "$id/3"), actions.filter { it.first == "artifact.version_published" }.map { it.second })
        }
    }

    @Test @Timeout(240)
    fun accessSharingQuotasAndTheHourlyRateAreEnforcedUnderLocks() = runBlocking {
        fixture { f ->
            val bucket = Bucket()
            val a = Replica(f.a, cipher, bucket); val b = Replica(f.b, cipher, bucket)
            activate(a, f)
            val owner = f.member.principal; val admin = f.admin.principal
            val third = f.team.createMember(admin, "reviewer", Role.MEMBER)
            val pending = f.team.login("reviewer", third.temporaryPassword, "artifact-test")
            f.team.changePassword(pending.principal, third.temporaryPassword, "artifact reviewer replacement password")
            val reviewer = f.team.login("reviewer", "artifact reviewer replacement password", "artifact-test").principal

            val id = publish(a.service, owner, null, mapOf("index.html" to bytes("<p>private</p>")), 0).artifact.id
            // A personal artifact does not exist for another member; an administrator reads it, audited.
            assertEquals("not_found", assertFailsWith<TeamFailure> { b.service.artifact(reviewer, id) }.code)
            assertTrue(b.service.list(reviewer, ArtifactQuery()).artifacts.isEmpty())
            assertEquals("admin", b.service.artifact(admin, id).relation)
            assertTrue(b.service.list(admin, ArtifactQuery()).artifacts.isEmpty(), "The team scope is what the administrator can read as a member")
            assertEquals(listOf(id), b.service.list(admin, ArtifactQuery(ArtifactScope.ALL)).artifacts.map { it.id })
            b.service.version(admin, id, 1)
            assertEquals(1, f.a.query { AuditEvents.selectAll().where { AuditEvents.action eq "artifact.admin_viewed" }.count() })

            // Sharing: by username; a viewer reads, an editor also publishes and invites; neither changes access or deletes.
            a.service.share(owner, id, "reviewer", "viewer")
            assertEquals("viewer", b.service.artifact(reviewer, id).relation)
            assertEquals(listOf(id), b.service.list(reviewer, ArtifactQuery(ArtifactScope.SHARED)).artifacts.map { it.id })
            assertEquals("artifact_not_writable", assertFailsWith<TeamFailure> { publish(b.service, reviewer, id, mapOf("index.html" to bytes("<p>edit</p>")), 1) }.code)
            assertEquals("artifact_not_writable", assertFailsWith<TeamFailure> { b.service.share(reviewer, id, "admin", "viewer") }.code)
            // A member whose username spells the reviewer's id never captures an invitation by id.
            val impostor = f.team.createMember(admin, reviewer.member.id, Role.MEMBER)
            f.team.changePassword(f.team.login(reviewer.member.id, impostor.temporaryPassword, "artifact-test").principal, impostor.temporaryPassword, "artifact impostor replacement password")
            val members = a.service.share(owner, id, reviewer.member.id, "editor")
            assertEquals(listOf("reviewer" to ArtifactMemberRole.EDITOR), members.members.map { it.username to it.role })
            // Nor while the member named by id is disabled: the invitation is refused instead of going to the impostor.
            val dormant = f.team.createMember(admin, "dormant", Role.MEMBER).member
            val lookalike = f.team.createMember(admin, dormant.id, Role.MEMBER)
            f.team.changePassword(f.team.login(dormant.id, lookalike.temporaryPassword, "artifact-test").principal, lookalike.temporaryPassword, "artifact lookalike replacement password")
            f.team.updateMember(admin, dormant.id, Role.MEMBER, true)
            assertEquals("artifact_member_not_found", assertFailsWith<TeamFailure> { a.service.share(owner, id, dormant.id, "viewer") }.code)
            assertEquals("artifact_member_not_found", assertFailsWith<TeamFailure> { a.service.share(owner, id, "dormant", "viewer") }.code, "Nor by a disabled member's username")
            assertEquals(2, publish(b.service, reviewer, id, mapOf("index.html" to bytes("<p>edit</p>")), 1).version.number)
            assertEquals(f.member.principal.member.id, a.service.artifact(owner, id).ownerId, "An editor's version stays the owner's artifact")
            assertEquals("artifact_access_forbidden", assertFailsWith<TeamFailure> { b.service.setAccess(reviewer, id, "team", true, null) }.code)
            assertEquals("artifact_delete_forbidden", assertFailsWith<TeamFailure> { b.service.delete(reviewer, id) }.code)
            assertEquals("invalid_artifact_member", assertFailsWith<TeamFailure> { b.service.share(reviewer, id, "developer", "viewer") }.code, "The owner is not invited")
            assertEquals("artifact_member_not_found", assertFailsWith<TeamFailure> { b.service.share(reviewer, id, "nobody", "viewer") }.code)
            b.service.unshare(owner, id, "reviewer")
            assertEquals("not_found", assertFailsWith<TeamFailure> { b.service.artifact(reviewer, id) }.code)

            // Team access: every member reads it; public follows the team's public sharing, interactively.
            assertEquals("team", a.service.setAccess(owner, id, "team", true, null).access.wire)
            assertEquals("team", b.service.artifact(reviewer, id).relation)
            assertEquals("artifact_public_forbidden", assertFailsWith<TeamFailure> { a.service.setAccess(admin, id, "public", true, null) }.code, "Owners make their artifacts public by default")
            val pinned = a.service.setAccess(owner, id, "public", false, 1)
            assertEquals(ArtifactAccess.PUBLIC, pinned.access); assertEquals(1, pinned.publicVersion); assertFalse(pinned.publicFollowsLatest)
            a.admin.saveSettings(admin, a.admin.settings(admin).copy(publicSharing = PublicSharing.OFF))
            assertEquals("artifact_public_sharing_off", assertFailsWith<TeamFailure> { a.service.setAccess(owner, id, "public", true, null) }.code)
            assertEquals("artifact_public_interactive", assertFailsWith<TeamFailure> { publish(a.service, owner, id, mapOf("index.html" to bytes("<p>3</p>")), 2, access = "team") }.code)
            val changes = f.a.query { AuditEvents.selectAll().where { AuditEvents.action eq "artifact.access_changed" }.map { it[AuditEvents.subjectId] } }
            assertEquals(listOf("$id:personal->team", "$id:team->public"), changes)

            // Quotas: the member's bytes, under the team lock, counted once per artifact.
            val settings = a.admin.settings(admin)
            a.admin.saveSettings(admin, settings.copy(quotas = settings.quotas.copy(memberBytes = 1024L * 1024, artifactsPerMember = 2)))
            val heavy = ByteArray(1024 * 1024) { 7 }
            assertEquals("artifact_quota_exceeded", assertFailsWith<TeamFailure> { publish(a.service, owner, null, mapOf("index.html" to bytes("<p>h</p>"), "a.bin" to heavy), 0) }.code)
            // The draft that failed does not count: only published artifacts do, at a new upload and at the first commit.
            val retry = a.service.startUpload(owner, null, listOf(ref(bytes("n"))))
            publish(a.service, owner, null, mapOf("index.html" to bytes("<p>second</p>")), 0)
            assertEquals("artifact_quota_exceeded", assertFailsWith<TeamFailure> { a.service.startUpload(owner, null, listOf(ref(bytes("n")))) }.code)
            send(a.service, owner, retry, listOf(bytes("n")))
            assertEquals("artifact_quota_exceeded", assertFailsWith<TeamFailure> {
                a.service.commit(owner, retry.artifactId, retry.uploadId, "index.html", mapOf("index.html" to ref(bytes("n"))), 0, null, null, null, null, null)
            }.code, "A draft started before the limit was reached is checked again when published")

            // The hourly rate counts the member's own publications (one here; the editor's version is the editor's): a limit of 1 refuses the next.
            val current = a.admin.settings(admin)
            a.admin.saveSettings(admin, current.copy(quotas = current.quotas.copy(publishesPerHour = 1, artifactsPerMember = 500)))
            val limited = assertFailsWith<TeamFailure> { a.service.startUpload(owner, id, listOf(ref(bytes("r")))) }
            assertEquals("artifact_rate_limited", limited.code); assertEquals(429, limited.status); assertTrue(limited.retryAfterSeconds!! in 1..3600)

            // whoCanPublish = admins: members cannot publish at all.
            val latest = a.admin.settings(admin)
            a.admin.saveSettings(admin, latest.copy(whoCanPublish = WhoCanPublish.ADMINS, quotas = latest.quotas.copy(publishesPerHour = 60)))
            assertEquals("artifact_publish_forbidden", assertFailsWith<TeamFailure> { a.service.startUpload(owner, id, listOf(ref(bytes("r")))) }.code)
        }
    }

    @Test @Timeout(240)
    fun deletionRetentionDraftsAndPruningRemoveTheBlobsWithProof() = runBlocking {
        fixture { f ->
            val bucket = Bucket()
            val a = Replica(f.a, cipher, bucket); val b = Replica(f.b, cipher, bucket)
            activate(a, f)
            val owner = f.member.principal; val admin = f.admin.principal
            val settings = a.admin.settings(admin)
            a.admin.saveSettings(admin, settings.copy(retention = ArtifactRetention(personalDays = 30, sharedDays = null, maxVersionsPerArtifact = 1)))

            // Pruning: with one version kept, version 1 goes and the blob only it used is collected from the bucket.
            val first = bytes("<p>one</p>"); val shared = bytes("shared asset"); val second = bytes("<p>two</p>")
            val v1 = publish(a.service, owner, null, mapOf("index.html" to first, "a.txt" to shared), 0)
            val id = v1.artifact.id
            assertNotNull(v1.artifact.expiresAt, "A personal artifact gets the personal retention")
            val v2 = publish(b.service, owner, id, mapOf("index.html" to second, "a.txt" to shared), 1)
            assertEquals(listOf(2), a.service.versions(owner, id).map { it.number })
            assertEquals((second.size + shared.size).toLong(), v2.artifact.storedBytes, "The pruned version's own blob is no longer counted")
            assertTrue(bucket.objects.keys.any { it.endsWith(ArtifactRules.sha256(first)) })
            // A file being collected cannot be sent again until its removal is done.
            assertEquals("artifact_busy", assertFailsWith<TeamFailure> { a.service.startUpload(owner, id, listOf(ref(first))) }.code)
            a.maintenance.run()
            assertFalse(bucket.objects.keys.any { it.endsWith(ArtifactRules.sha256(first)) }); assertTrue(bucket.objects.keys.any { it.endsWith(ArtifactRules.sha256(shared)) })
            assertEquals(0, f.a.query { ArtifactBlobs.selectAll().where { ArtifactBlobs.collectingSince.isNotNull() }.count() })

            // An expired upload: what it stored that nothing uses is removed.
            val orphan = bytes("uploaded, never committed")
            val upload = a.service.startUpload(owner, id, listOf(ref(orphan)))
            send(a.service, owner, upload, listOf(orphan))
            assertTrue(bucket.objects.keys.any { it.endsWith(ArtifactRules.sha256(orphan)) })
            f.a.query { ArtifactUploads.update({ ArtifactUploads.id eq upload.uploadId }) { it[expiresAt] = 1 } }
            b.maintenance.run()
            assertFalse(bucket.objects.keys.any { it.endsWith(ArtifactRules.sha256(orphan)) })
            assertEquals(0, f.a.query { ArtifactUploads.selectAll().count() })

            // A draft never committed for a day becomes a deletion; its folder is removed after the grace period.
            val draftUpload = a.service.startUpload(owner, null, listOf(ref(orphan)))
            send(a.service, owner, draftUpload, listOf(orphan))
            f.a.query { Artifacts.update({ Artifacts.id eq draftUpload.artifactId }) { it[updatedAt] = 1 } }
            a.maintenance.run()
            assertEquals("deleted", f.a.query { Artifacts.selectAll().where { Artifacts.id eq draftUpload.artifactId }.single()[Artifacts.state] })

            // Deletion: interactive, 404 at once, then the folder is deleted, listed again and proven empty.
            assertEquals("credential_scope", assertFailsWith<TeamFailure> { a.service.delete(owner.copy(serviceScopes = setOf(ServiceScope.ARTIFACTS_WRITE)), id) }.code)
            b.service.delete(owner, id)
            assertEquals("not_found", assertFailsWith<TeamFailure> { a.service.artifact(owner, id) }.code)
            assertTrue(a.admin.inUse(admin), "A deletion in progress still holds the bucket")
            a.maintenance.run()
            assertTrue(bucket.under(id).isNotEmpty(), "Nothing is removed during the grace period")
            f.a.query { Artifacts.update({ Artifacts.state eq "deleted" }) { it[updatedAt] = 1 } }
            a.maintenance.run()
            assertTrue(bucket.under(id).isEmpty()); assertTrue(bucket.under(draftUpload.artifactId).isEmpty())
            val row = f.a.query { Artifacts.selectAll().where { Artifacts.id eq id }.single() }
            assertNotNull(row[Artifacts.storageDeletedAt]); assertEquals(0, f.a.query { ArtifactVersions.selectAll().where { ArtifactVersions.artifactId eq id }.count() })
            assertFalse(a.admin.inUse(admin), "Once every folder is proven empty the bucket may change")

            // Retention: an artifact past its expiry is deleted by the system, audited.
            val kept = publish(a.service, owner, null, mapOf("index.html" to bytes("<p>kept</p>")), 0).artifact.id
            f.a.query { Artifacts.update({ Artifacts.id eq kept }) { it[expiresAt] = 1 } }
            b.maintenance.run()
            assertEquals("not_found", assertFailsWith<TeamFailure> { a.service.artifact(owner, kept) }.code)
            val audit = f.a.query { AuditEvents.selectAll().where { AuditEvents.action inList listOf("artifact.retention_expired", "artifact.storage_deleted", "artifact.deleted") }
                .map { Triple(it[AuditEvents.action], it[AuditEvents.subjectId], it[AuditEvents.actorId]) } }
            assertTrue(Triple("artifact.retention_expired", kept, "system") in audit, audit.toString())
            assertTrue(Triple("artifact.deleted", id, owner.member.id) in audit)
            assertEquals(setOf(id, draftUpload.artifactId), audit.filter { it.first == "artifact.storage_deleted" }.map { it.second }.toSet())
        }
    }

    @Test @Timeout(240)
    fun retriesUnusedBlobsAccessChangesAndFailingBucketsStayBounded() = runBlocking {
        fixture { f ->
            val bucket = Bucket()
            val a = Replica(f.a, cipher, bucket); val b = Replica(f.b, cipher, bucket)
            activate(a, f)
            val owner = f.member.principal; val admin = f.admin.principal

            // A refused file is sent again in the same upload, even when larger than a chunk.
            val large = ByteArray(ArtifactRules.CHUNK_BYTES * 2 + 5) { (it % 13).toByte() }
            val page = bytes("<p>page</p>"); val unused = bytes("sent but not in the version")
            val upload = a.service.startUpload(owner, null, listOf(ref(page), ref(large), ref(unused)))
            val sha = ArtifactRules.sha256(large)
            val wrong = large.copyOf().also { it[it.size - 1] = 99 }
            for (n in 0 until 2) a.service.putChunk(owner, upload.uploadId, sha, n, wrong.copyOfRange(n * ArtifactRules.CHUNK_BYTES, (n + 1) * ArtifactRules.CHUNK_BYTES))
            assertEquals("artifact_blob_mismatch", assertFailsWith<TeamFailure> { a.service.putChunk(owner, upload.uploadId, sha, 2, wrong.copyOfRange(2 * ArtifactRules.CHUNK_BYTES, wrong.size)) }.code)
            send(b.service, owner, upload, listOf(page, large, unused))

            // A blob the upload stored but the version does not use is marked and collected, never left uncounted.
            val published = b.service.commit(owner, upload.artifactId, upload.uploadId, "index.html", mapOf("index.html" to ref(page), "big.bin" to ref(large)), 0, null, null, null, null, null)
            assertEquals((page.size + large.size).toLong(), published.artifact.storedBytes)
            assertTrue(bucket.objects.keys.any { it.endsWith(ArtifactRules.sha256(unused)) })
            a.maintenance.run()
            assertFalse(bucket.objects.keys.any { it.endsWith(ArtifactRules.sha256(unused)) })
            val id = published.artifact.id

            // An access change counts the retention from the change, never expiring the artifact at once.
            val settings = a.admin.settings(admin)
            a.admin.saveSettings(admin, settings.copy(retention = settings.retention.copy(personalDays = null, sharedDays = 30)))
            f.a.query { Artifacts.update({ Artifacts.id eq id }) { it[publishedAt] = System.currentTimeMillis() - 90L * 86_400_000 } }
            val team = a.service.setAccess(owner, id, "team", true, null)
            assertTrue(team.expiresAt!! > System.currentTimeMillis() + 29L * 86_400_000)
            b.maintenance.run()
            assertEquals("team", a.service.artifact(owner, id).access.wire)

            // Open uploads are bounded per member.
            repeat(ArtifactRules.MAX_OPEN_UPLOADS.toInt()) { a.service.startUpload(owner, id, listOf(ref(page))) }
            val busy = assertFailsWith<TeamFailure> { a.service.startUpload(owner, id, listOf(ref(page))) }
            assertEquals("artifact_uploads_busy" to 429, busy.code to busy.status)
            f.a.query { ArtifactUploads.deleteAll() }

            // A failing bucket: the steps before it still run, and its deletion is retried later, behind the others.
            val expiring = publish(a.service, owner, null, mapOf("index.html" to bytes("<p>expiring</p>")), 0).artifact.id
            f.a.query { Artifacts.update({ Artifacts.id eq expiring }) { it[expiresAt] = 1 } }
            a.service.delete(owner, id)
            f.a.query { Artifacts.update({ Artifacts.id eq id }) { it[updatedAt] = 1 } }
            val failing = object : ArtifactObjectStore by bucket {
                override suspend fun list(connection: ArtifactStorageConnection, prefix: String, continuationToken: String?, maxKeys: Int): ObjectListing = error("unreachable")
                override suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String> = error("unreachable")
            }
            ArtifactMaintenance(a.store, a.admin, failing).run()
            assertEquals("deleted", f.a.query { Artifacts.selectAll().where { Artifacts.id eq expiring }.single()[Artifacts.state] }, "Retention ran despite the bucket")
            val deferred = f.a.query { Artifacts.selectAll().where { Artifacts.id eq id }.single() }
            assertNull(deferred[Artifacts.storageDeletedAt]); assertTrue(deferred[Artifacts.updatedAt] > 1, "The failed removal moved behind the others")
            assertTrue(bucket.under(id).isNotEmpty())
        }
    }

    @Test @Timeout(240)
    fun viewingFollowsTheSessionTheAccessAndTheAvailability() = runBlocking {
        fixture { f ->
            val a = Replica(f.a, cipher, Bucket())
            activate(a, f)
            val owner = f.member.principal; val admin = f.admin.principal
            val id = publish(a.service, owner, null, mapOf("index.html" to bytes("<p>one</p>")), 0).artifact.id
            publish(a.service, owner, id, mapOf("index.html" to bytes("<p>two</p>")), 1)
            fun ViewableVersion?.number() = this?.version?.number
            // A member's ticket holds while their login session and their access hold.
            assertEquals(2, a.store.viewable(id, 2, owner.member.id, owner.tokenId).number())
            assertEquals("<p>two</p>".length.toLong(), a.store.viewable(id, 2, owner.member.id, owner.tokenId)!!.version.files.single().size)
            assertNull(a.store.viewable(id, 2, admin.member.id, owner.tokenId), "A ticket's session belongs to its member")
            assertNull(a.store.viewable(id, 9, owner.member.id, owner.tokenId))
            assertNull(a.store.viewable(id, 2, null, null), "A personal artifact has no anonymous viewer")
            assertNull(a.store.publicArtifact(id))

            // Public, pinned to version 1: anyone sees version 1 only; following the latest moves the link to version 2.
            a.service.setAccess(owner, id, "public", false, 1)
            assertEquals(1, a.store.publicArtifact(id)!!.version.number)
            assertEquals(1, a.store.viewable(id, 1, null, null).number())
            assertNull(a.store.viewable(id, 2, null, null), "Only the version the link serves")
            a.service.setAccess(owner, id, "public", true, null)
            assertNull(a.store.viewable(id, 1, null, null), "Pinning moved: the previous version's anonymous tickets stop")
            assertEquals(2, a.store.publicArtifact(id)!!.version.number)
            a.admin.saveSettings(admin, a.admin.settings(admin).copy(publicSharing = PublicSharing.OFF))
            assertNull(a.store.publicArtifact(id), "Public sharing turned off hides every public link")
            assertNull(a.store.viewable(id, 2, null, null))
            assertEquals(2, a.store.viewable(id, 2, owner.member.id, owner.tokenId).number(), "Its members still read it")
            a.admin.saveSettings(admin, a.admin.settings(admin).copy(publicSharing = PublicSharing.OWNERS))
            a.service.setAccess(owner, id, "personal", true, null)
            assertNull(a.store.publicArtifact(id))

            // An administrator reads a personal artifact; artifacts turned off stop every ticket, then come back.
            assertEquals(2, a.store.viewable(id, 2, admin.member.id, admin.tokenId).number())
            assertEquals(ArtifactAvailability(true, null), a.store.availability(admin.teamId))
            a.admin.saveSettings(admin, a.admin.settings(admin).copy(enabled = false))
            assertFalse(a.store.availability(admin.teamId).active)
            assertNull(a.store.viewable(id, 2, owner.member.id, owner.tokenId))
            a.admin.saveSettings(admin, a.admin.settings(admin).copy(enabled = true))
            assertEquals(2, a.store.viewable(id, 2, owner.member.id, owner.tokenId).number())

            // Signing out, being disabled, or the artifact deleted: no ticket shows anything any more.
            val second = f.team.login("developer", "artifact member replacement password", "artifact-test").principal
            f.team.logout(owner)
            assertNull(a.store.viewable(id, 2, owner.member.id, owner.tokenId), "Signed out")
            assertEquals(2, a.store.viewable(id, 2, second.member.id, second.tokenId).number())
            f.team.updateMember(admin, second.member.id, Role.MEMBER, true)
            assertNull(a.store.viewable(id, 2, second.member.id, second.tokenId), "Disabled")
            f.team.updateMember(admin, second.member.id, Role.MEMBER, false)
            a.service.delete(f.team.login("developer", "artifact member replacement password", "artifact-test").principal, id)
            assertNull(a.store.viewable(id, 2, admin.member.id, admin.tokenId), "Deleted")
        }
    }

    @Test @Timeout(240)
    fun thePolicyFollowsAvailabilityAndPublishersAndRunsMustBeTheCallers() = runBlocking {
        fixture { f ->
            val a = Replica(f.a, cipher, Bucket()); val b = Replica(f.b, cipher, Bucket())
            val owner = f.member.principal; val admin = f.admin.principal
            assertEquals(ArtifactPolicy(false, "artifact_storage_missing"), b.service.policy(owner))
            a.admin.saveStorage(admin, 0, ArtifactStorageInput("https://s3.example.com", "us-east-1", "artifacts", "teamcode", true, "AKIAEXAMPLE", "synthetic-secret-key", ServerSideEncryption.NONE))
            assertEquals(ArtifactPolicy(false, "artifact_storage_unverified"), b.service.policy(owner))
            a.admin.recordTest(admin, 1, passed = true)
            assertEquals(ArtifactPolicy(false, "artifacts_disabled"), b.service.policy(owner))
            a.admin.saveSettings(admin, a.admin.settings(admin).copy(enabled = true))
            assertEquals(ArtifactPolicy(true, null), b.service.policy(owner))
            a.admin.saveSettings(admin, a.admin.settings(admin).copy(whoCanPublish = WhoCanPublish.ADMINS))
            assertEquals(ArtifactPolicy(false, "artifact_publish_forbidden"), b.service.policy(owner))
            assertEquals(ArtifactPolicy(true, null), b.service.policy(admin))
            a.admin.saveSettings(admin, a.admin.settings(admin).copy(whoCanPublish = WhoCanPublish.EVERYONE))
            // A failed test turns the policy off again.
            a.admin.recordTest(admin, 1, passed = false)
            assertEquals(ArtifactPolicy(false, "artifact_storage_unverified"), b.service.policy(owner))
            a.admin.recordTest(admin, 1, passed = true)

            // A new artifact records the caller's own run as its origin; another member's run is refused before any draft.
            val runs = AgentRunServiceImpl(f.a)
            val mine = "11111111-2222-4333-8444-555555555555"; val theirs = "66666666-7777-4888-8999-aaaaaaaaaaaa"
            runs.start(owner, mine, "claude", "interactive"); runs.start(admin, theirs, "codex", "interactive")
            val page = bytes("<html>run</html>")
            val upload = b.service.startUpload(owner, null, listOf(ref(page)), ArtifactOrigin("local_run", mine))
            assertEquals("local_run" to mine, f.a.query { Artifacts.selectAll().where { Artifacts.id eq upload.artifactId }.single().let { it[Artifacts.originKind] to it[Artifacts.originRunId] } })
            val drafts = f.a.query { Artifacts.selectAll().count() }
            assertEquals("artifact_run_forbidden", assertFailsWith<TeamFailure> { b.service.startUpload(owner, null, listOf(ref(page)), ArtifactOrigin("local_run", theirs)) }.code)
            assertEquals("artifact_run_forbidden", assertFailsWith<TeamFailure> { b.service.startUpload(owner, null, listOf(ref(page)), ArtifactOrigin("local_run", "00000000-0000-4000-8000-000000000000")) }.code)
            assertEquals(drafts, f.a.query { Artifacts.selectAll().count() })

            // The gallery's filters and the usage per member.
            send(b.service, owner, upload, listOf(page))
            val fromRun = b.service.commit(owner, upload.artifactId, upload.uploadId, "index.html", mapOf("index.html" to ref(page)), 0, "Run", null, null, "team", null).artifact
            val direct = publish(a.service, admin, null, mapOf("index.html" to bytes("<html>direct, a little longer</html>")), 0, title = "Direct").artifact
            fun ids(actor: Principal, query: ArtifactQuery) = runBlocking { a.service.list(actor, query) }.artifacts.map { it.id }
            assertEquals(listOf(fromRun.id), ids(owner, ArtifactQuery(originKind = "local_run")))
            assertEquals(listOf(fromRun.id), ids(owner, ArtifactQuery(runId = mine)))
            assertEquals(emptyList(), ids(owner, ArtifactQuery(originKind = "api")), "Another member's personal artifact stays out")
            assertEquals(listOf(direct.id), ids(admin, ArtifactQuery(ArtifactScope.ALL, originKind = "api")))
            assertEquals(listOf(fromRun.id), ids(admin, ArtifactQuery(ArtifactScope.ALL, access = ArtifactAccess.TEAM)))
            assertEquals(listOf(direct.id), ids(admin, ArtifactQuery(ArtifactScope.MINE, access = ArtifactAccess.PERSONAL)))
            assertEquals("invalid_artifact_query", assertFailsWith<TeamFailure> { a.service.list(owner, ArtifactQuery(originKind = "elsewhere")) }.code)
            val usage = b.admin.overview(admin).usage
            assertEquals(listOf(ArtifactMemberUsage(admin.member.id, admin.member.username, 1, direct.storedBytes), ArtifactMemberUsage(owner.member.id, owner.member.username, 1, page.size.toLong())), usage,
                "Largest first; the drafts left by the refused uploads hold nothing")
            a.service.delete(admin, direct.id)
            assertEquals(listOf(owner.member.id), b.admin.overview(admin).usage.map { it.memberId }, "A deleted artifact is no longer counted")
        }
    }

    private suspend fun activate(replica: Replica, f: Fixture) {
        replica.admin.saveStorage(f.admin.principal, 0, ArtifactStorageInput("https://s3.example.com", "us-east-1", "artifacts", "teamcode", true, "AKIAEXAMPLE", "synthetic-secret-key", ServerSideEncryption.NONE))
        replica.admin.recordTest(f.admin.principal, 1, passed = true)
        replica.admin.saveSettings(f.admin.principal, replica.admin.settings(f.admin.principal).copy(enabled = true))
    }

    private class Fixture(val a: Database, val b: Database, val team: SqlTeamService, val admin: LoginSession, val member: LoginSession)
    private suspend fun fixture(test: suspend (Fixture) -> Unit) {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a -> Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate(); val team = SqlTeamService(a); val initial = assertNotNull(team.bootstrap())
                val pending = team.login("admin", initial, "artifact-test"); team.changePassword(pending.principal, initial, "artifact owner replacement password")
                val admin = team.login("admin", "artifact owner replacement password", "artifact-test")
                val created = team.createMember(admin.principal, "developer", Role.MEMBER)
                val memberPending = team.login("developer", created.temporaryPassword, "artifact-test")
                team.changePassword(memberPending.principal, created.temporaryPassword, "artifact member replacement password")
                test(Fixture(a, b, team, admin, team.login("developer", "artifact member replacement password", "artifact-test")))
            } }
        }
    }
}
