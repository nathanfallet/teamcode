package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.CloudCreateOutcome
import me.nathanfallet.teamcode.domain.cloud.CloudSessionDraft
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.ServiceScope
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.todos.*
import me.nathanfallet.teamcode.domain.waits.WaitService
import me.nathanfallet.teamcode.domain.waits.WaitSessions
import me.nathanfallet.teamcode.infrastructure.database.SqlTodoStore
import me.nathanfallet.teamcode.infrastructure.database.SqlWaitStore
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/** A session's todo on real MySQL, with two pools standing for two replicas. */
class TodoPersistenceTest {
    private val repo = "acme/backend"
    private val long = "Do this item completely: what, where, why and what is out of scope, enough for a new agent to do it alone."
    private fun item(id: String, vararg deps: String, checks: List<TodoCheck> = listOf(TodoCheck.PrMerged(repo))) =
        TodoItemDefinition(id, "Item $id", long, TodoDoneWhen("It is merged", checks), deps.toList())

    private fun waker(r: AutomationReplica) = object : WaitSessions {
        override suspend fun authorize(actor: Principal, sessionId: String) = r.conversation.authorizeWaitAnswer(actor, sessionId)
        override suspend fun deliver(actor: Principal, sessionId: String, text: String, clientKey: String) = r.conversation.queueWaitAnswer(actor, sessionId, text, clientKey)
    }

    private suspend fun refusedWith(code: String, block: suspend () -> Unit) {
        val failure = assertFailsWith<TeamFailure> { block() }
        assertEquals(code, failure.code, failure.message)
    }

