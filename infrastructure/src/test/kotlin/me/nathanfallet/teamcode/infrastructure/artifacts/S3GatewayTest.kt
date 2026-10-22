package me.nathanfallet.teamcode.infrastructure.artifacts

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.time.Duration
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.team.*
import org.junit.jupiter.api.Timeout
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import kotlin.test.*

/** The S3 adapter against a real S3-compatible server: signatures, ranges, listing, deletion and the connection test. */
class S3GatewayTest {
    @Test @Timeout(180)
    fun putGetRangeListDeleteAndTheConnectionTest() = runBlocking {
        // MinIO no longer publishes server images, so the Versity S3 Gateway (Apache 2.0) stands for an S3-compatible bucket.
        GenericContainer("versity/versitygw:v1.0.16").withCommand("posix", "/data").withTmpFs(mapOf("/data" to "rw"))
            .withEnv("ROOT_ACCESS_KEY", USER).withEnv("ROOT_SECRET_KEY", PASSWORD).withExposedPorts(7070)
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(120))).use { gateway ->
                gateway.start()
                val endpoint = "http://${gateway.host}:${gateway.getMappedPort(7070)}"
                HttpClient(CIO) { expectSuccess = false; followRedirects = false }.use { http ->
                    createBucket(http, endpoint)
                    val connection = ArtifactStorageConnection(endpoint, "us-east-1", BUCKET, "teamcode/test", true, USER, PASSWORD, ServerSideEncryption.NONE)
                    val s3 = S3ObjectStore(http)
                    val key = ArtifactKeys.blob("teamcode/test", "team", "artifact", "0123")
                    val content = ByteArray(300_000) { (it % 251).toByte() }
                    s3.put(connection, key, content, "video/mp4")
                    s3.put(connection, ArtifactKeys.manifest("teamcode/test", "team", "artifact", 1), "{}".encodeToByteArray(), "application/json")
                    s3.put(connection, "teamcode/test/team/odd name+é.txt", "x".encodeToByteArray())

                    assertContentEquals(content, assertNotNull(s3.get(connection, key)).bytes)
                    val range = assertNotNull(s3.get(connection, key, 1000L..1009L))
                    assertContentEquals(content.copyOfRange(1000, 1010), range.bytes); assertEquals("bytes 1000-1009/300000", range.contentRange)
                    assertEquals(416, assertFailsWith<ObjectStoreException> { s3.get(connection, key, 400_000L..400_010L) }.status)
                    assertEquals(300_000, assertNotNull(s3.head(connection, key)).size)
                    assertNull(s3.get(connection, "teamcode/test/none")); assertNull(s3.head(connection, "teamcode/test/none"))
                    assertEquals("x", assertNotNull(s3.get(connection, "teamcode/test/team/odd name+é.txt")).bytes.decodeToString(), "Keys are encoded once")

                    val first = s3.list(connection, ArtifactKeys.teamRoot("teamcode/test", "team"), maxKeys = 2)
                    assertEquals(2, first.keys.size); assertNotNull(first.nextContinuationToken)
                    val rest = s3.list(connection, ArtifactKeys.teamRoot("teamcode/test", "team"), first.nextContinuationToken, 2)
                    assertEquals(3, (first.keys + rest.keys).map { it.key }.toSet().size); assertNull(rest.nextContinuationToken)

                    assertEquals(emptyList(), s3.delete(connection, (first.keys + rest.keys).map { it.key } + "teamcode/test/never-existed"))
                    assertEquals(emptyList(), s3.list(connection, "teamcode/test/").keys, "The prefix is empty afterwards")

                    // The dashboard's connection test, end to end against the same bucket.
                    val admin = Principal(Member("admin", "admin", Role.ADMIN, false, false), "session", "team")
                    val tested = mutableListOf<Boolean>()
                    val store = object : ArtifactAdminStore {
                        val stored = ArtifactStorage("team", endpoint, "us-east-1", BUCKET, "teamcode/test", true, USER, ServerSideEncryption.NONE, null, 3, 0)
                        override suspend fun storage(actor: Principal) = stored
                        override suspend fun saveStorage(actor: Principal, expectedVersion: Int, input: ArtifactStorageInput) = error("unused")
                        override suspend fun inUse(actor: Principal) = false
                        override suspend fun deleteStorage(actor: Principal) = error("unused")
                        override suspend fun recordTest(actor: Principal, version: Int, passed: Boolean): ArtifactStorage? { assertEquals(3, version); tested += passed; return stored }
                        override suspend fun settings(actor: Principal) = ArtifactSettings()
                        override suspend fun saveSettings(actor: Principal, settings: ArtifactSettings) = settings
                        override suspend fun connection(teamId: String) = connection
                    }
                    val result = ArtifactAdministrationService(store, s3).testStorage(admin)
                    assertTrue(result.ok, result.toString()); assertEquals(listOf("put", "get", "list", "delete"), result.steps.map { it.step })
                    assertEquals(listOf(true), tested)
                    assertEquals(emptyList(), s3.list(connection, "teamcode/test/").keys, "The probe is removed")

                    val wrong = ArtifactStorageConnection(endpoint, "us-east-1", BUCKET, "", true, USER, "not-the-password", ServerSideEncryption.NONE)
                    assertEquals("SignatureDoesNotMatch", assertFailsWith<ObjectStoreException> { s3.put(wrong, "k", ByteArray(1)) }.code)
                    val missingBucket = ArtifactStorageConnection(endpoint, "us-east-1", "no-such-bucket", "", true, USER, PASSWORD, ServerSideEncryption.NONE)
                    assertEquals("NoSuchBucket", assertFailsWith<ObjectStoreException> { s3.put(missingBucket, "k", ByteArray(1)) }.code)
                }
            }
    }

    /** CreateBucket is not part of the adapter: artifacts never create buckets. */
    private suspend fun createBucket(http: HttpClient, endpoint: String) {
        val url = Url(endpoint)
        val host = "${url.host}:${url.port}"
        val date = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC))
        val headers = mapOf("host" to host, "x-amz-content-sha256" to SigV4.EMPTY_SHA256, "x-amz-date" to date)
        val authorization = SigV4.authorization("PUT", "/$BUCKET", emptyList(), headers, SigV4.EMPTY_SHA256, USER, PASSWORD, "us-east-1", date)
        val response = http.put("$endpoint/$BUCKET") { headers.forEach { (k, v) -> header(k, v) }; header(HttpHeaders.Authorization, authorization) }
        assertTrue(response.status.isSuccess(), response.bodyAsText())
    }

    private companion object {
        const val USER = "teamcode-test"
        const val PASSWORD = "teamcode-test-password" // Synthetic credential for this disposable container only.
        const val BUCKET = "artifacts"
    }
}
