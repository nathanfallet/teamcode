package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.cloudSession
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.SessionKind
import me.nathanfallet.teamcode.companion.laptop
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionFilter
import me.nathanfallet.teamcode.companion.domain.models.SessionPage
import me.nathanfallet.teamcode.companion.domain.models.SessionScope
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.SessionWaiting
import me.nathanfallet.teamcode.companion.domain.models.TranscriptEvent
import me.nathanfallet.teamcode.companion.domain.models.TranscriptPage
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.FollowSessionListUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.LoadSessionsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionList
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionListSignal
import me.nathanfallet.teamcode.companion.domain.usecases.transcript.Transcript
import me.nathanfallet.teamcode.companion.unauthorized
import kotlin.test.*

class SessionUseCasesTest {
    /** The list's feed through the app's shared holder, as in the app. */
    private fun kotlinx.coroutines.test.TestScope.followList(sessions: FakeSessionRepository, features: me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository) =
        FollowSessionListUseCase(SessionFeed(sessions, features, backgroundScope), features, guard())

    private fun change(seq: Long, session: String, type: String = "cloud.turn", member: String = "m1", state: SessionState? = null, phase: String? = "completed", at: Long = 5_000) =
        SessionChange(seq, type, session, member, at, state, if (type == "cloud.turn") phase else null)

    @Test fun aListKeepsItsGroupAndScopeNewestFirst() {
        val list = SessionList(SessionScope.MINE, SessionFilter.OPEN, "m1").loaded(
            SessionPage(listOf(cloudSession("a", createdAt = 1), cloudSession("b", createdAt = 3), cloudSession("t", SessionState.TERMINATED, createdAt = 4), cloudSession("o", ownerId = "m2", createdAt = 5)), "cursor"),
        )
        assertEquals(listOf("b", "a"), list.sessions.map { it.id })
        assertEquals("cursor", list.nextBefore)
        val more = list.appended(SessionPage(listOf(cloudSession("a", createdAt = 1), cloudSession("z", createdAt = 0)), null))
        assertEquals(listOf("b", "a", "z"), more.sessions.map { it.id })
        assertNull(more.nextBefore)
    }

    @Test fun aWaitEndsWithItsAnswerWithTheTurnOrWhenTheSessionStopsRunning() {
        val list = SessionList(SessionScope.MINE, SessionFilter.OPEN, "m1").loaded(SessionPage(listOf(cloudSession("a", SessionState.ACTIVE)), null))
        fun waiting(seq: Long, request: String) = SessionChange(seq, SessionChange.WAITING, "a", "m1", seq * 1_000, null, null, waitingReason = "permission", requestId = request)
        fun answered(seq: Long, request: String?) = SessionChange(seq, SessionChange.ANSWERED, "a", "m1", seq * 1_000, null, null, requestId = request)
        val (asked, fetch) = list.applied(waiting(1, "r1"))
        assertNull(fetch)
        assertEquals(SessionWaiting("permission", "r1", 1_000), asked.sessions.single().waiting)
        assertEquals(1_000, asked.sessions.single().lastActivityAt)
        // A newer request, then the answer to the older one: the newer one still waits.
        val (newer, _) = asked.applied(waiting(2, "r2"))
        assertEquals("r2", newer.applied(answered(3, "r1")).first.sessions.single().waiting?.requestId)
        assertNull(newer.applied(answered(3, "r2")).first.sessions.single().waiting)
        assertNull(newer.applied(change(4, "a", phase = "completed")).first.sessions.single().waiting)
        assertNull(newer.applied(change(5, "a", type = "cloud.session.paused", state = SessionState.PAUSED)).first.sessions.single().waiting)
        // A turn starting does not end a wait.
        assertEquals("r2", newer.applied(change(6, "a", phase = "started")).first.sessions.single().waiting?.requestId)
    }

