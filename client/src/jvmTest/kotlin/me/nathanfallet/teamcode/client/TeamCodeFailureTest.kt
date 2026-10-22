package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TeamCodeFailureTest {
    private fun failing(status: HttpStatusCode, body: String, headers: Headers = headersOf(HttpHeaders.ContentType, "application/json")) =
        TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { respond(body, status, headers) }) { configure(this) }
        }

    @Test fun everyRequestStyleCarriesTheApiErrorCode() = runBlocking {
        val body = """{"error":{"type":"forbidden","message":"Administrator access is required."}}"""
        failing(HttpStatusCode.Forbidden, body).use { client ->
            // `accounts` goes through the plain request path, which used to drop the code.
            assertEquals("forbidden", assertFailsWith<TeamCodeException> { client.accounts() }.code)
            assertEquals("forbidden", assertFailsWith<TeamCodeException> { client.capabilities() }.code)
            assertEquals(403, assertFailsWith<TeamCodeException> { client.me() }.statusCode)
        }
    }

    @Test fun retryAfterAndRequestIdAreKeptOnlyWhenWellFormed() = runBlocking {
        val body = """{"error":{"type":"queue_full","message":"Busy."}}"""
        val headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("30"), "X-Request-Id" to listOf("req-42"))
        failing(HttpStatusCode.ServiceUnavailable, body, headers).use { client ->
            val error = assertFailsWith<TeamCodeException> { client.accounts() }
            assertEquals(503, error.statusCode); assertEquals("queue_full", error.code)
            assertEquals(30L, error.retryAfterSeconds); assertEquals("req-42", error.requestId)
        }
        val unsafe = headersOf(HttpHeaders.RetryAfter to listOf("Wed, 21 Oct 2026 07:28:00 GMT"), "X-Request-Id" to listOf("token=tc_secret value"))
        failing(HttpStatusCode.TooManyRequests, "<html>tc_secret</html>", unsafe).use { client ->
            val error = assertFailsWith<TeamCodeException> { client.accounts() }
            assertNull(error.code); assertNull(error.retryAfterSeconds); assertNull(error.requestId)
            assertFalse(error.message.orEmpty().contains("tc_secret"))
        }
    }

    @Test fun aMalformedRequestIdDoesNotHideAValidOne() = runBlocking {
        val headers = headersOf("X-Request-Id" to listOf("abc def"), "CF-Ray" to listOf("8a1b2c3d4e-CDG"))
        failing(HttpStatusCode.BadGateway, "", headers).use { client ->
            assertEquals("8a1b2c3d4e-CDG", assertFailsWith<TeamCodeException> { client.accounts() }.requestId)
        }
    }

    @Test fun anOversizedErrorBodyIsNotParsed() = runBlocking {
        val padded = """{"error":{"type":"forbidden","message":"${"x".repeat(20_000)}"}}"""
        failing(HttpStatusCode.Forbidden, padded).use { client ->
            val error = assertFailsWith<TeamCodeException> { client.accounts() }
            assertEquals(403, error.statusCode); assertNull(error.code)
        }
    }

    @Test fun errorCodesOutsideTheSafeAlphabetAreDropped() = runBlocking {
        failing(HttpStatusCode.BadRequest, """{"error":{"type":"Bearer tc_secret","message":"x"}}""").use { client ->
            assertNull(assertFailsWith<TeamCodeException> { client.accounts() }.code)
        }
    }

    @Test fun retryAfterAcceptsOnlyDeltaSeconds() {
        assertEquals(0L, retryAfterSeconds("0")); assertEquals(120L, retryAfterSeconds(" 120 "))
        assertNull(retryAfterSeconds("-1")); assertNull(retryAfterSeconds("12345678")); assertNull(retryAfterSeconds(null))
        assertNull(retryAfterSeconds("\u0663\u0660")) // Non-ASCII digits are not delta seconds.
    }
}
