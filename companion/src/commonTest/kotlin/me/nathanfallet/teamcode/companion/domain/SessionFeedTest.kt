package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import kotlin.test.*

/** The app's one connection to the caller-wide feed, shared by every screen that follows it. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionFeedTest {
    private val sessions = FakeSessionRepository()
    private val wire = Channel<LiveUpdate<SessionChange>>(Channel.UNLIMITED)
    private val turn = SessionChange(1, "cloud.turn", "s1", "m1", 5, null, "started")

    init { sessions.changes = flow { for (update in wire) emit(update) } }

    private fun TestScope.feed(features: FakeFeatures = FakeFeatures()) = SessionFeed(sessions, features, backgroundScope)

    @Test fun twoScreensShareOneConnectionAndTheLaterOneLoadsAtOnce() = runTest {
        val feed = feed()
        val first = mutableListOf<LiveUpdate<SessionChange>>()
        val second = mutableListOf<LiveUpdate<SessionChange>>()
        val a = launch { feed.follow("a1").collect { first += it } }
        runCurrent()
        wire.send(LiveUpdate.Opened(false)); runCurrent()
        // Joins a feed already open: it is told to load now, as the first one was when the feed opened.
        val b = launch { feed.follow("a1").collect { second += it } }
        runCurrent()
        wire.send(LiveUpdate.Item(turn)); runCurrent()
        assertEquals(listOf("feed"), sessions.calls)
        assertEquals(1, feed.openConnections())
        assertEquals(listOf(LiveUpdate.Opened(false), LiveUpdate.Item(turn)), first)
        assertEquals(listOf(LiveUpdate.Opened(false), LiveUpdate.Item(turn)), second)
        a.cancel(); b.cancel()
    }

    @Test fun theConnectionClosesAfterTheLastScreenLeftUnlessOneComesBackInTime() = runTest {
        val feed = feed()
        var connection = launch { feed.follow("a1").collect { } }
        runCurrent()
        connection.cancel(); runCurrent()
        // Back within the delay (the list replaced by a session, then back): the same connection.
        advanceTimeBy(4_000); runCurrent()
        connection = launch { feed.follow("a1").collect { } }
        runCurrent()
        assertEquals(1, sessions.calls.size)
        connection.cancel(); runCurrent()
        advanceTimeBy(5_001); runCurrent()
        // Nobody follows any more (the app in the background, the account signed out): closed.
        assertEquals(0, feed.openConnections())
        connection = launch { feed.follow("a1").collect { } }
        runCurrent()
        assertEquals(listOf("feed", "feed"), sessions.calls)
        connection.cancel()
    }

    @Test fun aScreenJoiningDuringAReconnectionLoadsWhenTheFeedIsBack() = runTest {
        val feed = feed()
        val first = launch { feed.follow("a1").collect { } }
        runCurrent()
        wire.send(LiveUpdate.Opened(false)); wire.send(LiveUpdate.Reconnecting(2_000)); runCurrent()
        val seen = mutableListOf<LiveUpdate<SessionChange>>()
        val late = launch { feed.follow("a1").collect { seen += it } }
        runCurrent()
        wire.send(LiveUpdate.Opened(true)); runCurrent()
        assertEquals(listOf<LiveUpdate<SessionChange>>(LiveUpdate.Opened(false)), seen)
        first.cancel(); late.cancel()
    }

    @Test fun aFailureEndsEveryScreenAndTheNextOneConnectsAgain() = runTest {
        val feed = feed()
        sessions.changes = flow { throw CompanionException.AccessRevoked() }
        val failures = List(2) { async { runCatching { feed.follow("a1").collect { } }.exceptionOrNull() } }
        runCurrent()
        failures.forEach { assertIs<CompanionException.AccessRevoked>(it.await()) }
        assertEquals(0, feed.openConnections())
        sessions.changes = flow { emit(LiveUpdate.Opened(false)) }
        assertEquals(listOf<LiveUpdate<SessionChange>>(LiveUpdate.Opened(false)), feed.follow("a1").let { flow -> mutableListOf<LiveUpdate<SessionChange>>().also { flow.collect(it::add) } })
    }

    @Test fun aStreamCancelledFromElsewhereEndsAsUnreachableInsteadOfLeavingTheScreensWaiting() = runTest {
        val feed = feed()
        // An HTTP client closed under the stream throws a cancellation the connection did not ask for.
        sessions.changes = flow { emit(LiveUpdate.Opened(false)); throw CancellationException("client closed") }
        val failure = runCatching { feed.follow("a1").collect { } }.exceptionOrNull()
        assertIs<CompanionException.Unreachable>(failure)
    }

    @Test fun waitingEventsAndEveryKindAreAskedFromAServerThatHasThem() = runTest {
        val feed = feed(FakeFeatures(setOf("cloud.sessions.v1", "cloud.events.v1", "cloud.sessions.remote.v1", "cloud.sessions.waiting.v1")))
        val connection = launch { feed.follow("a1").collect { } }
        runCurrent()
        assertEquals(listOf("feed cloud,remote waiting"), sessions.calls)
        connection.cancel()
    }

    @Test fun aSlowScreenSlowsTheReadingInsteadOfDroppingUpdates() = runTest {
        val feed = feed()
        val gate = Channel<Unit>()
        val seen = mutableListOf<LiveUpdate<SessionChange>>()
        val slow = launch { feed.follow("a1").collect { seen += it; gate.receive() } }
        runCurrent()
        repeat(200) { wire.send(LiveUpdate.Item(turn.copy(seq = it + 1L))) }
        runCurrent()
        repeat(200) { gate.send(Unit); runCurrent() }
        assertEquals((1L..200L).toList(), seen.map { (it as LiveUpdate.Item).value.seq })
        slow.cancel()
        gate.close()
        wire.close()
    }

}