    @Test @Timeout(240)
    fun aTodoHoldsAcrossReplicas() = runBlocking {
        AutomationWorld.run(91) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(SessionTodos, SessionTodoItems, CloudSessions, withLogs = false), "The reviewed migrations match the Exposed tables")
            }
            val waitsA = SqlWaitStore(w.a.database)
            val a = TodoService(SqlTodoStore(w.a.database), WaitBackedDropWaits(waitsA)); val b = TodoService(SqlTodoStore(w.b.database), WaitBackedDropWaits(SqlWaitStore(w.b.database)))
            suspend fun draft(name: String) = (w.a.sessions.create(w.member, null, CloudSessionDraft(name, w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
            val session = draft("Plan").id

            // One list per session, even when two replicas create at once.
            val created = coroutineScope {
                listOf(a, b).map { service -> async(Dispatchers.IO) { runCatching { service.create(w.member, session, "The plan", emptyList(), listOf(item("a"), item("b", "a"), item("c", "b"))) } } }.awaitAll()
            }
            assertEquals(1, created.count { it.isSuccess }, "exactly one replica creates")
            assertEquals("todo_exists", (created.single { it.isFailure }.exceptionOrNull() as TeamFailure).code)
            val list = a.get(w.member, session)!!
            assertEquals(listOf("a", "b", "c"), list.items.map { it.id })
            assertEquals(list, b.get(w.member, session), "both replicas read the same list")

            // A discovered item goes after the one in progress; the order survives a round trip.
            b.start(w.member, session, "a", false, null)
            val added = a.add(w.member, session, item("extra"), "A migration is needed")
            assertEquals(listOf("a", "extra", "b", "c"), added.items.map { it.id })
            assertEquals(TodoOrigin.DISCOVERED, added.item("extra")!!.origin)

            // Two replicas noting at once keep both notes (the row lock serializes them).
            coroutineScope { listOf(a to "from a", b to "from b").map { (s, t) -> async(Dispatchers.IO) { s.note(w.member, session, "a", t) } }.awaitAll() }
            assertEquals(setOf("from a", "from b"), a.get(w.member, session)!!.item("a")!!.notes.map { it.text }.toSet())

            // An item is done only when its checks pass; a refusal keeps the proof.
            refusedWith("not_done") { b.complete(w.member, session, "a", listOf(TodoCheckReport(0, false, "OPEN, 2 checks failing"))) }
            val seen = a.get(w.member, session)!!.item("a")!!
            assertEquals(TodoItemStatus.DOING, seen.status); assertEquals(false, seen.proof!!.passed); assertContains(seen.proof!!.text, "OPEN")
            refusedWith("not_done") { a.complete(w.member, session, "a", listOf(TodoCheckReport(0, null, "gh timed out"))) }
            assertNull(a.get(w.member, session)!!.item("a")!!.proof!!.passed, "unknown is stored as unknown")
            refusedWith("invalid_proof") { a.complete(w.member, session, "a", listOf(TodoCheckReport(3, true, "x"))) }
            val done = b.complete(w.member, session, "a", listOf(TodoCheckReport(0, true, "MERGED", listOf("https://github.com/acme/backend/pull/1"))))
            assertEquals(TodoItemStatus.DONE, done.item("a")!!.status)
            assertEquals(done.item("a"), a.get(w.member, session)!!.item("a"))
            assertEquals("b", TodoRules.next(a.get(w.member, session)!!)?.id.let { if (it == "extra") "b" else it }, "the next item follows the list order")

            // A drop request asks the owner through a wait; nothing is deleted before the yes.
            val asked = a.requestDrop(w.member, session, "extra", "No longer makes sense")
            val dropItem = asked.item("extra")!!
            assertEquals(TodoItemStatus.DROP_REQUESTED, dropItem.status)
            val waitService = WaitService(waitsA, waker(w.a))
            val wait = waitService.get(w.member, dropItem.dropWaitId!!)
            assertEquals(session, wait.sessionId)
            assertEquals(TodoItemStatus.DROP_REQUESTED, b.get(w.member, session)!!.item("extra")!!.status, "still open")
            waitService.answer(w.member, wait.id, WaitBackedDropWaits.YES, emptyMap())
            val afterYes = b.get(w.member, session)!!
            assertEquals(TodoItemStatus.DROPPED, afterYes.item("extra")!!.status)
            assertEquals(4, afterYes.items.size, "a dropped item stays in the list")
            val kept = a.requestDrop(w.member, session, "c", "Maybe not")
            waitService.answer(w.member, kept.item("c")!!.dropWaitId!!, WaitBackedDropWaits.NO, emptyMap())
            assertEquals(TodoItemStatus.TODO, a.get(w.member, session)!!.item("c")!!.status, "no keeps the item")
            assertNull(a.get(w.member, session)!!.item("c")!!.dropWaitId)

            // Others cannot touch the list; an administrator reads it.
            refusedWith("not_found") { a.note(w.admin, session, "a", "mine") }
            assertEquals(session, a.get(w.admin, session)!!.sessionId, "an administrator reads the team's todos")
            anExecutionCredentialIsConfinedToItsSession(a, b, w, session, draft("Other").id)

            // The todo ends with its session.
            w.a.database.query { CloudSessions.deleteWhere { CloudSessions.id eq session } }
            w.a.database.query {
                assertEquals(0L, SessionTodos.selectAll().where { SessionTodos.sessionId eq session }.count())
                assertEquals(0L, SessionTodoItems.selectAll().where { SessionTodoItems.sessionId eq session }.count())
            }
        }
    }

    private suspend fun anExecutionCredentialIsConfinedToItsSession(a: TodoService, b: TodoService, w: AutomationWorld, own: String, other: String) {
        suspend fun credential(scopes: Set<ServiceScope>, bound: String?): Principal {
            val tokenId = java.util.UUID.randomUUID().toString()
            w.a.database.query {
                val now = System.currentTimeMillis()
                Tokens.insert {
                    it[id] = tokenId; it[memberId] = w.member.member.id; it[tokenHash] = "hash-$tokenId"; it[device] = "cloud session"; it[deviceId] = null
                    it[createdAt] = now; it[expiresAt] = now + 3_600_000; it[serviceScopes] = kotlinx.serialization.json.Json.encodeToString(scopes.map { scope -> scope.wire })
                }
                if (bound != null) CloudInferenceCredentials.insert { it[CloudInferenceCredentials.tokenId] = tokenId; it[sessionId] = bound; it[epoch] = 1; it[teamId] = w.teamId; it[createdAt] = now; it[expiresAt] = now + 3_600_000 }
            }
            return w.member.copy(tokenId = tokenId, serviceScopes = scopes)
        }
        val agent = credential(setOf(ServiceScope.INFERENCE, ServiceScope.TODO_SESSION), own)
        // Whatever session it names, the credential acts on its own.
        assertEquals(own, a.get(agent, other)!!.sessionId)
        assertEquals("agent note", b.note(agent, other, "b", "agent note").item("b")!!.notes.single().text)
        assertNull(a.get(w.member, other), "the other session has no todo")
        // A service key carrying the scope without being bound to a session gets nothing.
        val unbound = credential(setOf(ServiceScope.TODO_SESSION), null)
        refusedWith("credential_scope") { a.get(unbound, own) }
        // Without the scope and without cloud scopes: refused.
        val inferenceOnly = credential(setOf(ServiceScope.INFERENCE), null)
        refusedWith("credential_scope") { a.get(inferenceOnly, own) }
    }
}
