package me.nathanfallet.teamcode.infrastructure.cloud

import java.math.BigInteger
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.cloud.ReconcileCloudSessionsUseCase.Companion.MANAGED_LABEL

/**
 * The NetworkPolicy applied to every session namespace before a session pod is created, set by the operator through
 * the deployment (`TEAMCODE_CLOUD_SESSION_*`, the chart's `cloudSessions.networkPolicy`), never from the dashboard: an
 * administrator or an `admin:cloud` key cannot change what a pod reaches.
 *
 * Sessions keep full internet access; only cluster-internal and metadata endpoints are blocked. The policy selects
 * every TeamCode pod of the namespace (`app.kubernetes.io/managed-by=teamcode`), denies all ingress (other session pods
 * included), and allows egress to:
 * - DNS, port 53 over UDP and TCP, to any destination (CoreDNS pods, NodeLocal DNSCache, a cloud resolver);
 * - the TeamCode server: the addresses its URL for pods resolves to, on its port, even when they are private;
 * - unless [lockdown], every public address on every port: `0.0.0.0/0` and `::/0` minus [PRIVATE_V4], [PRIVATE_V6]
 *   (RFC 1918, carrier-grade NAT, link-local and cloud metadata, unique-local: where pods, services and nodes live),
 *   the Kubernetes API server's addresses and [blockedCidrs];
 * - the [extraEgress] rules, verbatim, except that an `ipBlock` may never reach a link-local range.
 *
 * Inference needs no route to model providers: it goes through the runner's loopback proxy to the TeamCode server.
 */
