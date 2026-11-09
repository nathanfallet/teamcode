package me.nathanfallet.teamcode.domain.notifications

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class NotificationsTest {
    private val alice = Principal(Member("alice", "alice", Role.MEMBER, false, false), "token-a", "team")
    private val key = Principal(Member("alice", "alice", Role.MEMBER, false, false), "key-a", "team", setOf(ServiceScope.INFERENCE))

    private fun event(seq: Long, type: TeamEventType, subject: String, owner: String, data: String) = TeamEvent(seq, type, subject, owner, 1_000L * seq, data)
    private fun wait(id: String, assignee: String = "bob", kind: String = "human_request", resolver: String = "human", open: Boolean = true) =
        WaitSummary(id, assignee, "s1", kind, resolver, open, "Review the plan")

    @Test
    fun aWaitForAPersonNotifiesItsAssignee() {
        val draft = NotificationRules.draft(NotificationSource(event(3, TeamEventType.WAIT_CREATED, "w1", "bob", """{"waitId":"w1","sessionId":"s1"}"""), wait("w1"), "Fix the login"))!!
        assertEquals(NotificationDraft(3, "bob", NotificationKind.WAITING, "Fix the login", "Review the plan", "s1", "w1", "wait:w1"), draft)
    }

    @Test
    fun waitsThatAskNobodyOrAreOverDoNotNotify() {
        fun of(summary: WaitSummary?) = NotificationRules.draft(NotificationSource(event(1, TeamEventType.WAIT_CREATED, "w1", "bob", "{}"), summary, null))
        assertNull(of(wait("w1", resolver = "time")), "a timer resolves it")
        assertNull(of(wait("w1", open = false)), "answered before the pass")
        assertNull(of(null), "the wait is gone")
    }

    @Test
    fun aFailedTurnNotifiesThroughItsWaitOnly() {
        val failed = NotificationRules.draft(NotificationSource(event(1, TeamEventType.WAIT_CREATED, "w1", "bob", "{}"), wait("w1", kind = "turn_failed"), null))!!
        assertEquals(NotificationKind.TURN_FAILED, failed.kind)
        assertEquals("TeamCode", failed.title, "a session without a name")
        assertNull(NotificationRules.draft(NotificationSource(event(2, TeamEventType.CLOUD_TURN, "s1", "bob", """{"sessionId":"s1","phase":"failed"}"""), null, "S")))
    }

    @Test
    fun sessionEventsNotifyTheirOwner() {
        fun of(type: TeamEventType, data: String) = NotificationRules.draft(NotificationSource(event(7, type, "s1", "alice", data), null, "Fix the login"))
        val waiting = of(TeamEventType.SESSION_WAITING, """{"sessionId":"s1","reason":"question"}""")!!
        assertEquals(NotificationKind.WAITING, waiting.kind); assertEquals("alice", waiting.memberId); assertEquals("The agent has a question for you.", waiting.body)
        assertEquals("The agent asks for your permission.", of(TeamEventType.SESSION_WAITING, """{"sessionId":"s1","reason":"permission"}""")!!.body)
        assertEquals(NotificationKind.TURN_COMPLETED, of(TeamEventType.CLOUD_TURN, """{"sessionId":"s1","phase":"completed"}""")!!.kind)
        assertNull(of(TeamEventType.CLOUD_TURN, """{"sessionId":"s1","phase":"started"}"""))
        assertEquals(NotificationKind.SESSION_FAILED, of(TeamEventType.SESSION_ENDED, """{"sessionId":"s1","state":"failed"}""")!!.kind)
        assertNull(of(TeamEventType.SESSION_ENDED, """{"sessionId":"s1","state":"terminated"}"""), "a session someone ended")
        assertNull(of(TeamEventType.SESSION_PAUSED, """{"sessionId":"s1"}"""))
        assertNull(of(TeamEventType.SESSION_WAITING, "not json"))
    }

    private class MemoryStore : NotificationStore {
        var cursor = NotificationCursor("team", 0, 0)
        val sources = mutableListOf<NotificationSource>()
        val off = mutableSetOf<Triple<String, String, NotificationKind>>()
        val queued = mutableListOf<NotificationDelivery>()
        val records = mutableListOf<Triple<String, NotificationState, Long>>()
        val stored = mutableListOf<NotificationSetting>()
        var raced = false

        override suspend fun revalidate(actor: Principal) {}
        override suspend fun behind(limit: Int) = if (cursor.head > cursor.from) listOf(cursor) else emptyList()
        override suspend fun read(cursor: NotificationCursor, limit: Int) = NotificationSourcePage(sources.filter { it.event.seq > cursor.from }, cursor.head)
        override suspend fun disabled(memberIds: Set<String>) = off.filter { it.first in memberIds }.toSet()
        override suspend fun enqueue(cursor: NotificationCursor, through: Long, deliveries: List<NotificationDelivery>): Boolean {
            if (raced) return false
            this.cursor = cursor.copy(from = through); queued += deliveries; return true
        }
        override suspend fun claimDue(limit: Int, leaseMillis: Long) = queued.take(limit).also { queued.removeAll(it) }
        override suspend fun record(id: String, state: NotificationState, attempts: Int, nextAttemptAt: Long, error: String?) { records += Triple(id, state, nextAttemptAt) }
        override suspend fun maintain(limit: Int) = 0
        override suspend fun settings(teamId: String, memberId: String) = stored.toList()
        override suspend fun set(teamId: String, memberId: String, setting: NotificationSetting) { stored.removeAll { it.channel == setting.channel && it.kind == setting.kind }; stored += setting }
    }

    private class FakeChannel(override val id: String, val reach: Set<String>, var result: suspend () -> ChannelResult = { ChannelResult.DELIVERED }) : NotificationChannel {
        val sent = mutableListOf<Notification>()
        override suspend fun reachable(teamId: String, memberIds: Set<String>) = memberIds intersect reach
        override suspend fun deliver(notifications: List<Notification>) = notifications.associate { sent += it; it.id to result() }
    }

    private fun MemoryStore.waitFor(member: String, seq: Long) {
        sources += NotificationSource(event(seq, TeamEventType.WAIT_CREATED, "w$seq", member, "{}"), wait("w$seq", assignee = member), "S")
        cursor = cursor.copy(head = seq)
    }

    @Test
    fun eachMemberIsReachedOnTheChannelsTheyCanBeReachedOnAndKeptOn() = runBlocking {
        val store = MemoryStore()
        val push = FakeChannel("push", setOf("alice", "bob"))
        val chat = FakeChannel("chat", setOf("alice"))
        var ids = 0
        val worker = DeliverNotificationsUseCase(store, listOf(push, chat), clock = { 50L }, newId = { "n${++ids}" })
        store.waitFor("alice", 1); store.waitFor("bob", 2); store.waitFor("carol", 3)
        store.off += Triple("alice", "chat", NotificationKind.WAITING)

        assertEquals(2, worker.run())
        assertEquals(listOf("alice", "bob"), push.sent.map { it.memberId }, "carol has no destination")
        assertEquals(emptyList(), chat.sent, "alice turned it off on that channel")
        assertEquals(3L, store.cursor.from, "the cursor moved past every event looked at")
        assertTrue(store.records.all { it.second == NotificationState.DELIVERED })
        assertEquals(listOf(1L, 2L), push.sent.map { it.eventSeq })
    }

    @Test
    fun anotherReplicaWinningThePageQueuesNothing() = runBlocking {
        val store = MemoryStore().apply { waitFor("alice", 1); raced = true }
        val worker = DeliverNotificationsUseCase(store, listOf(FakeChannel("push", setOf("alice"))))
        assertEquals(0, worker.fanOut())
        assertEquals(emptyList(), store.queued)
    }

    @Test
    fun withoutAChannelNothingIsRead() = runBlocking {
        val store = MemoryStore().apply { waitFor("alice", 1) }
        assertEquals(0, DeliverNotificationsUseCase(store, emptyList()).run())
        assertEquals(0L, store.cursor.from)
    }

    @Test
    fun failuresAreRetriedWithBackoffThenGivenUp() = runBlocking {
        val store = MemoryStore()
        val channel = FakeChannel("push", setOf("alice")) { ChannelResult.retry("unavailable") }
        val worker = DeliverNotificationsUseCase(store, listOf(channel), clock = { 1_000L })
        fun due(attempts: Int, channelId: String = "push") = NotificationDelivery(Notification("d$attempts", "team", "alice", NotificationKind.WAITING, "T", "B", null, null, "k", 0, 1), channelId, attempts)

        store.queued += due(0); worker.run()
        assertEquals(Triple("d0", NotificationState.PENDING, 1_000L + NotificationService.BACKOFF_MILLIS[0]), store.records.last())
        store.queued += due(NotificationService.MAX_ATTEMPTS - 1); worker.run()
        assertEquals(NotificationState.FAILED, store.records.last().second, "out of attempts")
        channel.result = { ChannelResult.failed("unregistered") }
        store.queued += due(0); worker.run()
        assertEquals(NotificationState.FAILED, store.records.last().second, "refused for good")
        store.queued += due(0, "gone"); worker.run()
        assertEquals(NotificationState.FAILED, store.records.last().second, "a channel the server no longer offers")
    }

    @Test
    fun aSlowChannelTimesOutAndIsRetried() = runTest {
        val store = MemoryStore()
        val channel = FakeChannel("push", setOf("alice")) { delay(DeliverNotificationsUseCase.SEND_DEADLINE_MILLIS * 2); ChannelResult.DELIVERED }
        store.queued += NotificationDelivery(Notification("d", "team", "alice", NotificationKind.WAITING, "T", "B", null, null, "k", 0, 1), "push", 0)
        DeliverNotificationsUseCase(store, listOf(channel)).run()
        assertEquals(NotificationState.PENDING, store.records.single().second)
    }

    @Test
    fun cancellationIsNotSwallowed() {
        val store = MemoryStore()
        val channel = FakeChannel("push", setOf("alice")) { throw CancellationException("stopped") }
        store.queued += NotificationDelivery(Notification("d", "team", "alice", NotificationKind.WAITING, "T", "B", null, null, "k", 0, 1), "push", 0)
        assertFailsWith<CancellationException> { runBlocking { DeliverNotificationsUseCase(store, listOf(channel)).run() } }
        assertEquals(emptyList(), store.records, "nothing recorded: the lease brings it back")
    }

    @Test
    fun settingsListEveryChannelAndKindWithDefaults() = runBlocking {
        val store = MemoryStore()
        val service = NotificationService(store, listOf("push"))
        assertTrue(service.settings(alice).settings.all { it.enabled })
        assertEquals(NotificationKind.entries.size, service.settings(alice).settings.size)

        val after = service.set(alice, "push", "turn_completed", false)
        assertEquals(false, after.settings.single { it.kind == NotificationKind.TURN_COMPLETED }.enabled)
        assertEquals("invalid_notification_setting", assertFailsWith<TeamFailure> { service.set(alice, "slack", "waiting", false) }.code)
        assertEquals("invalid_notification_setting", assertFailsWith<TeamFailure> { service.set(alice, "push", "everything", false) }.code)
        assertEquals("credential_scope", assertFailsWith<TeamFailure> { service.settings(key) }.code)
    }
}
