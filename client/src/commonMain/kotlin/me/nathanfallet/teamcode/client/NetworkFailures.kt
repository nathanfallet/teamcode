package me.nathanfallet.teamcode.client

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

/**
 * Network failures by family, shared by the CLI's server messages and the release client. [HEADERS] is an answer whose
 * headers the engine's parser refused (a header line over 8 192 bytes for ktor-http-cio, used by the curl and WinHttp
 * engines); [PROXY] a proxy that refused or failed the connection.
 */
enum class NetworkFailure { DNS, REFUSED, TIMEOUT, TLS, CLOSED, PROXY, HEADERS, OTHER }

/**
 * The family of [error], detected from ktor's common types and each engine's error names and messages (inspected, never
 * printed); null when the error was not raised by the HTTP stack. A local disk or permission failure (also an IOException
 * on the JVM, where okio uses java.io) must not read as an unreachable server.
 */
fun networkFailure(error: Throwable): NetworkFailure? {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val text = chain.joinToString("\n") { it.message.orEmpty() }
    fun mentions(fragments: List<String>) = fragments.any { text.contains(it) }
    if (mentions(HEADER_TEXT)) return NetworkFailure.HEADERS
    val names = chain.map { it::class.simpleName.orEmpty() }
    fun named(vararg candidates: String) = names.any { name -> candidates.any(name::contains) }
    val packages = chain.map { it::class.qualifiedName.orEmpty() }
    // The curl engine throws plain IllegalStateExceptions; its messages name the request it failed.
    val fromHttpStack = packages.any { name -> HTTP_PACKAGES.any(name::startsWith) } || named(*ENGINE_NAMES) || mentions(CURL_TEXT) ||
        chain.any { it is ConnectTimeoutException || it is SocketTimeoutException || it is HttpRequestTimeoutException }
    if (!fromHttpStack) return null
    when {
        named("SSL", "Tls", "TLS", "Certificate") -> return NetworkFailure.TLS
        named("UnknownHost", "UnresolvedAddress") -> return NetworkFailure.DNS
        chain.any { it is ConnectTimeoutException || it is SocketTimeoutException || it is HttpRequestTimeoutException } || named("Timeout") -> return NetworkFailure.TIMEOUT
        named("ConnectException", "NoRouteToHost", "PortUnreachable") -> return if (mentions(CURL_PROXY_TEXT)) NetworkFailure.PROXY else NetworkFailure.REFUSED
        named("EOFException", "ClosedReceiveChannel", "ClosedByteChannel") -> return NetworkFailure.CLOSED
    }
    // Numeric engine codes only count for their own engine, so a port number elsewhere cannot match.
    val darwin = named("Darwin"); val winHttp = named("WinHttp")
    fun code(darwinCodes: List<Int>, winHttpCodes: List<Int>) =
        darwin && darwinCodes.any { text.contains("Code=$it ") || text.contains("Code=$it\"") || text.endsWith("Code=$it") } ||
            winHttp && winHttpCodes.any { Regex("(^|\\D)$it(\\D|$)").containsMatchIn(text) }
    return when {
        // Before DNS: curl's "Could not resolve proxy" is a proxy failure.
        // curl's own proxy errors first: "Could not resolve proxy" would otherwise read as DNS.
        mentions(CURL_PROXY_TEXT) -> NetworkFailure.PROXY
        mentions(TLS_TEXT) || code(listOf(-1200, -1201, -1202, -1203, -1204, -1205, -1206, -2000), listOf(12175, 12037, 12038, 12044, 12045, 12057, 12169)) -> NetworkFailure.TLS
        mentions(DNS_TEXT) || code(listOf(-1003, -1006), listOf(12007)) -> NetworkFailure.DNS
        mentions(TIMEOUT_TEXT) || code(listOf(-1001), listOf(12002)) -> NetworkFailure.TIMEOUT
        mentions(PROXY_TEXT) -> NetworkFailure.PROXY
        mentions(REFUSED_TEXT) || code(listOf(-1004, -1009, -1005), listOf(12029, 12030)) -> NetworkFailure.REFUSED
        else -> NetworkFailure.OTHER
    }
}

/**
 * A short, printable technical cause: the class and message of [error] and its first causes, URLs reduced to their origin
 * (a signed CDN query never shows), Darwin's `UserInfo` dictionary dropped, control characters removed, at most 200 characters.
 */
fun releaseFailureDetail(error: Throwable): String {
    val parts = generateSequence(error) { it.cause }.take(3).map { cause ->
        val message = cause.message.orEmpty().substringBefore("UserInfo=")
            .replace(URL) { match -> match.groupValues[1] + match.groupValues[2] + "/…" }
            .replace(USERINFO, "")
            .replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().trimEnd(',', ';', ' ')
        val name = cause::class.simpleName ?: "Exception"
        if (message.isEmpty()) name else "$name: $message"
    }.distinct().toList()
    val text = parts.joinToString(" < ")
    return if (text.length <= 200) text else text.take(199) + "…"
}

// The origin without any user:password@ part, then the rest of the URL dropped.
private val URL = Regex("((?:https?|wss?|socks[45]?h?)://)(?:[^/\\s\"'<>?#@]*@)?([^/\\s\"'<>?#@]+)[^\\s\"'<>]*")

// Engines: OkHttp (JVM tests), Curl (Linux), Darwin/NSURLSession (macOS) and WinHTTP (Windows). Case-sensitive on purpose:
// a lowercase host name such as `tls.example.com` in an engine message must not look like a TLS failure.
// Any remaining `name:secret@host` (a proxy written without a scheme).
private val USERINFO = Regex("[^\\s/\"'<>@:]+:[^\\s/\"'<>@]+@")

private val HTTP_PACKAGES = listOf("io.ktor.", "java.net.", "javax.net.", "okhttp3.", "java.nio.channels.UnresolvedAddress")
private val ENGINE_NAMES = arrayOf("Curl", "Darwin", "WinHttp")
private val CURL_TEXT = listOf("CurlRequestData(", "Connection failed for request", "TLS verification failed for request")
// ktor-http-cio's own wording (HttpParser, and the curl and WinHttp engines that use it), not generic parser errors.
private val HEADER_TEXT = listOf("Header line length limit exceeded", "Failed to parse response header")
private val CURL_PROXY_TEXT = listOf("resolve proxy", "from proxy after CONNECT", "CONNECT tunnel failed", "Proxy CONNECT aborted")
private val PROXY_TEXT = listOf("proxy server", "web proxy", "Proxy Authentication")
private val TLS_TEXT = listOf("SSL", "TLS", "certificate", "Certificate", "security error")
private val DNS_TEXT = listOf("resolve host", "Could not resolve", "Couldn't resolve", "could not be resolved", "Unable to resolve", "nodename nor servname",
    "Name or service not known")
private val TIMEOUT_TEXT = listOf("timed out", "Timeout was reached")
private val REFUSED_TEXT = listOf("Couldn't connect", "Could not connect", "Connection refused", "Failed to connect", "could not be established")
