package me.nathanfallet.teamcode.companion.domain

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.companion.FakeAuthRepository
import me.nathanfallet.teamcode.companion.FakeFeatures
import me.nathanfallet.teamcode.companion.FakeSessionRepository
import me.nathanfallet.teamcode.companion.FixedClock
import me.nathanfallet.teamcode.companion.InMemoryAccountRepository
import me.nathanfallet.teamcode.companion.credentials
import me.nathanfallet.teamcode.companion.domain.models.AnswerLine
import me.nathanfallet.teamcode.companion.domain.models.AnswerValue
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.models.WaitAnswer
import me.nathanfallet.teamcode.companion.domain.models.FieldInput
import me.nathanfallet.teamcode.companion.domain.models.FieldType
import me.nathanfallet.teamcode.companion.domain.models.LiveUpdate
import me.nathanfallet.teamcode.companion.domain.models.SessionChange
import me.nathanfallet.teamcode.companion.domain.models.Wait
import me.nathanfallet.teamcode.companion.domain.models.WaitAction
import me.nathanfallet.teamcode.companion.domain.models.WaitField
import me.nathanfallet.teamcode.companion.domain.models.WaitInputProblem
import me.nathanfallet.teamcode.companion.domain.models.WaitKind
import me.nathanfallet.teamcode.companion.domain.models.WaitOption
import me.nathanfallet.teamcode.companion.domain.models.WaitPage
import me.nathanfallet.teamcode.companion.domain.models.WaitStatus
import me.nathanfallet.teamcode.companion.domain.models.WaitSource
import me.nathanfallet.teamcode.companion.domain.repositories.WaitRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard
import me.nathanfallet.teamcode.companion.domain.usecases.auth.RefreshAccountUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.sessions.SessionFeed
import me.nathanfallet.teamcode.companion.domain.usecases.waits.AnswerWaitUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.FollowWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.LoadWaitsUseCase
import me.nathanfallet.teamcode.companion.domain.usecases.waits.WaitAnswerBuilder
import me.nathanfallet.teamcode.companion.domain.usecases.waits.WaitAnswerOutcome
import me.nathanfallet.teamcode.companion.domain.usecases.waits.WaitsSignal
import kotlin.test.*

private fun wait(id: String = "w1", fields: List<WaitField> = emptyList(), actions: List<WaitAction> = listOf(WaitAction("done", "Done"))) =
    Wait(id, WaitSource.WAIT, WaitKind.HUMAN_REQUEST, true, "s1", "Fix the login", null, "m1", "Review the plan", null, emptyList(), fields, actions, 1)

class WaitUseCasesTest {
    private class FakeWaits : WaitRepository {
        val calls = mutableListOf<String>()
        var page = WaitPage(emptyList(), null)
        var failure: Exception? = null
        override suspend fun list(accountId: String, status: WaitStatus, before: String?): WaitPage { calls += "list ${status.wire} ${before.orEmpty()}".trim(); return page }
        override suspend fun answer(accountId: String, waitId: String, action: String, values: Map<String, JsonElement>): Wait {
            calls += "answer $waitId $action ${values.entries.joinToString(",") { "${it.key}=${it.value}" }}"
            failure?.let { throw it }
            return wait(waitId).copy(open = false)
        }
    }

    private val feedback = WaitField("feedback", FieldType.TEXT, "Feedback")
    private val review = listOf(WaitAction("approve", "Approve"), WaitAction("request_changes", "Request changes", requires = listOf("feedback")))

    private fun problem(wait: Wait, action: String, inputs: Map<String, FieldInput>) =
        assertFailsWith<WaitInputProblem> { WaitAnswerBuilder.build(wait, wait.actions.first { it.id == action }, inputs) }