data class SessionNetworkPolicy(
    val enabled: Boolean = true,
    /** Off by default. On: no internet rule, only DNS, the server and [extraEgress]. */
    val lockdown: Boolean = false,
    val blockedCidrs: List<String> = emptyList(),
    val extraEgress: JsonArray = JsonArray(emptyList()),
) {
    init {
        blockedCidrs.forEach { require(Cidr.parse(it).prefix > 0) { "A blocked CIDR cannot be a whole address family: $it." } }
        extraEgress.forEach { rule -> requireValidRule(rule) }
    }

    /** Where the session pods reach the TeamCode server: its addresses as the server resolves them, and its port. */
    data class Server(val addresses: List<ByteArray>, val port: Int)

    /**
     * The NetworkPolicy object, as sent with server-side apply. [clusterApi] are the API server's addresses, removed
     * from the internet rule (a managed cluster's endpoint is often public).
     */
    fun manifest(server: Server? = null, clusterApi: List<ByteArray> = emptyList()): JsonObject = buildJsonObject {
        put("apiVersion", "networking.k8s.io/v1"); put("kind", "NetworkPolicy")
        putJsonObject("metadata") { put("name", NAME); putJsonObject("labels") { put(MANAGED_LABEL, "teamcode") } }
        putJsonObject("spec") {
            putJsonObject("podSelector") { putJsonObject("matchLabels") { put(MANAGED_LABEL, "teamcode") } }
            putJsonArray("policyTypes") { add("Ingress"); add("Egress") }
            // No ingress rule: nothing may connect to a session pod. The runner only dials out.
            putJsonArray("ingress") {}
            putJsonArray("egress") {
                addJsonObject { putJsonArray("ports") { for (protocol in listOf("UDP", "TCP")) addJsonObject { put("protocol", protocol); put("port", 53) } } }
                // The server, explicitly: its address may be private (an internal load balancer). Never a link-local one.
                server?.addresses?.map(Cidr::of)?.distinct()?.filter { address -> LINK_LOCAL.none { it.contains(address) } }?.takeIf { it.isNotEmpty() }?.let { addresses ->
                    addJsonObject {
                        putJsonArray("to") { addresses.forEach { address -> addJsonObject { putJsonObject("ipBlock") { put("cidr", address.toString()) } } } }
                        putJsonArray("ports") { addJsonObject { put("protocol", "TCP"); put("port", server.port) } }
                    }
                }
                if (!lockdown) {
                    val blocked = blockedCidrs.map(Cidr::parse) + clusterApi.map(Cidr::of)
                    val blockedV4 = PRIVATE_V4.map(Cidr::parse) + blocked.filter { !it.v6 }
                    // NAT64 and 6to4 stay usable (DNS64 clusters reach IPv4-only sites through them), minus the IPv4 ranges they embed.
                    val embedded = blockedV4.flatMap { listOf(it.nat64(), it.sixToFour()) }
                    for ((all, ranges) in listOf(Cidr.parse("0.0.0.0/0") to PRIVATE_V4.map(Cidr::parse), Cidr.parse("::/0") to PRIVATE_V6.map(Cidr::parse) + embedded)) {
                        // Only what the private ranges do not already cover, so the list stays readable.
                        val except = (ranges + blocked.filter { it.v6 == all.v6 && ranges.none { range -> range.contains(it) } }).distinct()
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
            "192.0.0.0/24", "192.168.0.0/16", "198.18.0.0/15", "224.0.0.0/3",
            // Azure's WireServer (host goal state and extension settings), a public-range address answering every VM.
            "168.63.129.16/32")
        /**
         * IPv6 ranges that are not public: loopback, local-use NAT64, Teredo (its client address is obfuscated), unique-local
         * (AWS metadata), link-local, multicast. Well-known NAT64 (`64:ff9b::/96`) and 6to4 (`2002::/16`) are excepted only
         * where they embed a blocked IPv4 range. IPv4-mapped addresses are never routed, and the API server refuses them.
         */
        val PRIVATE_V6 = listOf("::/127", "64:ff9b:1::/48", "2001::/32", "fc00::/7", "fe80::/10", "ff00::/8")

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
                lockdown = flag("TEAMCODE_CLOUD_SESSION_EGRESS_LOCKDOWN", false),
                blockedCidrs = value("TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
                extraEgress = extra,
            )
        }

        /**
         * Checks an operator rule at startup with the API server's own rules for egress rules, so a rule it would refuse
         * stops the start instead of failing every pod later: `to` (required, not empty) and `ports` only; a peer is an
         * `ipBlock` alone or a `namespaceSelector` and/or `podSelector`; `except` entries are strict subsets of their
         * block; ports name TCP, UDP or SCTP and a number (1-65535, with an optional `endPort`) or a service name. An
         * `ipBlock` that overlaps link-local must except all of it.
         */
        private fun requireValidRule(rule: JsonElement) {
            val fields = rule as? JsonObject ?: throw IllegalArgumentException("Each extra egress rule must be an object.")
            require(fields.keys.all { it == "to" || it == "ports" }) { "An extra egress rule holds only `to` and `ports`." }
            val peers = fields["to"]
            require(peers == null || peers is JsonArray) { "`to` must be an array." }
            // A rule without `to` allows every destination on its ports, link-local included.
            require(peers != null && peers.jsonArray.isNotEmpty()) { "An extra egress rule must name its destinations in `to`." }
            for (peer in peers.jsonArray) requireValidPeer(peer)
            val ports = fields["ports"] ?: return
            require(ports is JsonArray) { "`ports` must be an array." }
            ports.forEach(::requireValidPort)
        }

        private fun requireValidPeer(peer: JsonElement) {
            val entry = peer as? JsonObject ?: throw IllegalArgumentException("Each `to` entry must be an object.")
            require(entry.isNotEmpty() && entry.keys.all { it in setOf("ipBlock", "namespaceSelector", "podSelector") }) {
                "A `to` entry holds ipBlock, namespaceSelector or podSelector."
            }
            for (key in listOf("namespaceSelector", "podSelector")) entry[key]?.let(::requireValidSelector)
            val block = entry["ipBlock"] ?: return
            require(entry.size == 1) { "An ipBlock cannot be combined with selectors." }
            val fields = block as? JsonObject ?: throw IllegalArgumentException("An ipBlock must be an object.")
            require(fields.keys.all { it == "cidr" || it == "except" }) { "An ipBlock holds cidr and except." }
            val cidr = Cidr.parse((fields["cidr"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("An ipBlock needs a cidr."))
            val except = when (val list = fields["except"]) {
                null -> emptyList()
                is JsonArray -> list.map { Cidr.parse((it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("`except` holds CIDRs.")) }
                else -> throw IllegalArgumentException("`except` must be an array.")
            }
            except.forEach { require(cidr.contains(it) && it != cidr) { "$it is not a strict subset of $cidr." } }
            for (linkLocal in LINK_LOCAL.filter { it.v6 == cidr.v6 }) {
                require(!linkLocal.contains(cidr)) { "An extra egress rule may not reach link-local addresses: $cidr." }
                require(!cidr.contains(linkLocal) || except.any { it.contains(linkLocal) }) {
                    "An extra egress ipBlock containing $linkLocal must list it in `except`."
                }
            }
        }

        private val LABEL_VALUE = Regex("""([A-Za-z0-9]([-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?)?""")
        private val LABEL_KEY = Regex("""([a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?/)?[A-Za-z0-9]([-A-Za-z0-9_.]{0,61}[A-Za-z0-9])?""")

        private fun requireValidSelector(selector: JsonElement) {
            val fields = selector as? JsonObject ?: throw IllegalArgumentException("A selector must be an object.")
            require(fields.keys.all { it == "matchLabels" || it == "matchExpressions" }) { "A selector holds matchLabels and matchExpressions." }
            when (val labels = fields["matchLabels"]) {
                null -> {}
                is JsonObject -> labels.forEach { (key, value) ->
                    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    require(LABEL_KEY.matches(key) && text != null && LABEL_VALUE.matches(text)) { "Invalid label $key in a selector." }
                }
                else -> throw IllegalArgumentException("matchLabels must be an object.")
            }
            when (val expressions = fields["matchExpressions"]) {
                null -> {}
                is JsonArray -> expressions.forEach { expression ->
                    val e = expression as? JsonObject ?: throw IllegalArgumentException("A match expression must be an object.")
                    val key = (e["key"] as? JsonPrimitive)?.content
                    val operator = (e["operator"] as? JsonPrimitive)?.content
                    val values = e["values"] as? JsonArray
                    require(key != null && LABEL_KEY.matches(key)) { "A match expression needs a valid key." }
                    when (operator) {
                        "In", "NotIn" -> require(!values.isNullOrEmpty() && values.all { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(LABEL_VALUE::matches) == true }) { "$operator needs values." }
                        "Exists", "DoesNotExist" -> require(values.isNullOrEmpty()) { "$operator takes no values." }
                        else -> throw IllegalArgumentException("Unknown selector operator $operator.")
                    }
                }
                else -> throw IllegalArgumentException("matchExpressions must be an array.")
            }
        }

        private val PORT_NAME = Regex("""[a-z0-9]([-a-z0-9]{0,13}[a-z0-9])?""")

        private fun requireValidPort(port: JsonElement) {
            val fields = port as? JsonObject ?: throw IllegalArgumentException("Each port must be an object.")
            require(fields.keys.all { it in setOf("protocol", "port", "endPort") }) { "A port holds protocol, port and endPort." }
            fields["protocol"]?.let { require((it as? JsonPrimitive)?.content in setOf("TCP", "UDP", "SCTP")) { "A port's protocol is TCP, UDP or SCTP." } }
            val number = fields["port"] as? JsonPrimitive
            val value = number?.let { if (it.isString) null else it.intOrNull }
            when {
                number == null -> require(fields["endPort"] == null) { "endPort needs port." }
                value != null -> {
                    require(value in 1..65535) { "A port is between 1 and 65535." }
                    fields["endPort"]?.let { end -> require((end as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { it in value..65535 } == true) { "endPort is a port at least port." } }
                }
                else -> {
                    require(number.isString && PORT_NAME.matches(number.content) && number.content.any(Char::isLetter)) { "A named port is a service name." }
                    require(fields["endPort"] == null) { "endPort needs a numeric port." }
                }
            }
        }
    }
}

/** An IPv4 or IPv6 CIDR given as a literal. Parsed here, never by `InetAddress`, so no text is ever resolved as a host name. */
internal data class Cidr(val network: BigInteger, val prefix: Int, val v6: Boolean) {
    private val bits get() = if (v6) 128 else 32
    /** This IPv4 range inside the well-known NAT64 prefix `64:ff9b::/96`. */
    fun nat64() = Cidr(BigInteger.valueOf(0x64ff9b).shiftLeft(96).or(network), 96 + prefix, true).also { require(!v6) }
    /** This IPv4 range inside 6to4 (`2002:V4ADDR::/48`). */
    fun sixToFour() = Cidr(BigInteger.valueOf(0x2002).shiftLeft(112).or(network.shiftLeft(80)), 16 + prefix, true).also { require(!v6) }
    fun contains(other: Cidr) = other.v6 == v6 && other.prefix >= prefix && other.network.shiftRight(bits - prefix) == network.shiftRight(bits - prefix)
    override fun toString(): String {
        val address = if (!v6) (3 downTo 0).joinToString(".") { network.shiftRight(it * 8).toInt().and(0xff).toString() }
            else compact((7 downTo 0).map { network.shiftRight(it * 16).toInt().and(0xffff).toString(16) })
        return "$address/$prefix"
    }

    companion object {
        private val MAPPED = Cidr(BigInteger.valueOf(0xffff).shiftLeft(32), 96, true)
        /** One address, as a /32 or /128. */
        fun of(address: ByteArray): Cidr {
            require(address.size == 4 || address.size == 16) { "Not an IP address." }
            return Cidr(BigInteger(1, address), address.size * 8, address.size == 16)
        }
        fun parse(text: String): Cidr {
            fun invalid(): Nothing = throw IllegalArgumentException("Not a CIDR: $text.")
            val parts = text.trim().split('/')
            if (parts.size != 2) invalid()
            val v6 = ':' in parts[0]
            val value = (if (v6) v6(parts[0]) else v4(parts[0])) ?: invalid()
            val bits = if (v6) 128 else 32
            val prefix = parts[1].takeIf { it.length in 1..3 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 0..bits } ?: invalid()
            // Host bits are cleared, as the API server reads the block.
            return Cidr(value.shiftRight(bits - prefix).shiftLeft(bits - prefix), prefix, v6).also {
                // The API server refuses IPv4-mapped IPv6 blocks in a NetworkPolicy.
                require(!MAPPED.contains(it)) { "Write an IPv4-mapped address as IPv4: $text." }
            }
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
