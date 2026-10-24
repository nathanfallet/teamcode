package me.nathanfallet.teamcode.infrastructure.webhooks

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class HttpWebhookSenderTest {
    private val senders = mutableListOf<HttpWebhookSender>()
    @AfterTest fun close() = senders.forEach { it.close() }
    private fun sender(allowPrivateTargets: Boolean = false, lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }) =
        HttpWebhookSender(allowPrivateTargets, lookup, requestTimeoutMillis = 2_000).also { senders += it }
    /** The local receivers below listen on loopback, which only an operator's opt-in allows. */
    private fun internal() = sender(allowPrivateTargets = true)

    private fun serve(status: Int, onRequest: (headers: Map<String, String>, body: String) -> Unit = { _, _ -> }, location: String? = null): Pair<HttpServer, String> {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/hook") { exchange ->
            onRequest(exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() }, exchange.requestBody.readBytes().decodeToString())
            location?.let { exchange.responseHeaders.add("Location", it) }
            exchange.sendResponseHeaders(status, -1); exchange.close()
        }
        server.start()
        return server to "http://127.0.0.1:${server.address.port}/hook"
    }

    @Test fun postsTheSignedBodyAndReportsOnlyTheStatus() = runBlocking {
        var seen: Pair<Map<String, String>, String>? = null
        val (server, url) = serve(204, { headers, body -> seen = headers to body })
        try {
            val result = internal().send(url, mapOf("TeamCode-Signature" to "v1=abc", "Content-Type" to "application/json"), """{"id":"d1"}""")
            assertEquals(SendResultOf(204, null), SendResultOf(result.status, result.error)); assertTrue(result.delivered)
            assertEquals("""{"id":"d1"}""", seen!!.second); assertEquals("v1=abc", seen!!.first["teamcode-signature"])
        } finally { server.stop(0) }
    }
    private data class SendResultOf(val status: Int?, val error: String?)

    @Test fun statusesAreReportedAndRedirectsAreNotFollowed() = runBlocking {
        var hits = 0
        val (target, targetUrl) = serve(200, { _, _ -> hits++ })
        val (server, url) = serve(302, location = targetUrl)
        try {
            val result = internal().send(url, emptyMap(), "{}")
            assertEquals(302, result.status); assertFalse(result.delivered); assertFalse(result.retryable); assertEquals(0, hits, "The redirect target is never called")
        } finally { server.stop(0); target.stop(0) }
        val (failing, failingUrl) = serve(503)
        try { assertTrue(internal().send(failingUrl, emptyMap(), "{}").retryable) } finally { failing.stop(0) }
    }

    @Test fun refusesMetadataHostsBeforeAndAfterNameResolution() = runBlocking {
        val sender = sender(allowPrivateTargets = true, lookup = { if (it == "rebind.example.test") listOf(InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()))) else throw java.net.UnknownHostException(it) })
        assertEquals("host_not_allowed", sender.send("https://169.254.169.254/latest", emptyMap(), "{}").error, "A literal metadata address")
        assertEquals("host_not_allowed", sender.send("https://metadata.google.internal/", emptyMap(), "{}").error)
        assertEquals("host_not_allowed", sender.send("https://rebind.example.test/hook", emptyMap(), "{}").error, "A name that resolves to a metadata address")
        assertEquals("dns", sender.send("https://nowhere.example.test/hook", emptyMap(), "{}").error)
    }

    /** Audit L1: without the operator's opt-in, loopback and private targets are refused before any connection. */
    @Test fun privateTargetsAreRefusedByDefault() = runBlocking {
        var hits = 0
        val (server, url) = serve(204, { _, _ -> hits++ })
        try {
            val sender = sender(lookup = { host ->
                when (host) {
                    "internal.example.test" -> listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 5)))
                    "mixed.example.test" -> listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 215.toByte(), 14)), InetAddress.getLoopbackAddress())
                    else -> InetAddress.getAllByName(host).toList()
                }
            })
            assertEquals("host_not_allowed", sender.send(url, emptyMap(), "{}").error, "A loopback receiver")
            assertEquals("host_not_allowed", sender.send("https://10.0.0.5:8443/x", emptyMap(), "{}").error)
            assertEquals("host_not_allowed", sender.send("https://localhost/x", emptyMap(), "{}").error)
            assertEquals("host_not_allowed", sender.send("https://internal.example.test/x", emptyMap(), "{}").error, "A name that resolves to a private address")
            assertEquals("host_not_allowed", sender.send("https://mixed.example.test/x", emptyMap(), "{}").error, "Every answer must be public")
            assertEquals(0, hits)
            assertEquals(204, internal().send(url, emptyMap(), "{}").status, "The operator's opt-in allows it")
            assertEquals("dns", sender(lookup = { throw java.net.UnknownHostException(it) }).send("https://nowhere.example.test/x", emptyMap(), "{}").error)
        } finally { server.stop(0) }
    }

    /** A literal private address in the URL is refused on the URL itself, before any lookup or connection. */
    @Test fun aLiteralPrivateUrlIsRefusedBeforeAnyLookup() = runBlocking {
        var lookups = 0
        val counted = sender(lookup = { lookups++; listOf(InetAddress.getByAddress(byteArrayOf(93.toByte(), 184.toByte(), 215.toByte(), 14))) })
        for (url in listOf("https://10.0.0.5:8443/x", "https://127.0.0.1/x", "https://[::1]/x", "https://[fd12::1]/x", "https://0x7f000001/x", "https://localhost/x"))
            assertEquals("host_not_allowed", counted.send(url, emptyMap(), "{}").error, url)
        assertEquals(0, lookups)
    }

    /**
     * Audit L2: the connection dials the address its own lookup checked, and that lookup is the only one. A name that
     * answers a loopback address is refused without reaching the local receiver; with the operator's opt-in, the
     * request reaches exactly the checked address, with the original name kept in `Host`.
     */
    @Test fun theConnectionDialsTheAddressItChecked() = runBlocking {
        val hosts = mutableListOf<String>()
        val (server, url) = serve(204, { headers, _ -> hosts += headers["host"].orEmpty() })
        val port = url.substringAfter("127.0.0.1:").substringBefore('/')
        var lookups = 0
        val loopback = { _: String -> lookups++; listOf(InetAddress.getLoopbackAddress()) }
        try {
            val result = sender(lookup = loopback).send("http://rebind.example.test:$port/hook", emptyMap(), "{}")
            assertEquals("host_not_allowed", result.error, "Refused at the connection"); assertNull(result.status)
            assertTrue(hosts.isEmpty(), "The refused answer is never dialled")
            assertEquals(204, sender(allowPrivateTargets = true, lookup = loopback).send("http://receiver.example.test:$port/hook", emptyMap(), "{}").status)
            assertEquals(listOf("receiver.example.test:$port"), hosts, "Dialled the checked address, with the name kept in Host")
            assertEquals(2, lookups, "One lookup per connection, the one that was checked")
        } finally { server.stop(0) }
    }

    @Test fun networkFailuresAreShortCodes() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0); val port = server.address.port; server.stop(0)
        val result = internal().send("http://127.0.0.1:$port/hook", emptyMap(), "{}")
        assertNull(result.status); assertTrue(result.error in setOf("connect", "timeout"), result.error); assertTrue(result.retryable)
        val slow = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        slow.createContext("/hook") { Thread.sleep(4_000); it.sendResponseHeaders(200, -1); it.close() }; slow.start()
        try { assertEquals("timeout", internal().send("http://127.0.0.1:${slow.address.port}/hook", emptyMap(), "{}").error) } finally { slow.stop(0) }
    }
}