    @Test fun aTurnUpdatesTheRowAndMarksItUntilOpened() {
        val list = SessionList(SessionScope.MINE, SessionFilter.OPEN, "m1").loaded(SessionPage(listOf(cloudSession("a")), null))
        val (started, fetch) = list.applied(change(1, "a", phase = "started"))
        assertNull(fetch)
        assertEquals(SessionState.ACTIVE, started.sessions.single().state)
        assertTrue(started.finishedTurns.isEmpty())
        val (finished, _) = started.applied(change(2, "a", phase = "completed", at = 9_000))
        assertEquals(SessionState.READY, finished.sessions.single().state)
        assertEquals(9_000, finished.sessions.single().lastActivityAt)
        assertEquals(setOf("a"), finished.finishedTurns)
        assertTrue(finished.opened("a").finishedTurns.isEmpty())
    }

    @Test fun aStateThatLeavesTheGroupRemovesTheRowAndAnUnknownSessionIsFetchedOnlyIfItMayBelong() {
        val list = SessionList(SessionScope.MINE, SessionFilter.OPEN, "m1").loaded(SessionPage(listOf(cloudSession("a")), null))
        val (paused, none) = list.applied(change(1, "a", "cloud.session.ended", state = SessionState.TERMINATED))
        assertTrue(paused.sessions.isEmpty()); assertNull(none)
        // A pause keeps the row: active and paused sessions share the list.
        assertEquals(listOf("a"), list.applied(change(1, "a", "cloud.session.paused", state = SessionState.PAUSED)).first.sessions.map { it.id })
        // A session resumed elsewhere: read it, then put it in place.
        val (_, fetch) = paused.applied(change(2, "n", "cloud.session.resumed", state = SessionState.READY))
        assertEquals("n", fetch)
        assertEquals(listOf("n"), paused.upserted(cloudSession("n")).sessions.map { it.id })
        // A session that ended does not belong to the active list: nothing to read.
        assertNull(paused.applied(change(3, "x", "cloud.session.ended", state = SessionState.TERMINATED)).second)
        // Someone else's session on "Mine": ignored.
        assertNull(paused.applied(change(4, "y", "cloud.session.created", member = "m2", state = SessionState.CREATING)).second)
        val team = SessionList(SessionScope.TEAM, SessionFilter.OPEN, "m1")
        assertEquals("y", team.applied(change(4, "y", "cloud.session.created", member = "m2", state = SessionState.CREATING)).second)
    }

    @Test fun aFetchedSessionOlderThanTheLoadedPagesWaitsForThem() {
        val list = SessionList(SessionScope.MINE, SessionFilter.OPEN, "m1").loaded(SessionPage(listOf(cloudSession("new", createdAt = 10)), "more"))
        assertEquals(listOf("new"), list.upserted(cloudSession("old", createdAt = 1)).sessions.map { it.id })
        assertEquals(listOf("newer", "new"), list.upserted(cloudSession("newer", createdAt = 20)).sessions.map { it.id })
    }

    @Test fun aRefreshKeepsThePagesLoadedAfterTheFirstAndUnsettledRowsAreReadAgain() {
        val list = SessionList(SessionScope.MINE, SessionFilter.OPEN, "m1")
            .loaded(SessionPage(listOf(cloudSession("c", createdAt = 30), cloudSession("b", createdAt = 20)), "b"))
            .appended(SessionPage(listOf(cloudSession("a", createdAt = 10)), null))
        // "d" appeared and pushed "b" past the first page: page 1 is replaced, the older rows stay, and so does "everything is loaded".
        val refreshed = list.refreshed(SessionPage(listOf(cloudSession("d", createdAt = 40), cloudSession("c", SessionState.CREATING, createdAt = 30)), "c"))
        assertEquals(listOf("d", "c", "b", "a"), refreshed.sessions.map { it.id })
        assertNull(refreshed.nextBefore)
        assertEquals(listOf("c"), refreshed.unsettled)
        // A complete first page replaces everything.
        assertEquals(listOf("d"), list.refreshed(SessionPage(listOf(cloudSession("d", createdAt = 40)), null)).sessions.map { it.id })
        // A turn of a row still shown as creating reads it instead of guessing.
        assertEquals("c", refreshed.applied(change(1, "c", phase = "started")).second)
    }

