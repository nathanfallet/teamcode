package me.nathanfallet.teamcode.companion.infrastructure

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.NotificationKind
import me.nathanfallet.teamcode.companion.domain.models.PushTestOutcome
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteNotificationPreferencesRepository
import kotlin.test.*

/** The switches and the test push over a scripted server: the routes, the push channel only, and the refusals mapped. */
class RemoteNotificationPreferencesRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var answer: () -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to "{}" }
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answer()
                respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val repository = RemoteNotificationPreferencesRepository(AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1")))))

    @Test fun onlyThePushChannelCountsAndAMissingKindIsOn() = runTest {
        answer = { HttpStatusCode.OK to """{"channels":["push","slack"],"settings":[
            {"channel":"push","kind":"turn_completed","enabled":false},
            {"channel":"slack","kind":"waiting","enabled":false},
            {"channel":"push","kind":"from_the_future","enabled":false}
        ]}""" }
        val switches = repository.switches("a1")
        assertEquals("/api/v1/notifications/settings", requests.single().url.encodedPath)
        assertEquals(false, switches[NotificationKind.TURN_COMPLETED])
        assertEquals(true, switches[NotificationKind.WAITING])
        assertEquals(NotificationKind.entries.toSet(), switches.keys)
    }

    @Test fun aSwitchIsSavedOnThePushChannel() = runTest {
        answer = { HttpStatusCode.OK to """{"channels":["push"],"settings":[{"channel":"push","kind":"waiting","enabled":false}]}""" }
        val switches = repository.set("a1", NotificationKind.WAITING, false)
        assertEquals(HttpMethod.Put, requests.single().method)
        val body = (requests.single().body as TextContent).text
        assertTrue(""""channel":"push"""" in body && """"kind":"waiting"""" in body && """"enabled":false""" in body)
        assertEquals(false, switches[NotificationKind.WAITING])
    }

    @Test fun theTestIsReadFromTheServerAnswer() = runTest {
        answer = { HttpStatusCode.OK to """{"ok":true,"results":[{"status":"sent"},{"status":"rejected","error":"fcm_400"}]}""" }
        assertEquals(PushTestOutcome.Sent(1, 2), repository.test("a1"))
        assertEquals("/api/v1/notifications/push/test", requests.last().url.encodedPath)
        assertEquals(HttpMethod.Post, requests.last().method)
        answer = { HttpStatusCode.Conflict to """{"error":{"type":"push_no_device","message":"x"}}""" }
        assertEquals(PushTestOutcome.NoPhone, repository.test("a1"))
        answer = { HttpStatusCode.Conflict to """{"error":{"type":"push_off","message":"x"}}""" }
        assertEquals(PushTestOutcome.Off, repository.test("a1"))
        answer = { HttpStatusCode.TooManyRequests to """{"error":{"type":"push_test_rate_limited","message":"x"}}""" }
        assertFailsWith<CompanionException.RateLimited> { repository.test("a1") }
    }
}
