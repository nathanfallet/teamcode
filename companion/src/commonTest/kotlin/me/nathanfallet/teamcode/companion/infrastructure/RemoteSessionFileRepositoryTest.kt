package me.nathanfallet.teamcode.companion.infrastructure

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.toByteArray
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteSessionFileRepository
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteSessionRepository
import me.nathanfallet.teamcode.companion.infrastructure.stream.ReconnectPolicy
import me.nathanfallet.teamcode.companion.picked
import kotlin.test.*

/** The policy, an upload and its refusals, and a message naming its files, over a scripted server. */
class RemoteSessionFileRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val bodies = mutableListOf<String>()
    private val answers = ArrayDeque<Triple<HttpStatusCode, String, Map<String, String>>>()
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                bodies += request.body.received().decodeToString()
                val (status, text, headers) = answers.removeFirstOrNull() ?: Triple(HttpStatusCode.ServiceUnavailable, "{}", emptyMap())
                respond(ByteReadChannel(text), status, headersOf(*(mapOf(HttpHeaders.ContentType to "application/json") + headers).map { it.key to listOf(it.value) }.toTypedArray()))
            }
        })
    }
    private val calls = AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1"))))
    private val files = RemoteSessionFileRepository(calls)

    /** The body as a server reads it, so a streamed upload is written out. */
    private suspend fun OutgoingContent.received(): ByteArray = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes()
        is OutgoingContent.WriteChannelContent -> coroutineScope {
            val channel = ByteChannel()
            launch { try { writeTo(channel); channel.close() } catch (error: Exception) { channel.cancel(error) } }
            channel.toByteArray()
        }
        else -> ByteArray(0)
    }

    private fun answer(body: String, status: HttpStatusCode = HttpStatusCode.OK, headers: Map<String, String> = emptyMap()) = answers.addLast(Triple(status, body, headers))
    private fun refusal(code: String) = """{"error":{"type":"$code","message":"No."}}"""

    @Test fun thePolicyIsMapped() = runTest {
        answer("""{"available":false,"reason":"file_storage_not_configured","maxFileBytes":20,"maxMessageBytes":25,"maxFilesPerMessage":5,"types":["image/png"]}""")
        val policy = files.policy("a1")
        assertFalse(policy.available); assertEquals("file_storage_not_configured", policy.reason)
        assertEquals(listOf("image/png"), policy.types); assertEquals(5, policy.maxFiles)
        assertEquals("/api/v1/cloud/files/policy", requests.single().url.encodedPath)
    }

    @Test fun aRefusalKeepsItsCodeAndTheSessionGoneIsNotFound() = runTest {
        answer(refusal("file_too_large"), HttpStatusCode.PayloadTooLarge)
        val tooLarge = assertFailsWith<CompanionException.FileRefused> { files.upload("a1", "s1", picked("a.png"), "image/png", "k1") { _, _ -> } }
        assertEquals("file_too_large", tooLarge.code); assertEquals(413, tooLarge.statusCode)
        assertEquals("k1", requests.single().headers["Idempotency-Key"])
        assertEquals("a.png", requests.single().url.parameters["name"])

        answer(refusal("upload_quota"), HttpStatusCode.TooManyRequests, mapOf(HttpHeaders.RetryAfter to "3600"))
        val quota = assertFailsWith<CompanionException.FileRefused> { files.upload("a1", "s1", picked("a.png"), "image/png", "k2") { _, _ -> } }
        assertEquals("upload_quota", quota.code); assertEquals(3600, quota.retryAfterSeconds)

        answer(refusal("session_not_found"), HttpStatusCode.NotFound)
        assertFailsWith<CompanionException.NotFound> { files.upload("a1", "s1", picked("a.png"), "image/png", "k3") { _, _ -> } }
    }

    @Test fun aMessageNamesItsFiles() = runTest {
        answer("""{"id":"22222222-2222-4333-8444-555555555555","kind":"message","status":"queued","authorId":"m1","text":"","createdAt":1}""")
        RemoteSessionRepository(calls, ReconnectPolicy()).send("a1", "s1", "", "00000000-0000-4000-8000-000000000001", listOf("f1", "f2"))
        assertTrue(""""fileIds":["f1","f2"]""" in bodies.single(), bodies.single())
    }

    @Test fun typesAreGuessedByExtension() {
        assertEquals("image/jpeg", files.typeFor("IMG.JPG"))
        assertEquals("text/markdown", files.typeFor("notes.md"))
        assertNull(files.typeFor("report.docx"))
    }
}
