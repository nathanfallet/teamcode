package me.nathanfallet.teamcode.infrastructure.artifacts

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.*
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.cloud.CloudAdministrationService
import org.w3c.dom.Element

/**
 * [ArtifactObjectStore] over the S3 REST API with the server's Ktor client and an in-house SigV4 signer: no SDK, five
 * operations (PutObject, GetObject with Range, HeadObject, ListObjectsV2, DeleteObjects). Works with AWS S3, OVH,
 * Cloudflare R2 and MinIO, path-style or virtual-hosted.
 *
 * The endpoint host is resolved before every request and refused when any address is link-local, unspecified or a
 * cloud metadata address, as for Kubernetes API hosts. Error bodies are read only for their `<Code>`; nothing else of
 * them is kept, logged or returned. Redirects are not followed (the client is built with `followRedirects = false`).
 */
class S3ObjectStore(
    private val http: HttpClient,
    /** Tests pass a fixed resolver; production resolves with the system resolver. */
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val clock: () -> Long = System::currentTimeMillis,
) : ArtifactObjectStore {

    override suspend fun put(connection: ArtifactStorageConnection, key: String, bytes: ByteArray, contentType: String) {
        val extra = buildMap { if (connection.serverSideEncryption == ServerSideEncryption.AES256) put("x-amz-server-side-encryption", "AES256") }
        send(connection, HttpMethod.Put, key, emptyList(), extra, bytes, ContentType.parse(contentType)) { response ->
            if (!response.status.isSuccess()) throw failure(response)
        }
    }

    override suspend fun get(connection: ArtifactStorageConnection, key: String, range: LongRange?, maxBytes: Int): StoredObject? {
        val extra = buildMap { if (range != null) put("range", "bytes=${range.first}-${range.last}") }
        return send(connection, HttpMethod.Get, key, emptyList(), extra) { response ->
            when {
                response.status == HttpStatusCode.NotFound -> { drain(response); null }
                response.status == HttpStatusCode.RequestedRangeNotSatisfiable -> { drain(response); throw ObjectStoreException(416, "InvalidRange") }
                !response.status.isSuccess() -> throw failure(response)
                else -> {
                    val bytes = response.bodyAsChannel().readAtMost(maxBytes) ?: throw ObjectStoreException(response.status.value, "object_too_large")
                    StoredObject(bytes, response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: bytes.size.toLong(),
                        response.headers[HttpHeaders.ContentRange], response.headers[HttpHeaders.ETag])
                }
            }
        }
    }

    override suspend fun head(connection: ArtifactStorageConnection, key: String): ObjectHead? =
        send(connection, HttpMethod.Head, key, emptyList(), emptyMap()) { response ->
            when {
                response.status == HttpStatusCode.NotFound -> null
                !response.status.isSuccess() -> throw ObjectStoreException(response.status.value, "http_${response.status.value}")
                else -> ObjectHead(response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0, response.headers[HttpHeaders.ETag])
            }
        }

    override suspend fun list(connection: ArtifactStorageConnection, prefix: String, continuationToken: String?, maxKeys: Int): ObjectListing {
        val query = buildList {
            add("list-type" to "2"); add("prefix" to prefix); add("max-keys" to maxKeys.coerceIn(1, 1000).toString())
            if (continuationToken != null) add("continuation-token" to continuationToken)
        }
        return send(connection, HttpMethod.Get, null, query, emptyMap()) { response ->
            if (!response.status.isSuccess()) throw failure(response)
            val document = parse(response.bodyAsChannel().readAtMost(MAX_LISTING_BYTES) ?: throw ObjectStoreException(response.status.value, "listing_too_large"))
            val contents = document.getElementsByTagName("Contents")
            val keys = (0 until contents.length).map { index ->
                val item = contents.item(index) as Element
                ListedObject(item.text("Key") ?: "", item.text("Size")?.toLongOrNull() ?: 0)
            }.filter { it.key.isNotEmpty() }
            val truncated = document.documentElement.text("IsTruncated") == "true"
            ObjectListing(keys, if (truncated) document.documentElement.text("NextContinuationToken") else null)
        }
    }

    override suspend fun delete(connection: ArtifactStorageConnection, keys: List<String>): List<String> {
        require(keys.size <= 1000) { "DeleteObjects takes at most 1000 keys." }
        if (keys.isEmpty()) return emptyList()
        val body = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><Delete xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><Quiet>true</Quiet>""")
            keys.forEach { append("<Object><Key>").append(xml(it)).append("</Key></Object>") }
            append("</Delete>")
        }.encodeToByteArray()
        val md5 = Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("MD5").digest(body))
        return send(connection, HttpMethod.Post, null, listOf("delete" to ""), mapOf("content-md5" to md5), body, ContentType.Application.Xml) { response ->
            if (!response.status.isSuccess()) throw failure(response)
            val document = parse(response.bodyAsChannel().readAtMost(MAX_LISTING_BYTES) ?: throw ObjectStoreException(response.status.value, "listing_too_large"))
            val errors = document.getElementsByTagName("Error")
            (0 until errors.length).mapNotNull { (errors.item(it) as Element).text("Key") }
        }
    }

    private suspend fun <T> send(
        connection: ArtifactStorageConnection, method: HttpMethod, key: String?, query: List<Pair<String, String>>, extraHeaders: Map<String, String>,
        body: ByteArray? = null, contentType: ContentType? = null, read: suspend (HttpResponse) -> T,
    ): T {
        val endpoint = try { Url(connection.endpoint) } catch (_: Exception) { throw ObjectStoreException(null, "invalid_endpoint") }
        requireAllowedHost(endpoint.host)
        val authorityHost = if (connection.pathStyle) endpoint.host else "${connection.bucket}.${endpoint.host}"
        val hostHeader = if (endpoint.specifiedPort == 0 || endpoint.specifiedPort == endpoint.protocol.defaultPort) authorityHost else "$authorityHost:${endpoint.port}"
        val rawPath = buildString {
            if (connection.pathStyle) append('/').append(connection.bucket)
            append('/')
            if (key != null) append(key)
        }
        val canonicalUri = SigV4.encodePath(rawPath)
        val payloadHash = SigV4.hex(SigV4.sha256(body ?: ByteArray(0)))
        val amzDate = AMZ_DATE.format(java.time.Instant.ofEpochMilli(clock()).atOffset(ZoneOffset.UTC))
        val signed = linkedMapOf("host" to hostHeader, "x-amz-content-sha256" to payloadHash, "x-amz-date" to amzDate) + extraHeaders
        val authorization = SigV4.authorization(method.value, canonicalUri, query, signed, payloadHash, connection.accessKeyId, connection.secretAccessKey, connection.region, amzDate)
        val queryString = query.joinToString("&") { (k, v) -> "${SigV4.encode(k)}=${SigV4.encode(v)}" }
        val url = "${endpoint.protocol.name}://$hostHeader$canonicalUri" + (if (queryString.isEmpty()) "" else "?$queryString")
        try {
            return http.prepareRequest(url) {
                this.method = method
                signed.forEach { (name, value) -> header(name, value) }
                header(HttpHeaders.Authorization, authorization)
                if (body != null) setBody(ByteArrayContent(body, contentType ?: ContentType.Application.OctetStream))
            }.execute { read(it) }
        } catch (error: CancellationException) { throw error }
        catch (error: ObjectStoreException) { throw error }
        catch (error: io.ktor.client.plugins.HttpRequestTimeoutException) { throw ObjectStoreException(null, "timeout") }
        catch (error: Exception) { throw ObjectStoreException(null, "unreachable") }
    }

    private suspend fun requireAllowedHost(host: String) {
        val bare = host.removePrefix("[").removeSuffix("]")
        val addresses = try { withContext(Dispatchers.IO) { resolver(bare) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw ObjectStoreException(null, "dns") }
        if (addresses.isEmpty() || addresses.any { !CloudAdministrationService.allowedResolvedAddress(it.address) }) throw ObjectStoreException(null, "host_not_allowed")
    }

    private suspend fun failure(response: HttpResponse): ObjectStoreException {
        val text = response.bodyAsChannel().readAtMost(MAX_ERROR_BYTES)?.decodeToString()
        val code = text?.let { Regex("<Code>([A-Za-z0-9.]{1,64})</Code>").find(it)?.groupValues?.get(1) } ?: "http_${response.status.value}"
        return ObjectStoreException(response.status.value, code)
    }

    private suspend fun drain(response: HttpResponse) { response.bodyAsChannel().readAtMost(MAX_ERROR_BYTES) }

    companion object {
        private val AMZ_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private const val MAX_ERROR_BYTES = 64 * 1024
        private const val MAX_LISTING_BYTES = 8 * 1024 * 1024

        /** Reads the whole channel, or null as soon as it holds more than [limit] bytes. */
        internal suspend fun ByteReadChannel.readAtMost(limit: Int): ByteArray? {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = readAvailable(buffer, 0, buffer.size)
                if (count < 0) break
                if (count == 0) { if (isClosedForRead) break else continue }
                if (output.size() + count > limit) { cancel(null); return null }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun xml(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        /** No DTD, no external entity: a listing is plain XML. */
        private fun parse(bytes: ByteArray) = try {
            DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isXIncludeAware = false; isExpandEntityReferences = false; isNamespaceAware = false
            }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (_: Exception) { throw ObjectStoreException(null, "invalid_response") }

        /** The text of the first direct child named [name]. */
        private fun Element.text(name: String): String? {
            val children = childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element && node.tagName == name) return node.textContent
            }
            return null
        }
    }
}
