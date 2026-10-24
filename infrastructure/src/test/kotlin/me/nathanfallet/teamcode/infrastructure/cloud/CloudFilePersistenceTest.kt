package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/**
 * Files sent to sessions on real MySQL with two replicas and an in-memory bucket: who may use and download a file, the
 * quotas, idempotency, and the removal of its bytes once its host acknowledged it or it expired.
 */
class CloudFilePersistenceTest {
    /** The team's bucket in memory, shared by both replicas. */
    private class Bucket : ArtifactObjectStore {
        val objects = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        override suspend fun put(connection: ArtifactStorageConnection, key: String, bytes: ByteArray, contentType: String) { objects[key] = bytes }
        override suspend fun get(connection: ArtifactStorageConnection, key: String, range: LongRange?, maxBytes: Int) = objects[key]?.let { StoredObject(it, it.size.toLong(), null, null) }
        override suspend fun head(connection: ArtifactStorageConnection, key: String) = objects[key]?.let { ObjectHead(it.size.toLong(), null) }
        override suspend fun list(connection: ArtifactStorageConnection, prefix: String, continuationToken: String?, maxKeys: Int) =
            ObjectListing(objects.keys.filter { it.startsWith(prefix) }.sorted().map { ListedObject(it, objects.getValue(it).size.toLong()) }, null)
        override suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String> { keys.forEach(objects::remove); return emptyList() }
    }

