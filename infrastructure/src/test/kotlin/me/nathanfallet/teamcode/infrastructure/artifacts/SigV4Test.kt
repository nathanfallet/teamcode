package me.nathanfallet.teamcode.infrastructure.artifacts

import kotlin.test.*

/**
 * The worked examples of the S3 documentation, "Signature Calculations for the Authorization Header: Transferring
 * Payload in a Single Chunk (AWS Signature Version 4)": the example account, bucket `examplebucket`, `us-east-1`,
 * 2013-05-24 00:00:00 UTC.
 */
class SigV4Test {
    private val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
    private val date = "20130524T000000Z"
    private val host = "examplebucket.s3.amazonaws.com"

    @Test fun getObjectWithRange() {
        val headers = mapOf("host" to host, "range" to "bytes=0-9", "x-amz-content-sha256" to SigV4.EMPTY_SHA256, "x-amz-date" to date)
        assertEquals("f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            SigV4.signature("GET", "/test.txt", emptyList(), headers, SigV4.EMPTY_SHA256, secret, "us-east-1", date))
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
            "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            SigV4.authorization("GET", "/test.txt", emptyList(), headers, SigV4.EMPTY_SHA256, "AKIAIOSFODNN7EXAMPLE", secret, "us-east-1", date))
    }

    @Test fun putObjectWithAnEncodedKey() {
        val payload = SigV4.hex(SigV4.sha256("Welcome to Amazon S3.".encodeToByteArray()))
        assertEquals("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072", payload)
        val headers = mapOf("Host" to host, "Date" to "Fri, 24 May 2013 00:00:00 GMT", "x-amz-content-sha256" to payload, "x-amz-date" to date,
            "x-amz-storage-class" to "REDUCED_REDUNDANCY")
        assertEquals("/test%24file.text", SigV4.encodePath("/test\$file.text"))
        assertEquals("98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd",
            SigV4.signature("PUT", SigV4.encodePath("/test\$file.text"), emptyList(), headers, payload, secret, "us-east-1", date))
    }

    @Test fun getBucketLifecycleWithAnEmptyQueryValue() {
        val headers = mapOf("host" to host, "x-amz-content-sha256" to SigV4.EMPTY_SHA256, "x-amz-date" to date)
        assertEquals("fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543",
            SigV4.signature("GET", "/", listOf("lifecycle" to ""), headers, SigV4.EMPTY_SHA256, secret, "us-east-1", date))
    }

    @Test fun listObjectsWithSortedQueryParameters() {
        val headers = mapOf("host" to host, "x-amz-content-sha256" to SigV4.EMPTY_SHA256, "x-amz-date" to date)
        assertEquals("34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7",
            SigV4.signature("GET", "/", listOf("prefix" to "J", "max-keys" to "2"), headers, SigV4.EMPTY_SHA256, secret, "us-east-1", date))
    }

    @Test fun encodingFollowsRfc3986() {
        assertEquals("a%20b%2Fc~d_e-f.g%C3%A9", SigV4.encode("a b/c~d_e-f.gé"))
        assertEquals("/bucket/team/artifacts/x/blobs/ab%2Bcd", SigV4.encodePath("/bucket/team/artifacts/x/blobs/ab+cd"))
    }
}
