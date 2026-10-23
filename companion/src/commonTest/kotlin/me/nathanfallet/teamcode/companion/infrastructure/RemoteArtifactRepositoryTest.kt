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
import me.nathanfallet.teamcode.companion.FakePlatform
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteArtifactRepository
import kotlin.test.*

class RemoteArtifactRepositoryTest {
    private val id = "0b5c7c1e-9f0a-4d2b-8e5f-3a1c2d4e6f80"
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
    private val artifacts = RemoteArtifactRepository(AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1")))))
    private val artifactJson = """{"id":"$id","url":"https://team.example/artifact/$id","title":"Report","description":"Q3","icon":"chart","access":"team",
        "ownerId":"m1","ownerName":"ada","currentVersion":2,"storedBytes":10,"relation":"owner","originKind":"cloud_session","originSessionId":"s1",
        "createdAt":1,"updatedAt":5,"future":true}"""

    @Test fun theGalleryAsksForItsScopeAndSessionAndMapsEachCard() = runTest {
        answers += HttpStatusCode.OK to """{"artifacts":[$artifactJson],"nextBefore":"c2"}"""
        val page = artifacts.artifacts("a1", ArtifactScope.MINE, "s1", "c1")
        val url = requests.single().url
        assertEquals("/api/v1/artifacts", url.encodedPath)
        assertEquals("mine", url.parameters["scope"]); assertEquals("s1", url.parameters["session"]); assertEquals("c1", url.parameters["before"]); assertEquals("50", url.parameters["limit"])
        assertEquals("c2", page.nextBefore)
        val card = page.artifacts.single()
        assertEquals("Report", card.title); assertEquals(2, card.currentVersion); assertEquals("cloud_session", card.originKind); assertEquals("s1", card.originSessionId)
    }

    @Test fun aTicketIsAskedForTheVersionAndAHiddenArtifactIsNotFound() = runTest {
        answers += HttpStatusCode.OK to """{"artifactId":"$id","version":1,"contentUrl":"https://team.example/artifact/$id/tk/","expiresAt":9}"""
        val ticket = artifacts.ticket("a1", id, 1)
        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/api/v1/artifacts/$id/tickets", requests.single().url.encodedPath)
        assertEquals("https://team.example/artifact/$id/tk/", ticket.contentUrl)
        answers += HttpStatusCode.NotFound to """{"error":{"code":"artifact_not_found","message":"x"}}"""
        assertFailsWith<CompanionException.NotFound> { artifacts.artifact("a1", id) }
    }
}
