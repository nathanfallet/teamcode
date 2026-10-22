package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.artifacts.*
import kotlin.test.*

class ArtifactViewingTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private var now = 1_000_000_000_000L
    private val tickets = ArtifactTickets(ByteArray(32) { 3 }) { now }

    @Test fun ticketsVerifyOnlyForTheirArtifactUntilTheyExpire() {
        val member = ViewTicket(id, 2, "member-id", "token-id", now + 1_000)
        val value = tickets.issue(member)
        assertTrue(Regex("[A-Za-z0-9_.-]+").matches(value), "A ticket is safe in a path segment")
        assertEquals(member, tickets.verify(id, value))
        assertNull(tickets.verify("00000000-0000-4000-8000-000000000002", value), "Bound to its artifact")
        val (payload, mac) = value.split('.')
        assertNull(tickets.verify(id, (if (payload.first() == 'A') 'B' else 'A') + payload.drop(1) + "." + mac), "A changed payload fails its MAC")
        val forged = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("9:member-id:token-id:${now + 1_000}".encodeToByteArray())
        assertNull(tickets.verify(id, "$forged.$mac"), "Another version under the same MAC")
        assertNull(tickets.verify(id, payload.dropLast(1) + (if (payload.last() == 'A') 'B' else 'A') + "." + mac), "Only the canonical spelling verifies")
        assertNull(tickets.verify(id, "$payload.${mac.reversed()}"))
        assertNull(tickets.verify(id, "not-a-ticket"))
        assertNull(ArtifactTickets(ByteArray(32) { 4 }) { now }.verify(id, value), "Another server key never verifies it")
        val anonymous = tickets.issue(ViewTicket(id, 1, null, null, now + 1_000))
        assertTrue(tickets.verify(id, anonymous)!!.anonymous)
        now += 1_000
        assertNull(tickets.verify(id, value), "Expired")
        assertFailsWith<IllegalArgumentException> { tickets.issue(ViewTicket(id, 1, "member-id", null, now + 1)) }
    }

    @Test fun theTicketKeyIsDerivedWithHkdf() {
        // RFC 5869 extract-then-expand with a zero salt (as in its test case 3), computed independently for this label.
        val okm = ArtifactTickets.hkdf(ByteArray(32) { 0x0b }, "teamcode artifact-view".encodeToByteArray())
        assertEquals("f808841ca38e0f84314a45499964a3c90004c1596889d9c2431ec50ab223ed27", okm.joinToString("") { "%02x".format(it) })
        assertFailsWith<IllegalArgumentException> { ArtifactTickets.hkdf(ByteArray(16), ByteArray(0)) }
    }

    @Test fun rangesAreSingleAndWithinTheFile() {
        assertEquals(ByteRange.Whole, ArtifactViewing.parseRange(null, 10))
        assertEquals(ByteRange.Part(0L..3L), ArtifactViewing.parseRange("bytes=0-3", 10))
        assertEquals(ByteRange.Part(5L..9L), ArtifactViewing.parseRange("bytes=5-", 10))
        assertEquals(ByteRange.Part(7L..9L), ArtifactViewing.parseRange("bytes=-3", 10))
        assertEquals(ByteRange.Part(8L..9L), ArtifactViewing.parseRange("bytes=8-99", 10))
        assertEquals(ByteRange.Part(0L..9L), ArtifactViewing.parseRange("bytes=0-9", 10), "The whole file asked as a range is still a 206")
        assertEquals(ByteRange.Part(0L..9L), ArtifactViewing.parseRange("bytes=-20", 10))
        for (ignored in listOf("items=0-3", "bytes=5-2", "bytes=x-y", "bytes=-"))
            assertEquals(ByteRange.Whole, ArtifactViewing.parseRange(ignored, 10), ignored)
        for (refused in listOf("bytes=10-", "bytes=0-1,4-5", "bytes=-0"))
            assertEquals(ByteRange.Unsatisfiable, ArtifactViewing.parseRange(refused, 10), refused)
        assertEquals(ByteRange.Unsatisfiable, ArtifactViewing.parseRange("bytes=0-0", 0))
    }
}
