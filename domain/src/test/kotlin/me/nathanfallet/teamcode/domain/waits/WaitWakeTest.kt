package me.nathanfallet.teamcode.domain.waits

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.ada
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.admin
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.po
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.principal
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.review
import kotlin.test.*

class WaitWakeTest {
    private fun code(block: suspend () -> Unit) = assertFailsWith<TeamFailure> { runBlocking { block() } }.code

    @Test fun anAnswerIsQueuedOnTheSessionAsAMessageFromWhoAnsweredIt() = runBlocking {
        val sessions = FakeSessions(); val service = WaitService(WaitFixtures.FakeStore(), sessions)
        val id = service.create(principal(ada), review.copy(assigneeId = "po"), null).wait.id
        service.answer(principal(po), id, "request_changes", mapOf("feedback" to JsonPrimitive("Split step 2\nand test it")))
        val (session, author, text) = sessions.delivered.single()
        assertEquals("s1", session); assertEquals("po", author)
        assertEquals(
            "[TeamCode wait answered] Review the plan\nwait: $id\naction: Request changes (request_changes)\nanswered by: po\nFeedback: Split step 2\n  and test it",
            text,
        )
        assertEquals(listOf("po:s1"), sessions.authorized, "checked before anything is stored")
    }

    @Test fun anUnfilledFieldIsLeftOutAndAChoiceReadsWithItsLabel() {
        val wait = WaitFixtures.run {
            Wait("w", "team", WaitSource.WAIT, WaitKind.HUMAN_REQUEST, WaitResolver.HUMAN, WaitStatus.RESOLVED, "s1", null, null, "ada", null, "ada", "Pick\u0007 one", null, emptyList(),
                listOf(WaitField("a", FieldType.CHOICE, "A", options = listOf(WaitOption("x", "Ex"), WaitOption("y", "Y"))), WaitField("b", FieldType.TEXT, "B"), WaitField("c", FieldType.CHOICE, "C", multiple = true, options = listOf(WaitOption("m", "Em")))),
                listOf(WaitAction("done", "Done")),
                WaitAnswer("done", mapOf("a" to JsonPrimitive("x"), "c" to JsonArray(listOf(JsonPrimitive("m"), JsonPrimitive("z")))), "ada", 5), 1, 5)
        }
        assertEquals("[TeamCode wait answered] Pick one\nwait: w\naction: Done (done)\nanswered by: ada\nA: x (Ex)\nC: m (Em), z", WaitMessages.answered(wait, "ada"))
    }

    @Test fun aHugeAnswerIsCutToWhatAMessageMayHold() {
        val wait = WaitFixtures.run {
            Wait("w", "team", WaitSource.WAIT, WaitKind.HUMAN_REQUEST, WaitResolver.HUMAN, WaitStatus.RESOLVED, "s1", null, null, "ada", null, "ada", "T", null, emptyList(),
                (1..5).map { WaitField("f$it", FieldType.TEXT, "F$it") }, listOf(WaitAction("done", "Done")),
                WaitAnswer("done", (1..5).associate { "f$it" to JsonPrimitive("x".repeat(8_000)) }, "ada", 5), 1, 5)
        }
        val text = WaitMessages.answered(wait, "ada")
        assertEquals(32_000, text.length); assertTrue(text.endsWith("[truncated]"))
    }

    @Test fun aRefusedInstructorStoresNothingAndAFailedDeliveryCanBeRetriedWithTheSameAnswer() = runBlocking {
        val store = WaitFixtures.FakeStore(); val sessions = FakeSessions(); val service = WaitService(store, sessions)
        val id = service.create(principal(ada), review, null).wait.id
        sessions.refuse = TeamFailure("github_owner_only", 403, "x")
        assertEquals("github_owner_only", code { service.answer(principal(ada), id, "approve", emptyMap()) })
        assertEquals(WaitStatus.OPEN, store.waits.getValue(id).status, "an answer nobody could deliver is not stored")
        sessions.refuse = null
        sessions.failDelivery = IllegalStateException("database blip")
        assertFailsWith<IllegalStateException> { service.answer(principal(ada), id, "approve", emptyMap()) }
        assertEquals(WaitStatus.RESOLVED, store.waits.getValue(id).status, "the answer is stored")
        assertEquals(emptyList(), sessions.delivered)
        // The caller saw an error and sends the same answer again: it is delivered, not refused.
        sessions.failDelivery = null
        assertEquals(WaitStatus.RESOLVED, service.answer(principal(ada), id, "approve", emptyMap()).status)
        assertEquals(1, sessions.delivered.size)
        service.answer(principal(ada), id, "approve", emptyMap())
        assertEquals(1, sessions.delivered.size, "and once only")
        // Another action, another member, or other values is a lost race.
        assertEquals("wait_closed", code { service.answer(principal(ada), id, "request_changes", mapOf("feedback" to JsonPrimitive("x"))) })
        assertEquals("wait_closed", code { service.answer(principal(admin), id, "approve", emptyMap()) })
    }

    @Test fun aWaitWithoutSessionsStillAnswers() = runBlocking {
        val service = WaitService(WaitFixtures.FakeStore())
        val id = service.create(principal(ada), review, null).wait.id
        assertEquals(WaitStatus.RESOLVED, service.answer(principal(ada), id, "approve", emptyMap()).status)
    }
}
