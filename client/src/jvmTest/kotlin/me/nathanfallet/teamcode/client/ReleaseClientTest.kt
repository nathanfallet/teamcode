package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpResponseData
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writer
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
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
            assertEquals("api.github.com", request.url.host)
            when (request.url.encodedPath) {
                "/repos/nathanfallet/teamcode/releases/latest" -> {
                    assertEquals("application/vnd.github+json", request.headers[HttpHeaders.Accept])
                    // Only the asset ids are read from the JSON, never a download URL it names.
                    respond("""{"draft":false,"prerelease":false,"tag_name":"1.2.3","assets":[{"id":7,"name":"manifest.json","browser_download_url":"https://attacker.example/secret"},{"id":8,"name":"manifest.sig"}]}""")
                }
                else -> {
                    assertEquals("application/octet-stream", request.headers[HttpHeaders.Accept])
                    respond(if (request.url.encodedPath.endsWith("/assets/7")) " RAW MANIFEST BYTES " else " RAW SIGNATURE BYTES ")
                }
            }
        }.use { transport ->
            assertEquals("1.2.3", transport.latestVersion())
            val downloaded = transport.metadata("1.2.3")
            assertEquals(" RAW MANIFEST BYTES ", downloaded.manifest.decodeToString())
            assertEquals(" RAW SIGNATURE BYTES ", downloaded.signature.decodeToString())
            // The latest release's JSON already listed its assets: no second lookup.
            assertEquals(listOf("latest", "assets/7", "assets/8").map { "https://api.github.com/repos/nathanfallet/teamcode/releases/$it" }, calls)
        }
    }

    @Test fun anExplicitVersionIsLookedUpByItsExactTagOnce() = runBlocking {
        val paths = mutableListOf<String>()
        client(releaseServer("1.0.0-alpha.1", listOf("manifest.json", "manifest.sig")) { request -> paths += request.url.encodedPath; respond("bytes") }).use { transport ->
            transport.metadata("1.0.0-alpha.1")
            transport.metadata("1.0.0-alpha.1")
            assertEquals(4, paths.size)
        }
        for (body in listOf("""{"draft":false,"tag_name":"1.0.1","assets":[]}""", """{"draft":true,"tag_name":"1.0.0","assets":[]}""",
            """{"draft":false,"tag_name":"1.0.0"}""", """{"draft":false,"tag_name":"1.0.0","assets":[{"id":"1","name":"manifest.json"}]}""", "not JSON")) {
            client { respond(body) }.use { transport -> failure("release_discovery_invalid") { transport.metadata("1.0.0") } }
        }
        client { respond("", HttpStatusCode.NotFound) }.use { transport -> failure("release_http_404") { transport.metadata("1.0.0") } }
        client(releaseServer("1.0.0", listOf("manifest.json")) { respond("bytes") }).use { transport -> failure("release_http_404") { transport.metadata("1.0.0") } }
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
        client(releaseServer("1.0.0-alpha.1", listOf("manifest.json", "manifest.sig")) { request ->
            requests++
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])
            if (request.url.host == "api.github.com") respond("", HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "https://release-assets.githubusercontent.com/assets/file?sig=PRIVATE_CDN_QUERY"))
            else {
                // GitHub's REST headers stay on api.github.com.
                assertNull(request.headers["X-GitHub-Api-Version"])
                respond("raw bytes")
            }
        }).use { transport ->
            assertEquals("raw bytes", transport.metadata("1.0.0-alpha.1").manifest.decodeToString())
            assertEquals(4, requests)
        }
        requests = 0
        client(releaseServer("1.0.0", listOf("manifest.json", "manifest.sig")) { requests++; respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/loop")) }).use { transport ->
            failure("release_redirect_invalid") { transport.metadata("1.0.0") }
            assertEquals(6, requests)
        }
    }

    @Test fun redirectsCannotDowngradeOrLeaveTheReleaseOrigins() = runBlocking {
        for (address in listOf("http://release-assets.githubusercontent.com/file", "https://attacker.example/", "https://release-assets.githubusercontent.com.attacker.example/",
            "https://name:password@release-assets.githubusercontent.com/file", "https://github.com/elsewhere", "https://api.github.com/elsewhere",
            "https://release-assets.githubusercontent.com:444/file",
            "//objects.githubusercontent.com/file", "https://objects.githubusercontent.com/file#fragment", "https://objects.githubusercontent.com/file\r\nsecret")) {
            var requests = 0
            client(releaseServer("1.0.0", listOf("manifest.json", "manifest.sig")) { requests++; respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, address)) }).use { transport ->
                failure("release_redirect_invalid") { transport.metadata("1.0.0") }
                assertEquals(1, requests, address)
            }
        }
        // A release lookup is never redirected.
        client { respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://release-assets.githubusercontent.com/file")) }.use { transport ->
            failure("release_redirect_invalid") { transport.metadata("1.0.0") }
        }
    }

    @Test fun metadataBoundsApplyToHeadersAndStreamingBodiesWithoutTrustingContentLength() = runBlocking {
        val names = listOf("manifest.json", "manifest.sig")
        for ((body, headers) in listOf(
            "x" to headersOf(HttpHeaders.ContentLength, "16385"),
            "x" to headersOf(HttpHeaders.ContentLength, "invalid"),
            "x" to headersOf(HttpHeaders.ContentLength, "-1"),
            "xx" to headersOf(HttpHeaders.ContentLength, "1"),
            "x".repeat(16385) to Headers.Empty,
            "x" to headersOf(HttpHeaders.ContentEncoding, "gzip"),
        )) client(releaseServer("1.0.0", names) { respond(body, headers = headers) }).use { transport ->
            failure(if (headers[HttpHeaders.ContentEncoding] != null) "release_encoding_invalid" else "release_size_invalid") { transport.metadata("1.0.0") }
        }
        var calls = 0
        client(releaseServer("1.0.0", names) { calls++; respond(if (calls == 1) "{}" else "x".repeat(2049)) }).use { transport ->
            failure("release_size_invalid") { transport.metadata("1.0.0") }
        }
        client { respond("x".repeat(1024 * 1024 + 1)) }.use { transport -> failure("release_size_invalid") { transport.latestVersion() } }
    }

    @Test fun exactBinarySizeIsStreamedWithoutDeliveringOverflowBytes() = runBlocking {
        val received = mutableListOf<Byte>()
        client(releaseServer("1.0.0", listOf("teamcode-1.0.0-linux-x64", "teamcode-1.0.0-windows-x64.exe")) { request ->
            assertEquals("/repos/nathanfallet/teamcode/releases/assets/2", request.url.encodedPath)
            respond("binary")
        }).use { transport ->
            transport.binary("1.0.0", "windows-x64", 6) { data, size -> received.addAll(data.take(size)) }
            assertEquals("binary", received.toByteArray().decodeToString())
        }
        for (content in listOf("short", "too long")) {
            received.clear()
            client(releaseServer("1.0.0", listOf("teamcode-1.0.0-linux-x64")) { respond(content) }).use { transport ->
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
            assertNull(error.detail)
        }
        client { throw IllegalStateException("Failed for https://release-assets.githubusercontent.com/a?sig=PRIVATE_CDN_QUERY") }.use { transport ->
            val error = assertFailsWith<ReleaseDownloadException> { transport.latestVersion() }
            assertEquals("release_response_invalid", error.code)
            assertFalse(error.toString().contains("PRIVATE"))
            assertEquals("IllegalStateException: Failed for https://release-assets.githubusercontent.com/…", error.detail)
            assertNull(error.cause)
        }
    }

    @Test fun transportFailuresKeepTheirFamilyAndAShortCause() = runBlocking {
        for ((thrown, code) in listOf(
            java.net.UnknownHostException("api.github.com: Name or service not known") to "release_dns_failure",
            javax.net.ssl.SSLHandshakeException("PKIX path building failed") to "release_tls_failure",
            java.net.ConnectException("Connection refused") to "release_connection_failure",
            java.net.SocketTimeoutException("Read timed out") to "release_timeout",
            CurlIllegalStateException("Connection failed for request. Reason: Received HTTP code 407 from proxy after CONNECT") to "release_proxy_failure",
            io.ktor.utils.io.ClosedByteChannelException(null) to "release_connection_closed",
            CurlIllegalStateException("Connection failed for request. Reason: Send failure") to "release_network_failure",
            IllegalStateException("Header line length limit exceeded") to "release_response_invalid",
            UnsupportedOperationException("unexpected") to "release_unexpected_failure",
        )) client { throw thrown }.use { transport ->
            val error = assertFailsWith<ReleaseDownloadException> { transport.latestVersion() }
            assertEquals(code, error.code, thrown.toString())
            assertEquals(thrown.message?.let { "${thrown::class.simpleName}: $it" } ?: thrown::class.simpleName, error.detail)
        }
        client { respond("", HttpStatusCode.Forbidden, headersOf("x-ratelimit-remaining" to listOf("0"), "x-ratelimit-reset" to listOf("${System.currentTimeMillis() / 1000 + 600}"))) }.use { transport ->
            val error = assertFailsWith<ReleaseDownloadException> { transport.latestVersion() }
            assertEquals("release_rate_limited", error.code)
            assertEquals("GitHub API rate limit reached, it resets in about 10 minutes", error.detail)
        }
        client { respond("", HttpStatusCode.TooManyRequests, headersOf("x-ratelimit-remaining", "0")) }.use { transport -> failure("release_rate_limited") { transport.latestVersion() } }
        assertTrue(releaseFailureDetail(IllegalStateException("x".repeat(500))).length <= 200)
        assertEquals("DarwinHttpRequestException: Error Domain=NSURLErrorDomain Code=-1003 \"A server with the specified hostname could not be found.\"",
            releaseFailureDetail(DarwinHttpRequestException("Error Domain=NSURLErrorDomain Code=-1003 \"A server with the specified hostname could not be found.\" UserInfo={NSErrorFailingURLKey=https://release-assets.githubusercontent.com/a?sig=SECRET}")))
    }

    private class DarwinHttpRequestException(message: String) : Exception(message)
    private class CurlIllegalStateException(message: String) : IllegalStateException(message)

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
        val names = listOf("desktop-manifest.json", "desktop-manifest.sig", "teamcode-desktop-1.2.3-linux-x64.tar.gz", "install.sh", "install.ps1")
        val paths = mutableListOf<String>()
        client(releaseServer("1.2.3", names) { request ->
            paths += request.url.encodedPath
            assertNull(request.headers[HttpHeaders.Authorization])
            val name = names[request.url.encodedPath.substringAfterLast('/').toInt() - 1]
            respond(if (name.endsWith(".tar.gz")) "package" else "bytes of $name")
        }).use { transport ->
            val metadata = transport.desktopMetadata("1.2.3")
            assertEquals("bytes of desktop-manifest.json", metadata.manifest.decodeToString())
            assertEquals("bytes of desktop-manifest.sig", metadata.signature.decodeToString())
            val received = mutableListOf<Byte>()
            transport.desktopPackage("1.2.3", "linux-x64", 7) { data, size -> received.addAll(data.take(size)) }
            assertEquals("package", received.toByteArray().decodeToString())
            assertEquals("bytes of install.sh", transport.installScript("1.2.3", "install.sh").decodeToString())
            assertEquals("bytes of install.ps1", transport.installScript("1.2.3", "install.ps1").decodeToString())
        }
        assertEquals((1..5).map { "/repos/nathanfallet/teamcode/releases/assets/$it" }, paths)
    }

    @Test fun desktopDownloadsRefuseOtherNamesOversizedPackagesAndInexactBodies() = runBlocking {
        var requests = 0
        client(releaseServer("1.2.3", listOf("install.sh")) { requests++; respond("x".repeat(256 * 1024 + 1)) }).use { transport ->
            failure("release_asset_invalid") { transport.installScript("1.2.3", "../install.sh") }
            failure("release_asset_invalid") { transport.installScript("1.2.3", "manifest.json") }
            failure("release_version_invalid") { transport.installScript("latest", "install.sh") }
            failure("release_asset_invalid") { transport.desktopPackage("1.2.3", "linux-x64", 512L * 1024 * 1024 + 1) { _, _ -> error("must not receive") } }
            failure("release_asset_invalid") { transport.desktopPackage("1.2.3", "../escape", 1) { _, _ -> error("must not receive") } }
            assertEquals(0, requests)
            failure("release_size_invalid") { transport.installScript("1.2.3", "install.sh") }
        }
        client(releaseServer("1.2.3", listOf("teamcode-desktop-1.2.3-macos-arm64.dmg", "teamcode-desktop-1.2.3-macos-arm64.zip")) { respond("one byte more") }).use { transport ->
            failure("release_size_invalid") { transport.desktopPackage("1.2.3", "macos-arm64", 12) { _, _ -> } }
        }
    }

    /** A release lookup (`/releases/tags/<version>`) listing [names] as assets 1, 2, 3…, then [asset] for every other request. */
    private fun releaseServer(version: String, names: List<String>, asset: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData):
        suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
        if (request.url.encodedPath.endsWith("/releases/tags/$version"))
            respond("""{"draft":false,"prerelease":false,"tag_name":"$version","assets":[${names.mapIndexed { i, n -> """{"id":${i + 1},"name":"$n"}""" }.joinToString(",")}]}""")
        else asset(request)
    }

    /**
     * The native engines (curl's CURLOPT_TIMEOUT_MS, NSURLSession, WinHttp) bound the whole transfer, body included, by
     * the request timeout, so the request timeout each download asks for must cover its own budget: a 15 to 24 MB binary
     * under ~200 KB/s, or a desktop package, must not be cut at two minutes. MockEngine does not apply that timeout to the
     * body, so this checks what each request asks the engine for.
     */
    @Test fun eachDownloadAsksTheEngineForARequestTimeoutThatCoversItsBudget() = runBlocking {
        val timeouts = mutableMapOf<String, Long?>()
        client(releaseServer("1.0.0", listOf("manifest.json", "manifest.sig", "teamcode-1.0.0-linux-x64", "teamcode-desktop-1.0.0-linux-x64.tar.gz")) { request ->
            timeouts[request.url.encodedPath.substringAfterLast('/')] =
                request.getCapabilityOrNull(io.ktor.client.plugins.HttpTimeoutCapability)?.requestTimeoutMillis
            respond(if (request.url.encodedPath.endsWith("1") || request.url.encodedPath.endsWith("2")) "meta" else "binary")
        }).use { transport ->
            transport.metadata("1.0.0")
            transport.binary("1.0.0", "linux-x64", 6) { _, _ -> }
            transport.desktopPackage("1.0.0", "linux-x64", 6) { _, _ -> }
        }
        fun covers(budget: Long, value: Long?) = value == null || value == io.ktor.client.plugins.HttpTimeoutConfig.INFINITE_TIMEOUT_MS || value >= budget
        val binary = timeouts["3"] ?: timeouts["teamcode-1.0.0-linux-x64"]
        val desktop = timeouts["4"] ?: timeouts["teamcode-desktop-1.0.0-linux-x64.tar.gz"]
        assertTrue(covers(300_000, binary), "binary request timeout $binary, budget 300 s ($timeouts)")
        assertTrue(covers(900_000, desktop), "desktop package request timeout $desktop, budget 900 s ($timeouts)")
    }

    @Test fun aDownloadPastItsBudgetIsATimeoutNotANetworkFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handler = releaseServer("1.0.0", listOf("teamcode-1.0.0-linux-x64")) {
            respond(writer(dispatcher) { repeat(40) { delay(10_000); channel.writeByte(1); channel.flush() } }.channel)
        }
        ReleaseClient { configure -> HttpClient(MockEngine(MockEngineConfig().apply { this.dispatcher = dispatcher; addHandler(handler) })) { configure(this) } }.use { transport ->
            failure("release_timeout") { transport.binary("1.0.0", "linux-x64", 40) { _, _ -> } }
        }
    }

    @Test fun aLocalWriteFailureIsNotReportedAsANetworkFailure() = runBlocking {
        client(releaseServer("1.0.0", listOf("teamcode-1.0.0-linux-x64")) { respond("binary") }).use { transport ->
            val error = assertFailsWith<ReleaseDownloadException> {
                transport.binary("1.0.0", "linux-x64", 6) { _, _ -> throw kotlinx.io.IOException("No space left on device") }
            }
            assertEquals("release_write_failure", error.code)
        }
    }

    /**
     * GitHub's `github.com/<repo>/releases/download/…` redirect carries a Content-Security-Policy line of about 9.2 KB.
     * The curl (Linux) and WinHttp (Windows) engines parse response headers with ktor-http-cio, which refuses a line
     * over 8 192 bytes; this handler parses each response through that same parser, as those engines do.
     */
    @Test fun releaseDownloadsNeverDependOnGithubComHeadersThatTheNativeEnginesCannotParse() = runBlocking {
        val policy = "content-security-policy: default-src 'none'; " + "x".repeat(9_300)
        suspend fun parsed(raw: String) = io.ktor.http.cio.parseResponse(ByteReadChannel(raw.replace("\n", "\r\n")))
            ?: error("Failed to parse response header")
        val hosts = mutableListOf<String>()
        client(releaseServer("1.0.0", listOf("manifest.json", "manifest.sig", "teamcode-1.0.0-linux-x64")) { request ->
            hosts += request.url.host
            when (request.url.host) {
                "github.com" -> {
                    parsed("HTTP/1.1 302 Found\nlocation: https://release-assets.githubusercontent.com/a?sig=1\n$policy\ncontent-length: 0\n\n").close()
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://release-assets.githubusercontent.com/a?sig=1"))
                }
                "api.github.com" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://release-assets.githubusercontent.com/a?sig=1"))
                else -> respond("binary")
            }
        }).use { transport ->
            assertEquals("binary", transport.metadata("1.0.0").manifest.decodeToString())
            var received = 0
            transport.binary("1.0.0", "linux-x64", 6) { _, size -> received += size }
            assertEquals(6, received)
            assertFalse("github.com" in hosts, hosts.toString())
        }
    }
}