    @Test fun anAnswerOnlyHoldsTheFilledFieldsAndEachKeepsItsType() {
        val fields = listOf(
            feedback, WaitField("ok", FieldType.CONFIRM, "OK?"), WaitField("n", FieldType.NUMBER, "How many"), WaitField("d", FieldType.DATE, "Day"),
            WaitField("one", FieldType.CHOICE, "One", options = listOf(WaitOption("a", "A"), WaitOption("b", "B"))),
            WaitField("many", FieldType.CHOICE, "Many", multiple = true), WaitField("note", FieldType.INFO, "Note", help = "Read me"), WaitField("empty", FieldType.TEXT, "Empty"),
        )
        val wait = wait(fields = fields)
        val values = WaitAnswerBuilder.build(wait, wait.actions.single(), mapOf(
            "feedback" to FieldInput.Text("Split step 2"), "ok" to FieldInput.Flag(false), "n" to FieldInput.Text(" 12 "), "d" to FieldInput.Text("2028-02-29"),
            "one" to FieldInput.Chosen(setOf("a")), "many" to FieldInput.Chosen(setOf("x", "y")), "empty" to FieldInput.Text("  "),
        ))
        assertEquals(
            mapOf("feedback" to JsonPrimitive("Split step 2"), "ok" to JsonPrimitive(false), "n" to JsonPrimitive(12L), "d" to JsonPrimitive("2028-02-29"),
                "one" to JsonPrimitive("a"), "many" to JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive("y")))),
            values,
        )
        assertEquals(JsonPrimitive(1.5), WaitAnswerBuilder.build(wait(fields = listOf(WaitField("n", FieldType.NUMBER, "n"))), wait().actions.single(), mapOf("n" to FieldInput.Text("1.5")))["n"])
    }

    @Test fun whatTheActionRequiresIsCheckedBeforeAnythingIsSent() {
        val wait = wait(fields = listOf(feedback), actions = review)
        assertEquals(emptyMap(), WaitAnswerBuilder.build(wait, review[0], emptyMap()), "approving needs no feedback")
        val missing = problem(wait, "request_changes", mapOf("feedback" to FieldInput.Text("   ")))
        assertEquals("feedback", missing.fieldId); assertEquals(WaitInputProblem.Reason.MISSING, missing.reason); assertEquals("Feedback", missing.label)
        val required = wait(fields = listOf(feedback.copy(required = true)))
        assertEquals(WaitInputProblem.Reason.MISSING, problem(required, "done", emptyMap()).reason)
    }

    @Test fun aBadNumberDateOrLengthNamesItsField() {
        fun reason(type: FieldType, text: String, max: Int? = null) =
            problem(wait(fields = listOf(WaitField("f", type, "F", maxLength = max))), "done", mapOf("f" to FieldInput.Text(text))).reason
        assertEquals(WaitInputProblem.Reason.NOT_A_NUMBER, reason(FieldType.NUMBER, "twelve"))
        assertEquals(WaitInputProblem.Reason.NOT_A_NUMBER, reason(FieldType.NUMBER, "NaN"))
        assertEquals(WaitInputProblem.Reason.NOT_A_DATE, reason(FieldType.DATE, "2027-02-29"))
        assertEquals(WaitInputProblem.Reason.NOT_A_DATE, reason(FieldType.DATE, "29/02/2028"))
        assertEquals(WaitInputProblem.Reason.TOO_LONG, reason(FieldType.TEXT, "abcd", max = 3))
    }

    @Test fun aFileIsNotFilledHereButDoesNotBlockWhatDoesNotNeedIt() {
        val optional = wait(fields = listOf(WaitField("f", FieldType.FILE, "Screenshot"), WaitField("s", FieldType.SECRET, "Password")))
        assertEquals(emptyMap(), WaitAnswerBuilder.build(optional, optional.actions.single(), emptyMap()))
        val needed = wait(fields = listOf(WaitField("f", FieldType.FILE, "Screenshot", required = true)))
        assertEquals(WaitInputProblem.Reason.UNSUPPORTED, problem(needed, "done", emptyMap()).reason)
    }

    @Test fun aSecretIsSentAsTypedAndNeverPrintsItsValue() {
        val secret = WaitField("pw", FieldType.SECRET, "Password", required = true)
        val wait = wait(fields = listOf(secret))
        val typed = FieldInput.Secret("S3NTINEL-it's \"q\" \n line2")
        assertEquals(mapOf("pw" to JsonPrimitive(typed.value)), WaitAnswerBuilder.build(wait, wait.actions.single(), mapOf("pw" to typed)), "the value is sent as it was typed, not trimmed or altered")
        assertFalse("S3NTINEL" in typed.toString() + mapOf("pw" to typed).toString(), "a draft printed in a log shows no value")
        assertEquals(FieldInput.Secret("a"), FieldInput.Secret("a"))
        assertEquals(WaitInputProblem.Reason.MISSING, problem(wait, "done", mapOf("pw" to FieldInput.Secret("   "))).reason)
        assertEquals(WaitInputProblem.Reason.MISSING, problem(wait, "done", emptyMap()).reason)
        assertEquals(WaitInputProblem.Reason.TOO_LONG, problem(wait, "done", mapOf("pw" to FieldInput.Secret("é".repeat(2049)))).reason, "4 KiB of bytes")
        assertEquals(emptyMap(), WaitAnswerBuilder.build(wait(fields = listOf(secret.copy(required = false))), wait.actions.single(), emptyMap()), "an optional secret left empty is not sent")
    }

    @Test fun anAnsweredSecretReadsProvidedNeverAValue() {
        val secret = WaitField("pw", FieldType.SECRET, "Password")
        val answered = wait(fields = listOf(secret, WaitField("note", FieldType.TEXT, "Note"))).copy(open = false, answer = WaitAnswer("done", mapOf("pw" to JsonPrimitive("provided"), "note" to JsonPrimitive("ok"))))
        assertEquals(listOf(AnswerLine("Password", AnswerValue.Secret(true)), AnswerLine("Note", AnswerValue.Words("ok"))), answered.answerLines())
        val skipped = answered.copy(answer = WaitAnswer("done", mapOf("pw" to JsonPrimitive("not provided"))))
        assertEquals(AnswerValue.Secret(false), skipped.answerLines().single().value)
    }

    private fun guard() = AccountGuard(RefreshAccountUseCase(FakeAuthRepository(), InMemoryAccountRepository(), credentials(), FixedClock()))

    @Test fun anAnswerIsSentWithItsValuesAndTheRepositoryNeverSeesAnInvalidOne() = runTest {
        val waits = FakeWaits(); val answer = AnswerWaitUseCase(waits, guard())
        val wait = wait(fields = listOf(feedback), actions = review)
        val outcome = answer("a1", wait, "request_changes", mapOf("feedback" to FieldInput.Text("Split step 2")))
        assertIs<WaitAnswerOutcome.Answered>(outcome)
        assertEquals(listOf("answer w1 request_changes feedback=\"Split step 2\""), waits.calls)
        waits.calls.clear()
        assertIs<WaitAnswerOutcome.Problem>(answer("a1", wait, "request_changes", emptyMap()))
        assertIs<WaitAnswerOutcome.Closed>(answer("a1", wait, "gone", emptyMap()), "an action the wait no longer has")
        assertEquals(emptyList(), waits.calls)
    }

    @Test fun eachFailureOfAnAnswerBecomesTheOutcomeTheScreenCanShow() = runTest {
        val waits = FakeWaits(); val answer = AnswerWaitUseCase(waits, guard()); val wait = wait()
        suspend fun outcome(failure: Exception): WaitAnswerOutcome { waits.failure = failure; return answer("a1", wait, "done", emptyMap()) }
        assertIs<WaitAnswerOutcome.Closed>(outcome(CompanionException.Server(409, "wait_closed")))
        assertIs<WaitAnswerOutcome.Closed>(outcome(CompanionException.NotFound()))
        assertIs<WaitAnswerOutcome.Refused>(outcome(CompanionException.Server(400, "invalid_answer")))
        assertIs<WaitAnswerOutcome.Retry>(outcome(CompanionException.Server(503, null)))
        assertIs<WaitAnswerOutcome.Retry>(outcome(CompanionException.Unreachable()))
        assertIs<WaitAnswerOutcome.Retry>(outcome(CompanionException.RateLimited(20)))
        assertIs<WaitAnswerOutcome.Failed>(outcome(CompanionException.Server(403, "wait_assignee_only")))
        waits.failure = CompanionException.Unauthorized()
        assertFailsWith<CompanionException.Unauthorized> { answer("a1", wait, "done", emptyMap()) }
    }

    @Test fun theSectionIsHiddenFromAServerWithoutWaits() = runTest {
        val waits = FakeWaits(); val features = FakeFeatures()
        val load = LoadWaitsUseCase(waits, features, guard())
        assertFailsWith<CompanionException.Unsupported> { load("a1") }
        assertEquals(emptyList(), waits.calls)
        features.features += "waits.v1"; waits.page = WaitPage(listOf(wait()), null)
        assertEquals(listOf("w1"), load("a1").waits.map { it.id })
    }

    private fun waitEvent(seq: Long, type: String, member: String = "m1") = SessionChange(seq, type, "s1", member, seq, null, null, waitId = "w1")

    private fun TestScopeFollow(sessions: FakeSessionRepository, features: FakeFeatures, scope: kotlinx.coroutines.CoroutineScope) =
        FollowWaitsUseCase(SessionFeed(sessions, features, scope), features, guard())

    @Test fun theSectionLoadsWhenTheFeedOpensAfterAGapAndForItsMembersWaitsOnly() = runTest {
        val sessions = FakeSessionRepository(); val features = FakeFeatures().also { it.features += listOf("waits.v1") }
        val own = waitEvent(1, SessionChange.WAIT_CREATED); val other = waitEvent(2, SessionChange.WAIT_CREATED, member = "m2")
        val resolved = waitEvent(3, SessionChange.WAIT_RESOLVED)
        val turn = SessionChange(4, "cloud.turn", "s1", "m1", 4, null, "started")
        val asked = SessionChange(5, SessionChange.WAITING, "s1", "m1", 5, null, null, waitingReason = "permission", requestId = "r1")
        sessions.changes = flowOf(
            LiveUpdate.Opened(false), LiveUpdate.Item(own), LiveUpdate.Item(other), LiveUpdate.Item(turn), LiveUpdate.Item(resolved), LiveUpdate.Item(asked),
            LiveUpdate.Reconnecting(2_000), LiveUpdate.Opened(true), LiveUpdate.Gap,
        )
        val signals = TestScopeFollow(sessions, features, backgroundScope)("a1", "m1").toList()
        assertEquals(
            listOf(
                WaitsSignal.Connection(false), WaitsSignal.Reload, WaitsSignal.Reload, WaitsSignal.Reload, WaitsSignal.Reload,
                WaitsSignal.Connection(true), WaitsSignal.Connection(false), WaitsSignal.Reload,
            ),
            signals,
        )
        assertTrue(sessions.calls.any { it == "feed waiting waits" || it.startsWith("feed") && it.endsWith(" waits") }, sessions.calls.toString())
    }

    @Test fun withoutTheFeedTheSectionLoadsOnce() = runTest {
        val features = FakeFeatures().also { it.features = setOf("waits.v1") }
        assertEquals(listOf(WaitsSignal.Reload), TestScopeFollow(FakeSessionRepository(), features, backgroundScope)("a1", "m1").toList())
    }
}
