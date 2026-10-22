package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** An in-memory store for the rules: the SQL behaviour is proven on MySQL by `ArtifactStoragePersistenceTest`. */
class MemoryArtifactAdminStore : ArtifactAdminStore {
    var stored: ArtifactStorage? = null
    var secret: String? = null
    var settings = ArtifactSettings()
    var artifacts = false
    val tests = mutableListOf<Pair<Int, Boolean>>()
    override suspend fun storage(actor: Principal) = stored
    override suspend fun saveStorage(actor: Principal, expectedVersion: Int, input: ArtifactStorageInput): ArtifactStorage {
        if ((stored?.version ?: 0) != expectedVersion) throw TeamFailure("artifact_storage_conflict", 409, "Changed.")
        secret = input.secretAccessKey ?: secret
        return ArtifactStorage(actor.teamId, input.endpoint, input.region, input.bucket, input.prefix, input.pathStyle, input.accessKeyId, input.serverSideEncryption, null, expectedVersion + 1, 0)
            .also { stored = it }
    }
    override suspend fun inUse(actor: Principal) = artifacts
    override suspend fun deleteStorage(actor: Principal) { stored = null; settings = settings.copy(enabled = false) }
    override suspend fun recordTest(actor: Principal, version: Int, passed: Boolean): ArtifactStorage? {
        tests += version to passed
        stored = stored?.takeIf { it.version == version }?.copy(verifiedAt = if (passed) 1 else null) ?: stored
        return stored
    }
    override suspend fun settings(actor: Principal) = settings
    override suspend fun saveSettings(actor: Principal, settings: ArtifactSettings) = settings.copy(version = settings.version + 1).also { this.settings = it }
    override suspend fun connection(teamId: String) = stored?.let { ArtifactStorageConnection(it.endpoint, it.region, it.bucket, it.prefix, it.pathStyle, it.accessKeyId, secret!!, it.serverSideEncryption) }
}

/** A bucket in memory, with a switchable failure per operation. */
class MemoryObjectStore : ArtifactObjectStore {
    val objects = linkedMapOf<String, ByteArray>()
    var failing: Map<String, ObjectStoreException> = emptyMap()
    var corruptReads = false
    var unreachable = false
    private fun check(operation: String) { if (unreachable) throw java.io.IOException("connection refused"); failing[operation]?.let { throw it } }
    override suspend fun put(connection: ArtifactStorageConnection, key: String, bytes: ByteArray, contentType: String) { check("put"); objects[key] = bytes }
    override suspend fun get(connection: ArtifactStorageConnection, key: String, range: LongRange?, maxBytes: Int): StoredObject? {
        check("get")
        val bytes = objects[key] ?: return null
        val read = if (corruptReads) bytes.copyOf().also { it[0] = (it[0] + 1).toByte() } else bytes
        return StoredObject(read, read.size.toLong(), null, null)
    }
    override suspend fun head(connection: ArtifactStorageConnection, key: String) = objects[key]?.let { ObjectHead(it.size.toLong(), null) }
    override suspend fun list(connection: ArtifactStorageConnection, prefix: String, continuationToken: String?, maxKeys: Int): ObjectListing {
        check("list"); return ObjectListing(objects.filterKeys { it.startsWith(prefix) }.map { ListedObject(it.key, it.value.size.toLong()) }, null)
    }
    override suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String> { check("delete"); keys.forEach(objects::remove); return emptyList() }
}

class ArtifactAdministrationTest {
    private val admin = Principal(Member("admin", "admin", Role.ADMIN, false, false), "session", "team")
    private val member = Principal(Member("member", "member", Role.MEMBER, false, false), "member-session", "team")
    private val adminKey = admin.copy(serviceScopes = setOf(ServiceScope.ADMIN_CLOUD))
    private fun input(endpoint: String = "https://s3.gra.io.cloud.ovh.net", bucket: String = "artifacts", prefix: String = "teamcode", region: String = "gra", secret: String? = "secret-access-key") =
        ArtifactStorageInput(endpoint, region, bucket, prefix, false, "AKIAEXAMPLE", secret, ServerSideEncryption.NONE)
    private fun service(store: ArtifactAdminStore = MemoryArtifactAdminStore(), objects: ArtifactObjectStore = MemoryObjectStore()) =
        ArtifactAdministrationService(store, objects, probeId = { "probe" })

