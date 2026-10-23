package me.nathanfallet.teamcode.companion.infrastructure

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.EnrollmentAccess
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteSubscriptionAccountRepository
import kotlin.test.*

/** Adding a subscription account over a scripted server and provider: what is asked, in which order, and what is sent. */
class RemoteSubscriptionAccountRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    /** Answers by host: the TeamCode server and the provider's token endpoint are scripted apart. */
    private val answers = mutableMapOf<String, ArrayDeque<Pair<HttpStatusCode, String>>>()
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers[request.url.host]?.removeFirstOrNull() ?: (HttpStatusCode.ServiceUnavailable to "{}")
                respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val features = FakeFeatures(setOf("accounts.enrollment-policy.v1"))
    private val repository = RemoteSubscriptionAccountRepository(
        AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1")))),
        features, engine, { count -> ByteArray(count) { 7 } },
    )

    private fun answer(host: String, body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        answers.getOrPut(host) { ArrayDeque() }.addLast(status to body)

    private val accountJson = """{"id":"acc9","provider":"anthropic","displayName":"Team Max","credentialVersion":1,"disabled":false,"priority":0,"state":"READY","email":"ada@example.com"}"""
    private val tokens = """{"access_token":"access-secret","refresh_token":"refresh-secret","expires_in":3600}"""

    @Test fun theTypesAreTheClientsMenuWithTheApiKeyUnavailable() {
        assertEquals(listOf("anthropic-subscription" to true, "codex-subscription" to true, "anthropic-api" to false), repository.types.map { it.id to it.available })
    }

    @Test fun theCheckAsksThePolicyAnOlderServerTheRole() = runTest {
        answer("team.example", """{"policy":"administrators","allowed":false}""")
        assertEquals(EnrollmentAccess.ADMINISTRATORS_ONLY, repository.access("a1"))
        assertEquals("/api/v1/policy/account-enrollment", requests.single().url.encodedPath)
        features.features = emptySet()
        answer("team.example", """{"id":"m1","username":"ada","role":"ADMIN","disabled":false,"passwordChangeRequired":false}""")
        assertEquals(EnrollmentAccess.ALLOWED, repository.access("a1"))
        assertEquals("/api/v1/auth/me", requests.last().url.encodedPath)
    }

    @Test fun thePoolIsMappedWithItsLabel() = runTest {
        answer("team.example", "[$accountJson]")
        assertEquals("Team Max (ada@example.com)", repository.accounts("a1").single().label)
    }

    @Test fun aSignInExchangesOnTheDeviceThenEnrollsAFreshSignIn() = runTest {
        val signIn = repository.startSignIn("a1", repository.types.first())
        assertTrue(requests.isEmpty(), "Starting draws an attempt without any request")
        val url = Url(signIn.url)
        assertEquals("claude.ai", url.host)
        assertEquals("Claude", signIn.providerName)
        assertFalse(signIn.toString().contains(url.parameters["state"]!!))
        answer("platform.claude.com", tokens)
        answer("team.example", """{"created":true,"account":$accountJson}""", HttpStatusCode.Created)
        val added = signIn.complete("the-code#${url.parameters["state"]}")
        assertTrue(added.created); assertEquals("acc9", added.account.id)
        val (exchange, enroll) = requests
        assertEquals("/v1/oauth/token", exchange.url.encodedPath)
        assertNull(exchange.headers[HttpHeaders.Authorization], "No TeamCode credential goes to the provider")
        assertContains((exchange.body as TextContent).text, "\"code\":\"the-code\"")
        assertEquals("/api/v1/accounts", enroll.url.encodedPath)
        val body = (enroll.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        assertTrue(body.contains("\"accessToken\":\"access-secret\"") && body.contains("\"freshSignIn\":true") && body.contains("\"provider\":\"anthropic\""), body)
    }

    @Test fun aWrongPasteIsRefusedBeforeAnyRequestAndCanBeRetried() = runTest {
        val signIn = repository.startSignIn("a1", repository.types.first())
        val refused = assertFailsWith<CompanionException.ProviderSignIn> { signIn.complete("not-a-code") }
        assertFalse(refused.restart)
        assertTrue(requests.isEmpty())
        val other = assertFailsWith<CompanionException.ProviderSignIn> { signIn.complete("code#another-state") }
        assertTrue(other.restart, "A paste of another attempt needs a new one")
    }

    @Test fun aRejectedCodeAtTheProviderSpendsTheAttempt() = runTest {
        val signIn = repository.startSignIn("a1", repository.types[1])
        val state = Url(signIn.url).parameters["state"]
        answer("auth.openai.com", """{"error":"invalid_grant","error_description":"secret detail"}""", HttpStatusCode.BadRequest)
        val refused = assertFailsWith<CompanionException.ProviderSignIn> { signIn.complete("http://localhost:1455/auth/callback?code=c&state=$state") }
        assertTrue(refused.restart)
        assertFalse(refused.message!!.contains("secret detail"))
        assertEquals(1, requests.size, "Nothing is sent to the server")
    }

    @Test fun aServerRefusalKeepsItsCode() = runTest {
        val signIn = repository.startSignIn("a1", repository.types.first())
        answer("platform.claude.com", tokens)
        answer("team.example", """{"error":{"type":"account_in_use","message":"This account is in use."}}""", HttpStatusCode.Forbidden)
        val refused = assertFailsWith<CompanionException.EnrollmentRefused> { signIn.complete("c#${Url(signIn.url).parameters["state"]}") }
        assertEquals("account_in_use", refused.code)
    }

    @Test fun anApiKeyCannotStartASignIn() {
        assertFailsWith<CompanionException.UnavailableAccountType> { repository.startSignIn("a1", repository.types.last()) }
    }
}
