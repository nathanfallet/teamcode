package me.nathanfallet.teamcode.companion.infrastructure

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteAuthRepository
import kotlin.test.*

class RemoteAuthRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var answer: (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" }
    /** A fresh mock engine per HTTP client: the SDK closes each client it creates, engine included. */
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answer(request)
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val store = credentials()
    private val accounts = InMemoryAccountRepository(listOf(account("a1")))
    private fun repository(scope: kotlinx.coroutines.CoroutineScope) =
        TeamCodeClientProvider(engine, store, FakePlatform).let { RemoteAuthRepository(it, me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls(it, accounts), scope) }

    @Test fun capabilitiesAreMappedAndTheAppNamesItself() = runTest {
        answer = { HttpStatusCode.OK to """{"apiVersion":1,"serverVersion":"0.7.0","mode":"team","features":["cloud.events.v1"],"future":true}""" }
        val info = repository(backgroundScope).serverInfo("https://team.example")
        assertEquals("0.7.0", info.serverVersion); assertEquals(setOf("cloud.events.v1"), info.features)
        assertEquals("TeamCode-Companion/0.7.0 (Test)", requests.single().headers[HttpHeaders.UserAgent])
        assertNull(requests.single().headers[HttpHeaders.Authorization])
    }

    @Test fun aServerThatIsNotTeamCodeIsSaidSo() = runTest {
        answer = { HttpStatusCode.NotFound to "<html>nope</html>" }
        assertFailsWith<CompanionException.NotTeamCode> { repository(backgroundScope).serverInfo("https://other.example") }
        answer = { HttpStatusCode.OK to "<html>a portal</html>" }
        assertFailsWith<CompanionException.NotTeamCode> { repository(backgroundScope).serverInfo("https://other.example") }
        answer = { HttpStatusCode.Found to "" }
        assertFailsWith<CompanionException.NotTeamCode> { repository(backgroundScope).serverInfo("https://other.example") }
        // A proxy in front of a TeamCode server that is down is not "another server".
        answer = { HttpStatusCode.BadGateway to "<html>bad gateway</html>" }
        assertEquals(502, assertFailsWith<CompanionException.Server> { repository(backgroundScope).serverInfo("https://team.example") }.statusCode)
    }

    @Test fun loginSendsTheInstallationAndMapsTheMember() = runTest {
        answer = { HttpStatusCode.OK to """{"accessToken":"tc_token","expiresAt":42,"member":{"id":"m1","username":"ada","role":"ADMIN","disabled":false,"passwordChangeRequired":true}}""" }
        val session = repository(backgroundScope).login("https://team.example", "ada", "pw", "TeamCode Test · Bench", "11111111-1111-4111-8111-111111111111")
        assertEquals("tc_token", session.accessToken); assertTrue(session.member.passwordChangeRequired); assertEquals("ADMIN", session.member.role)
        val body = (requests.single().body as TextContent).text
        assertTrue(""""installationId":"11111111-1111-4111-8111-111111111111"""" in body)
        assertTrue(""""device":"TeamCode Test · Bench"""" in body)
        assertEquals("/api/v1/auth/login", requests.single().url.encodedPath)
    }

    @Test fun failuresAreTranslatedWithoutTheServerMessage() = runTest {
        val repository = repository(backgroundScope)
        answer = { HttpStatusCode.Unauthorized to """{"error":{"type":"invalid_credentials","message":"secret detail"}}""" }
        val wrong = assertFailsWith<CompanionException.InvalidCredentials> { repository.login("https://team.example", "ada", "bad", "d", "i") }
        assertFalse(wrong.message.orEmpty().contains("secret detail"))
        answer = { HttpStatusCode.TooManyRequests to """{"error":{"type":"login_rate_limit","message":"x"}}""" }
        assertFailsWith<CompanionException.RateLimited> { repository.login("https://team.example", "ada", "bad", "d", "i") }
        answer = { HttpStatusCode.BadRequest to """{"error":{"type":"invalid_password","message":"x"}}""" }
        assertFailsWith<CompanionException.PasswordRejected> { repository.changePassword("https://team.example", "temporary", "a", "b") }
        answer = { HttpStatusCode.Unauthorized to """{"error":{"type":"unauthorized","message":"x"}}""" }
        assertFailsWith<CompanionException.Unauthorized> { repository.me("a1") }
    }

    @Test fun accountCallsReadTheTokenFromTheStoreAtEachCall() = runTest {
        answer = { HttpStatusCode.OK to """{"id":"m1","username":"ada","role":"MEMBER","disabled":false,"passwordChangeRequired":false}""" }
        val repository = repository(backgroundScope)
        store.write("a1", "first")
        repository.me("a1")
        store.write("a1", "second")
        repository.me("a1")
        assertEquals(listOf("Bearer first", "Bearer second"), requests.map { it.headers[HttpHeaders.Authorization] })
    }

    @Test fun aSecureStorageFailureIsNotReportedAsAnUnreachableServer() = runTest {
        val locked = object : me.nathanfallet.teamcode.companion.domain.services.CredentialStore {
            override val persistent = true
            override suspend fun read(accountId: String): String? = throw IllegalStateException("The Keychain refused to read the token (-25308).")
            override suspend fun write(accountId: String, token: String) = Unit
            override suspend fun delete(accountId: String) = Unit
        }
        val repository = TeamCodeClientProvider(engine, locked, FakePlatform).let { RemoteAuthRepository(it, me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls(it, accounts), backgroundScope) }
        assertFailsWith<CompanionException.CredentialStorage> { repository.me("a1") }
        assertTrue(requests.isEmpty())
    }

    @Test fun renamingFindsTheCurrentDevice() = runTest {
        answer = { request ->
            if (request.url.encodedPath == "/api/v1/devices") HttpStatusCode.OK to """[{"id":"d0","memberId":"m1","name":"Laptop","createdAt":1,"lastLoginAt":1,"activeSessions":1,"current":false},{"id":"d1","memberId":"m1","name":"Phone","createdAt":1,"lastLoginAt":1,"activeSessions":1,"current":true}]"""
            else HttpStatusCode.OK to """{"id":"d1","memberId":"m1","name":"Work phone","createdAt":1,"lastLoginAt":1,"activeSessions":1,"current":true}"""
        }
        store.write("a1", "t")
        assertEquals("Work phone", repository(backgroundScope).renameCurrentDevice("a1", "Work phone"))
        assertEquals("/api/v1/devices/d1", requests.last().url.encodedPath)
    }
}
