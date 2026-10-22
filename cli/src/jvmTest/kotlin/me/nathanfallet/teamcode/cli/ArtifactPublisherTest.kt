package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.artifacts.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import okio.ByteString.Companion.toByteString
import kotlin.test.*

class ArtifactPublisherTest {
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val upload = "1f8fad5b-d9cb-469f-a165-70867728950e"
    private fun sha(bytes: ByteArray) = bytes.toByteString().sha256().hex()

    @Test fun onlyMissingFilesAreSentInChunksAndKeptFilesStay(): Unit = runBlocking {
        val page = "<html><title>Report</title></html>".encodeToByteArray()
        val large = ByteArray(1024 * 1024 + 10) { 3 }
        val kept = "body{}".encodeToByteArray()
        val chunks = mutableListOf<Pair<String, Int>>()
        var commit: CommitArtifactVersionRequest? = null
        var announced: CreateArtifactUploadRequest? = null
        val client = TeamCodeClient("https://team.example", { "token" }) { configure ->
            HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()
                val json = when {
                    path == "/api/v1/artifacts/$id" -> Serialization.json.encodeToString(ArtifactView.serializer(), ArtifactView(id, "u", "t", "", "", "personal", "o", "owner", 4, 1, "owner", "api", createdAt = 1, updatedAt = 1))
                    path == "/api/v1/artifacts/$id/versions/4" -> Serialization.json.encodeToString(ArtifactVersionView.serializer(), ArtifactVersionView(id, 4, "index.html", "d", 3, 9, "o", 1, 3,
                        listOf(ArtifactFileView("index.html", "a".repeat(64), 3, "text/html", "page"), ArtifactFileView("site.css", sha(kept), kept.size.toLong(), "text/css", "asset"),
                            ArtifactFileView("old.js", "b".repeat(64), 2, "text/javascript", "asset"))))
                    path == "/api/v1/artifacts/uploads" -> {
                        announced = Serialization.json.decodeFromString(CreateArtifactUploadRequest.serializer(), body!!.decodeToString())
                        Serialization.json.encodeToString(ArtifactUploadView.serializer(), ArtifactUploadView(upload, id, listOf(sha(page), sha(large)), 1024 * 1024, 9))
                    }
                    path.startsWith("/api/v1/artifacts/uploads/$upload/blobs/") -> {
                        chunks += path.split('/')[7] to body!!.size
                        """{"complete":false,"received":1,"total":2}"""
                    }
                    path == "/api/v1/artifacts/$id/versions" -> {
                        commit = Serialization.json.decodeFromString(CommitArtifactVersionRequest.serializer(), body!!.decodeToString())
                        Serialization.json.encodeToString(ArtifactPublishedView.serializer(), ArtifactPublishedView(
                            ArtifactView(id, "https://team.example/artifact/$id", "Report", "", "", "personal", "o", "owner", 5, 1, "owner", "api", createdAt = 1, updatedAt = 2),
                            ArtifactVersionView(id, 5, "index.html", "d", 3, 9, "o", 2, 4)))
                    }
                    else -> error("Unexpected $path")
                }
                respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { configure(this) }
        }
        val result = ArtifactPublisher(client).publish(ArtifactPublication(ArtifactSource("index.html", "index.html", page), listOf(ArtifactSource("img/big.bin", "big.bin", large)),
            removed = setOf("old.js"), artifactId = id))
        assertEquals(setOf("index.html", "site.css", "img/big.bin"), commit!!.files.keys, "The base version's other files are kept, the removed one dropped")
        assertEquals(4, commit!!.baseVersion); assertNull(commit!!.title, "A new version keeps the artifact's title: the page's <title> is not sent")
        assertEquals(3, announced!!.files.size)
        assertEquals(listOf(sha(page) to page.size, sha(large) to 1024 * 1024, sha(large) to 10), chunks, "Missing files only, in chunks of 1 MiB")
        assertEquals(2, result.filesUploaded); assertEquals(1, result.filesReused)
        assertFailsWith<CliUserException> { ArtifactPublisher(client).publish(ArtifactPublication(ArtifactSource("site.css", "site.css", kept))) }
        assertFailsWith<CliUserException> { ArtifactPublisher(client).publish(ArtifactPublication(ArtifactSource("index.html", "index.html", page), removed = setOf("nope.js"), artifactId = id)) }
    }
}