    private class Replica(val database: Database, val cluster: FakeClusterPort, cipher: CredentialCipher, bucket: Bucket) {
        val store = SqlCloudSessionStore(database)
        val conversations = SqlCloudConversationStore(database)
        val access = CloudAccessService(conversations, store)
        val admin = CloudAdministrationService(SqlCloudAdminStore(database, cipher), cluster)
        val artifacts = SqlArtifactAdminStore(database, cipher)
        val sessions = CloudSessionService(store, access = access)
        val reconciler = ReconcileCloudSessionsUseCase(store, cluster)
        val runner = RunnerChannelUseCase(store)
        val conversation = CloudConversationService(conversations, store, access, sleep = { delay(1) })
        val agent = RunnerConversationUseCase(store, conversations, { _, _ -> false }, sleep = { delay(1) })
        val files = CloudFileService(SqlCloudFileStore(database), store, conversation, object : CloudFileStorage {
            override suspend fun active(teamId: String) = artifacts.testedConnection(teamId)
            override suspend fun any(teamId: String) = artifacts.connection(teamId)
        }, bucket)
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
    private fun body(bytes: ByteArray): CloudFileBody {
        var offset = 0
        return CloudFileBody { max -> if (offset >= bytes.size) null else bytes.copyOfRange(offset, minOf(bytes.size, offset + max)).also { offset += it.size } }
    }
    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun filesReachOnlyTheirSessionAuthorAndExecutionAndAreRemovedOnceReceived() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                coroutineScope { listOf(a, b).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                a.query {
                    val drift = MigrationUtils.statementsRequiredForDatabaseMigration(CloudSessionFiles, CloudFileUsage, CloudSettingsTable, AuditEvents, withLogs = false)
                    assertEquals(emptyList(), drift, "The reviewed migration matches the Exposed tables")
                }
                val cluster = FakeClusterPort()
                val cipher = CredentialCipher(ByteArray(32) { 43 })
                val bucket = Bucket()
                val ra = Replica(a, cluster, cipher, bucket); val rb = Replica(b, cluster, cipher, bucket)
                val (admin, colleague, other) = members(a)
                val profile = administration(ra, admin, setOf(colleague.member.id, other.member.id))
                val s = ready(ra, rb, admin, colleague, profile)
                val s2 = ready(ra, rb, admin, colleague, profile)
                val team = admin.teamId

                // No tested bucket: the team cannot send files.
                assertFalse(ra.files.policy(colleague).available)
                failure("file_storage_not_configured") { ra.files.upload(colleague, s.id, "a.png", null, null, body(png)) }
                val storage = ra.artifacts.saveStorage(admin, 0, ArtifactStorageInput("https://s3.example.test", "auto", "bucket", "teamcode", true, "AKIAEXAMPLE", "synthetic-secret", ServerSideEncryption.AES256))
                failure("file_storage_not_configured") { ra.files.upload(colleague, s.id, "a.png", null, null, body(png)) }
                assertTrue(ra.artifacts.recordTest(admin, storage.version, passed = true))
                assertTrue(rb.files.policy(colleague).available)

                // Stored in 1 MiB parts under session-files/, audited with name, size and type only.
                val photo = (ra.files.upload(colleague, s.id, "../screens/shot.png", png.size.toLong(), "upload-1", body(png)) as CloudFileOutcome.Created).file
                assertEquals("shot.png", photo.name); assertEquals("image/png", photo.contentType); assertEquals(sha(png), photo.sha256)
                assertEquals((0..3).map { "teamcode/session-files/$team/${s.id}/${photo.id}/$it" }, bucket.objects.keys.filter { photo.id in it }.sortedBy { it.substringAfterLast('/').toInt() })
                assertEquals(photo.createdAt + CloudFileRules.TTL_MILLIS, photo.expiresAt)
                val audit = a.query { AuditEvents.selectAll().where { (AuditEvents.action eq "cloud.file.uploaded") and (AuditEvents.subjectId eq photo.id) }.single() }
                assertEquals(colleague.member.id, audit[AuditEvents.actorId]); assertEquals(colleague.tokenId, audit[AuditEvents.credentialId])
                assertEquals(buildJsonObject { put("sessionId", s.id); put("name", "shot.png"); put("size", png.size.toLong()); put("contentType", "image/png") },
                    Json.parseToJsonElement(audit[AuditEvents.details]!!))
                // Idempotency across replicas: the same file replays, other bytes under the key are refused.
                assertEquals(photo.id, assertIs<CloudFileOutcome.Replayed>(rb.files.upload(colleague, s.id, "../screens/shot.png", null, "upload-1", body(png))).file.id)
                failure("idempotency_conflict") { rb.files.upload(colleague, s.id, "../screens/shot.png", null, "upload-1", body(png + byteArrayOf(0))) }
                assertEquals(1, a.query { CloudSessionFiles.selectAll().where { CloudSessionFiles.clientKey eq "upload-1" }.count() })

                // A refused type leaves nothing behind once swept.
                failure("file_type_not_allowed") { ra.files.upload(colleague, s.id, "tool.exe", null, null, body(byteArrayOf(0x4D, 0x5A, 0, 0) + ByteArray(2 * 1024 * 1024))) }
                // A text that turns out binary after its first part, too.
                failure("file_type_not_allowed") { ra.files.upload(colleague, s.id, "log.txt", null, null, body(ByteArray(1024 * 1024) { 'a'.code.toByte() } + byteArrayOf(0))) }
                rb.files.sweep()
                assertEquals(setOf(photo.id), bucket.objects.keys.map { it.split('/')[4] }.toSet(), "Only the stored file has parts left")
                assertEquals(1, a.query { CloudSessionFiles.selectAll().count() })

                // Another member, even one the policy lets message the session, and another session, cannot use the file.
                ra.access.savePolicy(admin, 0, listOf(CloudAccessRule(CloudAccessSubject.Everyone, CloudPermission.entries.toSet(), CloudAccessScope.OWN),
                    CloudAccessRule(CloudAccessSubject.MemberSubject(other.member.id), setOf(CloudPermission.MESSAGE, CloudPermission.TRANSCRIPT), CloudAccessScope.TEAM)))
                failure("file_unavailable") { rb.conversation.send(other, s.id, InputRequest("", true, null, null, null, listOf(photo.id))) }
                failure("file_unavailable") { rb.conversation.send(colleague, s2.id, InputRequest("", true, null, null, null, listOf(photo.id))) }
                failure("file_unavailable") { rb.conversation.send(colleague, s.id, InputRequest("", true, null, null, null, listOf("00000000-0000-0000-0000-000000000000"))) }
                failure("invalid_files") { rb.conversation.send(colleague, s.id, InputRequest("x", true, null, null, null, List(6) { "f$it" })) }
                failure("invalid_message") { rb.conversation.send(colleague, s.id, InputRequest("", true, null, null, null)) }
                // Another member's upload to the owner's session stays theirs: the owner cannot send it either.
                val theirs = (ra.files.upload(other, s.id, "note.txt", null, null, body("from other".encodeToByteArray())) as CloudFileOutcome.Created).file
                failure("file_unavailable") { ra.conversation.send(colleague, s.id, InputRequest("", true, null, null, null, listOf(theirs.id))) }

                // The files of one message are bounded together by the team's setting.
                val small = (ra.files.upload(colleague, s.id, "notes.md", null, null, body("# Plan\n".encodeToByteArray())) as CloudFileOutcome.Created).file
                ra.admin.saveSettings(admin, ra.admin.settings(admin).copy(messageFileMebibytes = 3))
                failure("files_too_large") { rb.conversation.send(colleague, s.id, InputRequest("both", true, null, null, null, listOf(photo.id, small.id))) }
                ra.admin.saveSettings(admin, ra.admin.settings(admin).copy(messageFileMebibytes = CloudFileRules.DEFAULT_MESSAGE_MEBIBYTES))
                val sent = assertIs<InputOutcome.Accepted>(rb.conversation.send(colleague, s.id, InputRequest("Look", true, "with-files", "digest-1", null, listOf(photo.id, small.id))))
                assertEquals(listOf(photo.id, small.id), sent.input.files.map { it.id })
                failure("file_unavailable") { ra.conversation.send(colleague, s.id, InputRequest("again", true, null, null, null, listOf(small.id))) }
                assertIs<InputOutcome.Replayed>(ra.conversation.send(colleague, s.id, InputRequest("Look", true, "with-files", "digest-1", null, listOf(photo.id, small.id))))

                // Only the execution the message was delivered to downloads its files; another epoch is fenced.
                failure("not_found") { ra.files.runnerFile(team, s.id, 1, photo.id) }
                val delivered = rb.agent.poll(team, s.id, 1, 0, null, acceptsFiles = true).inputs.single()
                assertEquals(listOf(CloudFileRef(photo.id, "shot.png", png.size.toLong(), "image/png", sha(png)), CloudFileRef(small.id, "notes.md", 7, "text/markdown", sha("# Plan\n".encodeToByteArray()))), delivered.files)
                val download = ra.files.runnerFile(team, s.id, 1, photo.id)
                val received = java.io.ByteArrayOutputStream().also { out -> download.parts { out.write(it) } }.toByteArray()
                assertContentEquals(png, received)
                failure("execution_fenced") { ra.files.runnerFile(team, s.id, 2, photo.id) }
                failure("not_found") { ra.files.runnerFile(team, s2.id, 1, photo.id) }
                failure("not_found") { ra.files.runnerFile(team, s.id, 1, theirs.id) }

                // The host acknowledges: the transcript names the files (never a path), the bytes go at the next sweep.
                ra.agent.acknowledge(team, s.id, 1, listOf(RunnerInputAck(sent.input.id, true, null)))
                failure("not_found") { ra.files.runnerFile(team, s.id, 1, photo.id) }
                val event = rb.conversation.events(colleague, s.id, 0, null, 100).events.last { it.type == CloudEventType.USER_MESSAGE }
                val data = Json.parseToJsonElement(event.data).jsonObject
                assertEquals(listOf("shot.png", "notes.md"), data.getValue("files").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content })
                assertEquals(png.size.toLong(), data.getValue("files").jsonArray[0].jsonObject.getValue("size").jsonPrimitive.long)
                assertNotNull(a.query { CloudSessionFiles.selectAll().where { CloudSessionFiles.id eq photo.id }.single()[CloudSessionFiles.consumedAt] })
                rb.files.sweep()
                assertTrue(bucket.objects.keys.none { photo.id in it || small.id in it }, "Received files are removed from the bucket")
                assertEquals(0, a.query { CloudSessionFiles.selectAll().where { CloudSessionFiles.id inList listOf(photo.id, small.id) }.count() })

