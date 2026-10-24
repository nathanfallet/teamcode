package me.nathanfallet.teamcode.infrastructure.cloud

import java.math.BigInteger
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.cloud.ReconcileCloudSessionsUseCase.Companion.MANAGED_LABEL

/**
 * The NetworkPolicy applied to every session namespace before a session pod is created, set by the operator through
 * the deployment (`TEAMCODE_CLOUD_SESSION_*`, the chart's `cloudSessions.networkPolicy`), never from the dashboard: an
 * administrator or an `admin:cloud` key cannot widen what a pod reaches.
 *
 * It selects every TeamCode pod of the namespace (`app.kubernetes.io/managed-by=teamcode`), denies all ingress, and
 * allows egress only to:
 * - DNS, port 53 over UDP and TCP, to any destination (CoreDNS pods, NodeLocal DNSCache, a cloud resolver);
 * - with [internet], every public address: `0.0.0.0/0` and `::/0` minus [PRIVATE_V4], [PRIVATE_V6] and [blockedCidrs],
 *   so link-local and cloud metadata addresses, RFC 1918, carrier-grade NAT and unique-local ranges (where pods,
 *   services, nodes and most metadata endpoints live) are unreachable;
 * - the [extraEgress] rules, verbatim (for example the ingress controller pods when the server URL hairpins inside the
 *   cluster), except that an `ipBlock` may never reach a link-local range.
 *
 * The agent needs no direct route to model providers: inference goes through the runner's loopback proxy to the
 * TeamCode server, which the pod reaches at its public URL.
 */
