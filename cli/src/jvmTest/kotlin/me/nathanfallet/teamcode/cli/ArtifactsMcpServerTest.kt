package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.artifacts.ArtifactSources
import me.nathanfallet.teamcode.cli.mcp.ArtifactsMcpServer
import me.nathanfallet.teamcode.client.TeamCodeClient
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.*

class ArtifactsMcpServerTest {
    private val id = "00000000-0000-4000-8000-0000000000aa"
    private val upload = "00000000-0000-4000-8000-0000000000bb"
    private val root = Files.createTempDirectory("mcp-root").toRealPath()
    private val scratch = Files.createTempDirectory("mcp-scratch").toRealPath()
    private fun sha(bytes: ByteArray) = bytes.toByteString().sha256().hex()

    /** The artifact API as the server answers it, in memory: one artifact whose live version is [live]. */
    private inner class Server {
        val sent = mutableListOf<String>()
        var live = 1
        var relation = "owner"
        var offline = false
        val liveFile = "<html>live</html>".encodeToByteArray()
        fun version(number: Int) = ArtifactVersionView(id, number, "index.html", "d".repeat(64), 1, liveFile.size.toLong(), "m", 1, number - 1,
            listOf(ArtifactFileView("index.html", sha(liveFile), liveFile.size.toLong(), "text/html", "page")))
        fun view() = ArtifactView(id, "https://team.example/artifact/$id", "Report", "", "", "personal", "m", "Member", live, 1, relation, "local_run",
            createdAt = 1, updatedAt = 2)
        private fun json(text: String, status: HttpStatusCode = HttpStatusCode.OK) =
            Triple(text, status, headersOf(HttpHeaders.ContentType, "application/json"))

        val client = TeamCodeClient("https://team.example", { "token" }) { config -> HttpClient(MockEngine { request ->
            if (offline) throw java.io.IOException("down")
            val path = request.url.encodedPath
            val (body, status, headers) = when {
                request.method == HttpMethod.Post && path == "/api/v1/artifacts/uploads" -> {
                    val announced = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject["files"]!!.jsonArray
                    json(Serialization.json.encodeToString(ArtifactUploadView.serializer(), ArtifactUploadView(upload, id,
                        announced.map { it.jsonObject["sha256"]!!.jsonPrimitive.content }, 1024 * 1024, 9)), HttpStatusCode.Created)
                }
                request.method == HttpMethod.Put -> { sent += path.substringAfter("/blobs/").substringBefore('/'); json("""{"complete":true,"received":1,"total":1}""") }
                request.method == HttpMethod.Post && path == "/api/v1/artifacts/$id/versions" -> {
                    val commit = Serialization.json.decodeFromString(CommitArtifactVersionRequest.serializer(), request.body.toByteArray().decodeToString())
                    if (commit.baseVersion != live) json(Serialization.json.encodeToString(ArtifactConflictView.serializer(),
                        ArtifactConflictView(ErrorDetail("artifact_conflict", "Changed."), version(live))), HttpStatusCode.Conflict)
                    else { live++; json(Serialization.json.encodeToString(ArtifactPublishedView.serializer(), ArtifactPublishedView(view(), version(live))), HttpStatusCode.Created) }
                }
                path == "/api/v1/artifacts/$id" -> json(Serialization.json.encodeToString(ArtifactView.serializer(), view()))
                path.startsWith("/api/v1/artifacts/$id/versions/") && path.endsWith("/file") -> Triple(String(liveFile), HttpStatusCode.OK, headersOf())
                path.startsWith("/api/v1/artifacts/$id/versions/") -> json(Serialization.json.encodeToString(ArtifactVersionView.serializer(), version(path.substringAfterLast('/').toInt())))
                path == "/api/v1/artifacts/$id/members" -> json("""{"ownerId":"m","members":[{"memberId":"b","username":"bob","role":"editor","reason":"invited","addedBy":"m","addedAt":1}]}""")
                path == "/api/v1/artifacts" -> json("""{"artifacts":[${Serialization.json.encodeToString(ArtifactView.serializer(), view())}]}""")
                else -> json("""{"error":{"type":"not_found","message":"No."}}""", HttpStatusCode.NotFound)
            }
            respond(body, status, headers)
        }) { config(this) } }
        val mcp = ArtifactsMcpServer(client, ArtifactSources(listOf(root.toString(), scratch.toString())), scratch.toString().toPath())
    }