    @Test fun turnsOfSessionsFoundOutsideTheListAreNotReadAgainAndUnknownStatesAreNotPolled() {
        val active = SessionList(SessionScope.TEAM, SessionFilter.OPEN, "m1").loaded(SessionPage(listOf(cloudSession("a", createdAt = 10)), "more"))
        // A turn of a session the list does not hold: read once; found past the loaded pages, its next turns are not read.
        assertEquals("old", active.applied(change(1, "old")).second)
        val remembered = active.upserted(cloudSession("old", createdAt = 1))
        assertNull(remembered.applied(change(2, "old")).second)
        // An event saying its state changed reads it again, and the page that brings it forgets it.
        assertEquals("old", remembered.applied(change(3, "old", "cloud.session.resumed", state = SessionState.READY)).second)
        // A session that cannot be read leaves the list and its turns are not read again.
        val hidden = active.forgotten("a")
        assertTrue(hidden.sessions.isEmpty()); assertNull(hidden.applied(change(5, "a")).second)
        assertTrue("old" !in remembered.appended(SessionPage(listOf(cloudSession("old", createdAt = 1)), null)).outside)
        // A turn never runs in an ended session: that list never reads one.
        val ended = SessionList(SessionScope.TEAM, SessionFilter.ENDED, "m1")
        assertNull(ended.applied(change(4, "z")).second)
        // A state this version does not know is not read again every tick.
        val unknown = SessionList(SessionScope.MINE, SessionFilter.OPEN, "m1").loaded(SessionPage(listOf(cloudSession("u", SessionState.UNKNOWN)), null))
        assertTrue(unknown.unsettled.isEmpty())
        assertNull(unknown.applied(change(5, "u")).second, "nor on each of its turns")
    }

    @Test fun anUnreachableServerAtStartIsAskedAgainInsteadOfEndingTheList() = runTest {
        val sessions = FakeSessionRepository()
        sessions.changes = flowOf(LiveUpdate.Opened(false))
        var failures = 2
        val features = object : me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository {
            override suspend fun features(accountId: String, refresh: Boolean): Set<String> {
                if (failures-- > 0) throw CompanionException.Unreachable()
                return setOf("cloud.sessions.v1", "cloud.events.v1")
            }
        }
        val start = testScheduler.currentTime
        val signals = followList(sessions, features)("a1").toList()
        assertEquals(listOf(SessionListSignal.Connection(true), SessionListSignal.Connection(true), NO_REMOTE, SessionListSignal.Connection(false), SessionListSignal.Reload), signals)
        assertEquals(6_000, testScheduler.currentTime - start)
    }

    @Test fun aWaitEventSaysNothingAboutItsSession() {
        val list = SessionList(SessionScope.MINE, SessionFilter.OPEN, "m1").loaded(SessionPage(listOf(cloudSession("a", SessionState.ACTIVE)), null))
        listOf(SessionChange.WAIT_CREATED, SessionChange.WAIT_RESOLVED, SessionChange.WAIT_CANCELLED).forEach { type ->
            val (same, fetch) = list.applied(SessionChange(9, type, "a", "m1", 9_000, null, null, waitId = "w1"))
            assertEquals(list, same); assertNull(fetch)
        }
    }

    @Test fun aWorkflowRunEventIsNoSessionsChange() {
        val list = SessionList(SessionScope.MINE, SessionFilter.OPEN, "m1").loaded(SessionPage(listOf(cloudSession("a", SessionState.ACTIVE)), null))
        val (same, fetch) = list.applied(SessionChange(9, SessionChange.WORKFLOW_RUN, "", "m1", 9_000, null, null, runId = "r1"))
        assertEquals(list, same); assertNull(fetch)
    }

    @Test fun theListReloadsWhenTheFeedOpensFromNowAndOnAGapButNotOnAResume() = runTest {
        val sessions = FakeSessionRepository()
        val c = change(1, "a")
        sessions.changes = flowOf(LiveUpdate.Opened(false), LiveUpdate.Item(c), LiveUpdate.Reconnecting(2_000), LiveUpdate.Opened(true), LiveUpdate.Gap)
        val signals = followList(sessions, FakeFeatures())("a1").toList()
        assertEquals(
            listOf(NO_REMOTE, SessionListSignal.Connection(false), SessionListSignal.Reload, SessionListSignal.Changed(c), SessionListSignal.Connection(true), SessionListSignal.Connection(false), SessionListSignal.Reload),
            signals,
        )
    }

