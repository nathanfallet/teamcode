package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.DesktopReleaseFormat
import me.nathanfallet.teamcode.api.models.ReleaseFormat

/** Raw metadata is deliberately not deserialized until the CLI has authenticated its exact bytes. */
class DownloadedReleaseMetadata(val manifest: ByteArray, val signature: ByteArray)

/**
 * Sanitized errors never include response bodies, redirects, signed CDN query strings or credentials. [code] is stable
 * (scripts and support match on it); [detail] is a short technical cause (exception class and message, URLs reduced to
 * their origin, at most 200 characters) for the person reading the error, absent when TeamCode itself refused the answer.
 */
class ReleaseDownloadException(val code: String, val detail: String? = null) : Exception(code)

/**
 * Independent, credential-free public release transport. The factory must create a new owned client,
 * without TeamCode authentication/cookies/default headers. Forks may select another public repository
 * at build time; the downloaded manifest still requires the separately shipped publisher trust root.
 */
class ReleaseClient(
    repository: String = "nathanfallet/teamcode",
    createHttpClient: (io.ktor.client.HttpClientConfig<*>.() -> Unit) -> HttpClient,
) : AutoCloseable {
    private val repository = repository.also {
        require(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,99}/[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(it))
    }
    private val http = createHttpClient {
        expectSuccess = false
        followRedirects = false
        // No global request cap: each call sets its own from its budget (native engines bound the body with it too). The
        // socket timeout only ends a stalled stream: a slow but live download keeps going.
        install(HttpTimeout) { connectTimeoutMillis = 10_000; socketTimeoutMillis = 30_000 }
    }

    /**
     * Asset name to GitHub asset id, per release version, from the release JSON already read (one lookup per version).
     * Guarded by [assetLock]; an asset that answers 404 through a cached id drops the entry and is looked up again once,
     * so a release whose assets were re-uploaded is not reported as unpublished by a long-lived client.
     */
    private val assetIds = mutableMapOf<String, Map<String, Long>>()
    private val assetLock = Mutex()

    /** Returns only a stable published version hint; a signature must still authenticate its metadata. */
    suspend fun latestVersion(): String? = safe {
        withTimeout(LOOKUP_BUDGET) {
            val address = "https://api.github.com/repos/$repository/releases/latest"
            val bytes = fetch(address, MAX_RELEASE_JSON_BYTES, Kind.API, LOOKUP_BUDGET, absentAllowed = true) ?: return@withTimeout null
            val root = releaseJson(bytes)
            if (root == null || root["draft"] != JsonPrimitive(false) || root["prerelease"] != JsonPrimitive(false))
                throw ReleaseDownloadException("release_discovery_invalid")
            val tag = (root["tag_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val version = tag
            if (version == null || !ReleaseFormat.validVersion(version) || '-' in version)
                throw ReleaseDownloadException("release_discovery_invalid")
            assets(root)?.let { ids -> assetLock.withLock { assetIds[version] = ids } }
            version
        }
    }

    /** Fetches bounded raw manifest/signature bytes from canonical asset paths, never URLs in API JSON. */
    suspend fun metadata(version: String): DownloadedReleaseMetadata = safe {
        requireVersion(version)
        withTimeout(METADATA_BUDGET) {
            DownloadedReleaseMetadata(
                fetchAsset(version, "manifest.json", ReleaseFormat.MAX_MANIFEST_BYTES, METADATA_BUDGET),
                fetchAsset(version, "manifest.sig", ReleaseFormat.MAX_SIGNATURE_BYTES, METADATA_BUDGET),
            )
        }
    }

    /**
     * Streams exactly [size] bytes to private staging. Call only after authenticating the manifest;
     * [receive] must consume/copy each reused chunk before returning. The CLI verifies SHA-256 before
     * publishing or executing that staging file. No downloaded executable is run by this client.
     */
    suspend fun binary(version: String, target: String, size: Long, receive: suspend (ByteArray, Int) -> Unit) = safe {
        requireVersion(version)
        if (target !in ReleaseFormat.targets || size !in 1..ReleaseFormat.MAX_BINARY_BYTES)
            throw ReleaseDownloadException("release_asset_invalid")
        withTimeout(BINARY_BUDGET) {
            requestAsset(version, ReleaseFormat.fileName(version, target), BINARY_BUDGET) { response ->
                read(response, size, exact = true, receive)
            }
        }
        Unit
    }

    /** The desktop app's signed manifest of [version] (`desktop-manifest.json` and its signature), as raw bytes. */
    suspend fun desktopMetadata(version: String): DownloadedReleaseMetadata = safe {
        requireVersion(version)
        withTimeout(METADATA_BUDGET) {
            DownloadedReleaseMetadata(
                fetchAsset(version, DesktopReleaseFormat.MANIFEST, ReleaseFormat.MAX_MANIFEST_BYTES, METADATA_BUDGET),
                fetchAsset(version, DesktopReleaseFormat.SIGNATURE, ReleaseFormat.MAX_SIGNATURE_BYTES, METADATA_BUDGET),
            )
        }
    }

    /**
     * Streams exactly [size] bytes of the desktop package of [version] for [target]. Call only after authenticating the
     * desktop manifest; the caller verifies its SHA-256 before opening it. Bounded to 15 minutes (packages are ~100 MB).
     */
    suspend fun desktopPackage(version: String, target: String, size: Long, receive: suspend (ByteArray, Int) -> Unit) = safe {
        requireVersion(version)
        if (target !in ReleaseFormat.targets || size !in 1..DesktopReleaseFormat.MAX_PACKAGE_BYTES)
            throw ReleaseDownloadException("release_asset_invalid")
        withTimeout(DESKTOP_PACKAGE_BUDGET) {
            requestAsset(version, DesktopReleaseFormat.fileName(version, target), DESKTOP_PACKAGE_BUDGET) { response ->
                read(response, size, exact = true, receive)
            }
        }
        Unit
    }

    /**
     * The install script ([name], `install.sh` or `install.ps1`) of [version] exactly, never the latest alias. Its bytes
     * are not trusted here: the caller compares their SHA-256 with the one it was built with before running them.
     */
    suspend fun installScript(version: String, name: String): ByteArray = safe {
        requireVersion(version)
        if (name !in setOf("install.sh", "install.ps1")) throw ReleaseDownloadException("release_asset_invalid")
        withTimeout(METADATA_BUDGET) { fetchAsset(version, name, DesktopReleaseFormat.MAX_INSTALL_SCRIPT_BYTES, METADATA_BUDGET) }
    }

    /**
     * The REST address of a release asset. Never `github.com/<repo>/releases/download/…`: GitHub's redirect there carries a
     * Content-Security-Policy line of over 9 KB, and the curl (Linux) and WinHttp (Windows) engines refuse any header line
     * over 8 192 bytes (ktor-http-cio's parser), so every native download failed outside macOS. The REST asset endpoint
     * answers with short headers and redirects to the same CDN. The id comes from the release JSON of that exact tag (an
     * id, never a URL from the JSON); the bytes are still authenticated by the signed manifest and its SHA-256.
     */
    private suspend fun requestAsset(version: String, file: String, budget: Long, receive: suspend (HttpResponse) -> Unit) {
        val cached = assetLock.withLock { assetIds[version] }
        val ids = cached ?: releaseAssets(version).also { ids -> assetLock.withLock { assetIds[version] = ids } }
        try { request(assetAddress(ids, file), Kind.ASSET, budget, absentAllowed = false, receive) }
        catch (error: ReleaseDownloadException) {
            // A 404 answers before any byte reaches [receive], so one retry with fresh ids delivers nothing twice.
            if (cached == null || error.code != "release_http_404") throw error
            val fresh = releaseAssets(version).also { assetLock.withLock { assetIds[version] = it } }
            request(assetAddress(fresh, file), Kind.ASSET, budget, absentAllowed = false, receive)
        }
    }

    private suspend fun fetchAsset(version: String, file: String, limit: Int, budget: Long): ByteArray {
        var output: ByteArray? = null
        requestAsset(version, file, budget) { response -> output = readBounded(response, limit) }
        return requireNotNull(output)
    }

    private fun assetAddress(ids: Map<String, Long>, file: String): String {
        val id = ids[file] ?: throw ReleaseDownloadException("release_http_404")
        return "https://api.github.com/repos/$repository/releases/assets/$id"
    }

    private suspend fun releaseAssets(version: String): Map<String, Long> {
        val bytes = fetch("https://api.github.com/repos/$repository/releases/tags/$version", MAX_RELEASE_JSON_BYTES, Kind.API, LOOKUP_BUDGET,
            absentAllowed = true) ?: throw ReleaseDownloadException("release_http_404")
        val root = releaseJson(bytes)
        if (root == null || root["draft"] != JsonPrimitive(false) || root["tag_name"] != JsonPrimitive(version))
            throw ReleaseDownloadException("release_discovery_invalid")
        return assets(root) ?: throw ReleaseDownloadException("release_discovery_invalid")
    }

    private fun releaseJson(bytes: ByteArray): JsonObject? = try {
        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
    } catch (_: CharacterCodingException) { null }
    catch (_: IllegalArgumentException) { null }

    /** Asset names to ids; null when the list is missing or malformed. Only the name and the numeric id are read. */
    private fun assets(root: JsonObject): Map<String, Long>? {
        val list = root["assets"] as? JsonArray ?: return null
        return list.associate { element ->
            val asset = element as? JsonObject ?: return null
            val name = (asset["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val id = (asset["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 } ?: return null
            name to id
        }
    }
    private fun requireVersion(version: String) {
        if (!ReleaseFormat.validVersion(version)) throw ReleaseDownloadException("release_version_invalid")
    }

    /** [API]: GitHub JSON, never redirected. [ASSET]: a REST asset download, redirected only to GitHub's release CDN. */
    private enum class Kind { API, ASSET }

    private suspend fun fetch(address: String, limit: Int, kind: Kind, budget: Long, absentAllowed: Boolean = false): ByteArray? {
        var output: ByteArray? = null
        request(address, kind, budget, absentAllowed) { response -> output = readBounded(response, limit) }
        return output
    }

    private suspend fun readBounded(response: HttpResponse, limit: Int): ByteArray {
        val bytes = ByteArray(limit)
        var count = 0
        read(response, limit.toLong(), exact = false) { buffer, size -> buffer.copyInto(bytes, count, 0, size); count += size }
        return bytes.copyOf(count)
    }

    private suspend fun request(address: String, kind: Kind, budget: Long, absentAllowed: Boolean, receive: suspend (HttpResponse) -> Unit) {
        var current = address
        repeat(6) { attempt ->
            var next: String? = null
            var complete = false
            http.prepareGet(current) {
                timeout { requestTimeoutMillis = budget }
                header(HttpHeaders.UserAgent, "TeamCode release client")
                header(HttpHeaders.AcceptEncoding, "identity")
                // GitHub's REST headers only go to api.github.com, never to the CDN a redirect points at.
                if (Url(current).host == "api.github.com") {
                    header(HttpHeaders.Accept, if (kind == Kind.API) "application/vnd.github+json" else "application/octet-stream")
                    header("X-GitHub-Api-Version", "2026-03-10")
                }
            }.execute { response ->
                if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                    val location = response.headers.getAll(HttpHeaders.Location)?.singleOrNull()
                        ?: throw ReleaseDownloadException("release_redirect_invalid")
                    if (attempt == 5 || kind == Kind.API) throw ReleaseDownloadException("release_redirect_invalid")
                    next = checkedRedirect(location)
                } else if (absentAllowed && response.status == HttpStatusCode.NotFound) {
                    complete = true
                } else {
                    if ((response.status.value == 403 || response.status.value == 429) && response.headers["x-ratelimit-remaining"] == "0")
                        throw ReleaseDownloadException("release_rate_limited", rateLimitDetail(response.headers["x-ratelimit-reset"]))
                    if (response.status != HttpStatusCode.OK) throw ReleaseDownloadException("release_http_${response.status.value}")
                    if (response.headers[HttpHeaders.ContentEncoding]?.lowercase()?.let { it != "identity" } == true)
                        throw ReleaseDownloadException("release_encoding_invalid")
                    receive(response)
                    complete = true
                }
            }
            if (complete) return
            current = requireNotNull(next)
        }
    }

    /**
     * Asset downloads may only be redirected to GitHub's release CDN, on any path: the CDN serves signed, short-lived
     * paths, and the bytes are authenticated afterwards (signed manifest, then SHA-256), never trusted for being there.
     */
    private fun checkedRedirect(value: String): String {
        if (value.length !in 1..8192 || value.any { it <= ' ' || it == '\u007f' }) throw ReleaseDownloadException("release_redirect_invalid")
        val url = try { Url(value) } catch (_: IllegalArgumentException) { throw ReleaseDownloadException("release_redirect_invalid") }
        if (!value.startsWith("https://") || url.protocol != URLProtocol.HTTPS || url.port != 443 ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.fragment.isNotEmpty() ||
            url.host !in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            throw ReleaseDownloadException("release_redirect_invalid")
        return value
    }

    private suspend fun read(response: HttpResponse, bound: Long, exact: Boolean, receive: suspend (ByteArray, Int) -> Unit) {
        val advertised = response.headers.getAll(HttpHeaders.ContentLength)?.let { values ->
            values.singleOrNull()?.toLongOrNull()?.takeIf { it >= 0 } ?: throw ReleaseDownloadException("release_size_invalid")
        }
        if (advertised != null && (advertised > bound || (exact && advertised != bound)))
            throw ReleaseDownloadException("release_size_invalid")
        val buffer = ByteArray(8192)
        val channel = response.bodyAsChannel()
        var count = 0L
        while (true) {
            val size = channel.readAvailable(buffer, 0, minOf(buffer.size.toLong(), bound - count + 1).toInt())
            if (size < 0) break
            count += size
            if (count > bound) throw ReleaseDownloadException("release_size_invalid")
            if (size > 0) deliver(receive, buffer, size)
        }
        if ((exact && count != bound) || (advertised != null && count != advertised))
            throw ReleaseDownloadException("release_size_invalid")
    }

    /**
     * The caller's sink (staging file) failing with an I/O error is a local write error, never a network one. Any other
     * error of the sink is the caller's own (a refusal, a busy installation): [safe] rethrows it unchanged.
     */
    private suspend fun deliver(receive: suspend (ByteArray, Int) -> Unit, buffer: ByteArray, size: Int) = try { receive(buffer, size) }
    catch (error: CancellationException) { throw error }
    catch (error: ReleaseDownloadException) { throw error }
    catch (error: Exception) {
        if (error is okio.IOException || error is kotlinx.io.IOException) throw ReleaseDownloadException("release_write_failure", releaseFailureDetail(error))
        throw SinkFailure(error)
    }

    private class SinkFailure(val original: Exception) : Exception()

    private suspend fun <T> safe(operation: suspend () -> T): T = try { operation() }
    catch (error: TimeoutCancellationException) {
        // Our own withTimeout budget, unless the caller itself was cancelled meanwhile.
        currentCoroutineContext().ensureActive()
        throw ReleaseDownloadException("release_timeout", "the download did not finish within its time budget")
    }
    catch (error: CancellationException) { throw error }
    catch (error: ReleaseDownloadException) { throw error }
    catch (error: SinkFailure) { throw error.original }
    catch (error: Exception) { throw ReleaseDownloadException(releaseFailureCode(error), releaseFailureDetail(error)) }

    override fun close() = http.close()

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun rateLimitDetail(reset: String?): String {
        val seconds = reset?.toLongOrNull()?.minus(kotlin.time.Clock.System.now().epochSeconds) ?: return "GitHub API rate limit reached"
        val minutes = (seconds.coerceIn(0, 3_600) + 59) / 60
        return "GitHub API rate limit reached, it resets in " + if (minutes <= 1) "about a minute" else "about $minutes minutes"
    }

    private companion object {
        const val LOOKUP_BUDGET = 15_000L
        const val METADATA_BUDGET = 30_000L
        const val BINARY_BUDGET = 300_000L
        const val DESKTOP_PACKAGE_BUDGET = 900_000L
        /** A release JSON lists every asset (about 45 KB for 1.0.0 with its desktop packages). */
        const val MAX_RELEASE_JSON_BYTES = 1024 * 1024
    }
}

/** The stable code of a transport failure: its network family, a malformed answer, or an unexpected local error. */
internal fun releaseFailureCode(error: Throwable): String = when (networkFailure(error)) {
    NetworkFailure.TIMEOUT -> "release_timeout"
    NetworkFailure.DNS -> "release_dns_failure"
    NetworkFailure.TLS -> "release_tls_failure"
    NetworkFailure.PROXY -> "release_proxy_failure"
    NetworkFailure.REFUSED -> "release_connection_failure"
    NetworkFailure.CLOSED -> "release_connection_closed"
    NetworkFailure.HEADERS -> "release_response_invalid"
    NetworkFailure.OTHER -> "release_network_failure"
    // Inside the transport (the sink is handled apart), an I/O error is a network one even when no engine marker names it.
    null -> if (generateSequence(error) { it.cause }.take(8).any { it is kotlinx.io.IOException || it is okio.IOException })
        "release_network_failure" else "release_unexpected_failure"
}

