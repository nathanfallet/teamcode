package me.nathanfallet.teamcode.cli.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.*

/** `teamcode cloud attach` with files: local refusals, the policy, uploads and the message that carries them, against a fake server. */
class AttachFilesTest {
    private val session = "00000000-0000-4000-8000-0000000000aa"
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(250 * 1024) { (it % 7).toByte() }
    private val directory: Path = Files.createTempDirectory("attach-files").toRealPath()

    /** The fake server's state and what it saw. */
    private inner class Server(
        var features: List<String> = listOf(CloudFiles.FEATURE, "cloud.messages.v1"),
        var available: Boolean = true,
        var maxFileBytes: Long = 20L * 1024 * 1024,
        var types: List<String> = listOf("image/png", "image/jpeg", "application/pdf", "text/plain", "text/markdown"),
        var uploadError: Pair<HttpStatusCode, String>? = null,
        var messageError: Pair<HttpStatusCode, String>? = null,
    ) {
        val requests = mutableListOf<String>()
        val uploads = mutableListOf<String>()
        val messages = mutableListOf<JsonObject>()
        private var nextFile = 0

        val client = TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                requests += "${request.method.value} $path"
                fun refuse(error: Pair<HttpStatusCode, String>, headers: Headers = json) = respond("""{"error":{"type":"${error.second}","message":"No."}}""", error.first, headers)
                when {
                    path == "/api/v1/capabilities" -> respond(buildJsonObject {
                        put("serverVersion", "1.0.0"); put("mode", "team"); putJsonArray("features") { features.forEach { add(it) } }
                    }.toString(), HttpStatusCode.OK, json)
                    path == "/api/v1/cloud/files/policy" -> respond(buildJsonObject {
                        put("available", available); if (!available) put("reason", CloudFiles.STORAGE_NOT_CONFIGURED)
                        put("maxFileBytes", maxFileBytes); put("maxMessageBytes", 25L * 1024 * 1024); put("maxFilesPerMessage", 5)
                        putJsonArray("types") { types.forEach { add(it) } }
                    }.toString(), HttpStatusCode.OK, json)
                    path == "/api/v1/cloud/sessions/$session/files" -> {
                        val name = request.url.parameters["name"]!!
                        uploads += name
                        val bytes = request.body.toByteArray()
                        uploadError?.let { return@MockEngine refuse(it, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("7200"))) }
                        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        respond("""{"id":"f${++nextFile}","sessionId":"$session","name":"$name","size":${bytes.size},"contentType":"image/png","sha256":"$sha","createdAt":1,"expiresAt":2}""", HttpStatusCode.Created, json)
                    }
                    path == "/api/v1/cloud/sessions/$session/messages" -> {
                        messages += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        messageError?.let { return@MockEngine refuse(it) }
                        respond("""{"id":"i${messages.size}","kind":"message","status":"queued","authorId":"m1","createdAt":1}""", HttpStatusCode.Accepted, json)
                    }
                    else -> respond("", HttpStatusCode.NotFound)
                }
            }) { configure(this) }
        }
    }

    private class Attach(val server: AttachFilesTest.Server, home: String?) {
        val said = mutableListOf<String>()
        val files = AttachFiles(server.client, "00000000-0000-4000-8000-0000000000aa", home = home)
        val requests = AttachRequests()
        suspend fun type(line: String, canSend: Boolean = true): Boolean = attachLine(line, canSend, requests, files, { said += it },
            interrupt = { said += "interrupted" }, answer = { said += "answered" },
            send = { text ->
                val ids = files.pending.ids
                try { server.client.sendCloudMessage("00000000-0000-4000-8000-0000000000aa", SendCloudMessageRequest(text, fileIds = ids)); if (ids.isNotEmpty()) files.sent() }
                catch (error: me.nathanfallet.teamcode.client.TeamCodeException) { said += files.refused(error) }
            })
    }

    private fun file(name: String, bytes: ByteArray = png): Path = directory.resolve(name).also { Files.write(it, bytes) }
    private fun attach(server: Server = Server()) = Attach(server, home = directory.toString())

    @Test fun localRefusalsMakeNoRequest() = runTest {
        val server = Server()
        val attach = attach(server)
        Files.createDirectories(directory.resolve("folder"))
        val target = file("target.png")
        Files.createSymbolicLink(directory.resolve("link.png"), target)
        attach.type("/attach ~/folder")
        attach.type("/attach ${directory.resolve("link.png")}")
        attach.type("/attach ~/missing.png")
        attach.type("/attach ~")
        assertEquals(listOf(
            "~/folder is a directory; attach its files one by one.",
            "${directory.resolve("link.png")} is a symbolic link; attach the file it points to by its own path.",
            "No file at ~/missing.png.",
            "~ is a directory; attach its files one by one.",
        ), attach.said)
        // Without a home directory, ~ cannot be expanded.
        val homeless = Attach(server, home = null)
        homeless.type("/attach ~/target.png")
        assertEquals("No home directory to expand ~ in ~/target.png.", homeless.said.single())
        assertEquals(emptyList(), server.requests, "Refused before any request")
    }

    @Test fun aReadOnlyAttachmentNeverUploads() = runTest {
        val server = Server()
        val attach = attach(server)
        file("a.png")
        attach.type("/attach ~/a.png", canSend = false)
        attach.type("~/a.png", canSend = false)
        assertEquals(listOf("This attachment is read-only.", "This attachment is read-only."), attach.said)
        assertEquals(emptyList(), server.requests)
        // Detaching still works.
        assertFalse(attach.type("/detach", canSend = false))
    }

    @Test fun aServerWithoutFilesOrABucketIsExplainedWithoutUploading() = runTest {
        file("a.png")
        val old = Server(features = listOf("cloud.messages.v1"))
        attach(old).run {
            type("/attach ~/a.png"); type("/attach ~/a.png")
            assertEquals("This server does not take files (it lacks cloud.files.v1); it needs an update first.", said.first())
        }
        assertEquals(listOf("GET /api/v1/capabilities"), old.requests, "The capability is read once")
        val unconfigured = Server(available = false)
        attach(unconfigured).run {
            type("/attach ~/a.png")
            assertEquals("Files are off for this team: an administrator has to configure and test the team's artifact storage first.", said.single())
        }
        assertEquals(emptyList(), unconfigured.uploads)
    }

    @Test fun sizeAndTypeAreCheckedAgainstThePolicyBeforeUploading() = runTest {
        val server = Server(maxFileBytes = 100 * 1024, types = listOf("image/png", "text/plain"))
        val attach = attach(server)
        file("a.png"); file("doc.pdf", "%PDF-1.7".encodeToByteArray()); file("empty.txt", ByteArray(0)); file("blob.bin", byteArrayOf(1, 0, 2))
        attach.type("/attach ~/a.png")
        attach.type("/attach ~/doc.pdf")
        attach.type("/attach ~/empty.txt")
        attach.type("/attach ~/blob.bin")
        assertEquals(listOf(
            "~/a.png is 250 KB; the team allows 100 KB per file.",
            "~/doc.pdf is not a type the team takes (PNG, text).",
            "~/empty.txt is empty.",
            "~/blob.bin is not a type the team takes (PNG, text).",
        ), attach.said)
        assertEquals(emptyList(), server.uploads)
    }

    @Test fun attachedFilesAreListedAndCarriedByTheNextMessage() = runTest {
        val server = Server()
        val attach = attach(server)
        file("a.png"); file("notes.log", "plain text\n".encodeToByteArray())
        attach.type("/attach ~/a.png")
        attach.type("/file ~/notes.log")
        assertEquals("Uploaded ~/a.png (250 KB).", attach.said[0])
        assertEquals("Attached to your next message: a.png (250 KB). /unattach drops it.", attach.said[1])
        assertEquals("Attached to your next message: a.png (250 KB), notes.log (11 B). /unattach drops them.", attach.said.last())
        attach.type("/files")
        assertEquals(attach.said[attach.said.size - 2], attach.said.last())
        attach.type("what is in these?")
        assertEquals(listOf("a.png", "notes.log"), server.uploads)
        val message = server.messages.single()
        assertEquals("what is in these?", message["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("f1", "f2"), message["fileIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Sent: the next message carries none.
        attach.type("thanks")
        assertNull(server.messages.last()["fileIds"]?.jsonArray?.takeIf { it.isNotEmpty() })
        attach.type("/files")
        assertEquals("No file waits for the next message.", attach.said.last())
    }

    @Test fun aPathFollowedByAMessageSendsBothAtOnceAndUnattachDropsTheQueue() = runTest {
        val server = Server()
        val attach = attach(server)
        file("my shot.png")
        attach.type("/attach \"~/my shot.png\" \"what is wrong here?\"")
        assertEquals("what is wrong here?", server.messages.single()["text"]!!.jsonPrimitive.content)
        assertEquals(listOf("f1"), server.messages.single()["fileIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        attach.type("/attach ~/my\\ shot.png")
        attach.type("/unattach")
        assertEquals("Dropped 1 file; the server deletes it within 24 hours.", attach.said.last())
        attach.type("plain")
        assertEquals(1, server.messages.size - 1)
        assertNull(server.messages.last()["fileIds"]?.jsonArray?.takeIf { it.isNotEmpty() })
    }

    @Test fun aPastedPathAsksAndNeverUploadsByItself() = runTest {
        val server = Server()
        val attach = attach(server)
        val path = file("dropped.png").toString()
        attach.type(path)
        assertEquals("Attach $path to your next message? (y/N)", attach.said.last())
        attach.type("")
        assertEquals("Not attached.", attach.said.last())
        assertEquals(emptyList(), server.uploads); assertEquals(emptyList(), server.messages)
        attach.type("'$path'")
        attach.type("y")
        assertEquals(listOf("dropped.png"), server.uploads)
        assertEquals(emptyList(), server.messages, "Accepting attaches; it sends nothing")
        // A line other than yes or no is not taken as an answer: it is not attached and the line goes out as a message.
        attach.type(path)
        attach.type("never mind")
        assertEquals(1, server.uploads.size)
        assertEquals("never mind", server.messages.single()["text"]!!.jsonPrimitive.content)
        // A path to a file that does not exist is just a message.
        attach.type("$directory/nothing.png")
        assertEquals("$directory/nothing.png", server.messages.last()["text"]!!.jsonPrimitive.content)
    }

    @Test fun serverRefusalsAreExplained() = runTest {
        file("a.png")
        val cases = listOf(
            (HttpStatusCode.PayloadTooLarge to CloudFiles.FILE_TOO_LARGE) to "~/a.png is larger than the 20.0 MB the team allows per file.",
            (HttpStatusCode.UnsupportedMediaType to CloudFiles.FILE_TYPE_NOT_ALLOWED) to "~/a.png is not a type the team takes (PNG, JPEG, PDF, text, Markdown); its content decides, not its name.",
            (HttpStatusCode.TooManyRequests to CloudFiles.UPLOAD_QUOTA) to "Your daily upload quota is used up; try again in 2 h.",
            (HttpStatusCode.Conflict to CloudFiles.STORAGE_NOT_CONFIGURED) to "Files are off for this team: an administrator has to configure and test the team's artifact storage first.",
            (HttpStatusCode.Forbidden to "cloud_permission_denied") to "You may not send files to this session.",
        )
        for ((error, expected) in cases) {
            val server = Server(uploadError = error)
            val attach = attach(server)
            attach.type("/attach ~/a.png \"look\"")
            assertEquals(expected, attach.said.last(), error.second)
            assertEquals(1, server.uploads.size, "${error.second} is not retried")
            assertEquals(emptyList(), server.messages, "A refused file sends no message")
        }
        // A message whose file is gone empties the queue.
        val server = Server(messageError = HttpStatusCode.Conflict to CloudFiles.FILE_UNAVAILABLE)
        val attach = attach(server)
        attach.type("/attach ~/a.png")
        attach.type("look")
        assertEquals("Message refused: an attached file is no longer available (sent already or expired after 24 hours). Attach it again.", attach.said.last())
        assertTrue(attach.files.pending.isEmpty())
    }

    @Test fun aSixthFileIsRefusedLocally() = runTest {
        val server = Server()
        val attach = attach(server)
        file("a.png")
        repeat(6) { attach.type("/attach ~/a.png") }
        assertEquals(5, server.uploads.size)
        assertEquals("A message carries 5 files at most: send the message first, or /unattach.", attach.said.last())
    }
}
