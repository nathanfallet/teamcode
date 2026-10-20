package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.ReleaseFormat

/** Raw metadata is deliberately not deserialized until the CLI has authenticated its exact bytes. */
class DownloadedReleaseMetadata(val manifest: ByteArray, val signature: ByteArray)

/** Sanitized errors never include response bodies, redirects, signed CDN query strings or credentials. */
class ReleaseDownloadException(val code: String) : Exception(code)

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
        install(HttpTimeout) { connectTimeoutMillis = 10_000; socketTimeoutMillis = 15_000; requestTimeoutMillis = 120_000 }
    }

    /** Returns only a stable published version hint; a signature must still authenticate its metadata. */
    suspend fun latestVersion(): String? = safe {
        withTimeout(15_000) {
            val address = "https://api.github.com/repos/$repository/releases/latest"
            val bytes = fetch(address, 256 * 1024, api = true, absentAllowed = true) ?: return@withTimeout null
            val root = try {
                Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
            } catch (_: CharacterCodingException) { null }
            catch (_: IllegalArgumentException) { null }
            if (root == null || root["draft"] != JsonPrimitive(false) || root["prerelease"] != JsonPrimitive(false))
                throw ReleaseDownloadException("release_discovery_invalid")
            val tag = (root["tag_name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val version = tag
            if (version == null || !ReleaseFormat.validVersion(version) || '-' in version)
                throw ReleaseDownloadException("release_discovery_invalid")
            version
        }
    }

    /** Fetches bounded raw manifest/signature bytes from canonical asset paths, never URLs in API JSON. */
    suspend fun metadata(version: String): DownloadedReleaseMetadata = safe {
        requireVersion(version)
        withTimeout(30_000) {
            DownloadedReleaseMetadata(
                requireNotNull(fetch(assetUrl(version, "manifest.json"), ReleaseFormat.MAX_MANIFEST_BYTES)),
                requireNotNull(fetch(assetUrl(version, "manifest.sig"), ReleaseFormat.MAX_SIGNATURE_BYTES)),
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
        withTimeout(300_000) {
            request(assetUrl(version, ReleaseFormat.fileName(version, target)), api = false, absentAllowed = false) { response ->
                read(response, size, exact = true, receive)
            }
        }
        Unit
    }

    private fun assetUrl(version: String, file: String) = "https://github.com/$repository/releases/download/$version/$file"
    private fun requireVersion(version: String) {
        if (!ReleaseFormat.validVersion(version)) throw ReleaseDownloadException("release_version_invalid")
    }

    private suspend fun fetch(address: String, limit: Int, api: Boolean = false, absentAllowed: Boolean = false): ByteArray? {
        var output: ByteArray? = null
        request(address, api, absentAllowed) { response ->
            val bytes = ByteArray(limit)
            var count = 0
            read(response, limit.toLong(), exact = false) { buffer, size -> buffer.copyInto(bytes, count, 0, size); count += size }
            output = bytes.copyOf(count)
        }
        return output
    }

    private suspend fun request(address: String, api: Boolean, absentAllowed: Boolean, receive: suspend (HttpResponse) -> Unit) {
        var current = address
        repeat(6) { attempt ->
            var next: String? = null
            var complete = false
            http.prepareGet(current) {
                header(HttpHeaders.UserAgent, "TeamCode release client")
                header(HttpHeaders.AcceptEncoding, "identity")
                if (api) {
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2026-03-10")
                }
            }.execute { response ->
                if (response.status.value in setOf(301, 302, 303, 307, 308)) {
                    val location = response.headers.getAll(HttpHeaders.Location)?.singleOrNull()
                        ?: throw ReleaseDownloadException("release_redirect_invalid")
                    if (attempt == 5 || api) throw ReleaseDownloadException("release_redirect_invalid")
                    next = checkedRedirect(location, address)
                } else if (absentAllowed && response.status == HttpStatusCode.NotFound) {
                    complete = true
                } else {
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

    private fun checkedRedirect(value: String, original: String): String {
        if (value.length !in 1..8192 || value.any { it <= ' ' || it == '\u007f' }) throw ReleaseDownloadException("release_redirect_invalid")
        val url = try { Url(value) } catch (_: IllegalArgumentException) { throw ReleaseDownloadException("release_redirect_invalid") }
        if (!value.startsWith("https://") || url.protocol != URLProtocol.HTTPS || url.port != 443 ||
            !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty() || url.fragment.isNotEmpty() ||
            url.host !in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            throw ReleaseDownloadException("release_redirect_invalid")
        if (url.host == "github.com" && (url.encodedPath != Url(original).encodedPath || !url.parameters.isEmpty()))
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
            if (size > 0) receive(buffer, size)
        }
        if ((exact && count != bound) || (advertised != null && count != advertised))
            throw ReleaseDownloadException("release_size_invalid")
    }

    private suspend fun <T> safe(operation: suspend () -> T): T = try { operation() }
    catch (error: CancellationException) { throw error }
    catch (error: ReleaseDownloadException) { throw error }
    catch (_: Exception) { throw ReleaseDownloadException("release_network_failure") }

    override fun close() = http.close()
}