                // A file nobody sent is removed after its day, and can no longer be sent.
                val stale = (ra.files.upload(colleague, s.id, "stale.csv", null, null, body("a,b\n1,2\n".encodeToByteArray())) as CloudFileOutcome.Created).file
                assertEquals("text/csv", stale.contentType)
                a.query { CloudSessionFiles.update({ CloudSessionFiles.id eq stale.id }) { it[expiresAt] = 1 } }
                failure("file_unavailable") { ra.conversation.send(colleague, s.id, InputRequest("", true, null, null, null, listOf(stale.id))) }
                ra.files.sweep()
                assertTrue(bucket.objects.keys.none { stale.id in it })
                assertEquals(0, a.query { CloudSessionFiles.selectAll().where { CloudSessionFiles.id eq stale.id }.count() })

                // A rejected or cancelled message releases its files too.
                val dropped = (ra.files.upload(colleague, s.id, "drop.txt", null, null, body("drop".encodeToByteArray())) as CloudFileOutcome.Created).file
                val rejected = assertIs<InputOutcome.Accepted>(ra.conversation.send(colleague, s.id, InputRequest("", true, null, null, null, listOf(dropped.id))))
                // A runner that does not download files rejects the message rather than handing the agent its text alone.
                assertEquals(emptyList(), rb.agent.poll(team, s.id, 1, 0, null).inputs)
                assertEquals("files_unsupported", ra.conversation.input(colleague, s.id, rejected.input.id).reason)
                ra.files.sweep()
                assertTrue(bucket.objects.keys.none { dropped.id in it })

