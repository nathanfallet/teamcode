package me.nathanfallet.teamcode.infrastructure.artifacts

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 for S3 requests (header form), as S3, OVH, Cloudflare R2 and MinIO accept it. Only what the
 * artifact store sends is covered: one region, the `s3` service, a hex payload hash in `x-amz-content-sha256`.
 */
internal object SigV4 {
    const val ALGORITHM = "AWS4-HMAC-SHA256"
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * The `Authorization` header for a request. [canonicalUri] is the already encoded path (see [encodePath]);
     * [query] holds raw, unencoded pairs; [headers] are every header to sign, `host` and `x-amz-date` included.
     */
    fun authorization(
        method: String, canonicalUri: String, query: List<Pair<String, String>>, headers: Map<String, String>, payloadHash: String,
        accessKeyId: String, secretAccessKey: String, region: String, amzDate: String, service: String = "s3",
    ): String {
        val signed = signature(method, canonicalUri, query, headers, payloadHash, secretAccessKey, region, amzDate, service)
        return "$ALGORITHM Credential=$accessKeyId/${scope(amzDate, region, service)}, SignedHeaders=${signedHeaders(headers)}, Signature=$signed"
    }

    fun signature(
        method: String, canonicalUri: String, query: List<Pair<String, String>>, headers: Map<String, String>, payloadHash: String,
        secretAccessKey: String, region: String, amzDate: String, service: String = "s3",
    ): String {
        val canonical = canonicalRequest(method, canonicalUri, query, headers, payloadHash)
        val toSign = "$ALGORITHM\n$amzDate\n${scope(amzDate, region, service)}\n${hex(sha256(canonical.encodeToByteArray()))}"
        var key = hmac(("AWS4$secretAccessKey").encodeToByteArray(), amzDate.substring(0, 8))
        key = hmac(key, region); key = hmac(key, service); key = hmac(key, "aws4_request")
        return hex(hmac(key, toSign))
    }

    fun canonicalRequest(method: String, canonicalUri: String, query: List<Pair<String, String>>, headers: Map<String, String>, payloadHash: String): String {
        val canonicalQuery = query.map { (k, v) -> encode(k) to encode(v) }.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { (k, v) -> "$k=$v" }
        val canonicalHeaders = headers.entries.map { (k, v) -> k.lowercase() to v.trim().replace(Regex(" +"), " ") }.sortedBy { it.first }
            .joinToString("") { (k, v) -> "$k:$v\n" }
        return "$method\n$canonicalUri\n$canonicalQuery\n$canonicalHeaders\n${signedHeaders(headers)}\n$payloadHash"
    }

    private fun signedHeaders(headers: Map<String, String>) = headers.keys.map { it.lowercase() }.sorted().joinToString(";")
    private fun scope(amzDate: String, region: String, service: String) = "${amzDate.substring(0, 8)}/$region/$service/aws4_request"

    /** RFC 3986 encoding of one value: everything but unreserved characters, `/` included. */
    fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xff
            if ((c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) || (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code) append(c.toChar())
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
    }

    /** A path whose segments are each [encode]d, so `/` separates them; S3 is never double-encoded. */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { encode(it) }

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun hmac(key: ByteArray, data: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(data.encodeToByteArray())
    }
}
