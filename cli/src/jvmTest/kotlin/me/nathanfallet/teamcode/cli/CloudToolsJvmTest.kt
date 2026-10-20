package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.cli.cloud.agent.RunnerAgentTools
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

class CloudToolsJvmTest {
    private fun client(handler: (String) -> Pair<HttpStatusCode, String>) = TeamCodeClient("https://team.example", { "tcex_x" }) { configure ->
        HttpClient(MockEngine { request -> val (status, body) = handler(request.url.encodedPath); respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }) { configure(this) }
    }

    @Test fun anAgentStartsEvenWhenToolsCannotBePrepared() = runBlocking {
        val tools = RunnerAgentTools(client { HttpStatusCode.ServiceUnavailable to "{}" }, { throw IllegalStateException("no container") })
        assertEquals(emptyList(), tools.prepare("claude"))
    }

    @Test fun serversAreListedWithTheExecutionCredentialAndBadNamesDropped() = runBlocking {
        val paths = mutableListOf<String>()
        val tools = RunnerAgentTools(client { path -> paths += path
            if (path == "/api/v1/mcp/servers") HttpStatusCode.OK to """[{"name":"tracker","path":"/mcp/tracker"},{"name":"Bad Name","path":"/x"},{"name":"tracker","path":"/mcp/tracker"}]"""
            else HttpStatusCode.Forbidden to "{}" }, { throw IllegalStateException("no container") })
        assertEquals(listOf("tracker"), tools.prepare("codex"))
        assertTrue("/api/v1/skill-delivery" in paths, "Skills are looked up too: $paths")
    }
}
