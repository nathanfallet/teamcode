package me.nathanfallet.teamcode.presentation

import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import java.net.InetAddress

/**
 * The reverse proxies whose `X-Forwarded-For` the server believes, as CIDR ranges or single addresses
 * (`TEAMCODE_TRUSTED_PROXIES`). Login throttling keys its budget on the client's address, so behind a proxy that is not
 * listed every client shares the proxy's address. Nothing is trusted by default: a client could otherwise choose its own
 * address by sending the header.
 */
class TrustedProxies(ranges: List<String>) {
    private val ranges = ranges.map(::range)

    /** The address of the client: [peer], or the last forwarded address that is not a trusted proxy when [peer] is one. */
    fun clientAddress(peer: String, forwardedFor: List<String>): String = canonical(walk(peer, forwardedFor))
    private fun walk(peer: String, forwardedFor: List<String>): String {
        if (ranges.isEmpty()) return peer
        var client = peer
        val hops = forwardedFor.flatMap { it.split(',') }.map { it.trim() }
        for (hop in hops.asReversed()) {
            if (!isTrusted(client)) break
            // Anything that is not an address literal was not written by a proxy that appends the client's address.
            if (literal(hop) == null) break
            client = hop
        }
        return client
    }
    private fun isTrusted(address: String): Boolean {
        val bytes = literal(address)?.address ?: return false
        return ranges.any { (network, bits) -> network.size == bytes.size && matches(network, bytes, bits) }
    }

    companion object {
        val NONE = TrustedProxies(emptyList())
        /** One spelling per address (IPv4-mapped IPv6 as IPv4, IPv6 uncompressed and lowercase), so it is one throttle key. */
        private fun canonical(address: String): String = literal(address)?.hostAddress ?: address
        /** Comma-separated ranges; a blank value trusts nothing. */
        fun parse(value: String?): TrustedProxies = TrustedProxies(value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() })

        private fun range(value: String): Pair<ByteArray, Int> {
            val address = value.substringBefore('/')
            val network = requireNotNull(literal(address)) { "TEAMCODE_TRUSTED_PROXIES: $value is not an IP address or CIDR range." }.address
            val bits = if ('/' in value) requireNotNull(value.substringAfter('/').toIntOrNull()) { "TEAMCODE_TRUSTED_PROXIES: $value has an invalid prefix length." } else network.size * 8
            require(bits in 0..network.size * 8) { "TEAMCODE_TRUSTED_PROXIES: $value has an invalid prefix length." }
            return network to bits
        }
        private fun matches(network: ByteArray, address: ByteArray, bits: Int): Boolean {
            for (i in 0 until bits / 8) if (network[i] != address[i]) return false
            val rest = bits % 8
            if (rest == 0) return true
            val mask = (0xff shl (8 - rest)) and 0xff
            return (network[bits / 8].toInt() and mask) == (address[bits / 8].toInt() and mask)
        }
        /** Parses an IPv4 or IPv6 literal without any name resolution; null for anything else. */
        private fun literal(value: String): InetAddress? {
            val text = value.removePrefix("[").removeSuffix("]")
            val ipv4 = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""").matchEntire(text)
            if (ipv4 != null) return if (ipv4.groupValues.drop(1).all { it.toInt() <= 255 }) InetAddress.getByName(text) else null
            if (text.isEmpty() || ':' !in text || text.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return null
            return try { InetAddress.getByName(text) } catch (_: java.net.UnknownHostException) { null }
        }
    }
}

internal val TRUSTED_PROXIES = AttributeKey<TrustedProxies>("teamcode.trusted.proxies")
/** The client's address for login throttling, through the configured [TrustedProxies]. */
internal fun ApplicationCall.clientAddress(): String = (application.attributes.getOrNull(TRUSTED_PROXIES) ?: TrustedProxies.NONE)
    .clientAddress(request.local.remoteAddress, request.headers.getAll(HttpHeaders.XForwardedFor).orEmpty())
