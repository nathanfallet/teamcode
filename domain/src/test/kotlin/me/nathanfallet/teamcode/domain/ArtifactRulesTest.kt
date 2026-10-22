package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.cloud.CloudConversationRules
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class ArtifactRulesTest {
    private fun sha(text: String) = ArtifactRules.sha256(text.encodeToByteArray())
    private fun file(text: String) = UploadFile(sha(text), text.encodeToByteArray().size.toLong())

    @Test fun manifestsKeepPortablePathsAPageEntryAndTheLimits() {
        val files = linkedMapOf("index.html" to file("<h1>Hi</h1>"), "css/site.css" to file("body{}"), "img/a.png" to file("png"), "about.htm" to file("x"))
        val manifest = ArtifactRules.validateManifest("index.html", files)
        assertEquals(listOf("about.htm", "css/site.css", "img/a.png", "index.html"), manifest.map { it.path })
        assertEquals(mapOf("about.htm" to "html", "css/site.css" to "asset", "img/a.png" to "asset", "index.html" to "page"), manifest.associate { it.path to it.role })
        assertEquals("text/html; charset=utf-8", manifest.single { it.path == "index.html" }.contentType)
        assertEquals("image/png", manifest.single { it.path == "img/a.png" }.contentType)
        assertEquals("application/octet-stream", ArtifactRules.contentType("tool.exe"), "Unknown types are never rendered")
        assertEquals("application/octet-stream", ArtifactRules.contentType("page.xhtml"), "Only .html and .htm are HTML")

        for (bad in listOf("../a.html", "/abs.html", ".hidden/a.css", "a//b.css", "CON.css", "a\\\\b.css", "dir/", "x.html."))
            assertEquals("invalid_artifact_path", assertFailsWith<TeamFailure> { ArtifactRules.validateManifest("index.html", files + (bad to file(bad))) }.code, bad)
        assertEquals("invalid_artifact_entry", assertFailsWith<TeamFailure> { ArtifactRules.validateManifest("css/site.css", files) }.code)
        assertEquals("invalid_artifact_entry", assertFailsWith<TeamFailure> { ArtifactRules.validateManifest("missing.html", files) }.code)
        assertEquals("invalid_artifact_manifest", assertFailsWith<TeamFailure> { ArtifactRules.validateManifest("index.html", files + ("INDEX.html" to file("y"))) }.code)
        assertEquals("invalid_artifact_manifest", assertFailsWith<TeamFailure> { ArtifactRules.validateManifest("index.html", files + ("css" to file("z"))) }.code)
        val many = (0..ArtifactRules.MAX_VERSION_FILES).associate { "f$it.txt" to file("$it") } + ("index.html" to file("p"))
        assertEquals("invalid_artifact_manifest", assertFailsWith<TeamFailure> { ArtifactRules.validateManifest("index.html", many) }.code)
        val big = mapOf("index.html" to UploadFile(sha("p"), ArtifactRules.MAX_TEXT_FILE_BYTES + 1))
        assertEquals("artifact_file_too_large", assertFailsWith<TeamFailure> { ArtifactRules.validateManifest("index.html", big) }.code)
        val heavy = (1..17).associate { "f$it.bin" to UploadFile(sha("$it"), ArtifactRules.MAX_TEXT_FILE_BYTES) } + ("index.html" to file("p"))
        assertEquals("artifact_version_too_large", assertFailsWith<TeamFailure> { ArtifactRules.validateManifest("index.html", heavy) }.code)
    }

    @Test fun theDigestIsCanonicalAndCoversPathsHashesAndSizes() {
        val a = ArtifactRules.validateManifest("index.html", mapOf("index.html" to file("a"), "b.css" to file("b")))
        val reordered = ArtifactRules.validateManifest("index.html", linkedMapOf("b.css" to file("b"), "index.html" to file("a")))
        assertEquals(ArtifactRules.digest(a), ArtifactRules.digest(reordered))
        val renamed = ArtifactRules.validateManifest("index.html", mapOf("index.html" to file("a"), "c.css" to file("b")))
        assertNotEquals(ArtifactRules.digest(a), ArtifactRules.digest(renamed))
        assertEquals(ArtifactRules.sha256("b.css\u0000${sha("b")}\u00001\nindex.html\u0000${sha("a")}\u00001\n".encodeToByteArray()), ArtifactRules.digest(a))
    }

    @Test fun chunksAreOneMebibyteWithAnEmptyFileAsOneEmptyChunk() {
        assertEquals(1, ArtifactRules.chunks(0)); assertEquals(0, ArtifactRules.chunkSize(0, 0))
        assertEquals(1, ArtifactRules.chunks(ArtifactRules.CHUNK_BYTES.toLong()))
        assertEquals(3, ArtifactRules.chunks(2L * ArtifactRules.CHUNK_BYTES + 5)); assertEquals(5, ArtifactRules.chunkSize(2L * ArtifactRules.CHUNK_BYTES + 5, 2))
        assertEquals(ArtifactRules.CHUNK_BYTES, ArtifactRules.chunkSize(2L * ArtifactRules.CHUNK_BYTES + 5, 1))
    }

    @Test fun blobsAreCheckedThenTextIsScannedForCredentialsWithoutKeepingThem() {
        val page = "<p>Report</p>".encodeToByteArray()
        assertNull(ArtifactRules.inspect(page, ArtifactRules.sha256(page), page.size.toLong()))
        assertEquals("artifact_blob_mismatch", assertFailsWith<TeamFailure> { ArtifactRules.inspect(page, sha("other"), page.size.toLong()) }.code)
        assertEquals("artifact_blob_mismatch", assertFailsWith<TeamFailure> { ArtifactRules.inspect(page, ArtifactRules.sha256(page), 3) }.code)
        val leaks = mapOf(
            "const key = 'sk-ant-api03-abcdefghijklmnop1234';" to "provider_api_key",
            "token ghp_abcdefghijklmnopqrstuvwxyz0123456789" to "github_token",
            "AKIAABCDEFGHIJKLMNOP" to "aws_access_key",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIE\n-----END RSA PRIVATE KEY-----" to "private_key",
            "fetch('https://user:hunter2hunter2@example.com/data')" to "url_credentials",
            "Authorization: Bearer abcDEF123456789xyz" to "authorization_header",
        )
        for ((text, kind) in leaks) {
            val bytes = "<html>$text</html>".encodeToByteArray()
            assertEquals(kind, ArtifactRules.inspect(bytes, ArtifactRules.sha256(bytes), bytes.size.toLong()), text)
        }
        // Ordinary code naming a secret, prose and binary content are not refused.
        for (text in listOf("const API_KEY = process.env.API_KEY_PLACEHOLDER;", "The token configuration is documented below.", "password: 'not-a-real-one'"))
            assertNull(CloudConversationRules.detect(text), text)
        val binary = byteArrayOf(0, 1, 2) + "sk-ant-api03-abcdefghijklmnop1234".encodeToByteArray()
        assertNull(ArtifactRules.inspect(binary, ArtifactRules.sha256(binary), binary.size.toLong()), "Binary content is not scanned")
        val large = ByteArray((ArtifactRules.MAX_BINARY_FILE_BYTES + 1).toInt()).also { it[0] = 0 }
        assertEquals("artifact_file_too_large", assertFailsWith<TeamFailure> { ArtifactRules.inspect(large, ArtifactRules.sha256(large), large.size.toLong()) }.code)
    }

    @Test fun announcementsAndDetailsAreBounded() {
        ArtifactRules.validateAnnouncement(listOf(file("a"), file("b")))
        assertEquals("invalid_artifact_upload", assertFailsWith<TeamFailure> { ArtifactRules.validateAnnouncement(listOf(file("a"), file("a"))) }.code)
        assertEquals("invalid_artifact_upload", assertFailsWith<TeamFailure> { ArtifactRules.validateAnnouncement(listOf(UploadFile("ABC", 1))) }.code)
        assertEquals("invalid_artifact_upload", assertFailsWith<TeamFailure> { ArtifactRules.validateAnnouncement(emptyList()) }.code)
        val sent = (1..65).map { UploadFile(sha("$it"), 1024L * 1024) }
        assertEquals("artifact_publication_too_large", assertFailsWith<TeamFailure> { ArtifactRules.validateSent(sent) }.code)
        ArtifactRules.validateDetails("Weekly report", "One sentence.", "chart")
        for ((title, description, icon) in listOf(Triple(" ", null, null), Triple("a\u0007", null, null), Triple(null, "x".repeat(301), null), Triple(null, null, "Chart"), Triple(null, null, "two words")))
            assertEquals("invalid_artifact_details", assertFailsWith<TeamFailure> { ArtifactRules.validateDetails(title, description, icon) }.code)
        assertEquals("report", ArtifactRules.defaultTitle("docs/report.html"))
    }

    @Test fun publicationsNeverMakePublicAndKeysNeverChangeAccessOrDelete() = runBlocking {
        val service = ArtifactService(stub(), stub(), stub())
        val owner = Principal(Member("owner", "owner", Role.MEMBER, false, false), "session", "team")
        val files = mapOf("index.html" to file("a"))
        val id = "00000000-0000-4000-8000-000000000001"
        assertEquals("artifact_public_interactive", assertFailsWith<TeamFailure> { service.commit(owner, id, id, "index.html", files, 0, null, null, null, "public", null) }.code)
        assertEquals("artifact_capabilities_unsupported", assertFailsWith<TeamFailure> { service.commit(owner, id, id, "index.html", files, 0, null, null, null, null, mapOf("db" to "on")) }.code)
        val key = owner.copy(serviceScopes = setOf(ServiceScope.ARTIFACTS_WRITE))
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { service.setAccess(key, id, "public", true, null) }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { service.delete(key, id) }.code)
        val reader = owner.copy(serviceScopes = setOf(ServiceScope.ARTIFACTS_READ))
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { service.startUpload(reader, null, listOf(file("a"))) }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { service.share(reader, id, "someone", "viewer") }.code)
        assertEquals("forbidden", assertFailsWith<TeamFailure> { service.list(owner, ArtifactQuery(ArtifactScope.ALL)) }.code, "Only administrators list every artifact")
        assertEquals("not_found", assertFailsWith<TeamFailure> { service.artifact(owner, "not-an-id") }.code)
    }

    /** An implementation that is never called: the checks above must refuse before any persistence. */
    private inline fun <reified T : Any> stub(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        if (method.name == "toString") "stub" else error("Unexpected call to ${method.name}.")
    } as T
}
