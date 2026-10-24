package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.serialization.json.*
import kotlin.test.*

class SessionNetworkPolicyTest {
    private fun JsonObject.spec() = this["spec"]!!.jsonObject
    private fun JsonElement.blocks() = jsonObject["to"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["ipBlock"]?.jsonObject }
    private fun JsonObject.cidr() = this["cidr"]!!.jsonPrimitive.content
    private fun JsonObject.except() = this["except"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

    @Test fun theDefaultDeniesIngressAndAllowsOnlyDnsAndPublicAddresses() {
        val policy = SessionNetworkPolicy().manifest()
        assertEquals("NetworkPolicy", policy["kind"]!!.jsonPrimitive.content)
        assertEquals("teamcode-sessions", policy["metadata"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val spec = policy.spec()
        assertEquals(mapOf("app.kubernetes.io/managed-by" to "teamcode"), spec["podSelector"]!!.jsonObject["matchLabels"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content },
            "Every TeamCode pod of the namespace, whatever its session or epoch")
        assertEquals(listOf("Ingress", "Egress"), spec["policyTypes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonArray(emptyList()), spec["ingress"], "Nothing may connect to a session pod")
        val egress = spec["egress"]!!.jsonArray
        assertEquals(3, egress.size)
        val dns = egress[0].jsonObject
        assertNull(dns["to"], "DNS goes to whatever resolver the cluster uses")
        assertEquals(setOf("UDP" to 53, "TCP" to 53), dns["ports"]!!.jsonArray.map { it.jsonObject["protocol"]!!.jsonPrimitive.content to it.jsonObject["port"]!!.jsonPrimitive.int }.toSet())
        val (v4, v6) = egress.drop(1).map { it.blocks().single() }
        assertEquals("0.0.0.0/0", v4.cidr()); assertEquals("::/0", v6.cidr())
        assertNull(egress[1].jsonObject["ports"], "Every port of a public address")
        for (blocked in listOf("169.254.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10", "127.0.0.0/8"))
            assertTrue(blocked in v4.except(), blocked)
        for (blocked in listOf("fe80::/10", "fc00::/7", "::ffff:0:0/96", "64:ff9b::/96", "2002::/16"))
            assertTrue(blocked in v6.except(), blocked)
    }

    @Test fun cloudMetadataAndClusterAddressesAreNeverInTheInternetRule() {
        val except = SessionNetworkPolicy().manifest().spec()["egress"]!!.jsonArray.drop(1).flatMap { rule -> rule.blocks().flatMap { it.except() } }.map(Cidr::parse)
        // AWS, GCP and Azure IMDS, Alibaba's, AWS's IPv6 IMDS, a typical pod and service address, a node on a Docker network.
        for (address in listOf("169.254.169.254/32", "100.100.100.200/32", "fd00:ec2::254/128", "10.96.0.1/32", "10.244.1.7/32", "172.18.0.2/32", "fe80::1/128"))
            assertTrue(except.any { it.contains(Cidr.parse(address)) }, address)
        assertFalse(except.any { it.contains(Cidr.parse("140.82.112.3/32")) }, "github.com stays reachable")
        assertFalse(except.any { it.contains(Cidr.parse("2606:4700::6810:84e5/128")) }, "a public IPv6 address stays reachable")
    }

    @Test fun withoutInternetOnlyDnsAndTheOperatorsRulesRemain() {
        val rules = Json.parseToJsonElement("""[{"to":[{"namespaceSelector":{"matchLabels":{"kubernetes.io/metadata.name":"ingress-nginx"}}}],"ports":[{"protocol":"TCP","port":443}]}]""").jsonArray
        val egress = SessionNetworkPolicy(internet = false, extraEgress = rules).manifest().spec()["egress"]!!.jsonArray
        assertEquals(2, egress.size)
        assertEquals(rules.single(), egress[1], "Appended verbatim")
    }

    @Test fun blockedCidrsNarrowTheInternetRuleOfTheirFamily() {
        val egress = SessionNetworkPolicy(blockedCidrs = listOf("203.0.113.7/24", "2001:db8::/32")).manifest().spec()["egress"]!!.jsonArray
        val (v4, v6) = egress.drop(1).map { it.blocks().single() }
        assertTrue("203.0.113.0/24" in v4.except(), "Host bits are cleared"); assertFalse("2001:db8::/32" in v4.except())
        assertTrue("2001:db8::/32" in v6.except())
    }

    @Test fun extraRulesCannotReachLinkLocalAddresses() {
        fun rules(json: String) = Json.parseToJsonElement(json).jsonArray
        for (bad in listOf(
            """[{"to":[{"ipBlock":{"cidr":"169.254.169.254/32"}}]}]""",
            """[{"to":[{"ipBlock":{"cidr":"0.0.0.0/0"}}]}]""",
            """[{"to":[{"ipBlock":{"cidr":"169.0.0.0/8","except":["169.254.169.0/24"]}}]}]""",
            """[{"to":[{"ipBlock":{"cidr":"fe80::/64"}}]}]""",
            """[{"ports":[{"port":80}]}]""",
            """[{"to":[]}]""",
            """[{"to":[{"podSelector":{}}],"from":[]}]""",
            """["not a rule"]""",
        )) assertFailsWith<IllegalArgumentException>(bad) { SessionNetworkPolicy(extraEgress = rules(bad)) }
        SessionNetworkPolicy(extraEgress = rules("""[{"to":[{"ipBlock":{"cidr":"0.0.0.0/0","except":["169.254.0.0/16"]}}],"ports":[{"port":22}]}]"""))
        SessionNetworkPolicy(extraEgress = rules("""[{"to":[{"ipBlock":{"cidr":"10.0.0.5/32"}},{"podSelector":{"matchLabels":{"app":"proxy"}}}]}]"""))
    }

    @Test fun deploymentVariablesAreParsedStrictly() {
        assertEquals(SessionNetworkPolicy(), SessionNetworkPolicy.fromEnvironment { null })
        val env = mapOf(
            "TEAMCODE_CLOUD_SESSION_NETWORK_POLICY" to "true", "TEAMCODE_CLOUD_SESSION_EGRESS_INTERNET" to "FALSE",
            "TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS" to " 203.0.113.0/24, ,2001:db8::/32 ",
            "TEAMCODE_CLOUD_SESSION_EGRESS_EXTRA" to """[{"to":[{"ipBlock":{"cidr":"10.0.0.5/32"}}]}]""",
        )
        val parsed = SessionNetworkPolicy.fromEnvironment(env::get)
        assertTrue(parsed.enabled); assertFalse(parsed.internet)
        assertEquals(listOf("203.0.113.0/24", "2001:db8::/32"), parsed.blockedCidrs)
        assertEquals(1, parsed.extraEgress.size)
        assertFalse(SessionNetworkPolicy.fromEnvironment(mapOf("TEAMCODE_CLOUD_SESSION_NETWORK_POLICY" to "false")::get).enabled)
        for ((name, value) in listOf(
            "TEAMCODE_CLOUD_SESSION_NETWORK_POLICY" to "off", "TEAMCODE_CLOUD_SESSION_EGRESS_INTERNET" to "yes",
            "TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS" to "metadata.google.internal/32", "TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS" to "0.0.0.0/0",
            "TEAMCODE_CLOUD_SESSION_EGRESS_BLOCKED_CIDRS" to "300.1.1.1/32", "TEAMCODE_CLOUD_SESSION_EGRESS_EXTRA" to "{}",
            "TEAMCODE_CLOUD_SESSION_EGRESS_EXTRA" to "not json",
        )) assertFailsWith<IllegalArgumentException>("$name=$value") { SessionNetworkPolicy.fromEnvironment(mapOf(name to value)::get) }
    }

    @Test fun cidrsAreLiteralsInCanonicalForm() {
        assertEquals("10.0.0.0/8", Cidr.parse("10.1.2.3/8").toString())
        assertEquals("::ffff:0:0/96", Cidr.parse("0:0:0:0:0:ffff:0:0/96").toString())
        assertEquals("::ffff:0:0/96", Cidr.parse("::ffff:0.0.0.0/96").toString())
        assertEquals("2001:db8:0:1::/64", Cidr.parse("2001:DB8:0:1:0:0:0:0/64").toString())
        assertEquals("::/0", Cidr.parse("::/0").toString())
        assertEquals("fd00:ec2::254/128", Cidr.parse("fd00:ec2::254/128").toString())
        assertTrue(Cidr.parse("169.254.0.0/16").contains(Cidr.parse("169.254.169.254/32")))
        assertFalse(Cidr.parse("169.254.169.254/32").contains(Cidr.parse("169.254.0.0/16")))
        assertFalse(Cidr.parse("0.0.0.0/0").contains(Cidr.parse("::/0")))
        for (bad in listOf("10.0.0.0", "10.0.0.0/33", "localhost/32", "1:2:3:4:5:6:7:8:9/64", "1::2::3/64", "::/129", "10.0.0/8", "10.0.0.0/-1"))
            assertFailsWith<IllegalArgumentException>(bad) { Cidr.parse(bad) }
    }
}
