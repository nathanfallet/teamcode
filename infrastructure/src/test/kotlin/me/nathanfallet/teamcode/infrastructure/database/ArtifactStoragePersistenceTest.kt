package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** The artifact bucket and settings on real MySQL with two pools standing for two replicas. */
class ArtifactStoragePersistenceTest {
    private val cipher = CredentialCipher(ByteArray(32) { it.toByte() })
    private fun input(endpoint: String = "https://s3.gra.io.cloud.ovh.net", bucket: String = "artifacts", prefix: String = "teamcode", secret: String? = "first-secret-access-key") =
        ArtifactStorageInput(endpoint, "gra", bucket, prefix, false, "AKIAEXAMPLE", secret, ServerSideEncryption.AES256)

    @Test @Timeout(180)
    fun storageIsSealedVersionedAndFixedOnceArtifactsExist() = runBlocking {
        fixture { f ->
            f.a.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(ArtifactStorageTable, ArtifactSettingsTable, Artifacts, ArtifactMembers,
                    ArtifactVersions, ArtifactVersionFiles, ArtifactBlobs, ArtifactUploads, ArtifactUploadChunks, ArtifactUploadBlobs, withLogs = false), "The reviewed migrations match the Exposed tables")
            }
            val a = SqlArtifactAdminStore(f.a, cipher); val b = SqlArtifactAdminStore(f.b, cipher)
            assertNull(a.storage(f.admin.principal))
            assertEquals("artifact_storage_secret_required", assertFailsWith<TeamFailure> { a.saveStorage(f.admin.principal, 0, input(secret = null)) }.code)
            val saved = a.saveStorage(f.admin.principal, 0, input())
            assertEquals(1, saved.version); assertNull(saved.verifiedAt); assertEquals(ServerSideEncryption.AES256, saved.serverSideEncryption)
            assertEquals("artifact_storage_conflict", assertFailsWith<TeamFailure> { b.saveStorage(f.admin.principal, 0, input()) }.code, "The other replica saw version 1")

            // The secret is sealed, bound to the team, and only the server's connection reads it.
            val envelope = f.a.query { ArtifactStorageTable.selectAll().single()[ArtifactStorageTable.secret] }
            assertFalse("first-secret-access-key" in envelope)
            assertEquals("first-secret-access-key", cipher.decrypt(envelope, "artifact-storage:${f.team}").decodeToString())
            assertFailsWith<Exception> { cipher.decrypt(envelope, "artifact-storage:other-team") }
            assertEquals("first-secret-access-key", b.connection(f.team)!!.secretAccessKey)
            assertFalse("first-secret-access-key" in saved.toString() + b.connection(f.team).toString())

            // A rotation keeps the bucket; an empty secret keeps the stored one; moving the bucket needs its own secret.
            val rotated = b.saveStorage(f.admin.principal, 1, input(secret = null))
            assertEquals(2, rotated.version); assertEquals("first-secret-access-key", a.connection(f.team)!!.secretAccessKey)
            assertEquals("artifact_storage_secret_required", assertFailsWith<TeamFailure> { a.saveStorage(f.admin.principal, 2, input(bucket = "other", secret = null)) }.code)

            // A passed test marks this version verified; a later save or a failed test clears it.
            assertTrue(a.recordTest(f.admin.principal, 2, passed = true)); assertNotNull(a.storage(f.admin.principal)!!.verifiedAt)
            assertFalse(a.recordTest(f.admin.principal, 1, passed = false), "A test of an older version is not recorded")
            assertNotNull(a.storage(f.admin.principal)!!.verifiedAt, "and changes nothing")
            assertTrue(b.recordTest(f.admin.principal, 2, passed = false)); assertNull(a.storage(f.admin.principal)!!.verifiedAt)
            assertTrue(a.recordTest(f.admin.principal, 2, passed = true))
            val moved = a.saveStorage(f.admin.principal, 2, input(bucket = "other-bucket", secret = "second-secret-access-key"))
            assertNull(moved.verifiedAt); assertEquals("other-bucket", moved.bucket)

            // Once an artifact exists, the endpoint, bucket and prefix are fixed, the keys are not, and removal is refused.
            assertFalse(a.inUse(f.admin.principal))
            f.a.query { insertArtifact(f.team, f.member.principal.member.id) }
            assertTrue(b.inUse(f.admin.principal))
            for (change in listOf(input(endpoint = "https://s3.sbg.io.cloud.ovh.net", bucket = "other-bucket"), input(bucket = "third"), input(bucket = "other-bucket", prefix = "elsewhere")))
                assertEquals("artifact_storage_in_use", assertFailsWith<TeamFailure> { a.saveStorage(f.admin.principal, 3, change) }.code)
            assertEquals(4, a.saveStorage(f.admin.principal, 3, input(bucket = "other-bucket", secret = "third-secret-access-key")).version)
            assertEquals("artifact_storage_in_use", assertFailsWith<TeamFailure> { a.deleteStorage(f.admin.principal) }.code)
            // A deleted artifact whose folder was proven empty no longer holds the bucket; one still being deleted does.
            f.a.query { Artifacts.update({ Artifacts.teamId eq f.team }) { it[state] = "deleted" } }
            assertTrue(a.inUse(f.admin.principal))
            f.a.query { Artifacts.update({ Artifacts.teamId eq f.team }) { it[storageDeletedAt] = 2 } }
            assertFalse(a.inUse(f.admin.principal)); assertFalse(a.overview(f.admin.principal).inUse)

