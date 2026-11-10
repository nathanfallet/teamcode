package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.account
import me.nathanfallet.teamcode.companion.cloudSession
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.SessionState
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitAction
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitPage
import me.nathanfallet.teamcode.companion.domain.models.WaitResolver
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.push.ActivityKind
import me.nathanfallet.teamcode.companion.domain.usecases.push.ActivityNotice
import me.nathanfallet.teamcode.companion.domain.usecases.push.WatchActivityUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchActivityUseCaseTest {
    private fun wait(id: String, kind: WaitKind = WaitKind.HUMAN_REQUEST, resolver: WaitResolver = WaitResolver.HUMAN) =
        Wait(id, WaitSource.WAIT, kind, true, "s1", "Fix the login", null, "m1", "Review the plan", null, emptyList(), emptyList(), listOf(WaitAction("done", "Done")), 1, resolver = resolver)

    private class FakeWaits(val page: WaitPage) : WaitRepository {
        override suspend fun list(accountId: String, status: WaitStatus, before: String?) = page
        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>) = error("unused")
    }

    private fun change(seq: Long, type: String, member: String = "m1", phase: String? = null, state: SessionState? = null, reason: String? = null, waitId: String? = null) =
        LiveUpdate.Item(SessionChange(seq, type, "s1", member, seq, state, phase, waitingReason = reason, waitId = waitId))

    @Test fun theMemberIsNotifiedOfWhatTheServerWouldPushAndNothingElse() = runTest {
        val sessions = FakeSessionRepository().apply {
            byId["s1"] = cloudSession("s1")
            changes = flowOf(
                LiveUpdate.Opened(resumed = false),
                change(1, SessionChange.WAITING, reason = "question"),
                change(2, SessionChange.TURN, member = "m2", phase = "completed"), // Another member's session, on an administrator's feed.
                change(3, SessionChange.TURN, phase = "started"),
                change(4, SessionChange.WAIT_CREATED, waitId = "w1"),
                change(5, SessionChange.WAIT_CREATED, waitId = "w2"), // Resolved by a timer: nobody is asked.
                change(6, SessionChange.WAIT_CREATED, waitId = "w3"),
                change(7, SessionChange.WAIT_CREATED, waitId = "w9"), // Already gone.
                change(8, "cloud.session.ended", state = SessionState.FAILED),
                change(9, SessionChange.TURN, phase = "completed"),
            )
        }
        val waits = FakeWaits(WaitPage(listOf(wait("w1"), wait("w2", WaitKind.TIMED, WaitResolver.TIME), wait("w3", WaitKind.TURN_FAILED)), null))
        val accounts = InMemoryAccountRepository(listOf(account("a1", memberId = "m1"), account("demo", memberId = "m1")))
        val watch = WatchActivityUseCase(accounts, SessionFeed(sessions, FakeFeatures(), backgroundScope), sessions, waits, skip = { it == "demo" })
        val link = "teamcode://notification?origin=https%3A%2F%2Fteam.example&member=m1&session=s1"
        assertEquals(
            listOf(
                ActivityNotice("a1", ActivityKind.QUESTION, "Session s1", null, link),
                ActivityNotice("a1", ActivityKind.WAIT, "Fix the login", "Review the plan", "$link&wait=w1"),
                ActivityNotice("a1", ActivityKind.TURN_FAILED, "Fix the login", null, "$link&wait=w3"),
                ActivityNotice("a1", ActivityKind.SESSION_FAILED, "Session s1", null, link),
                ActivityNotice("a1", ActivityKind.TURN_COMPLETED, "Session s1", null, link),
            ),
            watch().take(5).toList(),
        )
    }
}