                quotas(ra, rb, a, colleague, s2)
            } }
        }
    }

    private suspend fun quotas(ra: Replica, rb: Replica, a: Database, colleague: Principal, s2: CloudSession) {
        // At most 20 files a session holds that no message names yet, counted under the session lock across replicas.
        coroutineScope {
            (1..CloudFileRules.MAX_UNSENT_FILES_PER_SESSION + 4).map { n ->
                async(Dispatchers.IO) { runCatching { (if (n % 2 == 0) ra else rb).files.upload(colleague, s2.id, "f$n.txt", null, null, body("file $n".encodeToByteArray())) } }
            }.awaitAll()
        }.let { outcomes ->
            assertEquals(CloudFileRules.MAX_UNSENT_FILES_PER_SESSION, outcomes.count { it.isSuccess })
            assertTrue(outcomes.filter { it.isFailure }.all { (it.exceptionOrNull() as? TeamFailure)?.code == "upload_quota" })
        }
        a.query { CloudSessionFiles.update({ CloudSessionFiles.sessionId eq s2.id }) { it[deletingSince] = 1 } }
        ra.files.sweep()
        // At most 100 MiB its host has not received.
        val held = (ra.files.upload(colleague, s2.id, "held.txt", null, null, body("held".encodeToByteArray())) as CloudFileOutcome.Created).file
        a.query { CloudSessionFiles.update({ CloudSessionFiles.id eq held.id }) { it[size] = CloudFileRules.SESSION_BUFFER_BYTES - 2 } }
        failure("upload_quota") { rb.files.upload(colleague, s2.id, "more.txt", null, null, body("more".encodeToByteArray())) }
        a.query { CloudSessionFiles.update({ CloudSessionFiles.id eq held.id }) { it[deletingSince] = 1 } }
        ra.files.sweep()
        // And a daily amount per member, with the time until the next UTC day.
        a.query { CloudFileUsage.update({ CloudFileUsage.memberId eq colleague.member.id }) { it[bytes] = CloudFileRules.MEMBER_DAILY_BYTES } }
        val daily = assertFailsWith<TeamFailure> { ra.files.upload(colleague, s2.id, "late.txt", null, null, body("late".encodeToByteArray())) }
        assertEquals("upload_quota", daily.code); assertEquals(429, daily.status)
        assertTrue(daily.retryAfterSeconds!! in 1..86_400)
        assertEquals(CloudFileRules.MEMBER_DAILY_BYTES, a.query { CloudFileUsage.selectAll().where { CloudFileUsage.memberId eq colleague.member.id }.single()[CloudFileUsage.bytes] })
    }

    private suspend fun members(a: Database): Triple<Principal, Principal, Principal> {
        val team = SqlTeamService(a)
        val password = team.bootstrap()!!
        team.changePassword(team.login("admin", password, "test").principal, password, "files admin replacement password")
        val admin = team.login("admin", "files admin replacement password", "test").principal
        fun String.member(): Principal = runBlocking {
            val created = team.createMember(admin, this@member, Role.MEMBER)
            team.changePassword(team.login(this@member, created.temporaryPassword, "test").principal, created.temporaryPassword, "$this@member replacement password")
            team.login(this@member, "$this@member replacement password", "test").principal
        }
        return Triple(admin, "files-colleague".member(), "files-other".member())
    }

    private suspend fun administration(ra: Replica, admin: Principal, assigned: Set<String>): CloudProfile {
        val cluster = ra.admin.saveCluster(admin, null, null, CloudClusterInput("lab", "https://k8s.example.test:6443",
            "-----BEGIN CERTIFICATE-----\nMIIBsynthetic\n-----END CERTIFICATE-----\n", "synthetic-service-account-token-0123456789", listOf("teamcode"), 10, "standard"))
        return ra.admin.saveProfile(admin, null, 0, "toolchain", CloudProfileSpec(cluster.id, "teamcode", "registry.example/toolchain:2026-10"), assigned)
    }

    private suspend fun ready(ra: Replica, rb: Replica, admin: Principal, owner: Principal, profile: CloudProfile): CloudSession {
        val created = (ra.sessions.create(owner, null, CloudSessionDraft("Files", profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        pass(ra); pass(ra)
        val pod = CloudSessionService.podName(created.id, 1)
        ra.cluster.start(pod); pass(ra)
        ra.runner.register(admin.teamId, created.id, 1, pod)
        rb.runner.heartbeat(admin.teamId, created.id, 1, ready = true)
        return ra.store.find(admin.teamId, created.id)!!.also { assertEquals(READY, it.state) }
    }

    private suspend fun makeDue(r: Replica) = r.database.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }
    private suspend fun pass(r: Replica) { makeDue(r); r.reconciler() }
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
}