            // A member and a key never pass the store's revalidation as someone else.
            assertEquals("authentication_required", assertFailsWith<TeamFailure> { a.storage(f.admin.principal.copy(tokenId = "revoked")) }.code)

            val actions = f.a.query { AuditEvents.selectAll().where { AuditEvents.action like "artifacts.%" }.map { it[AuditEvents.action] } }
            assertEquals(4, actions.count { it == "artifacts.storage_saved" }); assertEquals(3, actions.count { it == "artifacts.storage_tested" }, "The stale test left no record")
        }
    }

    @Test @Timeout(180)
    fun artifactsTurnOnOnlyWithATestedStorageAndRemovingItTurnsThemOff() = runBlocking {
        fixture { f ->
            val a = SqlArtifactAdminStore(f.a, cipher); val b = SqlArtifactAdminStore(f.b, cipher)
            val defaults = a.settings(f.admin.principal)
            assertEquals(ArtifactSettings(), defaults); assertEquals(ArtifactAvailability(false, "artifact_storage_missing"), ArtifactAvailability.of(defaults, null))
            assertEquals("artifact_storage_missing", assertFailsWith<TeamFailure> { a.saveSettings(f.admin.principal, defaults.copy(enabled = true)) }.code)
            val off = a.saveSettings(f.admin.principal, defaults.copy(publicSharing = PublicSharing.ADMINS, quotas = ArtifactQuotas(publishesPerHour = 10)))
            assertEquals(1, off.version); assertEquals(PublicSharing.ADMINS, off.publicSharing); assertEquals(10, off.quotas.publishesPerHour)
            assertEquals("artifact_settings_conflict", assertFailsWith<TeamFailure> { b.saveSettings(f.admin.principal, defaults.copy(version = 0)) }.code)

            a.saveStorage(f.admin.principal, 0, input())
            assertEquals("artifact_storage_unverified", assertFailsWith<TeamFailure> { a.saveSettings(f.admin.principal, off.copy(enabled = true)) }.code)
            a.recordTest(f.admin.principal, 1, passed = true)
            val on = b.saveSettings(f.admin.principal, off.copy(enabled = true, retention = ArtifactRetention(30, null, 10)))
            assertTrue(on.enabled); assertEquals(ArtifactRetention(30, null, 10), on.retention)
            assertTrue(ArtifactAvailability.of(on, a.storage(f.admin.principal)).active)

            // Saving the storage again stops artifacts until a test passes; the switch itself is kept.
            a.saveStorage(f.admin.principal, 1, input(secret = null))
            assertEquals(ArtifactAvailability(false, "artifact_storage_unverified"), ArtifactAvailability.of(a.settings(f.admin.principal), a.storage(f.admin.principal)))
            a.recordTest(f.admin.principal, 2, passed = true)
            assertTrue(ArtifactAvailability.of(a.settings(f.admin.principal), a.storage(f.admin.principal)).active)

            // Removing the storage turns the switch off as a new settings version, so a form loaded before is stale.
            val before = a.settings(f.admin.principal)
            b.deleteStorage(f.admin.principal)
            assertNull(a.storage(f.admin.principal)); assertNull(a.connection(f.team))
            assertFalse(a.settings(f.admin.principal).enabled); assertEquals(before.version + 1, a.settings(f.admin.principal).version)
            assertEquals("artifact_settings_conflict", assertFailsWith<TeamFailure> { a.saveSettings(f.admin.principal, before.copy(enabled = false)) }.code)
            assertEquals("artifact_storage_missing", assertFailsWith<TeamFailure> { a.deleteStorage(f.admin.principal) }.code)

            val actions = f.a.query { AuditEvents.selectAll().where { AuditEvents.action like "artifacts.%" }.map { it[AuditEvents.action] to it[AuditEvents.subjectId] } }
            assertTrue("artifacts.public_sharing_changed" to "owners_of_artifacts->admins" in actions, actions.toString())
            assertEquals(1, actions.count { it.first == "artifacts.public_sharing_changed" }, "Only a change of public sharing is recorded as one")
            assertEquals(3, actions.count { it.first == "artifacts.settings_updated" }, "Two saves and the switch turned off by the removal"); assertEquals(1, actions.count { it.first == "artifacts.storage_deleted" })
        }
    }

    private fun insertArtifact(team: String, owner: String) {
        Artifacts.insert {
            it[id] = java.util.UUID.randomUUID().toString(); it[teamId] = team; it[ownerId] = owner; it[createdBy] = owner; it[credentialId] = null
            it[originKind] = "api"; it[originRunId] = null; it[originSessionId] = null; it[originEpoch] = null
            it[title] = "Report"; it[description] = ""; it[icon] = "chart"; it[access] = "personal"; it[publicFollowsLatest] = true; it[publicVersion] = null
            it[currentVersion] = 0; it[state] = "draft"; it[storedBytes] = 0; it[generation] = 0; it[createdAt] = 1; it[updatedAt] = 1
        }
    }

    private class Fixture(val a: Database, val b: Database, val admin: LoginSession, val member: LoginSession) { val team get() = admin.principal.teamId }
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
                test(Fixture(a, b, admin, team.login("developer", "artifact member replacement password", "artifact-test")))
            } }
        }
    }
}
