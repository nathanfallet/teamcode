package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class TeamEventsTest {
    private val member = Member("member", "member", Role.MEMBER, false, false)
    private val colleague = Member("colleague", "colleague", Role.MEMBER, false, false)
    private val admin = Member("admin", "admin", Role.ADMIN, false, false)
    private fun principal(who: Member, scopes: Set<ServiceScope>? = null) = Principal(who, "token", "team", scopes)

    /** The journal with the same visibility rule as the SQL store, which `TeamEventPersistenceTest` proves on MySQL. */
    private class Journal : TeamEventStore {
        val events = mutableListOf<TeamEvent>()
        var oldestKept: Long? = null
        var revoked = false
        var head: Long? = null
        fun add(type: TeamEventType, owner: String, subject: String = "s-$owner") = events.add(TeamEvent(events.size + 1L, type, subject, owner, 1_000, "{}"))
        override suspend fun revalidate(actor: Principal) { if (revoked) throw TeamFailure("unauthorized", 401, "Revoked.") }
        override suspend fun head(teamId: String) = head ?: events.size.toLong()
        override suspend fun oldest(teamId: String) = oldestKept ?: events.firstOrNull()?.seq
        override suspend fun read(teamId: String, audience: TeamEventAudience, after: Long, types: Set<TeamEventType>?, limit: Int): TeamEventPage {
            val head = events.size.toLong()
            val visible = events.filter { it.seq > after && (types == null || it.type in types) && audience.sees(it.type, it.ownerId) }
            val page = visible.take(limit)
            return if (visible.size > limit) TeamEventPage(page, page.last().seq, true) else TeamEventPage(page, maxOf(head, after), false)
        }
        override suspend fun purge(limit: Int) = 0
    }
    private class Stop : RuntimeException()

    /** Reads until [count] events arrived, running [between] at each pause so events can arrive while following. */
    private suspend fun collect(feed: TeamEventFeed, count: Int, between: () -> Unit = {}): List<TeamEvent> {
        val seen = mutableListOf<TeamEvent>()
        try { feed.follow(emit = { seen += it; if (seen.size >= count) throw Stop() }, idle = { between() }) } catch (_: Stop) {}
        return seen
    }

    @Test fun sessionTransitionsBecomeEvents() {
        fun of(before: me.nathanfallet.teamcode.domain.cloud.CloudSessionState, after: me.nathanfallet.teamcode.domain.cloud.CloudSessionState, action: String? = null) = TeamEventRules.session(before, after, action)
        assertEquals(listOf(TeamEventType.SESSION_PAUSED), of(PAUSING, PAUSED, "cloud.session.paused"))
        assertEquals(listOf(TeamEventType.SESSION_RESUMED), of(RESUMING, READY))
        assertEquals(listOf(TeamEventType.SESSION_ENDED), of(ACTIVE, TERMINATED))
        assertEquals(listOf(TeamEventType.SESSION_ENDED), of(PREPARING, FAILED))
        assertEquals(listOf(TeamEventType.SESSION_WOKEN), of(PAUSED, PAUSED, "cloud.session.woken"), "A wake changes no state")
        assertEquals(listOf(TeamEventType.SESSION_RENAMED), of(READY, READY, "cloud.session.renamed"), "A rename changes no state")
        assertEquals(emptyList(), of(READY, ACTIVE), "A turn is its own event, not a state event")
        assertEquals(emptyList(), of(CREATING, PREPARING))
        assertEquals(emptyList(), of(PAUSED, PAUSED), "A same-state write is silent")
        assertEquals(emptyList(), of(TRANSFERRING, PAUSED), "A declined or cancelled transfer is not a new pause")
    }

    @Test fun audienceFollowsTheCredential() {
        val own = principal(member).teamEventAudience()
        assertTrue(own.sees(TeamEventType.CLOUD_TURN, "member") && !own.sees(TeamEventType.CLOUD_TURN, "colleague"))
        assertTrue(own.sees(TeamEventType.RUN_STALE, "member") && !own.sees(TeamEventType.RUN_STALE, "colleague"))
        val interactiveAdmin = principal(admin).teamEventAudience()
        assertTrue(interactiveAdmin.sees(TeamEventType.CLOUD_TURN, "colleague") && interactiveAdmin.sees(TeamEventType.RUN_ENDED, "colleague"))
        val cloudKey = principal(admin, setOf(ServiceScope.ADMIN_CLOUD)).teamEventAudience()
        assertTrue(cloudKey.sees(TeamEventType.SESSION_PAUSED, "colleague"), "admin:cloud sees every session")
        assertFalse(cloudKey.sees(TeamEventType.RUN_STARTED, "admin"), "but no run without usage:read")
        val ownerKey = principal(admin, setOf(ServiceScope.CLOUD_READ, ServiceScope.USAGE_READ)).teamEventAudience()
        assertTrue(ownerKey.sees(TeamEventType.CLOUD_TURN, "admin") && !ownerKey.sees(TeamEventType.CLOUD_TURN, "colleague"), "An administrator's plain key reads as its owner")
        assertTrue(ownerKey.sees(TeamEventType.RUN_STARTED, "admin") && !ownerKey.sees(TeamEventType.RUN_STARTED, "colleague"))
        val monitor = principal(admin, setOf(ServiceScope.TEAM_READ)).teamEventAudience()
        assertTrue(monitor.sees(TeamEventType.CLOUD_TURN, "colleague") && monitor.sees(TeamEventType.RUN_STALE, "colleague"), "team:read sees the team's sessions and runs")
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { principal(member, setOf(ServiceScope.USAGE_READ)).teamEventAudience() }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { principal(member, setOf(ServiceScope.INFERENCE)).teamEventAudience() }.code)
        assertEquals("password_change_required", assertFailsWith<TeamFailure> { principal(member.copy(passwordChangeRequired = true)).teamEventAudience() }.code)
    }

    @Test fun openingChecksTheCursorAndReportsPurgedHistory() = runBlocking {
        val journal = Journal().apply { repeat(5) { add(TeamEventType.CLOUD_TURN, "member") } }
        val service = TeamEventService(journal, sleep = {})
        assertEquals("invalid_cursor", assertFailsWith<TeamFailure> { service.open(principal(member), -1, null) }.code)
        assertEquals("invalid_cursor", assertFailsWith<TeamFailure> { service.open(principal(member), 6, null) }.code, "Ahead of the head: another server's cursor")
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { service.open(principal(member, setOf(ServiceScope.CLOUD_READ)), null, setOf(TeamEventType.RUN_STALE)) }.code, "Only types the credential cannot see")
        assertNull(service.open(principal(member), 3, null).oldestRetained)
        journal.oldestKept = 4
        assertNull(service.open(principal(member), 3, null).oldestRetained, "Nothing between the cursor and the oldest event is missing")
        assertNull(service.open(principal(member), 0, null).oldestRetained, "0 asks for the whole retained history")
        journal.oldestKept = 5
        assertEquals(5L, service.open(principal(member), 2, null).oldestRetained)
        journal.oldestKept = null; val emptied = Journal().apply { repeat(3) { add(TeamEventType.CLOUD_TURN, "member") } }; emptied.events.clear(); emptied.head = 10
        assertEquals(11L, TeamEventService(emptied, sleep = {}).open(principal(member), 5, null).oldestRetained, "Everything was purged: the history restarts after the head")
        assertEquals(1, collect(service.open(principal(member), null, null), 1) { journal.add(TeamEventType.CLOUD_TURN, "member") }.size, "Without a cursor, only what comes next")
    }

    @Test fun membersSeeOnlyTheirOwnAndResumeWithoutRepeatsOrLoss() = runBlocking {
        val journal = Journal()
        val service = TeamEventService(journal, sleep = {})
        journal.add(TeamEventType.SESSION_CREATED, "member"); journal.add(TeamEventType.SESSION_CREATED, "colleague"); journal.add(TeamEventType.RUN_STARTED, "colleague")
        journal.add(TeamEventType.CLOUD_TURN, "member"); journal.add(TeamEventType.RUN_STALE, "member")
        val mine = collect(service.open(principal(member), 0, null), 3)
        assertEquals(listOf(1L, 4L, 5L), mine.map { it.seq })
        assertTrue(mine.all { it.ownerId == "member" }, "The colleague's events never reach the member")
        val everything = collect(service.open(principal(admin), 0, null), 5)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), everything.map { it.seq })
        // Reconnecting after the last id received sees what came since, once, in order.
        val resumed = collect(service.open(principal(member), mine.last().seq, null), 2) { journal.add(TeamEventType.CLOUD_TURN, "colleague"); journal.add(TeamEventType.SESSION_PAUSED, "member"); journal.add(TeamEventType.SESSION_ENDED, "member") }
        assertEquals(listOf(7L, 8L), resumed.map { it.seq })
        assertEquals(listOf(TeamEventType.SESSION_PAUSED, TeamEventType.SESSION_ENDED), resumed.map { it.type })
        val filtered = collect(service.open(principal(admin), 0, setOf(TeamEventType.CLOUD_TURN)), 2)
        assertEquals(listOf(4L, 6L), filtered.map { it.seq })
    }

    @Test fun pagesDoNotStallOnInvisibleEvents() = runBlocking {
        val journal = Journal()
        repeat(TeamEventService.PAGE * 3) { journal.add(TeamEventType.CLOUD_TURN, "colleague") }
        journal.add(TeamEventType.SESSION_PAUSED, "member")
        val seen = collect(TeamEventService(journal, sleep = {}).open(principal(member), 0, null), 1)
        assertEquals(listOf(TeamEventService.PAGE * 3L + 1), seen.map { it.seq })
    }

    @Test fun aRevokedCredentialEndsTheStream() = runBlocking {
        val journal = Journal().apply { add(TeamEventType.CLOUD_TURN, "member") }
        var polls = 0
        val service = TeamEventService(journal, sleep = { if (++polls == 1) journal.revoked = true })
        val feed = service.open(principal(member), 1, null)
        val failure = assertFailsWith<TeamFailure> { feed.follow(emit = {}, idle = {}) }
        assertEquals("unauthorized", failure.code)
        assertTrue(polls >= (TeamEventService.RECHECK_MILLIS / TeamEventService.POLL_MILLIS).toInt(), "Rechecked every ${TeamEventService.RECHECK_MILLIS} ms of polling")
    }
}