    @Test fun theLastLoadedRowStaysWhenItChanges() {
        val list = me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionList(
            me.nathanfallet.teamcode.companion.domain.models.SessionScope.MINE, me.nathanfallet.teamcode.companion.domain.models.SessionFilter.OPEN, "m1")
            .refreshed(me.nathanfallet.teamcode.companion.domain.models.SessionPage(listOf(cloudSession("a", createdAt = 3), cloudSession("b", createdAt = 2), cloudSession("c", createdAt = 1)), "c"))
        val updated = list.upserted(cloudSession("c", state = me.nathanfallet.teamcode.companion.domain.models.SessionState.ACTIVE, createdAt = 1))
        assertEquals(listOf("a", "b", "c"), updated.sessions.map { it.id })
        assertEquals(me.nathanfallet.teamcode.companion.domain.models.SessionState.ACTIVE, updated.sessions.last().state)
    }

    @Test fun withoutTheFeedTheListIsReloadedEveryFifteenSeconds() = runTest {
        val sessions = FakeSessionRepository()
        val start = testScheduler.currentTime
        val signals = followList(sessions, FakeFeatures(setOf("cloud.sessions.v1")))("a1").take(5).toList()
        // "Reconnecting" ends first, in case the start needed retries.
        assertEquals(listOf(NO_REMOTE, SessionListSignal.Connection(reconnecting = false)) + List(3) { SessionListSignal.Reload }, signals)
        assertEquals(30_000, testScheduler.currentTime - start)
        assertFalse("feed" in sessions.calls)
    }

    @Test fun aServerWithoutSessionsSaysSoAndA401ChecksTheAccountOnce() = runTest {
        val sessions = FakeSessionRepository()
        assertFailsWith<CompanionException.Unsupported> { LoadSessionsUseCase(sessions, FakeFeatures(emptySet()), guard())("a1", SessionScope.MINE, SessionFilter.OPEN) }
        val auth = FakeAuthRepository().apply { meResult = ::unauthorized }
        val accounts = InMemoryAccountRepository(listOf(account("a1")))
        val store = credentials().apply { write("a1", "token") }
        val guard = AccountGuard(RefreshAccountUseCase(auth, accounts, store, FixedClock()))
        sessions.pages = { _, _ -> throw CompanionException.Unauthorized() }
        assertFailsWith<CompanionException.Unauthorized> { LoadSessionsUseCase(sessions, FakeFeatures(), guard)("a1", SessionScope.MINE, SessionFilter.OPEN) }
        assertEquals(listOf("me a1"), auth.calls)
        assertFalse(accounts.accounts.value.single().signedIn)
        assertNull(store.read("a1"))
    }

    @Test fun aTranscriptMergesPagesAndLiveEventsBySequenceOnce() {
        fun msg(seq: Long) = TranscriptEvent.AgentMessage(seq, seq, "line $seq", null)
        val latest = Transcript().withLatest(TranscriptPage(listOf(msg(5), msg(6)), nextBefore = 5, lastSeq = 6))
        assertEquals(6, latest.newestSeq)
        val live = latest.withLive(msg(7)).withLive(msg(6)).withLive(msg(8))
        assertEquals(listOf(5L, 6, 7, 8), live.events.map { it.seq })
        val older = live.withOlder(TranscriptPage(listOf(msg(3), msg(4), msg(5)), nextBefore = null, lastSeq = 8))
        assertEquals(listOf(3L, 4, 5, 6, 7, 8), older.events.map { it.seq })
        assertNull(older.nextBefore)
        assertEquals(8, older.newestSeq)
        // A page that says the session has newer events than it holds: following resumes after them.
        assertEquals(12, Transcript().withLatest(TranscriptPage(emptyList(), null, lastSeq = 12)).newestSeq)
    }

