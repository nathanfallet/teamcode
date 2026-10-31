package me.nathanfallet.teamcode.domain.waits

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.ada
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.bob
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.principal
import kotlin.test.*

/** Secret fields (docs/waits.md#secrets): the value is kept apart from everything a wait shows. */
class WaitSecretTest {
    private val sentinel = "S3NTINEL-pa55w0rd-é-'quoted'"
    private fun code(block: suspend () -> Unit) = assertFailsWith<TeamFailure> { runBlocking { block() } }.code
    private fun asking(vararg fields: WaitField, session: String = "s1") = WaitDefinition(session, "Shop access", fields = fields.toList())
    private val password = WaitField("shop_password", FieldType.SECRET, "Shop password", required = true)

    @Test fun theRulesAcceptASecretAndRefuseWhatDoesNotFit() {
        WaitRules.validate(asking(password, WaitField("token", FieldType.SECRET, "Token", oneTime = true)))
        assertEquals("invalid_wait", code { WaitRules.validate(asking(WaitField("pw", FieldType.SECRET, "Password", default = JsonPrimitive("x")))) }, "no default for a secret")
        assertEquals("invalid_wait", code { WaitRules.validate(asking(WaitField("pw", FieldType.TEXT, "Password", oneTime = true))) }, "oneTime is for secrets")
        assertEquals("invalid_wait", code { WaitRules.validate(asking(WaitField("Pw", FieldType.SECRET, "A"), WaitField("pW", FieldType.SECRET, "B"))) }, "two ids would be the same variable")
        assertEquals("invalid_wait", code { WaitRules.validate(asking(WaitField("pw", FieldType.SECRET, "A", maxLength = 10))) })
        assertEquals("invalid_wait", code { WaitRules.validate(asking(WaitField("pw", FieldType.SECRET, "A"), session = "s1").copy(kind = WaitKind.WORKFLOW)) }, "never a workflow step")
        assertEquals("invalid_wait", code { WaitRules.validate(WaitDefinition("s1", "t", resolver = WaitResolver.TIME, fields = listOf(password), schedule = WaitSchedule(afterSeconds = 60))) }, "never a timer")
        assertEquals("TEAMCODE_WAIT_SHOP_PASSWORD", WaitRules.secretVariable("shop_password"))
    }

    @Test fun theValueIsAStringOfAtMost4KiBWithoutNul() {
        fun check(value: kotlinx.serialization.json.JsonElement) = WaitRules.checkAnswer(listOf(password), listOf(WaitAction("done", "Done")), "done", mapOf("shop_password" to value))
        check(JsonPrimitive("a".repeat(4096)))
        assertEquals("invalid_answer", code { check(JsonPrimitive("a".repeat(4097))) })
        assertEquals("invalid_answer", code { check(JsonPrimitive("é".repeat(2049))) }, "bytes, not characters")
        assertEquals("invalid_answer", code { check(JsonPrimitive("a\u0000b")) })
        assertEquals("invalid_answer", code { check(JsonPrimitive(12)) })
        assertEquals("invalid_answer", code { check(JsonNull) }, "a required secret is needed")
        assertTrue(runCatching { check(JsonPrimitive("line1\nline2")) }.isSuccess, "a multi-line key is a secret too")
        val failure = assertFailsWith<TeamFailure> { check(JsonPrimitive(sentinel + "\u0000")) }
        assertFalse(sentinel in failure.message.orEmpty(), "a refusal never repeats the value")
    }