data class SessionNetworkPolicy(
    val enabled: Boolean = true,
    val internet: Boolean = true,
    val blockedCidrs: List<String> = emptyList(),
    val extraEgress: JsonArray = JsonArray(emptyList()),
) {
    init {
        blockedCidrs.forEach { require(Cidr.parse(it).prefix > 0) { "A blocked CIDR cannot be a whole address family: $it." } }
        extraEgress.forEach { rule -> requireValidRule(rule) }
    }

    /** The NetworkPolicy object, as sent with server-side apply. */
    fun manifest(): JsonObject = buildJsonObject {
        put("apiVersion", "networking.k8s.io/v1"); put("kind", "NetworkPolicy")
        putJsonObject("metadata") { put("name", NAME); putJsonObject("labels") { put(MANAGED_LABEL, "teamcode") } }
        putJsonObject("spec") {
            putJsonObject("podSelector") { putJsonObject("matchLabels") { put(MANAGED_LABEL, "teamcode") } }
            putJsonArray("policyTypes") { add("Ingress"); add("Egress") }
            // No ingress rule: nothing may connect to a session pod. The runner only dials out.
            putJsonArray("ingress") {}
            putJsonArray("egress") {
                addJsonObject { putJsonArray("ports") { for (protocol in listOf("UDP", "TCP")) addJsonObject { put("protocol", protocol); put("port", 53) } } }
                if (internet) {
                    val blocked = blockedCidrs.map(Cidr::parse)
                    for ((all, private) in listOf(Cidr.parse("0.0.0.0/0") to PRIVATE_V4, Cidr.parse("::/0") to PRIVATE_V6)) {
                        val except = (private.map(Cidr::parse) + blocked.filter { it.v6 == all.v6 }).distinct()
                        addJsonObject { putJsonArray("to") { addJsonObject { putJsonObject("ipBlock") {
                            put("cidr", all.toString()); putJsonArray("except") { except.forEach { add(it.toString()) } }
                        } } } }
                    }
                }
                extraEgress.forEach { add(it) }
            }
        }
    }

    companion object {
        const val NAME = "teamcode-sessions"
        /** Never reachable from a session pod, whatever the settings: link-local, where cloud metadata services answer. */
        private val LINK_LOCAL = listOf("169.254.0.0/16", "fe80::/10").map(Cidr::parse)
        /** Not public: this host, RFC 1918, carrier-grade NAT (Alibaba's metadata), loopback, link-local, IETF, benchmark, multicast and reserved. */
        val PRIVATE_V4 = listOf("0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
            "192.0.0.0/24", "192.168.0.0/16", "198.18.0.0/15", "224.0.0.0/3")
        /** IPv6 forms that are not public or embed an IPv4 destination: mapped, NAT64, Teredo, 6to4, unique-local (AWS metadata), link-local, multicast. */
        val PRIVATE_V6 = listOf("::/127", "::ffff:0:0/96", "64:ff9b::/96", "64:ff9b:1::/48", "2001::/32", "2002::/16", "fc00::/7", "fe80::/10", "ff00::/8")

        /**
         * Reads the deployment variables; an invalid value fails the server start rather than run sessions with a
         * policy other than the one the operator wrote.
         */
        fun fromEnvironment(get: (String) -> String?): SessionNetworkPolicy {
            fun value(name: String) = get(name)?.trim()?.takeIf { it.isNotEmpty() }
            fun flag(name: String, default: Boolean) = when (value(name)?.lowercase()) {
                null -> default; "true" -> true; "false" -> false
                else -> throw IllegalArgumentException("$name must be true or false.")
            }
            val extra = value("TEAMCODE_CLOUD_SESSION_EGRESS_EXTRA")?.let {
                try { Json.parseToJsonElement(it) as? JsonArray } catch (_: Exception) { null }
                    ?: throw IllegalArgumentException("TEAMCODE_CLOUD_SESSION_EGRESS_EXTRA must be a JSON array of NetworkPolicy egress rules.")
            } ?: JsonArray(emptyList())
            return SessionNetworkPolicy(
                enabled = flag("TEAMCODE_CLOUD_SESSION_NETWORK_POLICY", true),
                internet = flag("TEAMCODE_CLOUD_SESSION_EGRESS_INTERNET", true),
                blockedCidrs = value("TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
                extraEgress = extra,
            )
        }

        /** An egress rule holds only `to` and `ports`; an `ipBlock` that overlaps link-local must except all of it. */
        private fun requireValidRule(rule: JsonElement) {
            val fields = rule as? JsonObject ?: throw IllegalArgumentException("Each extra egress rule must be an object.")
            require(fields.keys.all { it == "to" || it == "ports" }) { "An extra egress rule holds only `to` and `ports`." }
            val peers = fields["to"]
            require(peers == null || peers is JsonArray) { "`to` must be an array." }
            require(fields["ports"] == null || fields["ports"] is JsonArray) { "`ports` must be an array." }
            // A rule without `to` allows every destination on its ports, link-local included.
            require(peers != null && peers.jsonArray.isNotEmpty()) { "An extra egress rule must name its destinations in `to`." }
            for (peer in peers.jsonArray) {
                val block = (peer as? JsonObject ?: throw IllegalArgumentException("Each `to` entry must be an object."))["ipBlock"] ?: continue
                val cidr = Cidr.parse(((block as? JsonObject)?.get("cidr") as? JsonPrimitive)?.contentOrNull
                    ?: throw IllegalArgumentException("An ipBlock needs a cidr."))
                val except = (block.jsonObject["except"] as? JsonArray)?.map { Cidr.parse(it.jsonPrimitive.content) }.orEmpty()
                for (linkLocal in LINK_LOCAL.filter { it.v6 == cidr.v6 }) {
                    require(!linkLocal.contains(cidr)) { "An extra egress rule may not reach link-local addresses: $cidr." }
                    require(!cidr.contains(linkLocal) || except.any { it.contains(linkLocal) }) {
                        "An extra egress ipBlock containing $linkLocal must list it in `except`."
                    }
                }
            }
        }
    }
}

/** An IPv4 or IPv6 CIDR given as a literal. Parsed here, never by `InetAddress`, so no text is ever resolved as a host name. */
internal data class Cidr(val network: BigInteger, val prefix: Int, val v6: Boolean) {
    private val bits get() = if (v6) 128 else 32
    fun contains(other: Cidr) = other.v6 == v6 && other.prefix >= prefix && other.network.shiftRight(bits - prefix) == network.shiftRight(bits - prefix)
    override fun toString(): String {
        val address = if (!v6) (3 downTo 0).joinToString(".") { network.shiftRight(it * 8).toInt().and(0xff).toString() }
            else compact((7 downTo 0).map { network.shiftRight(it * 16).toInt().and(0xffff).toString(16) })
        return "$address/$prefix"
    }

    companion object {
        fun parse(text: String): Cidr {
            fun invalid(): Nothing = throw IllegalArgumentException("Not a CIDR: $text.")
            val parts = text.trim().split('/')
            if (parts.size != 2) invalid()
            val v6 = ':' in parts[0]
            val value = (if (v6) v6(parts[0]) else v4(parts[0])) ?: invalid()
            val bits = if (v6) 128 else 32
            val prefix = parts[1].takeIf { it.length in 1..3 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 0..bits } ?: invalid()
            // Host bits are cleared, as the API server reads the block.
            return Cidr(value.shiftRight(bits - prefix).shiftLeft(bits - prefix), prefix, v6)
        }

        private fun v4(text: String): BigInteger? {
            val octets = text.split('.')
            if (octets.size != 4) return null
            return octets.fold(BigInteger.ZERO) { acc, octet ->
                val byte = octet.takeIf { it.length in 1..3 && it.all(Char::isDigit) }?.toInt()?.takeIf { it <= 255 } ?: return null
                acc.shiftLeft(8).or(BigInteger.valueOf(byte.toLong()))
            }
        }

        private fun v6(text: String): BigInteger? {
            fun groups(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                val pieces = part.split(':')
                val out = mutableListOf<Int>()
                pieces.forEachIndexed { index, piece ->
                    // A trailing dotted IPv4 address stands for the last two groups.
                    if (index == pieces.lastIndex && '.' in piece) {
                        val v4 = v4(piece)?.toInt() ?: return null
                        out += v4.ushr(16).and(0xffff); out += v4.and(0xffff)
                    } else {
                        if (piece.length !in 1..4 || !piece.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
                        out += piece.toInt(16)
                    }
                }
                return out
            }
            val halves = text.split("::")
            val all = when (halves.size) {
                1 -> groups(text)?.takeIf { it.size == 8 }
                2 -> {
                    val head = groups(halves[0]) ?: return null; val tail = groups(halves[1]) ?: return null
                    if (head.size + tail.size > 7) return null
                    head + List(8 - head.size - tail.size) { 0 } + tail
                }
                else -> null
            } ?: return null
            return all.fold(BigInteger.ZERO) { acc, group -> acc.shiftLeft(16).or(BigInteger.valueOf(group.toLong())) }
        }

        /** The longest run of two or more zero groups becomes `::` (RFC 5952). */
        private fun compact(groups: List<String>): String {
            var bestStart = -1; var bestLength = 1
            var i = 0
            while (i < groups.size) {
                if (groups[i] == "0") {
                    var j = i; while (j < groups.size && groups[j] == "0") j++
                    if (j - i > bestLength) { bestStart = i; bestLength = j - i }
                    i = j
                } else i++
            }
            if (bestStart < 0) return groups.joinToString(":")
            return groups.subList(0, bestStart).joinToString(":") + "::" + groups.subList(bestStart + bestLength, groups.size).joinToString(":")
        }
    }
}