    @Test fun onlyInteractiveAdministratorsReachTheBucketAndSettings() = runBlocking {
        val artifacts = service()
        for (actor in listOf(member, adminKey)) {
            assertEquals(403, assertFailsWith<TeamFailure> { artifacts.storage(actor) }.status)
            assertEquals(403, assertFailsWith<TeamFailure> { artifacts.saveStorage(actor, 0, input()) }.status)
            assertEquals(403, assertFailsWith<TeamFailure> { artifacts.testStorage(actor) }.status)
            assertEquals(403, assertFailsWith<TeamFailure> { artifacts.saveSettings(actor, ArtifactSettings()) }.status)
            assertEquals(403, assertFailsWith<TeamFailure> { artifacts.deleteStorage(actor) }.status)
        }
        assertNull(artifacts.storage(admin))
    }

    @Test fun theEndpointBucketPrefixAndRegionAreValidated() = runBlocking {
        val artifacts = service()
        val invalid = listOf(
            input(endpoint = "http://s3.example.test"), input(endpoint = "https://s3.example.test/path"), input(endpoint = "https://user:pw@s3.example.test"),
            input(endpoint = "https://s3.example.test?x=1"), input(endpoint = "https://"), input(bucket = "AB"), input(bucket = "a..b"), input(bucket = "-bucket"),
            input(prefix = "/lead"), input(prefix = "trail/"), input(prefix = "a/../b"), input(prefix = "a/./b"), input(prefix = "UPPER"), input(prefix = (1..9).joinToString("/") { "s" }),
            input(region = "US East"), input(secret = "has space here"), input(secret = "short"),
        )
        for (candidate in invalid) assertEquals("invalid_artifact_storage", assertFailsWith<TeamFailure>(candidate.toString()) { artifacts.saveStorage(admin, 0, candidate) }.code, candidate.toString())
        for (host in listOf("https://169.254.169.254", "https://metadata.google.internal", "https://[fe80::1]", "https://169.254.1.1", "https://0.0.0.0"))
            assertEquals("artifact_storage_host_not_allowed", assertFailsWith<TeamFailure>(host) { artifacts.saveStorage(admin, 0, input(endpoint = host)) }.code, host)
        // MinIO on a private network or loopback, and R2's auto region, are fine; so is no prefix at all.
        for (fine in listOf(input(endpoint = "https://10.0.0.5:9000", region = "us-east-1"), input(endpoint = "https://127.0.0.1:9000/"), input(endpoint = "https://account.r2.cloudflarestorage.com", region = "auto", prefix = "")))
            artifacts.saveStorage(admin, artifacts.storage(admin)?.version ?: 0, fine)
        assertFalse("secret-access-key" in input().toString(), "The secret is never printed")
    }

    @Test fun settingsAreBounded() = runBlocking {
        val artifacts = service()
        val bad = listOf(
            ArtifactSettings(quotas = ArtifactQuotas(memberBytes = 1)), ArtifactSettings(quotas = ArtifactQuotas(artifactsPerMember = 0)),
            ArtifactSettings(quotas = ArtifactQuotas(publishesPerHour = 0)), ArtifactSettings(retention = ArtifactRetention(personalDays = 0)),
            ArtifactSettings(retention = ArtifactRetention(sharedDays = 4000)), ArtifactSettings(retention = ArtifactRetention(maxVersionsPerArtifact = 0)),
        )
        for (settings in bad) assertEquals("invalid_artifact_settings", assertFailsWith<TeamFailure> { artifacts.saveSettings(admin, settings) }.code, settings.toString())
        assertEquals(1, artifacts.saveSettings(admin, ArtifactSettings(retention = ArtifactRetention(30, 365, 10))).version)
    }

