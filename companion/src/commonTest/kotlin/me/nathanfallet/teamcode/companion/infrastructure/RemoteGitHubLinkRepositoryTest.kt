package me.nathanfallet.teamcode.companion.infrastructure

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.GitHubLinkState
import me.nathanfallet.teamcode.companion.domain.models.GitHubPoll
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteGitHubLinkRepository
import kotlin.test.*

/** The GitHub link over a scripted server: the CLI's routes, what is mapped, and how the 409 refusals are worded. */
class RemoteGitHubLinkRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val answers = ArrayDeque<Pair<HttpStatusCode, String>>()
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                val (status, body) = answers.removeFirstOrNull() ?: (HttpStatusCode.ServiceUnavailable to "{}")
                respond(ByteReadChannel(body), status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val features = FakeFeatures(setOf("github.link.v1"))
    private val repository = RemoteGitHubLinkRepository(
        AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1")))), features,
    )

    private fun answer(body: String, status: HttpStatusCode = HttpStatusCode.OK) = answers.addLast(status to body)

    private val linked = """{"memberId":"m1","state":"linked","appConfigured":true,"login":"octocat","userId":583231,"linkedAt":1700000000000}"""

    @Test fun theCapabilityIsAskedAgainFromTheServer() = runTest {
        assertTrue(repository.offered("a1"))
        assertEquals(listOf(true), features.refreshes)
        features.features = setOf("cloud.sessions.v1")
        assertFalse(repository.offered("a1"))
    }

    @Test fun theLinkIsTheCallersOwnWithItsLogin() = runTest {
        answer(linked)
        val link = repository.link("a1")
        assertEquals("/api/v1/github/link", requests.single().url.encodedPath)
        assertEquals(HttpMethod.Get, requests.single().method)
        assertEquals(GitHubLinkState.LINKED, link.state)
        assertEquals("octocat", link.login)
        answer("""{"memberId":"m1","state":"reconnect_required","appConfigured":true,"login":"octocat"}""")
        assertEquals(GitHubLinkState.RECONNECT_REQUIRED, repository.link("a1").state)
        answer("""{"memberId":"m1","state":"not_linked","appConfigured":false}""")
        val none = repository.link("a1")
        assertEquals(GitHubLinkState.NOT_LINKED, none.state)
        assertFalse(none.appConfigured)
        assertNull(none.login)
    }

    @Test fun theDeviceFlowStartsAndPollsOnTheCliRoutes() = runTest {
        answer("""{"id":"d1","userCode":"ABCD-1234","verificationUri":"https://github.com/login/device","expiresAt":1900000000000,"interval":5}""")
        val device = repository.start("a1")
        assertEquals("/api/v1/github/link/device", requests.last().url.encodedPath)
        assertEquals(HttpMethod.Post, requests.last().method)
        assertEquals("ABCD-1234", device.userCode)
        assertEquals(5, device.interval)
        answer("""{"status":"pending","interval":10}""")
        assertEquals(GitHubPoll.Pending(10), repository.poll("a1", "d1"))
        assertEquals("/api/v1/github/link/device/d1", requests.last().url.encodedPath)
        answer("""{"status":"linked","link":$linked}""")
        assertEquals("octocat", (repository.poll("a1", "d1") as GitHubPoll.Linked).link.login)
        answer("""{"status":"denied"}""")
        assertEquals(GitHubPoll.Denied, repository.poll("a1", "d1"))
        answer("""{"status":"expired"}""")
        assertEquals(GitHubPoll.Expired, repository.poll("a1", "d1"))
    }

    @Test fun aLinkedAnswerWithoutTheLinkReadsIt() = runTest {
        answer("""{"status":"linked"}""")
        answer(linked)
        assertEquals("octocat", (repository.poll("a1", "d1") as GitHubPoll.Linked).link.login)
        assertEquals("/api/v1/github/link", requests.last().url.encodedPath)
    }

    @Test fun theConflictsOfTheStartAreWorded() = runTest {
        answer("""{"error":{"type":"github_app_not_configured","message":"x"}}""", HttpStatusCode.Conflict)
        val missing = assertFailsWith<CompanionException.GitHubRefused> { repository.start("a1") }
        assertEquals(CompanionException.GitHubRefused.APP_NOT_CONFIGURED, missing.code)
        assertContains(missing.message!!, "GitHub App")
        answer("""{"error":{"type":"github_device_flow_disabled","message":"x"}}""", HttpStatusCode.Conflict)
        assertEquals(CompanionException.GitHubRefused.DEVICE_FLOW_DISABLED, assertFailsWith<CompanionException.GitHubRefused> { repository.start("a1") }.code)
    }

    @Test fun unlinkingDeletesTheCallersLink() = runTest {
        answer("", HttpStatusCode.NoContent)
        repository.unlink("a1")
        assertEquals(HttpMethod.Delete, requests.single().method)
        assertEquals("/api/v1/github/link", requests.single().url.encodedPath)
    }
}