    @Test fun aListKeepsEveryKindAndTheStatesItsFilterCovers() {
        val remote = cloudSession("r", createdAt = 2_000, host = laptop())
        val open = SessionList(SessionScope.MINE, SessionFilter.OPEN, "m1").loaded(SessionPage(listOf(cloudSession("c"), remote, cloudSession("p", SessionState.PAUSED, createdAt = 500), cloudSession("t", SessionState.TERMINATED, createdAt = 400)), null))
        assertEquals(listOf("r", "c", "p"), open.sessions.map { it.id })
        // Ended sessions join the "All" list, and only theirs the "Ended" one.
        val all = SessionList(SessionScope.MINE, SessionFilter.ALL, "m1").loaded(SessionPage(open.sessions + cloudSession("t", SessionState.TERMINATED, createdAt = 400), null))
        assertEquals(listOf("r", "c", "p", "t"), all.sessions.map { it.id })
        val ended = SessionList(SessionScope.MINE, SessionFilter.ENDED, "m1").loaded(SessionPage(all.sessions, null))
        assertEquals(listOf("t"), ended.sessions.map { it.id })
        // A session that ends leaves the open list and a paused one stays in it.
        assertTrue(open.upserted(cloudSession("c", SessionState.TERMINATED)).sessions.none { it.id == "c" })
        assertTrue(open.upserted(cloudSession("c", SessionState.PAUSED)).sessions.any { it.id == "c" })
        // An event saying a session ended or paused reads it into the list that covers that state.
        assertEquals("n", open.applied(change(1, "n", "cloud.session.paused", state = SessionState.PAUSED)).second)
        assertNull(open.applied(change(1, "n", "cloud.session.ended", state = SessionState.TERMINATED)).second)
        assertEquals("n", all.applied(change(1, "n", "cloud.session.ended", state = SessionState.TERMINATED)).second)
        assertEquals("n", ended.applied(change(1, "n", "cloud.session.ended", state = SessionState.TERMINATED)).second)
    }

    @Test fun everyKindIsAskedForOnlyFromAServerWithRemoteSessions() = runTest {
        val sessions = FakeSessionRepository()
        val remote = FakeFeatures(setOf("cloud.sessions.v1", "cloud.events.v1", "cloud.sessions.remote.v1"))
        LoadSessionsUseCase(sessions, remote, guard())("a1", SessionScope.MINE, SessionFilter.OPEN)
        val older = FakeFeatures(setOf("cloud.sessions.v1", "cloud.events.v1"))
        LoadSessionsUseCase(sessions, older, guard())("a1", SessionScope.MINE, SessionFilter.OPEN)
        assertEquals(listOf("list MINE OPEN null cloud,remote", "list MINE OPEN null"), sessions.calls)

        sessions.calls.clear()
        sessions.changes = flowOf(LiveUpdate.Opened(false))
        assertEquals(SessionListSignal.RemoteSessions(true), followList(sessions, remote)("a1").toList().first())
        followList(sessions, older)("a1").toList()
        // The feed carries every kind from a server that has them, and is asked as before otherwise.
        assertEquals(listOf("feed cloud,remote", "feed"), sessions.calls)
    }

    @Test fun aRemoteSessionIsNeitherResumedFromHereNorTransferred() {
        val paused = cloudSession("r", SessionState.PAUSED, host = laptop(online = false), problemCode = "host_offline")
        assertFalse(me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase.canResume(paused))
        assertFalse(me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase.canTransfer(paused))
        assertTrue(me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase.canMessage(paused))
        assertTrue(paused.hostOffline)
        val cloud = cloudSession("c", SessionState.PAUSED, problemCode = "host_offline")
        assertTrue(me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase.canResume(cloud))
        assertTrue(me.nathanfallet.teamcode.companion.domain.usecases.sessions.ControlSessionUseCase.canTransfer(cloud))
        assertFalse(cloud.hostOffline)
    }

    private companion object {
        val NO_REMOTE = SessionListSignal.RemoteSessions(false)
    }

    private fun guard() = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))
}
