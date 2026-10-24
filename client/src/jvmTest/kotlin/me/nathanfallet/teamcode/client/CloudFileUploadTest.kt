package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import java.security.MessageDigest
import kotlin.test.*

/** `uploadCloudFile` and the message that carries files, against a fake server (`cloud.files.v1`). */
class CloudFileUploadTest {
    private val session = "00000000-0000-4000-8000-0000000000aa"
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(300_000) { (it % 251).toByte() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    /** One request as the fake server saw it, its body read whole. */
    private class Seen(val method: HttpMethod, val path: String, val query: Parameters, val headers: Headers, val contentLength: Long?, val contentType: ContentType?, val body: ByteArray)

    private fun fileView(bytes: ByteArray, sha256: String = sha(bytes)) =
        """{"id":"11111111-2222-4333-8444-555555555555","sessionId":"$session","name":"a.png","size":${bytes.size},"contentType":"image/png","sha256":"$sha256","createdAt":1,"expiresAt":2}"""

    private fun server(seen: MutableList<Seen>, answer: suspend MockRequestHandleScope.(Seen, Int) -> HttpResponseData) =
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request: HttpRequestData ->
                val body = request.body.received()
                val one = Seen(request.method, request.url.encodedPath, request.url.parameters, request.headers, request.body.contentLength, request.body.contentType, body)
                seen += one
                answer(one, seen.size)
            }) { configure(this) }
        }

    /** The body as a server reads it: a writer that fails ends it in error instead of leaving it open (as the mock's own reader does). */
    private suspend fun OutgoingContent.received(): ByteArray = if (this !is OutgoingContent.WriteChannelContent) toByteArray() else coroutineScope {
        val channel = ByteChannel()
        launch { try { writeTo(channel); channel.close() } catch (error: Exception) { channel.cancel(error) } }
        channel.toByteArray()
    }

    private fun error(code: String) = """{"error":{"type":"$code","message":"No."}}"""

    @Test fun aFileIsStreamedWithItsNameKeyAndProgressAndCheckedAgainstTheStoredDigest() = runTest {
        val seen = mutableListOf<Seen>()
        val progress = mutableListOf<Long>()
        server(seen) { _, _ -> respond(fileView(png), HttpStatusCode.Created, json) }.use { client ->
            val file = client.uploadCloudFile(session, "my shot.png", png, "image/png", idempotencyKey = "key-1") { sent, total -> assertEquals(png.size.toLong(), total); progress += sent }
            assertEquals("11111111-2222-4333-8444-555555555555", file.id)
            assertEquals(sha(png), file.sha256)
        }
        val request = seen.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/v1/cloud/sessions/$session/files", request.path)
        assertEquals("my shot.png", request.query["name"])
        assertEquals("key-1", request.headers[CloudHeaders.IDEMPOTENCY_KEY])
        assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
        assertEquals(png.size.toLong(), request.contentLength, "The size is announced, so the server refuses a file too large before reading it")
        assertEquals(ContentType.Image.PNG, request.contentType)
        assertContentEquals(png, request.body)
        // From 0, in chunks of 64 KiB, up to the whole file.
        assertEquals(0L, progress.first()); assertEquals(png.size.toLong(), progress.last())
        assertEquals(progress.sorted(), progress); assertTrue(progress.size >= 5, progress.toString())
    }

    @Test fun aSourceIsReadAgainForEachAttemptWithTheSameKeyAndAReplayIsAccepted() = runTest {
        val seen = mutableListOf<Seen>()
        val retries = mutableListOf<Throwable>()
        var opened = 0
        val source = CloudFileSource(png.size.toLong()) { opened++; Buffer().apply { write(png) } }
        server(seen) { _, attempt ->
            when (attempt) {
                1 -> respond(error(CloudFiles.STORAGE_UNAVAILABLE), HttpStatusCode.ServiceUnavailable, json)
                2 -> throw java.io.IOException("connection reset")
                3 -> respond(error("rate_limited"), HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("2")))
                // The server already stored it under this key: it answers the first upload again.
                else -> respond(fileView(png), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType to listOf("application/json"), CloudHeaders.IDEMPOTENCY_REPLAYED to listOf("true")))
            }
        }.use { client ->
            val file = client.uploadCloudFile(session, "a.png", source, idempotencyKey = "same-key", onRetry = { retries += it })
            assertEquals(png.size.toLong(), file.size)
        }
        assertEquals(4, seen.size); assertEquals(4, opened)
        assertEquals(listOf("same-key"), seen.map { it.headers[CloudHeaders.IDEMPOTENCY_KEY] }.distinct(), "A retry never stores the file twice")
        seen.forEach { assertContentEquals(png, it.body) }
        assertEquals(3, retries.size)
        assertEquals(CloudFiles.STORAGE_UNAVAILABLE, (retries[0] as TeamCodeException).code)
    }

    @Test fun refusalsKeepTheServerCodeAndAreNotRetried() = runTest {
        val refusals = listOf(
            Triple(HttpStatusCode.PayloadTooLarge, CloudFiles.FILE_TOO_LARGE, null),
            Triple(HttpStatusCode.UnsupportedMediaType, CloudFiles.FILE_TYPE_NOT_ALLOWED, null),
            Triple(HttpStatusCode.TooManyRequests, CloudFiles.UPLOAD_QUOTA, 3_600L),
            Triple(HttpStatusCode.Conflict, CloudFiles.STORAGE_NOT_CONFIGURED, null),
            Triple(HttpStatusCode.BadRequest, "empty_file", null),
            Triple(HttpStatusCode.BadRequest, "invalid_file_name", null),
            Triple(HttpStatusCode.UnprocessableEntity, "idempotency_conflict", null),
            Triple(HttpStatusCode.Forbidden, "cloud_permission_denied", null),
        )
        for ((status, code, retryAfter) in refusals) {
            val seen = mutableListOf<Seen>()
            val headers = headersOf(*listOfNotNull(HttpHeaders.ContentType to listOf("application/json"), retryAfter?.let { HttpHeaders.RetryAfter to listOf(it.toString()) }).toTypedArray())
            server(seen) { _, _ -> respond(error(code), status, headers) }.use { client ->
                val failure = assertFailsWith<TeamCodeException>(code) { client.uploadCloudFile(session, "a.png", png) }
                assertEquals(code, failure.code); assertEquals(status.value, failure.statusCode)
                assertEquals(retryAfter, failure.retryAfterSeconds)
            }
            assertEquals(1, seen.size, "$code is not retried")
        }
    }

    @Test fun otherStoredBytesAreNeverUsed() = runTest {
        val seen = mutableListOf<Seen>()
        server(seen) { _, _ -> respond(fileView(png, sha256 = "0".repeat(64)), HttpStatusCode.Created, json) }.use { client ->
            assertEquals(TeamCodeClient.FILE_INTEGRITY_FAILED, assertFailsWith<TeamCodeException> { client.uploadCloudFile(session, "a.png", png) }.code)
        }
        assertEquals(1, seen.size)
    }

    @Test fun aSourceShorterThanAnnouncedFailsWithoutARetry() = runTest {
        val shorter = CloudFileSource(png.size + 10L) { Buffer().apply { write(png) } }
        var requests = 0
        val client = TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request -> requests++; request.body.received(); respond(fileView(png), HttpStatusCode.Created, json) }) { configure(this) }
        }
        client.use { assertFailsWith<CloudFileReadException> { it.uploadCloudFile(session, "a.png", shorter) } }
        assertEquals(1, requests, "Never retried")
    }

    @Test fun thePolicyIsReadAndAMessageCarriesItsFiles() = runTest {
        val seen = mutableListOf<Seen>()
        server(seen) { request, _ ->
            when (request.path) {
                "/api/v1/cloud/files/policy" -> respond("""{"available":false,"reason":"file_storage_not_configured","maxFileBytes":20971520,"maxMessageBytes":26214400,"maxFilesPerMessage":5,"types":["image/png","text/plain"]}""", HttpStatusCode.OK, json)
                else -> respond("""{"id":"22222222-2222-4333-8444-555555555555","kind":"message","status":"queued","authorId":"m1","text":"look","createdAt":1}""", HttpStatusCode.Accepted, json)
            }
        }.use { client ->
            val policy = client.cloudFilePolicy()
            assertFalse(policy.available); assertEquals(CloudFiles.STORAGE_NOT_CONFIGURED, policy.reason); assertEquals(20L * 1024 * 1024, policy.maxFileBytes)
            client.sendCloudMessage(session, SendCloudMessageRequest("look", fileIds = listOf("f1", "f2")), "message-key")
        }
        assertEquals("/api/v1/cloud/files/policy", seen[0].path)
        val message = Json.parseToJsonElement(seen[1].body.decodeToString()).jsonObject
        assertEquals("/api/v1/cloud/sessions/$session/messages", seen[1].path)
        assertEquals(listOf("f1", "f2"), message["fileIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("look", message["text"]!!.jsonPrimitive.content)
    }

    @Test fun typesAreGuessedByExtensionAndTextByItsBytes() {
        assertEquals("image/jpeg", CloudFileTypes.forName("Photo.JPG"))
        assertEquals("text/markdown", CloudFileTypes.forName("notes.md"))
        assertNull(CloudFileTypes.forName("build.log")); assertNull(CloudFileTypes.forName("png"))
        assertTrue(CloudFileTypes.isText("héllo\n".encodeToByteArray()))
        assertFalse(CloudFileTypes.isText(byteArrayOf(0x68, 0)))
        assertFalse(CloudFileTypes.isText(byteArrayOf(0xC3.toByte(), 0x28)))
    }
}
