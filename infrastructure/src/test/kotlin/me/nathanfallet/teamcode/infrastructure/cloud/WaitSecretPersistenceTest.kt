package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.cloud.CloudCreateOutcome
import me.nathanfallet.teamcode.domain.cloud.CloudSessionDraft
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.waits.*
import me.nathanfallet.teamcode.infrastructure.database.SqlWaitStore
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialDecryptionException
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/**
 * Secret fields of waits on real MySQL, two pools standing for two replicas (docs/waits.md#secrets): encrypted at rest, bound to
 * the session, served once when one-time, purged at expiry, and a sentinel value found nowhere else.
 */
class WaitSecretPersistenceTest {
    private val sentinel = "S3NTINEL-pa55w0rd-é-'quoted'"
    private val cipher = CredentialCipher(ByteArray(32) { 73 })

    private class Replica(val service: WaitService, val store: SqlWaitStore)
    private fun waker(r: AutomationReplica) = object : WaitSessions {
        override suspend fun authorize(actor: Principal, sessionId: String) = r.conversation.authorizeWaitAnswer(actor, sessionId)
        override suspend fun deliver(actor: Principal, sessionId: String, text: String, clientKey: String) = r.conversation.queueWaitAnswer(actor, sessionId, text, clientKey)
    }

    @Test @Timeout(300)
    fun secretsAreSealedBoundToTheirSessionAndServedByTheRulesAcrossReplicas() = runBlocking {
        AutomationWorld.run(73) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(Waits, WaitSecretTable, CloudSessions, withLogs = false), "The reviewed migration matches the Exposed tables")
            }
            val storeA = SqlWaitStore(w.a.database, cipher); val storeB = SqlWaitStore(w.b.database, cipher)
            val a = Replica(WaitService(storeA, waker(w.a)), storeA); val b = Replica(WaitService(storeB, waker(w.b)), storeB)
            suspend fun draft(name: String, agent: String = "claude"): String {
                val id = (w.a.sessions.create(w.member, null, CloudSessionDraft(name, w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session.id
                w.a.database.query { CloudSessions.update({ CloudSessions.id eq id }) { it[agentKind] = agent } }
                return id
            }
            val owner = w.member.member.id
            val password = WaitField("shop_password", FieldType.SECRET, "Shop password", required = true)
            val code = WaitField("code", FieldType.SECRET, "One time code", oneTime = true)
            fun ask(session: String, vararg fields: WaitField) = WaitDefinition(session, "Shop access", assigneeId = w.admin.member.id, fields = fields.toList())
            suspend fun secretRows(session: String) = w.a.database.query { WaitSecretTable.selectAll().where { WaitSecretTable.sessionId eq session }.toList() }

            // --- An answer stores the sealed value in its own transaction and the value is nowhere else.
            val session = draft("Shop")
            val id = a.service.create(w.member, ask(session, password, code, WaitField("note", FieldType.TEXT, "Note")), null).wait.id
            val answered = b.service.answer(w.admin, id, "done", mapOf("shop_password" to JsonPrimitive(sentinel), "code" to JsonPrimitive("123456"), "note" to JsonPrimitive("hi")))
            assertEquals(JsonPrimitive("provided"), answered.answer!!.values["shop_password"])
            val rows = secretRows(session).associateBy { it[WaitSecretTable.envName] }
            assertEquals(setOf("TEAMCODE_WAIT_SHOP_PASSWORD", "TEAMCODE_WAIT_CODE"), rows.keys)
            val sealed = rows.getValue("TEAMCODE_WAIT_SHOP_PASSWORD")
            assertNotEquals(sentinel, sealed[WaitSecretTable.value]); assertFalse("S3NTINEL" in sealed[WaitSecretTable.value], "the column is not the plaintext")
            assertTrue(sealed[WaitSecretTable.value].startsWith("v1:"))
            assertEquals(sentinel, cipher.decrypt(sealed[WaitSecretTable.value], "wait-secret:${w.teamId}:$session:TEAMCODE_WAIT_SHOP_PASSWORD").decodeToString())
            assertFailsWith<CredentialDecryptionException> { cipher.decrypt(sealed[WaitSecretTable.value], "wait-secret:${w.teamId}:other:TEAMCODE_WAIT_SHOP_PASSWORD") }
            assertFailsWith<CredentialDecryptionException> { cipher.decrypt(sealed[WaitSecretTable.value], "wait-secret:${w.teamId}:$session:TEAMCODE_WAIT_CODE") }
            assertTrue(rows.getValue("TEAMCODE_WAIT_CODE")[WaitSecretTable.oneTime]); assertFalse(sealed[WaitSecretTable.oneTime])
            assertEquals(owner, sealed[WaitSecretTable.ownerId])
            val lifetime = sealed[WaitSecretTable.expiresAt] - sealed[WaitSecretTable.createdAt]
            assertEquals(WaitRules.SECRET_LIFETIME_MILLIS, lifetime, "bounded to 24 hours")

            // The sentinel is in no column of the tables that hold a wait, its audit, its feed, its transcript or its messages.
            suspend fun everywhereElse(): String = w.a.database.query {
                listOf<Table>(Waits, AuditEvents, TeamEvents, CloudSessionEvents, CloudSessionInputs, CloudSessionTranscripts).joinToString("\n") { table ->
                    table.selectAll().joinToString("\n") { row -> table.columns.joinToString("|") { c -> row.getOrNull(c)?.toString().orEmpty() } }
                }
            }
            val dump = everywhereElse()
            assertFalse("S3NTINEL" in dump, "no table but wait_secrets holds the value"); assertFalse("123456" in dump)
            assertTrue("wait.secret_provided" in dump); assertTrue("TEAMCODE_WAIT_SHOP_PASSWORD" in dump, "the wake message names the variable")
            val message = w.a.conversation.inputs(w.member, session, null, 10).inputs.single { it.kind == me.nathanfallet.teamcode.domain.cloud.CloudInputKind.MESSAGE }
            assertTrue("Shop password: secret provided: TEAMCODE_WAIT_SHOP_PASSWORD (available in your shell environment)" in message.text!!, message.text)
            val stored = a.service.get(w.admin, id)
            assertFalse("S3NTINEL" in stored.toString())

            // --- Served to the session's owner only, by either replica; a one-time secret is deleted by being served.
            assertEquals(emptyMap(), a.store.serveSecrets(w.teamId, session, w.admin.member.id).values, "not another owner")
            assertEquals(emptyMap(), a.store.serveSecrets(w.teamId, "no-such-session", owner).values)
            val first = b.store.serveSecrets(w.teamId, session, owner)
            assertEquals(mapOf("TEAMCODE_WAIT_SHOP_PASSWORD" to sentinel, "TEAMCODE_WAIT_CODE" to "123456"), first.values)
            assertEquals(setOf("TEAMCODE_WAIT_CODE"), first.oneTime)
            assertFalse("S3NTINEL" in first.toString())
            val second = a.store.serveSecrets(w.teamId, session, owner)
            assertEquals(mapOf("TEAMCODE_WAIT_SHOP_PASSWORD" to sentinel), second.values, "the one-time secret is gone, the other stays")
            assertEquals(1, secretRows(session).size)
            assertEquals(1, w.a.database.query { AuditEvents.selectAll().where { (AuditEvents.subjectId eq id) and (AuditEvents.action eq "wait.secret_consumed") }.count() }.toInt())

            // --- Two replicas serving a one-time secret together: exactly one gets it.
            val racing = draft("Race")
            val raceWait = a.service.create(w.member, ask(racing, code), null).wait.id
            b.service.answer(w.admin, raceWait, "done", mapOf("code" to JsonPrimitive("987654")))
            val served = coroutineScope { listOf(a, b, a, b, a, b).map { r -> async(Dispatchers.IO) { r.store.serveSecrets(w.teamId, racing, owner) } }.awaitAll() }
            assertEquals(1, served.count { "TEAMCODE_WAIT_CODE" in it.values }, "served exactly once across replicas")

            // --- The same name in a later answer replaces the value; one row per name.
            val again = a.service.create(w.member, ask(session, password), null).wait.id
            b.service.answer(w.admin, again, "done", mapOf("shop_password" to JsonPrimitive("second-value")))
            assertEquals(1, secretRows(session).size)
            assertEquals("second-value", a.store.serveSecrets(w.teamId, session, owner).values["TEAMCODE_WAIT_SHOP_PASSWORD"])

            // --- An unanswered optional secret stores nothing and reads "not provided".
            val optional = draft("Optional")
            val skipped = a.service.create(w.member, ask(optional, WaitField("maybe", FieldType.SECRET, "Maybe")), null).wait.id
            assertEquals(JsonPrimitive("not provided"), b.service.answer(w.admin, skipped, "done", emptyMap()).answer!!.values["maybe"])
            assertEquals(0, secretRows(optional).size)
            refusedWith("invalid_answer") { a.service.answer(w.admin, a.service.create(w.member, ask(optional, password), null).wait.id, "done", emptyMap()) }

            // --- Expiry: past its lifetime nothing is served, and the worker's pass deletes the row.
            w.a.database.query { WaitSecretTable.update({ WaitSecretTable.sessionId eq session }) { it[expiresAt] = 1L } }
            assertEquals(emptyMap(), b.store.serveSecrets(w.teamId, session, owner).values)
            assertEquals(1, secretRows(session).size, "still stored until the worker passes")
            a.service.resolveDue()
            assertEquals(0, secretRows(session).size, "purged")

            // --- Ends with its session; a Codex session cannot be asked.
            val doomed = draft("Doomed")
            b.service.answer(w.admin, a.service.create(w.member, ask(doomed, password), null).wait.id, "done", mapOf("shop_password" to JsonPrimitive("x")))
            assertEquals(1, secretRows(doomed).size)
            w.a.database.query { CloudSessions.deleteWhere { CloudSessions.id eq doomed } }
            assertEquals(0, secretRows(doomed).size, "cascade")
            val codex = draft("Codex", agent = "codex")
            refusedWith("secret_unsupported_agent") { a.service.create(w.member, ask(codex, password), null) }
            a.service.create(w.member, ask(codex, WaitField("note", FieldType.TEXT, "Note")), null)

            // --- A transfer: someone else owning the session now is not served the former owner's secrets.
            val handed = draft("Handed")
            b.service.answer(w.admin, a.service.create(w.member, ask(handed, password), null).wait.id, "done", mapOf("shop_password" to JsonPrimitive("former")))
            assertEquals(emptyMap(), a.store.serveSecrets(w.teamId, handed, w.admin.member.id).values)

            // --- Without a key the server cannot keep a secret, and says so before storing the answer.
            val keyless = WaitService(SqlWaitStore(w.a.database), waker(w.a))
            val sessionNoKey = draft("NoKey")
            val noKey = keyless.create(w.member, ask(sessionNoKey, password), null).wait.id
            refusedWith("secrets_unavailable") { keyless.answer(w.admin, noKey, "done", mapOf("shop_password" to JsonPrimitive(sentinel))) }
            assertEquals(WaitStatus.OPEN, keyless.get(w.admin, noKey).status, "nothing was stored")
        }
    }
}
