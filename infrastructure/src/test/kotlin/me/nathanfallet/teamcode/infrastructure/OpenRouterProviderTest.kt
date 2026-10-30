package me.nathanfallet.teamcode.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.providers.OpenRouterAccountVerifier
import me.nathanfallet.teamcode.infrastructure.providers.OpenRouterTransport
import kotlin.test.*

class OpenRouterProviderTest {
    private val key = ProviderCredentials("sk-or-v1-secret-key", "api-key", 4_102_444_800_000L)
    private fun json(body: String) = headersOf(HttpHeaders.ContentType, "application/json") to body

    @Test fun verifiesTheKeyAndNamesTheAccountByADigestOfIt() = runBlocking {
        var authorization: String? = null
        val http = HttpClient(MockEngine { req ->
            authorization = req.headers[HttpHeaders.Authorization]
            assertEquals("/api/v1/key", req.url.encodedPath)
            respond("""{"data":{"label":"sk-or-v1-abc...xyz","limit":10,"limit_remaining":4,"is_management_key":false,"organization_id":"org_1"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        http.use {
            val verified = OpenRouterAccountVerifier(it).verify("team", key)
            assertEquals("Bearer sk-or-v1-secret-key", authorization)
            assertEquals("openrouter", verified.identity.provider)
            assertEquals("org_1", verified.identity.organizationId)
            assertEquals(32, verified.identity.accountId.length)
            assertFalse(verified.identity.accountId.contains("secret"), "The identity never carries the key")
            assertEquals("OpenRouter (sk-or-v1-abc...xyz)", verified.displayName)
            // The same key is the same account; another key is another account.
            assertEquals(verified.identity, OpenRouterAccountVerifier(it).verify("team", key).identity)
            assertNotEquals(verified.identity.accountId, OpenRouterAccountVerifier(it).verify("team", ProviderCredentials("sk-or-v1-other", "api-key", key.expiresAtMillis)).identity.accountId)
        }
    }
    @Test fun refusesARejectedKeyAndAManagementKeyWithoutEchoingEither() = runBlocking {
        HttpClient(MockEngine { respond("""{"error":{"message":"No auth credentials found sk-or-v1-secret-key"}}""", HttpStatusCode.Unauthorized) }).use {
            val error = assertFailsWith<TeamFailure> { OpenRouterAccountVerifier(it).verify("team", key) }
            assertEquals("provider_verification_failed", error.code); assertEquals(422, error.status)
            assertFalse(error.message.orEmpty().contains("secret"))
        }
        HttpClient(MockEngine { respond("""{"data":{"label":"m","is_management_key":true}}""", headers = headersOf(HttpHeaders.ContentType, "application/json")) }).use {
            val error = assertFailsWith<TeamFailure> { OpenRouterAccountVerifier(it).verify("team", key) }
            assertEquals("provider_verification_failed", error.code)
            assertTrue(error.message.orEmpty().contains("management key"))
        }
    }
    @Test fun anApiKeyIsNeverRefreshed() = runBlocking {
        HttpClient(MockEngine { error("A key needs no network call to stay valid") }).use {
            assertSame(key, OpenRouterTransport(it).refresh(key))
        }
    }
    @Test fun theKeysOwnSpendingCapBecomesAQuotaWindowAndNoCapMeansNoWindow() {
        fun parse(body: String) = OpenRouterTransport.parseKeyLimit("a", Json.parseToJsonElement(body).jsonObject, 1_000)
        val half = parse("""{"limit":10,"limit_remaining":5,"usage":5}""").single()
        assertEquals("credits", half.window); assertEquals(0.5, half.utilization); assertNull(half.resetsAtMillis)
        assertEquals(1.0, parse("""{"limit":10,"limit_remaining":-2}""").single().utilization, "An overspent key is exhausted, never above 100%")
        assertEquals(0.25, parse("""{"limit":4,"usage":1}""").single().utilization, "Without limit_remaining the usage tells it")
        assertTrue(parse("""{"limit":null,"limit_remaining":null,"usage":12}""").isEmpty())
        assertTrue(parse("""{"limit":0,"limit_remaining":0}""").isEmpty())
        assertTrue(parse("""{"limit":"ten"}""").isEmpty())
    }
}
