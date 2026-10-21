package me.nathanfallet.teamcode.infrastructure.webhooks

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class HttpWebhookSenderTest {
    private val http = HttpClient(CIO) {
        followRedirects = false; expectSuccess = false
        install(HttpTimeout) { connectTimeoutMillis = 2_000; requestTimeoutMillis = 2_000 }
    }
    @AfterTest fun close() = http.close()

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
            val result = HttpWebhookSender(http).send(url, mapOf("TeamCode-Signature" to "v1=abc", "Content-Type" to "application/json"), """{"id":"d1"}""")
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
            val result = HttpWebhookSender(http).send(url, emptyMap(), "{}")
            assertEquals(302, result.status); assertFalse(result.delivered); assertFalse(result.retryable); assertEquals(0, hits, "The redirect target is never called")
        } finally { server.stop(0); target.stop(0) }
        val (failing, failingUrl) = serve(503)
        try { assertTrue(HttpWebhookSender(http).send(failingUrl, emptyMap(), "{}").retryable) } finally { failing.stop(0) }
    }

    @Test fun refusesMetadataHostsBeforeAndAfterNameResolution() = runBlocking {
        val sender = HttpWebhookSender(http, resolver = { if (it == "rebind.example.test") listOf(InetAddress.getByAddress(byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()))) else throw java.net.UnknownHostException(it) })
        assertEquals("host_not_allowed", sender.send("https://169.254.169.254/latest", emptyMap(), "{}").error, "A literal metadata address")
        assertEquals("host_not_allowed", sender.send("https://metadata.google.internal/", emptyMap(), "{}").error)
        assertEquals("host_not_allowed", sender.send("https://rebind.example.test/hook", emptyMap(), "{}").error, "A name that resolves to a metadata address")
        assertEquals("dns", sender.send("https://nowhere.example.test/hook", emptyMap(), "{}").error)
    }

    @Test fun networkFailuresAreShortCodes() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0); val port = server.address.port; server.stop(0)
        val result = HttpWebhookSender(http).send("http://127.0.0.1:$port/hook", emptyMap(), "{}")
        assertNull(result.status); assertTrue(result.error in setOf("connect", "timeout"), result.error); assertTrue(result.retryable)
        val slow = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        slow.createContext("/hook") { Thread.sleep(4_000); it.sendResponseHeaders(200, -1); it.close() }; slow.start()
        try { assertEquals("timeout", HttpWebhookSender(http).send("http://127.0.0.1:${slow.address.port}/hook", emptyMap(), "{}").error) } finally { slow.stop(0) }
    }
}
