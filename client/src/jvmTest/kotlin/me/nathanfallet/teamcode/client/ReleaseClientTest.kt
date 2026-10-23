package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import kotlin.test.*

class ReleaseClientTest {
    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        ReleaseClient { configure -> HttpClient(MockEngine(handler)) { configure(this) } }
    private suspend fun failure(code: String, block: suspend () -> Unit) =
        assertEquals(code, assertFailsWith<ReleaseDownloadException> { block() }.code)

    @Test fun discoveryOnlyProvidesAHintAndDownloadsUseCanonicalCredentialFreePaths() = runBlocking {
        val calls = mutableListOf<String>()
        client { request ->
            calls += request.url.toString()
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])
            assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
            assertEquals("TeamCode release client", request.headers[HttpHeaders.UserAgent])
            when (request.url.host) {
                "api.github.com" -> {
                    assertEquals("application/vnd.github+json", request.headers[HttpHeaders.Accept])
                    respond("""{"draft":false,"prerelease":false,"tag_name":"1.2.3","assets":[{"browser_download_url":"https://attacker.example/secret"}]}""")
                }
                else -> {
                    assertEquals("github.com", request.url.host)
                    assertTrue(request.url.encodedPath.startsWith("/nathanfallet/teamcode/releases/download/1.2.3/"))
                    respond(if (request.url.encodedPath.endsWith("manifest.json")) " RAW MANIFEST BYTES " else " RAW SIGNATURE BYTES ")
                }
            }
        }.use { transport ->
            assertEquals("1.2.3", transport.latestVersion())
            val downloaded = transport.metadata("1.2.3")
            assertEquals(" RAW MANIFEST BYTES ", downloaded.manifest.decodeToString())
            assertEquals(" RAW SIGNATURE BYTES ", downloaded.signature.decodeToString())
            assertEquals(3, calls.size)
        }
    }

    @Test fun invalidDiscoveryAndDraftPrereleaseOrRedirectResultsCannotChooseAVersion() = runBlocking {
        for (body in listOf("{}", "not JSON", """{"draft":false,"prerelease":false,"tag_name":"v1.0.0"}""", """{"draft":true,"prerelease":false,"tag_name":"1.0.0"}""",
            """{"draft":false,"prerelease":true,"tag_name":"1.0.0-alpha"}""",
            """{"draft":false,"prerelease":false,"tag_name":"1.0.0-alpha"}""",
            """{"draft":false,"prerelease":false,"tag_name":"v../escape"}""",
            """{"draft":false,"prerelease":false,"tag_name":123}""")) {
            client { respond(body) }.use { transport -> failure("release_discovery_invalid") { transport.latestVersion() } }
        }
        client { respond(ByteReadChannel(byteArrayOf(0xff.toByte()))) }.use { transport -> failure("release_discovery_invalid") { transport.latestVersion() } }
        client { respond("", HttpStatusCode.NotFound) }.use { assertNull(it.latestVersion()) }
        client { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://api.github.com/elsewhere")) }.use { transport ->
            failure("release_redirect_invalid") { transport.latestVersion() }
        }
    }

    @Test fun githubCdnRedirectsAreBoundedAndNeverCarryAuthentication() = runBlocking {
        var requests = 0
        client { request ->
            requests++
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])
            if (request.url.host == "github.com") respond("", HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://release-assets.githubusercontent.com/assets/file?sig=PRIVATE_CDN_QUERY"))
            else respond("raw bytes")
        }.use { transport ->
            assertEquals("raw bytes", transport.metadata("1.0.0-alpha.1").manifest.decodeToString())
            assertEquals(4, requests)
        }
        requests = 0
        client { requests++; respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/loop")) }.use { transport ->
            failure("release_redirect_invalid") { transport.metadata("1.0.0") }
            assertEquals(6, requests)
        }
    }

    @Test fun redirectsCannotDowngradeOrLeaveTheReleaseOrigins() = runBlocking {
        for (address in listOf("http://github.com/file", "https://attacker.example/", "https://release-assets.githubusercontent.com.attacker.example/",
            "https://name:password@release-assets.githubusercontent.com/file", "https://github.com/elsewhere", "https://github.com:444/elsewhere",
            "//objects.githubusercontent.com/file", "https://objects.githubusercontent.com/file#fragment", "https://objects.githubusercontent.com/file\r\nsecret")) {
            var requests = 0
            client { requests++; respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, address)) }.use { transport ->
                failure("release_redirect_invalid") { transport.metadata("1.0.0") }
                assertEquals(1, requests, address)
            }
        }
    }

    @Test fun metadataBoundsApplyToHeadersAndStreamingBodiesWithoutTrustingContentLength() = runBlocking {
        for ((body, headers) in listOf(
            "x" to headersOf(HttpHeaders.ContentLength, "16385"),
            "x" to headersOf(HttpHeaders.ContentLength, "invalid"),
            "x" to headersOf(HttpHeaders.ContentLength, "-1"),
            "xx" to headersOf(HttpHeaders.ContentLength, "1"),
            "x".repeat(16385) to Headers.Empty,
            "x" to headersOf(HttpHeaders.ContentEncoding, "gzip"),
        )) client { respond(body, headers = headers) }.use { transport ->
            failure(if (headers[HttpHeaders.ContentEncoding] != null) "release_encoding_invalid" else "release_size_invalid") { transport.metadata("1.0.0") }
        }
        var calls = 0
        client { calls++; respond(if (calls == 1) "{}" else "x".repeat(2049)) }.use { transport ->
            failure("release_size_invalid") { transport.metadata("1.0.0") }
        }
        client { respond("x".repeat(256 * 1024 + 1)) }.use { transport -> failure("release_size_invalid") { transport.latestVersion() } }
    }

    @Test fun exactBinarySizeIsStreamedWithoutDeliveringOverflowBytes() = runBlocking {
        val received = mutableListOf<Byte>()
        client { request ->
            assertEquals("/nathanfallet/teamcode/releases/download/1.0.0/teamcode-1.0.0-windows-x64.exe", request.url.encodedPath)
            respond("binary")
        }.use { transport ->
            transport.binary("1.0.0", "windows-x64", 6) { data, size -> received.addAll(data.take(size)) }
            assertEquals("binary", received.toByteArray().decodeToString())
        }
        for (content in listOf("short", "too long")) {
            received.clear()
            client { respond(content) }.use { transport ->
                failure("release_size_invalid") { transport.binary("1.0.0", "linux-x64", 6) { data, size -> received.addAll(data.take(size)) } }
                assertTrue(received.size <= 6)
            }
        }
    }

    @Test fun invalidRequestsAndRemoteErrorsDoNotExposeBodiesOrCdnQueries() = runBlocking {
        var requests = 0
        client { requests++; respond("PRIVATE_RESPONSE_BODY", HttpStatusCode.Forbidden) }.use { transport ->
            for (version in listOf("../escape", "1.0.0?secret", "v1.0.0")) failure("release_version_invalid") { transport.metadata(version) }
            failure("release_asset_invalid") { transport.binary("1.0.0", "../escape", 1) { _, _ -> error("must not receive") } }
            failure("release_asset_invalid") { transport.binary("1.0.0", "linux-x64", 256L * 1024 * 1024 + 1) { _, _ -> error("must not receive") } }
            assertEquals(0, requests)
            val error = assertFailsWith<ReleaseDownloadException> { transport.metadata("1.0.0") }
            assertEquals("release_http_403", error.code)
            assertFalse(error.toString().contains("PRIVATE"))
        }
        client { throw IllegalStateException("PRIVATE_CDN_QUERY") }.use { transport ->
            val error = assertFailsWith<ReleaseDownloadException> { transport.latestVersion() }
            assertEquals("release_network_failure", error.code)
            assertFalse(error.toString().contains("PRIVATE"))
            assertNull(error.cause)
        }
    }

    @Test fun cancellingAnUpdateCheckCancelsWorkInsteadOfBecomingAConnectivityError() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        client { entered.complete(Unit); awaitCancellation() }.use { transport ->
            val operation = async { transport.latestVersion() }
            entered.await()
            operation.cancelAndJoin()
            assertTrue(operation.isCancelled)
            assertFailsWith<CancellationException> { operation.await() }
        }
    }

    @Test fun desktopMetadataPackagesAndInstallScriptsUseTheirCanonicalPinnedPaths() = runBlocking {
        val paths = mutableListOf<String>()
        client { request ->
            paths += request.url.encodedPath
            assertNull(request.headers[HttpHeaders.Authorization])
            respond(if (request.url.encodedPath.endsWith(".tar.gz")) "package" else "bytes of ${request.url.encodedPath.substringAfterLast('/')}")
        }.use { transport ->
            val metadata = transport.desktopMetadata("1.2.3")
            assertEquals("bytes of desktop-manifest.json", metadata.manifest.decodeToString())
            assertEquals("bytes of desktop-manifest.sig", metadata.signature.decodeToString())
            val received = mutableListOf<Byte>()
            transport.desktopPackage("1.2.3", "linux-x64", 7) { data, size -> received.addAll(data.take(size)) }
            assertEquals("package", received.toByteArray().decodeToString())
            assertEquals("bytes of install.sh", transport.installScript("1.2.3", "install.sh").decodeToString())
            assertEquals("bytes of install.ps1", transport.installScript("1.2.3", "install.ps1").decodeToString())
        }
        val base = "/nathanfallet/teamcode/releases/download/1.2.3/"
        assertEquals(listOf("desktop-manifest.json", "desktop-manifest.sig", "teamcode-desktop-1.2.3-linux-x64.tar.gz", "install.sh", "install.ps1").map { base + it }, paths)
    }

    @Test fun desktopDownloadsRefuseOtherNamesOversizedPackagesAndInexactBodies() = runBlocking {
        var requests = 0
        client { requests++; respond("x".repeat(256 * 1024 + 1)) }.use { transport ->
            failure("release_asset_invalid") { transport.installScript("1.2.3", "../install.sh") }
            failure("release_asset_invalid") { transport.installScript("1.2.3", "manifest.json") }
            failure("release_version_invalid") { transport.installScript("latest", "install.sh") }
            failure("release_asset_invalid") { transport.desktopPackage("1.2.3", "linux-x64", 512L * 1024 * 1024 + 1) { _, _ -> error("must not receive") } }
            failure("release_asset_invalid") { transport.desktopPackage("1.2.3", "../escape", 1) { _, _ -> error("must not receive") } }
            assertEquals(0, requests)
            failure("release_size_invalid") { transport.installScript("1.2.3", "install.sh") }
        }
        client { respond("one byte more") }.use { transport ->
            failure("release_size_invalid") { transport.desktopPackage("1.2.3", "macos-arm64", 12) { _, _ -> } }
        }
    }
}