    @Test fun anAnswerKeepsTheValueApartAndEveryViewReadsProvided() = runBlocking {
        val store = WaitFixtures.FakeStore(); val sessions = FakeSessions(); val service = WaitService(store, sessions)
        val id = service.create(principal(ada), asking(password, WaitField("token", FieldType.SECRET, "Token", oneTime = true), WaitField("note", FieldType.TEXT, "Note")), null).wait.id
        val answered = service.answer(principal(ada), id, "done", mapOf("shop_password" to JsonPrimitive(sentinel), "note" to JsonPrimitive("fine")))
        assertEquals(JsonPrimitive("provided"), answered.answer!!.values["shop_password"])
        assertEquals(JsonPrimitive("not provided"), answered.answer!!.values["token"])
        assertEquals(JsonPrimitive("fine"), answered.answer!!.values["note"])
        assertFalse(sentinel in service.get(principal(ada), id).toString(), "the stored wait")
        assertFalse(sentinel in store.audit.toString(), "the audit")
        assertEquals("wait.secret_provided:$id:shop_password", store.audit.last { it.startsWith("wait.secret_provided") })
        val message = sessions.delivered.single().third
        assertFalse(sentinel in message, "the wake message")
        assertTrue("Shop password: secret provided: TEAMCODE_WAIT_SHOP_PASSWORD (available in your shell environment)" in message, message)
        assertTrue("Token: secret not provided" in message, message)
        val kept = store.kept.getValue("s1" to "TEAMCODE_WAIT_SHOP_PASSWORD")
        assertEquals(sentinel, kept.value); assertFalse(kept.oneTime); assertEquals(1_000L + WaitRules.SECRET_LIFETIME_MILLIS, kept.expiresAt)
        assertFalse(sentinel in kept.toString(), "a secret never prints its value")
    }

    @Test fun aOneTimeSecretIsMarkedAndTheMessageSaysForThisTurnOnly() = runBlocking {
        val store = WaitFixtures.FakeStore(); val sessions = FakeSessions(); val service = WaitService(store, sessions)
        val id = service.create(principal(ada), asking(WaitField("code", FieldType.SECRET, "Code", oneTime = true)), null).wait.id
        service.answer(principal(ada), id, "done", mapOf("code" to JsonPrimitive(sentinel)))
        assertTrue(store.kept.values.single().oneTime)
        assertTrue("secret provided: TEAMCODE_WAIT_CODE (available in your shell environment, for this turn only)" in sessions.delivered.single().third)
    }

    @Test fun theSameAnswerSentAgainWakesOnceAndStoresNothingMore() = runBlocking {
        val store = WaitFixtures.FakeStore(); val sessions = FakeSessions(); val service = WaitService(store, sessions)
        val id = service.create(principal(ada), asking(password), null).wait.id
        val values = mapOf("shop_password" to JsonPrimitive(sentinel))
        service.answer(principal(ada), id, "done", values)
        store.audit.clear()
        service.answer(principal(ada), id, "done", values)
        assertEquals(1, sessions.delivered.size); assertEquals(emptyList(), store.audit)
        assertEquals("wait_closed", code { service.answer(principal(ada), id, "other", values) })
    }

    @Test fun aCodexSessionCannotAskForASecret() {
        val service = WaitService(WaitFixtures.FakeStore(), FakeSessions())
        assertEquals("secret_unsupported_agent", code { service.create(principal(bob), asking(password, session = "s2"), null) })
        runBlocking { service.create(principal(bob), asking(WaitField("note", FieldType.TEXT, "Note"), session = "s2"), null) }
    }

    @Test fun secretsAreServedForTheirOwnerAndLeaveWithTheirLifetime() = runBlocking {
        val store = WaitFixtures.FakeStore(); val service = WaitService(store, FakeSessions())
        val id = service.create(principal(ada), asking(password, WaitField("code", FieldType.SECRET, "Code", oneTime = true)), null).wait.id
        service.answer(principal(ada), id, "done", mapOf("shop_password" to JsonPrimitive(sentinel), "code" to JsonPrimitive("123456")))
        assertEquals(ServedSecrets().values, store.serveSecrets("team", "s1", "bob").values, "not to another owner")
        val first = store.serveSecrets("team", "s1", "ada")
        assertEquals(mapOf("TEAMCODE_WAIT_SHOP_PASSWORD" to sentinel, "TEAMCODE_WAIT_CODE" to "123456"), first.values)
        assertEquals(setOf("TEAMCODE_WAIT_CODE"), first.oneTime)
        assertEquals(setOf("TEAMCODE_WAIT_SHOP_PASSWORD"), store.serveSecrets("team", "s1", "ada").values.keys, "a one-time secret is served once")
        assertFalse(sentinel in first.toString())
        store.clock += WaitRules.SECRET_LIFETIME_MILLIS + 1
        assertEquals(emptyMap(), store.serveSecrets("team", "s1", "ada").values)
        service.resolveDue()
        assertEquals(emptyMap(), store.kept, "the worker's pass purges what expired")
    }
}
