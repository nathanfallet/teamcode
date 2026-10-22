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

    @Test fun theArtifactToolsStartOnlyWhenTheExecutionsOwnerMayPublish() = runBlocking {
        fun tools(answer: Pair<HttpStatusCode, String>, binary: String? = "/opt/teamcode/teamcode") =
            RunnerAgentTools(client { path -> if (path == "/api/v1/artifacts/policy") answer else HttpStatusCode.NotFound to "{}" }, { throw IllegalStateException("no container") },
                binary = binary, workspace = "/workspace/")
        val started = assertNotNull(tools(HttpStatusCode.OK to """{"canPublish":true}""").artifacts())
        assertEquals("/opt/teamcode/teamcode", started.command); assertEquals("/workspace", started.root)
        assertEquals("/tmp/teamcode-artifacts", started.scratch); assertEquals(listOf("/workspace/.home", "/workspace/.agent-home"), started.refused, "Neither the runner's home nor the agent's is published")
        for (reason in listOf("artifact_storage_missing", "artifact_storage_unverified", "artifacts_disabled", "artifact_publish_forbidden"))
            assertNull(tools(HttpStatusCode.OK to """{"canPublish":false,"reason":"$reason"}""").artifacts(), reason)
        assertNull(tools(HttpStatusCode.NotFound to "{}").artifacts(), "An older server without the policy")
        assertNull(tools(HttpStatusCode.OK to """{"canPublish":true}""", binary = null).artifacts())
        val elsewhere = RunnerAgentTools(client { path -> if (path == "/api/v1/artifacts/policy") HttpStatusCode.OK to """{"canPublish":true}""" else HttpStatusCode.NotFound to "{}" },
            { throw IllegalStateException("no container") }, binary = "/opt/teamcode/teamcode", home = "/workspace/agent-home/").artifacts()
        assertEquals(listOf("/workspace/.home", "/workspace/.agent-home", "/workspace/agent-home"), elsewhere?.refused, "A home elsewhere is refused too")
    }
}