    @Test fun aPassedTestWritesReadsListsAndDeletesTheProbeOnly() = runBlocking {
        val store = MemoryArtifactAdminStore(); val objects = MemoryObjectStore()
        val artifacts = service(store, objects)
        assertEquals("artifact_storage_missing", assertFailsWith<TeamFailure> { artifacts.testStorage(admin) }.code)
        artifacts.saveStorage(admin, 0, input())
        objects.objects["teamcode/team/artifacts/kept/blobs/x"] = ByteArray(1)
        val result = artifacts.testStorage(admin)
        assertTrue(result.ok); assertEquals(listOf("put", "get", "list", "delete"), result.steps.map { it.step })
        assertEquals(setOf("teamcode/team/artifacts/kept/blobs/x"), objects.objects.keys, "Only the probe was written and removed")
        assertEquals(listOf(1 to true), store.tests)
        assertEquals(ArtifactAvailability(false, "artifacts_disabled"), artifacts.availability(admin))
    }

    @Test fun aFailedStepIsReportedWithItsCodeAndRecordedAsAFailure() = runBlocking {
        val store = MemoryArtifactAdminStore(); val objects = MemoryObjectStore()
        val artifacts = service(store, objects)
        artifacts.saveStorage(admin, 0, input())

        objects.failing = mapOf("put" to ObjectStoreException(403, "AccessDenied"))
        val denied = artifacts.testStorage(admin)
        assertFalse(denied.ok); assertEquals(listOf(StorageTestStep("put", false, 403, "AccessDenied")), denied.steps, "Nothing else is tried without a written probe")

        objects.failing = mapOf("list" to ObjectStoreException(403, "AccessDenied"))
        val listing = artifacts.testStorage(admin)
        assertEquals(listOf(true, true, false, true), listing.steps.map { it.ok }, "The probe is still deleted after a failed listing")
        assertTrue(objects.objects.isEmpty())

        objects.failing = emptyMap(); objects.corruptReads = true
        assertEquals("digest_mismatch", artifacts.testStorage(admin).steps.single { it.step == "get" }.code)
        objects.corruptReads = false; objects.unreachable = true
        assertEquals(StorageTestStep("put", false, null, "unreachable"), artifacts.testStorage(admin).steps.single())
        assertEquals(listOf(false, false, false, false), store.tests.map { it.second })
        assertEquals(ArtifactAvailability(false, "artifact_storage_unverified"), artifacts.availability(admin))
    }

    @Test fun availabilityNeedsAStorageATestAndTheSwitch() {
        val stored = ArtifactStorage("team", "https://s3.example.test", "gra", "b", "", false, "AKIA", ServerSideEncryption.NONE, null, 1, 0)
        assertEquals("artifact_storage_missing", ArtifactAvailability.of(ArtifactSettings(enabled = true), null).reason)
        assertEquals("artifact_storage_unverified", ArtifactAvailability.of(ArtifactSettings(enabled = true), stored).reason)
        assertEquals("artifacts_disabled", ArtifactAvailability.of(ArtifactSettings(enabled = false), stored.copy(verifiedAt = 1)).reason)
        assertEquals(ArtifactAvailability(true, null), ArtifactAvailability.of(ArtifactSettings(enabled = true), stored.copy(verifiedAt = 1)))
    }

    @Test fun keysAreOneFolderPerArtifact() {
        assertEquals("tc/team/artifacts/a/blobs/abc", ArtifactKeys.blob("tc", "team", "a", "abc"))
        assertEquals("team/artifacts/a/versions/3.json", ArtifactKeys.manifest("", "team", "a", 3))
        assertTrue(ArtifactKeys.blob("tc", "team", "a", "abc").startsWith(ArtifactKeys.artifactRoot("tc", "team", "a")))
    }
}
