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
            // The block that follows a wake message reads the list with the owner's answer applied, from either replica.
            val wake = b.wakeBlock(w.teamId, session)!!
            assertTrue(wake.startsWith("[TeamCode todo] Your todo: 1 of 4 items done."), wake)
            assertTrue(wake.contains("extra") && wake.contains("dropped by the owner"), wake)
            assertEquals(wake, a.context(w.member, session))
            val kept = a.requestDrop(w.member, session, "c", "Maybe not")
            waitService.answer(w.member, kept.item("c")!!.dropWaitId!!, WaitBackedDropWaits.NO, emptyMap())
            assertEquals(TodoItemStatus.TODO, a.get(w.member, session)!!.item("c")!!.status, "no keeps the item")
            assertNull(a.get(w.member, session)!!.item("c")!!.dropWaitId)

            // A link is stored with the item, completed by the same address and frozen against another one, across replicas.
            val pr = TodoLink("pr", "https://github.com/acme/backend/pull/7", number = 7)
            a.link(w.member, session, "b", pr, false)
            b.link(w.member, session, "b", pr.copy(sha = "4e1c9a2"), false)
            assertEquals(listOf("4e1c9a2"), a.get(w.member, session)!!.item("b")!!.links.map { it.sha })
            refusedWith("already_linked") { a.link(w.member, session, "b", TodoLink("pr", "https://github.com/acme/backend/pull/8"), false) }
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

    @Test @Timeout(240)
    fun theStopHookCountsOnceAcrossReplicasAndAnOpenWaitLetsTheStopPass() = runBlocking {
        AutomationWorld.run(92) { w ->
            val drops = WaitBackedDropWaits(SqlWaitStore(w.a.database))
            val a = TodoService(SqlTodoStore(w.a.database), drops); val b = TodoService(SqlTodoStore(w.b.database), WaitBackedDropWaits(SqlWaitStore(w.b.database)))
            val session = (w.a.sessions.create(w.member, null, CloudSessionDraft("Stop", w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session.id
            a.create(w.member, session, "The plan", emptyList(), listOf(item("a"), item("b", "a")))
            val verify = TodoStopInput(TodoStopPhase.VERIFY, orphans = listOf("https://github.com/acme/backend/pull/5"), lastMessage = "stuck")
            assertEquals(TodoStopAction.VERIFY, a.stop(w.member, session, TodoStopInput(TodoStopPhase.BEGIN)).action)
            // Two stops at once, from two replicas, each counted once: 1 and 2, never both 1.
            coroutineScope { listOf(a, b).map { s -> async(Dispatchers.IO) { s.stop(w.member, session, verify) } }.awaitAll() }
            val counted = a.get(w.member, session)!!
            assertEquals(2, counted.stall)
            assertEquals(listOf("a", "b", "finish-pr-5"), counted.items.map { it.id }, "the orphan PR is added once, by whichever replica comes first")
            // The limit: of two stops that arrive together, exactly one opens the wait.
            val results = coroutineScope { listOf(a, b).map { s -> async(Dispatchers.IO) { runCatching { s.stop(w.member, session, verify) }.getOrNull() } }.awaitAll() }
            assertEquals(1, results.count { it?.escalated == true }, "one wait is opened, not two")
            assertEquals(1, results.count { it?.action == TodoStopAction.BLOCK })
            val after = a.get(w.member, session)!!
            val stuck = after.items.single { it.status == TodoItemStatus.DROP_REQUESTED }
            assertEquals("a", stuck.id)
            w.a.database.query { assertEquals(1L, Waits.selectAll().where { (Waits.sessionId eq session) and (Waits.status eq "open") }.count()) }
            assertEquals(listOf("blocked"), after.hookEvents.map { it.kind })
            // A wait of the session is open: every stop passes without a question, from either replica.
            assertEquals("wait_open", b.stop(w.member, session, TodoStopInput(TodoStopPhase.BEGIN)).why)
            // The owner answers: the drop is applied, and what the hook recorded about itself survives.
            WaitService(SqlWaitStore(w.a.database), waker(w.a)).answer(w.member, stuck.dropWaitId!!, WaitBackedDropWaits.NO, emptyMap())
            assertEquals(TodoItemStatus.TODO, b.get(w.member, session)!!.item("a")!!.status)
            assertEquals(TodoStopAction.VERIFY, b.stop(w.member, session, TodoStopInput(TodoStopPhase.BEGIN)).action)
            a.hookError(w.member, session, "The stop hook failed (IllegalStateException).")
            assertEquals(listOf("blocked", "hook_error"), b.get(w.member, session)!!.hookEvents.map { it.kind })
            // Done items are checked again at the end: a PR that was reverted reopens its item.
            val ok = { index: Int -> TodoCheckReport(index, true, "MERGED") }
            b.stop(w.member, session, TodoStopInput(TodoStopPhase.VERIFY, items = listOf("a", "b", "finish-pr-5").map { TodoStopItemReport(it, listOf(ok(0))) }))
            assertEquals(TodoStopAction.SETTLE, b.stop(w.member, session, TodoStopInput(TodoStopPhase.VERIFY)).action)
            val reverted = a.stop(w.member, session, TodoStopInput(TodoStopPhase.SETTLE, items = listOf(TodoStopItemReport("a", listOf(TodoCheckReport(0, false, "PR #1 was reverted"))), TodoStopItemReport("b", listOf(ok(0))), TodoStopItemReport("finish-pr-5", listOf(ok(0))))))
            assertEquals(TodoStopAction.BLOCK, reverted.action)
            assertEquals(TodoItemStatus.TODO, a.get(w.member, session)!!.item("a")!!.status)
            b.stop(w.member, session, TodoStopInput(TodoStopPhase.VERIFY, items = listOf(TodoStopItemReport("a", listOf(ok(0))))))
            val settled = b.stop(w.member, session, TodoStopInput(TodoStopPhase.SETTLE, items = listOf("a", "b", "finish-pr-5").map { TodoStopItemReport(it, listOf(ok(0))) }))
            assertEquals(listOf(TodoStopAction.ALLOW, "settled", TodoListStatus.DONE), listOf(settled.action, settled.why, a.get(w.member, session)!!.status))
        }
    }

    @Test @Timeout(240)
    fun theReminderReadsTurnsInBatchesAndJudgesEachTurnEndOnceAcrossReplicas() = runBlocking {
        AutomationWorld.run(93) { w ->
            val a = SqlTodoStore(w.a.database); val b = SqlTodoStore(w.b.database)
            val svcA = TodoService(a, WaitBackedDropWaits(SqlWaitStore(w.a.database))); val svcB = TodoService(b, WaitBackedDropWaits(SqlWaitStore(w.b.database)))
            suspend fun session(name: String) = (w.a.sessions.create(w.member, null, CloudSessionDraft(name, w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session.id
            val running = session("Running"); val paused = session("Paused"); val noTurn = session("No turn")
            for (id in listOf(running, paused, noTurn)) svcA.create(w.member, id, "Plan", emptyList(), listOf(item("a"), item("b", "a")))
            suspend fun turn(id: String, seq: Long, at: Long, phase: String) = w.a.database.query {
                CloudSessionEvents.insert { it[sessionId] = id; it[CloudSessionEvents.seq] = seq; it[teamId] = w.teamId; it[epoch] = 0; it[runnerSeq] = null; it[type] = "turn"; it[CloudSessionEvents.at] = at; it[data] = """{"phase":"$phase"}""" }
            }
            w.a.database.query {
                CloudSessions.update({ CloudSessions.id inList listOf(running, noTurn) }) { it[state] = "active"; it[desiredState] = "running" }
                CloudSessions.update({ CloudSessions.id eq paused }) { it[state] = "paused"; it[desiredState] = "paused" }
            }
            // `running`: a turn started at 1000 and ended completed at 2000; `paused`: the same but not running; `noTurn`: no turn event at all.
            for (id in listOf(running, paused)) { turn(id, 1, 1_000, "started"); turn(id, 2, 2_000, "completed") }
            val now = a.now()
            val candidates = b.candidates(now - 3_600_000, 50)
            assertEquals(setOf(running, noTurn), candidates.map { it.sessionId }.toSet(), "only active sessions that are meant to run")
            assertEquals(w.member.member.id, candidates.first().ownerId)
            assertEquals(mapOf(running to TodoTurnEnd(2, 1_000), paused to TodoTurnEnd(2, 1_000)), b.turnEnds(listOf(running, paused, noTurn)))
            // A turn that is running (latest event `started`) or was interrupted is not an end.
            turn(running, 3, 3_000, "started")
            assertEquals(setOf(paused), a.turnEnds(listOf(running, paused, noTurn)).keys)
            turn(running, 4, 4_000, "interrupted")
            assertTrue(a.turnEnds(listOf(running)).isEmpty())
            turn(running, 5, 5_000, "started"); turn(running, 6, 6_000, "completed")
            assertEquals(TodoTurnEnd(6, 5_000), a.turnEnds(listOf(running)).getValue(running))

            // Two replicas judge the same turn end at once: one reminds, the other finds it marked.
            val sent = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val owner = Principal(w.member.member, "waits-resolver", w.teamId)
            val outcomes = coroutineScope {
                listOf(svcA, svcB).map { s -> async(Dispatchers.IO) { s.remind(w.teamId, owner, running, TodoTurnEnd(6, 5_000), true) { text, key -> sent += key; assertTrue(text.startsWith("[TeamCode todo] 0 of 2 items done.")) } } }.awaitAll()
            }
            assertEquals(1, outcomes.count { it == TodoReminderOutcome.REMINDED }, outcomes.toString())
            assertEquals(1, sent.size)
            val after = svcA.get(w.member, running)!!
            assertEquals(1, after.stall); assertEquals(6L, after.reminderSeq)
            // The next turn end is a new judgement; a stop the hook saw inside it is not reminded.
            svcB.stop(w.member, running, TodoStopInput(TodoStopPhase.BEGIN))
            val hookAt = svcA.get(w.member, running)!!.lastHookAt!!
            assertEquals(TodoReminderOutcome.SKIPPED, svcA.remind(w.teamId, owner, running, TodoTurnEnd(7, hookAt - 1), true) { _, _ -> error("not sent") })
            assertEquals(7L, svcB.get(w.member, running)!!.reminderSeq)
            // Stalls 2 and 3: the third turn end without progress asks the owner (a wait the server opens as itself), and no message goes out.
            assertEquals(TodoReminderOutcome.REMINDED, svcA.remind(w.teamId, owner, running, TodoTurnEnd(8, hookAt + 10), true) { _, _ -> sent += "second" })
            assertEquals(TodoReminderOutcome.ESCALATED, svcB.remind(w.teamId, owner, running, TodoTurnEnd(9, hookAt + 10), true) { _, _ -> error("not sent") })
            // The wait now open holds the reminder back, from either replica.
            assertEquals(TodoReminderOutcome.SKIPPED, svcA.remind(w.teamId, owner, running, TodoTurnEnd(10, hookAt + 10), true) { _, _ -> error("not sent") })
            w.a.database.query { assertEquals(1L, Waits.selectAll().where { (Waits.sessionId eq running) and (Waits.status eq "open") }.count()) }
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

    @Test @Timeout(240)
    fun theFeedHearsOfAChangeAScreenShowsAndNotOfTheRemindersBookkeeping() = runBlocking {
        AutomationWorld.run(98) { w ->
            val store = SqlTodoStore(w.a.database)
            val todos = TodoService(store, WaitBackedDropWaits(SqlWaitStore(w.a.database)))
            val session = (w.a.sessions.create(w.member, null, CloudSessionDraft("Plan", w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session.id
            suspend fun events() = w.a.database.query {
                TeamEvents.selectAll().where { (TeamEvents.type eq "cloud.todo") and (TeamEvents.subjectId eq session) }.orderBy(TeamEvents.seq to SortOrder.ASC).map { it[TeamEvents.data] }
            }
            assertEquals(emptyList(), events())

            todos.create(w.member, session, "The plan", emptyList(), listOf(item("a"), item("b", "a")))
            assertEquals(1, events().size, "a list created is announced")
            assertContains(events().single(), "\"done\":0"); assertContains(events().single(), "\"total\":2"); assertContains(events().single(), "\"kind\":\"cloud\"")

            // The reminder's bookkeeping is not shown by any screen: a quiet turn publishes nothing.
            store.mutate(w.member.teamId, session) { it.copy(lastHookAt = 5L, reminderSeq = 3L) to Unit }
            assertEquals(1, events().size)

            todos.start(w.member, session, "a", false, null)
            assertEquals(2, events().size, "an item started is announced")
            todos.add(w.member, session, item("extra"), "A migration is needed")
            assertContains(events().last(), "\"total\":3")
            // The owner hears it; the event names the session only by its counter, never an item or a proof.
            val row = w.a.database.query { TeamEvents.selectAll().where { (TeamEvents.type eq "cloud.todo") and (TeamEvents.subjectId eq session) }.first() }
            assertEquals(w.member.member.id, row[TeamEvents.ownerId])
            assertFalse("Item a" in row[TeamEvents.data])
        }
    }
}