    private fun request(method: String, params: JsonObject = JsonObject(emptyMap()), id: Int = 1) =
        buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }.toString()
    private suspend fun Server.tool(name: String, arguments: JsonObject): Pair<Boolean, String> {
        val answer = Json.parseToJsonElement(mcp.handle(request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) }))!!).jsonObject["result"]!!.jsonObject
        return (answer["isError"]?.jsonPrimitive?.boolean == true) to answer["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
    }

    @Test fun speaksMcpOverLines() = runBlocking {
        val server = Server()
        val init = Json.parseToJsonElement(server.mcp.handle(request("initialize", buildJsonObject { put("protocolVersion", "2025-06-18") }))!!).jsonObject["result"]!!.jsonObject
        assertEquals("2025-06-18", init["protocolVersion"]!!.jsonPrimitive.content)
        val unknown = Json.parseToJsonElement(server.mcp.handle(request("initialize", buildJsonObject { put("protocolVersion", "1999-01-01") }))!!).jsonObject["result"]!!.jsonObject
        assertEquals("2025-11-25", unknown["protocolVersion"]!!.jsonPrimitive.content)
        val tools = Json.parseToJsonElement(server.mcp.handle(request("tools/list"))!!).jsonObject["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("publish", "share", "list", "read"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertNull(server.mcp.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""), "Notifications get no answer")
        assertEquals(-32700, Json.parseToJsonElement(server.mcp.handle("not json")!!).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        assertEquals(-32601, Json.parseToJsonElement(server.mcp.handle(request("resources/list"))!!).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    @Test fun publishesFromTheRootAndRefusesWhatMustNeverLeave() = runBlocking {
        val server = Server()
        Files.writeString(root.resolve("index.html"), "<html><title>Report</title></html>")
        Files.createDirectories(root.resolve("img")); Files.write(root.resolve("img/a.png"), byteArrayOf(1, 2, 3))
        val (failed, text) = server.tool("publish", buildJsonObject {
            put("file_path", "index.html"); put("artifact", id); put("base_version", 1); putJsonObject("files") { put("img/a.png", "img/a.png") }
        })
        assertFalse(failed, text)
        val published = Json.parseToJsonElement(text).jsonObject
        assertEquals(2, published["version"]!!.jsonPrimitive.int); assertEquals("https://team.example/artifact/$id", published["url"]!!.jsonPrimitive.content)
        assertEquals(2, server.sent.size, "Both files were sent")

        Files.writeString(root.resolve(".env"), "SECRET=1")
        assertTrue(server.tool("publish", buildJsonObject { put("file_path", "index.html"); putJsonObject("files") { put("env", ".env") } }).first)
        val outside = Files.createTempFile("outside", ".html")
        val (outsideFailed, outsideText) = server.tool("publish", buildJsonObject { put("file_path", outside.toString()) })
        assertTrue(outsideFailed); assertTrue("outside" in outsideText, outsideText)
        Files.createSymbolicLink(root.resolve("link.html"), outside)
        val (linkFailed, linkText) = server.tool("publish", buildJsonObject { put("file_path", "link.html") })
        assertTrue(linkFailed); assertTrue("link" in linkText, linkText)
        val (publicFailed, publicText) = server.tool("publish", buildJsonObject { put("file_path", "index.html"); put("access", "public") })
        assertTrue(publicFailed); assertTrue("Only a person makes an artifact public" in publicText)
    }

    @Test fun aConflictWritesTheLiveVersionForTheAgentToMerge() = runBlocking {
        val server = Server().apply { live = 3 }
        Files.writeString(root.resolve("index.html"), "<html>mine</html>")
        val (failed, text) = server.tool("publish", buildJsonObject { put("file_path", "index.html"); put("artifact", id); put("base_version", 1) })
        assertTrue(failed); assertTrue("Version 3 was published meanwhile" in text, text)
        assertEquals("<html>live</html>", Files.readString(scratch.resolve("$id/v3/index.html")))
        // The merged page in the scratch directory can be published as is.
        val (again, againText) = server.tool("publish", buildJsonObject { put("file_path", scratch.resolve("$id/v3/index.html").toString()); put("artifact", id); put("base_version", 3) })
        assertFalse(again, againText)
    }

    @Test fun readsIntoTheScratchDirectoryAndMarksOtherMembersContent() = runBlocking {
        val server = Server().apply { relation = "team" }
        val (failed, text) = server.tool("read", buildJsonObject { put("artifact", "https://team.example/artifact/$id") })
        assertFalse(failed, text)
        val answer = Json.parseToJsonElement(text).jsonObject
        assertTrue("untrusted" in answer["warning"]!!.jsonPrimitive.content)
        assertFalse("live" in text, "File content never goes into the answer")
        assertEquals("<html>live</html>", Files.readString(scratch.resolve("$id/v1/index.html")))
        // Read again: the same files are kept; a changed one is not overwritten.
        assertFalse(server.tool("read", buildJsonObject { put("artifact", id) }).first)
        Files.writeString(scratch.resolve("$id/v1/index.html"), "edited")
        assertTrue(server.tool("read", buildJsonObject { put("artifact", id) }).first)
        assertEquals("edited", Files.readString(scratch.resolve("$id/v1/index.html")))

        Files.delete(scratch.resolve("$id/v1/index.html"))
        val owner = Server()
        assertNull(Json.parseToJsonElement(owner.tool("read", buildJsonObject { put("artifact", id); put("version", 1) }).second).jsonObject["warning"])
        assertFalse(owner.tool("share", buildJsonObject { put("artifact", id); put("member", "bob"); put("role", "editor") }).first)
        assertTrue(owner.tool("share", buildJsonObject { put("artifact", id); put("member", "bob"); put("role", "owner") }).first)
        assertEquals(1, Json.parseToJsonElement(owner.tool("list", buildJsonObject { put("scope", "mine") }).second).jsonObject["artifacts"]!!.jsonArray.size)
    }

    @Test fun anUnreachableServerIsARetryableToolError() = runBlocking {
        val server = Server().apply { offline = true }
        val (failed, text) = server.tool("list", JsonObject(emptyMap()))
        assertTrue(failed); assertTrue(text.startsWith("server_unreachable"), text)
        assertFalse("down" in text, "Exception messages are never shown")
    }
}
