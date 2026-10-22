package me.nathanfallet.teamcode.infrastructure.artifacts

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import java.net.InetAddress
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.artifacts.*
import kotlin.test.*

/** The S3 adapter against a mock engine: the exact requests it signs and sends, and what it keeps of an answer. */
class S3ObjectStoreTest {
    private val clock = { 1_369_353_600_000L } // 2013-05-24T00:00:00Z
    private val public = { _: String -> listOf(InetAddress.getByAddress(byteArrayOf(203.toByte(), 0, 113, 10))) }
    private fun connection(pathStyle: Boolean = false, sse: ServerSideEncryption = ServerSideEncryption.NONE) =
        ArtifactStorageConnection("https://s3.example.test", "gra", "artifacts", "tc", pathStyle, "AKIDEXAMPLE", "secret-access-key-example", sse)

    private fun store(handler: MockRequestHandler, resolver: (String) -> List<InetAddress> = public) =
        S3ObjectStore(HttpClient(MockEngine(handler)) { expectSuccess = false; followRedirects = false }, resolver, clock)

    private fun bytes(request: HttpRequestData) = (request.body as? OutgoingContent.ByteArrayContent)?.bytes() ?: ByteArray(0)

    /** Recomputes the signature from what was actually sent, so a header changed after signing would fail. */
    private fun assertSigned(request: HttpRequestData, expectedHost: String) {
        val authorization = request.headers[HttpHeaders.Authorization]!!
        val signedNames = Regex("SignedHeaders=([^,]+)").find(authorization)!!.groupValues[1].split(';')
        assertEquals(expectedHost, request.headers[HttpHeaders.Host])
        val headers = signedNames.associateWith { request.headers[it]!! }
        val payload = SigV4.hex(MessageDigest.getInstance("SHA-256").digest(bytes(request)))
        assertEquals(payload, request.headers["x-amz-content-sha256"])
        val query = request.url.parameters.entries().flatMap { (k, values) -> values.map { k to it } }
        val expected = SigV4.signature(request.method.value, request.url.encodedPath, query, headers, payload, "secret-access-key-example", "gra", "20130524T000000Z")
        assertTrue(authorization.endsWith("Signature=$expected"), authorization)
        assertTrue(authorization.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20130524/gra/s3/aws4_request, "))
    }

    @Test fun putIsSignedVirtualHostedWithEncryptionAndPathStyleWhenAsked() = runBlocking {
        val seen = mutableListOf<HttpRequestData>()
        val s3 = store({ request -> seen += request; respond("", HttpStatusCode.OK) })
        s3.put(connection(sse = ServerSideEncryption.AES256), "tc/team/artifacts/a/blobs/abc", "hello".encodeToByteArray(), "text/html")
        val put = seen.single()
        assertEquals(HttpMethod.Put, put.method)
        assertEquals("https://artifacts.s3.example.test/tc/team/artifacts/a/blobs/abc", put.url.toString())
        assertEquals("AES256", put.headers["x-amz-server-side-encryption"])
        assertTrue("x-amz-server-side-encryption" in put.headers[HttpHeaders.Authorization]!!, "The encryption header is signed")
        assertContentEquals("hello".encodeToByteArray(), bytes(put))
        assertSigned(put, "artifacts.s3.example.test")

        s3.put(connection(pathStyle = true), "tc/x y+z", ByteArray(0))
        val pathStyle = seen.last()
        assertEquals("/artifacts/tc/x%20y%2Bz", pathStyle.url.encodedPath)
        assertNull(pathStyle.headers["x-amz-server-side-encryption"])
        assertSigned(pathStyle, "s3.example.test")
    }

    @Test fun getSendsTheRangeAndMissingObjectsAreNull() = runBlocking {
        val s3 = store({ request ->
            when (request.url.encodedPath) {
                "/k" -> respond("0123456789".substring(2, 6), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 2-5/10"))
                else -> respond("<Error><Code>NoSuchKey</Code></Error>", HttpStatusCode.NotFound)
            }
        })
        val partial = assertNotNull(s3.get(connection(), "k", 2L..5L))
        assertEquals("2345", partial.bytes.decodeToString()); assertEquals("bytes 2-5/10", partial.contentRange)
        assertNull(s3.get(connection(), "missing"))
        assertNull(s3.head(connection(), "missing"))
        val tooLarge = assertFailsWith<ObjectStoreException> { s3.get(connection(), "k", maxBytes = 2) }
        assertEquals("object_too_large", tooLarge.code)
    }

    @Test fun errorsKeepOnlyTheS3CodeNeverTheBody() = runBlocking {
        val s3 = store({ respond("<Error><Code>AccessDenied</Code><Message>key AKIDEXAMPLE has no right on secret-bucket-name</Message></Error>", HttpStatusCode.Forbidden) })
        val error = assertFailsWith<ObjectStoreException> { s3.put(connection(), "k", ByteArray(1)) }
        assertEquals(403, error.status); assertEquals("AccessDenied", error.code)
        assertFalse("secret-bucket-name" in error.message.orEmpty() || "AKIDEXAMPLE" in error.message.orEmpty())
        val odd = assertFailsWith<ObjectStoreException> { store({ respond("<Error><Code>Not a <code></Code></Error>", HttpStatusCode.BadGateway) }).put(connection(), "k", ByteArray(1)) }
        assertEquals("http_502", odd.code, "A code that is not a plain identifier is not echoed")
    }

    @Test fun metadataAndLinkLocalHostsAreRefusedAfterDnsWithoutSending() = runBlocking {
        var sent = 0
        for (address in listOf(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()), byteArrayOf(0, 0, 0, 0))) {
            val s3 = store({ sent++; respond("", HttpStatusCode.OK) }, { listOf(InetAddress.getByAddress(address)) })
            assertEquals("host_not_allowed", assertFailsWith<ObjectStoreException> { s3.put(connection(), "k", ByteArray(1)) }.code)
        }
        val unresolved = store({ sent++; respond("", HttpStatusCode.OK) }, { throw java.net.UnknownHostException(it) })
        assertEquals("dns", assertFailsWith<ObjectStoreException> { unresolved.head(connection(), "k") }.code)
        assertEquals(0, sent)
        val loopback = store({ sent++; respond("", HttpStatusCode.OK) }, { listOf(InetAddress.getLoopbackAddress()) })
        loopback.put(connection(), "k", ByteArray(1)); assertEquals(1, sent, "Loopback (a local MinIO) is allowed")
    }

    @Test fun listPaginatesAndDeleteReportsTheKeysItCouldNotRemove() = runBlocking {
        val seen = mutableListOf<HttpRequestData>()
        val s3 = store({ request ->
            seen += request
            if (request.method == HttpMethod.Post) respond("""<?xml version="1.0"?><DeleteResult><Error><Key>b</Key><Code>AccessDenied</Code></Error></DeleteResult>""")
            else respond("""<?xml version="1.0" encoding="UTF-8"?><ListBucketResult><IsTruncated>true</IsTruncated><Contents><Key>tc/a</Key><Size>3</Size></Contents><Contents><Key>tc/b</Key><Size>5</Size></Contents><NextContinuationToken>next+/=</NextContinuationToken></ListBucketResult>""")
        })
        val listing = s3.list(connection(), "tc/", continuationToken = "tok/1", maxKeys = 2)
        assertEquals(listOf(ListedObject("tc/a", 3), ListedObject("tc/b", 5)), listing.keys); assertEquals("next+/=", listing.nextContinuationToken)
        val list = seen.single()
        assertEquals("2", list.url.parameters["list-type"]); assertEquals("tc/", list.url.parameters["prefix"]); assertEquals("tok/1", list.url.parameters["continuation-token"])
        assertSigned(list, "artifacts.s3.example.test")

        assertEquals(listOf("b"), s3.delete(connection(), listOf("a", "b&<c>")))
        val delete = seen.last()
        assertEquals(HttpMethod.Post, delete.method); assertTrue(delete.url.parameters.contains("delete"))
        assertTrue("<Key>b&amp;&lt;c&gt;</Key>" in bytes(delete).decodeToString(), "Keys are escaped in the XML body")
        assertEquals(java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes(delete))), delete.headers["content-md5"])
        assertSigned(delete, "artifacts.s3.example.test")
        assertEquals(emptyList(), s3.delete(connection(), emptyList()))
    }

    @Test fun aListingWithADoctypeIsRefused() = runBlocking {
        val s3 = store({ respond("""<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e SYSTEM "file:///etc/passwd">]><ListBucketResult><Contents><Key>&e;</Key></Contents></ListBucketResult>""") })
        assertEquals("invalid_response", assertFailsWith<ObjectStoreException> { s3.list(connection(), "") }.code)
    }
}
