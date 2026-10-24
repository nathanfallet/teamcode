package me.nathanfallet.teamcode.infrastructure.network

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** Thrown by [PinnedDns] when a host resolves to nothing or to an address the policy refuses. */
class PinnedDnsRefusal(val host: String) : UnknownHostException("The destination address is not allowed.")

/** Thrown by [PinnedDns] when the lookup takes longer than its deadline: an I/O failure, never a cancellation. */
class PinnedDnsTimeout(val host: String) : UnknownHostException("The destination did not resolve in time.")

/**
 * The address check and the connection on one lookup (audit L2). Used as the CIO engine's `dnsResolver`: the engine
 * dials exactly the address returned here, and keeps the URL's host name for SNI, certificate verification and the
 * `Host` header. A name that answers a public address to a first lookup and a private one to the next (DNS rebinding)
 * can no longer pass the check with one and be dialled with the other.
 *
 * Every answer must pass [allowed], not just the first: a name that also resolves to a refused address is refused
 * whole, as the checks before the connection do.
 */
object PinnedDns {
    private const val LOOKUP_DEADLINE_MILLIS = 5_000L

    fun resolver(
        allowed: (ByteArray) -> Boolean,
        lookup: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): suspend (String) -> List<String> = { name ->
        val host = name.removePrefix("[").removeSuffix("]")
        // A lookup cannot be interrupted, so it runs outside the caller's scope and is abandoned after the deadline.
        // A timeout is reported as an I/O failure: a CancellationException here would look like the caller's own cancellation.
        val addresses = withTimeoutOrNull(LOOKUP_DEADLINE_MILLIS) { CoroutineScope(Dispatchers.IO).async { lookup(host) }.await() }
            ?: throw PinnedDnsTimeout(host)
        if (addresses.isEmpty() || addresses.any { !allowed(it.address) }) throw PinnedDnsRefusal(host)
        addresses.map { it.hostAddress }
    }
}
