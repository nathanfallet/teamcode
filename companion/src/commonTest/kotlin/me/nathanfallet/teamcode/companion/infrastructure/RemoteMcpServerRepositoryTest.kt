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
import me.nathanfallet.teamcode.companion.domain.models.McpStatus
import me.nathanfallet.teamcode.companion.domain.models.McpTransport
import me.nathanfallet.teamcode.companion.infrastructure.client.TeamCodeClientProvider
import me.nathanfallet.teamcode.companion.infrastructure.repositories.AccountCalls
import me.nathanfallet.teamcode.companion.infrastructure.repositories.RemoteMcpServerRepository
import kotlin.test.*

/** The MCP servers over a scripted server: one request to the CLI's route, and what is mapped. */
class RemoteMcpServerRepositoryTest {
    private val requests = mutableListOf<HttpRequestData>()
    private var body = "[]"
    private val engine = object : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit) = MockEngine(MockEngineConfig().apply {
            block()
            addHandler { request ->
                requests += request
                respond(ByteReadChannel(body), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
    }
    private val repository = RemoteMcpServerRepository(AccountCalls(TeamCodeClientProvider(engine, credentials(), FakePlatform), InMemoryAccountRepository(listOf(account("a1")))))

    @Test fun theServersAreReadInOneRequestAndMapped() = runTest {
        body = """[
            {"name":"github","path":"/mcp/github","transport":"stdio","state":"failed","failure":"memory"},
            {"name":"docs","path":"/mcp/docs","transport":"http"},
            {"name":"old","path":"/mcp/old"}
        ]"""
        val servers = repository.servers("a1")
        assertEquals("/api/v1/mcp/servers", requests.single().url.encodedPath)
        assertEquals(HttpMethod.Get, requests.single().method)
        assertEquals(listOf(McpTransport.STDIO, McpTransport.HTTP, McpTransport.UNKNOWN), servers.map { it.transport })
        assertEquals("memory", servers[0].failure)
        assertTrue(servers[0].status is McpStatus.Unavailable)
        assertEquals(McpStatus.Untracked, servers[1].status)
        assertEquals(McpStatus.Untracked, servers[2].status)
    }
}
