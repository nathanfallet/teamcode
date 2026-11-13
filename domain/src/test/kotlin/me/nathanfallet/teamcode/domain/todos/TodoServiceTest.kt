package me.nathanfallet.teamcode.domain.todos

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** The service applies the rules atomically and verifies by itself what it can see: an artifact, a wait of the session. */
class TodoServiceTest {
    private class Store : TodoStore {
        var list: TodoList? = null
        var artifacts = mutableMapOf<String, Int>()
        var waits = mutableMapOf<String, WaitState>()
        var openWait = false
        val hookOff = mutableSetOf<String>()
        override suspend fun stopHookEnabled(teamId: String, memberId: String) = memberId !in hookOff
        override suspend fun stopHookDisabled(teamId: String) = hookOff.toSet()
        override suspend fun setStopHookEnabled(teamId: String, memberId: String, enabled: Boolean): Boolean {
            if (memberId != "ada" && memberId != "root") return false
            if (enabled) hookOff.remove(memberId) else hookOff.add(memberId); return true
        }
        override suspend fun now() = 1_000L
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun executionSession(tokenId: String): String? = null
        override suspend fun sessionOwner(teamId: String, sessionId: String) = "ada".takeIf { sessionId == "s1" }
        override suspend fun find(teamId: String, sessionId: String) = list
        override suspend fun create(list: TodoList) { this.list = list }
        override suspend fun <T> mutate(teamId: String, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T { val (next, value) = change(list!!); list = next; return value }
        override suspend fun reset(teamId: String, sessionId: String, actor: Principal, check: (TodoList) -> Unit): TodoList {
            val current = list?.takeIf { it.sessionId == sessionId } ?: throw TeamFailure("todo_not_found", 404, "none")
            check(current); list = null; return current
        }
        override suspend fun artifactVersion(teamId: String, artifactId: String) = artifacts[artifactId]
        override suspend fun waitState(teamId: String, sessionId: String, waitId: String) = waits[waitId]
        override suspend fun hasOpenWait(teamId: String, sessionId: String) = openWait
        override suspend fun dropOutcomes(teamId: String, waitIds: Collection<String>) = emptyMap<String, DropOutcome>()
    }
    private val ada = Principal(Member("ada", "ada", Role.MEMBER, false, false), "token", "team", null)
    private val long = "Do this item completely: what, where, why and what is out of scope, enough for a new agent to do it alone."
    private fun item(id: String, vararg checks: TodoCheck) = TodoItemDefinition(id, "Item $id", long, TodoDoneWhen("done", checks.toList()))

    @Test fun anArtifactItemIsDoneOnlyWhenTheServerSeesTheArtifact() = runBlocking {
        val store = Store(); val service = TodoService(store)
        service.create(ada, "s1", "Plan", emptyList(), listOf(item("findings", TodoCheck.ArtifactPublished(minVersion = 2))))
        // The caller's report on a check the server verifies is ignored, even a lie.
        val lie = listOf(TodoCheckReport(0, true, "I published it"))
        assertEquals("not_done", assertFailsWith<TeamFailure> { runBlocking { service.complete(ada, "s1", "findings", lie) } }.code)
        assertTrue(store.list!!.item("findings")!!.proof!!.text.contains("No artifact is linked"))
        service.link(ada, "s1", "findings", TodoLink("artifact", "art-1"), false)
        store.artifacts["art-1"] = 1
        assertEquals("not_done", assertFailsWith<TeamFailure> { runBlocking { service.complete(ada, "s1", "findings", lie) } }.code)
        assertTrue(store.list!!.item("findings")!!.proof!!.text.contains("version 2 is needed"))
        store.artifacts["art-1"] = 2
        assertEquals(TodoItemStatus.DONE, service.complete(ada, "s1", "findings", emptyList()).item("findings")!!.status)
    }

    @Test fun aWaitItemFollowsTheAnswerOfItsLinkedWait() = runBlocking {
        val store = Store(); val service = TodoService(store)
        service.create(ada, "s1", "Plan", emptyList(), listOf(item("approve", TodoCheck.WaitAnswered("approve"))))
        service.link(ada, "s1", "approve", TodoLink("wait", "w1"), false)
        store.waits["w1"] = WaitState("open", null)
        assertEquals("not_done", assertFailsWith<TeamFailure> { runBlocking { service.complete(ada, "s1", "approve", emptyList()) } }.code)
        store.waits["w1"] = WaitState("resolved", "request_changes")
        assertEquals("not_done", assertFailsWith<TeamFailure> { runBlocking { service.complete(ada, "s1", "approve", emptyList()) } }.code)
        assertTrue(store.list!!.item("approve")!!.proof!!.text.contains("request_changes"))
        store.waits["w1"] = WaitState("resolved", "approve")
        assertEquals(TodoItemStatus.DONE, service.complete(ada, "s1", "approve", emptyList()).item("approve")!!.status)
    }

    @Test fun reportsAreBoundedAndMatchedByPosition() = runBlocking {
        val service = TodoService(Store())
        service.create(ada, "s1", "Plan", emptyList(), listOf(item("a", TodoCheck.PrMerged("acme/x"), TodoCheck.CiGreen("acme/x", of = "a"))))
        fun code(reports: List<TodoCheckReport>) = assertFailsWith<TeamFailure> { runBlocking { service.complete(ada, "s1", "a", reports) } }.code
        assertEquals("invalid_proof", code(listOf(TodoCheckReport(2, true, "x"))))
        assertEquals("invalid_proof", code(listOf(TodoCheckReport(0, true, "x"), TodoCheckReport(0, true, "y"))))
        assertEquals("invalid_proof", code(listOf(TodoCheckReport(0, true, "x", listOf("http://insecure.example")))))
        assertEquals("not_done", code(listOf(TodoCheckReport(0, true, "merged"))), "the second check was not reported")
        assertEquals(TodoItemStatus.DONE, service.complete(ada, "s1", "a", listOf(TodoCheckReport(0, true, "merged"), TodoCheckReport(1, true, "green"))).item("a")!!.status)
    }

    private class Drops : TodoDropWaits {
        val blocked = mutableListOf<Triple<String, String, Boolean>>()
        override suspend fun open(actor: Principal, sessionId: String, item: TodoItem, reason: String) = "drop-1"
        override suspend fun cancel(actor: Principal, waitId: String) = Unit
        override suspend fun openBlocked(actor: Principal, sessionId: String, title: String, body: String, dropItem: Boolean): String { blocked += Triple(title, body, dropItem); return "blocked-1" }
    }
    private fun begin(background: Boolean = false) = TodoStopInput(TodoStopPhase.BEGIN, background = background)
    private fun verify(vararg items: TodoStopItemReport, orphans: List<String> = emptyList()) = TodoStopInput(TodoStopPhase.VERIFY, items = items.toList(), orphans = orphans, lastMessage = "I am stuck")
    private val pr = TodoCheck.PrMerged("acme/x")

    private val root = Principal(Member("root", "root", Role.ADMIN, false, false), "token", "team", null)

    @Test fun aMemberWhoseStopHookIsOffIsNeverHeldBack() = runBlocking {
        val store = Store(); val service = TodoService(store)
        service.create(ada, "s1", "Plan", emptyList(), listOf(item("a", TodoCheck.PrMerged("acme/x"))))
        assertEquals(TodoStopAction.VERIFY, service.stop(ada, "s1", begin()).action)
        service.setStopHook(root, "ada", false)
        val before = store.list
        val off = service.stop(ada, "s1", begin())
        assertEquals(TodoStopAction.ALLOW, off.action); assertEquals("hook_disabled", off.why)
        assertEquals(before, store.list, "nothing is counted or written while the hook is off")
        service.setStopHook(root, "ada", true)
        assertEquals(TodoStopAction.VERIFY, service.stop(ada, "s1", begin()).action)
    }

    @Test fun onlyAnAdministratorTurnsTheStopHookOff() = runBlocking {
        val service = TodoService(Store())
        assertEquals("forbidden", assertFailsWith<TeamFailure> { runBlocking { service.setStopHook(ada, "ada", false) } }.code)
        assertEquals("not_found", assertFailsWith<TeamFailure> { runBlocking { service.setStopHook(root, "ghost", false) } }.code)
    }

    @Test fun aStopWithNothingToHoldBackPassesWithoutAQuestion() = runBlocking {
        val store = Store(); val service = TodoService(store, Drops())
        assertEquals("no_list", service.stop(ada, "s1", begin()).why)
        service.create(ada, "s1", "Plan", emptyList(), listOf(item("a", pr)))
        assertEquals(TodoStopAction.VERIFY, service.stop(ada, "s1", begin()).action)
        assertEquals("background_task", service.stop(ada, "s1", begin(background = true)).why)
        store.openWait = true
        assertEquals("wait_open", service.stop(ada, "s1", begin()).why, "any open wait of the session lets the stop pass, and nothing is verified")
        store.openWait = false
        store.list = store.list!!.copy(status = TodoListStatus.DONE)
        assertEquals("finished", service.stop(ada, "s1", begin()).why)
    }

    @Test fun anItemWhoseChecksPassIsTickedAndAllClosedMeansSettle() = runBlocking {
        val store = Store(); val service = TodoService(store, Drops())
        service.create(ada, "s1", "Plan", emptyList(), listOf(item("a", pr), item("b", pr)))
        val first = service.stop(ada, "s1", verify(TodoStopItemReport("a", listOf(TodoCheckReport(0, true, "PR #1 MERGED")))))
        assertEquals(TodoStopAction.BLOCK, first.action)
        assertEquals(TodoItemStatus.DONE, store.list!!.item("a")!!.status)
        assertTrue(first.reason!!.startsWith("[TeamCode todo] 1 of 2 items done."), first.reason)
        assertTrue(first.reason!!.contains("Next item: b"))
        val second = service.stop(ada, "s1", verify(TodoStopItemReport("b", listOf(TodoCheckReport(0, true, "PR #2 MERGED")))))
        assertEquals(TodoStopAction.SETTLE, second.action)
        // A check that regressed reopens the item; the list is done only when every condition still holds.
        val reopened = service.stop(ada, "s1", TodoStopInput(TodoStopPhase.SETTLE, items = listOf(TodoStopItemReport("a", listOf(TodoCheckReport(0, true, "ok"))), TodoStopItemReport("b", listOf(TodoCheckReport(0, false, "PR #2 was reverted"))))))
        assertEquals(TodoStopAction.BLOCK, reopened.action)
        assertEquals(TodoItemStatus.TODO, store.list!!.item("b")!!.status)
        assertTrue(reopened.reason!!.contains("PR #2 was reverted"))
        service.stop(ada, "s1", verify(TodoStopItemReport("b", listOf(TodoCheckReport(0, true, "PR #2 MERGED again")))))
        val done = service.stop(ada, "s1", TodoStopInput(TodoStopPhase.SETTLE, items = listOf(TodoStopItemReport("a", listOf(TodoCheckReport(0, true, "ok"))), TodoStopItemReport("b", listOf(TodoCheckReport(0, true, "ok"))))))
        assertEquals(listOf(TodoStopAction.ALLOW, "settled", TodoListStatus.DONE), listOf(done.action, done.why, store.list!!.status))
    }

    @Test fun aClosedTodoIsResetAndANewOneStartsFromNothing() = runBlocking {
        val store = Store(); val service = TodoService(store, Drops())
        assertEquals("todo_not_found", assertFailsWith<TeamFailure> { runBlocking { service.reset(ada, "s1") } }.code, "Nothing to reset")
        service.create(ada, "s1", "First task", emptyList(), listOf(item("a", pr)))
        // Active: no way out by resetting, whatever the progress.
        assertEquals("todo_active", assertFailsWith<TeamFailure> { runBlocking { service.reset(ada, "s1") } }.code)
        assertEquals(TodoItemStatus.TODO, store.list!!.item("a")!!.status, "The refusal changed nothing")
        service.stop(ada, "s1", verify(TodoStopItemReport("a", listOf(TodoCheckReport(0, true, "PR #1 MERGED")))))
        service.stop(ada, "s1", TodoStopInput(TodoStopPhase.SETTLE, items = listOf(TodoStopItemReport("a", listOf(TodoCheckReport(0, true, "ok"))))))
        assertEquals(TodoListStatus.DONE, store.list!!.status)
        // Closed: a new item is refused, which is the dead end the reset opens.
        assertEquals("todo_closed", assertFailsWith<TeamFailure> { runBlocking { service.add(ada, "s1", item("b", pr), "More work") } }.code)
        service.reset(ada, "s1")
        assertNull(store.list, "As if the session never had a todo")
        assertNull(service.get(ada, "s1"))
        val second = service.create(ada, "s1", "Second task", emptyList(), listOf(item("b", pr)))
        assertEquals(listOf("b") to TodoListStatus.ACTIVE, second.items.map { it.id } to second.status, "A new todo, with its own items and reference")
        assertEquals("Second task", store.list!!.reference)
    }

    @Test fun anUnknownOrFailedCheckNeverTicksAnItem() = runBlocking {
        val store = Store(); val service = TodoService(store, Drops())
        service.create(ada, "s1", "Plan", emptyList(), listOf(item("a", pr)))
        service.stop(ada, "s1", verify(TodoStopItemReport("a", listOf(TodoCheckReport(0, null, "unknown: gh did not answer")))))
        assertEquals(TodoItemStatus.TODO, store.list!!.item("a")!!.status)
        assertNull(store.list!!.item("a")!!.proof!!.passed)
    }

    @Test fun theCounterOnlyCountsStopsWithoutProgressAndTheThirdOpensAWait() = runBlocking {
        val store = Store(); val drops = Drops(); val service = TodoService(store, drops)
        service.create(ada, "s1", "Plan", emptyList(), listOf(item("a", pr), item("b", pr)))
        // Stop 1 and 2: no progress. Stop 2 sees the same counters as stop 1.
        assertEquals(1, service.stop(ada, "s1", verify()).let { store.list!!.stall })
        assertEquals(2, service.stop(ada, "s1", verify()).let { store.list!!.stall })
        // A note is progress: the counter starts again, and the agent is not stopped by the limit however many times it advances.
        service.note(ada, "s1", "a", "tried the merge queue")
        assertEquals(0, service.stop(ada, "s1", verify()).let { store.list!!.stall })
        assertEquals(TodoStopAction.BLOCK, service.stop(ada, "s1", verify()).action)
        assertEquals(1, store.list!!.stall)
        assertEquals(TodoStopAction.BLOCK, service.stop(ada, "s1", verify()).action)
        assertEquals(2, store.list!!.stall)
        val third = service.stop(ada, "s1", verify())
        assertEquals(listOf(TodoStopAction.ALLOW, "escalated", true), listOf(third.action, third.why, third.escalated))
        assertEquals(1, drops.blocked.size)
        assertTrue(drops.blocked.single().second.contains("I am stuck"))
        assertEquals(0, store.list!!.stall, "the counter starts again once the owner was asked")
        assertEquals(TodoItemStatus.DROP_REQUESTED, store.list!!.item("a")!!.status)
        assertEquals("blocked-1", store.list!!.item("a")!!.dropWaitId)
        assertEquals("blocked", store.list!!.hookEvents.last().kind)
    }

    @Test fun aPullRequestOfTheSessionThatNoItemCoversBecomesAnItem() = runBlocking {
        val store = Store(); val service = TodoService(store, Drops())
        service.create(ada, "s1", "Plan", emptyList(), listOf(item("a", pr)))
        service.link(ada, "s1", "a", TodoLink("pr", "https://github.com/acme/x/pull/1", 1), false)
        service.stop(ada, "s1", verify(orphans = listOf("https://github.com/acme/x/pull/1", "https://github.com/acme/x/pull/7", "https://example.com/acme/x/pull/9")))
        assertEquals(listOf("a", "finish-pr-7"), store.list!!.items.map { it.id })
        val added = store.list!!.item("finish-pr-7")!!
        assertEquals(TodoOrigin.DISCOVERED, added.origin)
        assertEquals("https://github.com/acme/x/pull/7", added.links.single().ref)
        assertEquals(listOf<TodoCheck>(TodoCheck.PrMerged("acme/x")), added.doneWhen.checks)
        service.stop(ada, "s1", verify(orphans = listOf("https://github.com/acme/x/pull/7")))
        assertEquals(2, store.list!!.items.size, "the same PR is not added twice")
    }

    @Test fun aHookErrorIsKeptOnTheListAndOnlyTheNewestAreKept() = runBlocking {
        val store = Store(); val service = TodoService(store, Drops())
        service.create(ada, "s1", "Plan", emptyList(), listOf(item("a", pr)))
        repeat(25) { service.hookError(ada, "s1", "The stop hook failed ($it).") }
        assertEquals(TodoRules.MAX_HOOK_EVENTS, store.list!!.hookEvents.size)
        assertEquals("The stop hook failed (24).", store.list!!.hookEvents.last().text)
        assertTrue(store.list!!.hookEvents.all { it.kind == "hook_error" })
    }

    @Test fun theWakeBlockIsTheListWithoutTheInstructionAndAppliesTheOwnersDropAnswer() = runBlocking {
        val store = Store(); val service = TodoService(store, Drops())
        assertNull(service.wakeBlock("team", "s1"), "no list, no block")
        service.create(ada, "s1", "Plan: https://example.com/plan (v2)", emptyList(), listOf(item("a", pr), item("b", pr)))
        val block = service.wakeBlock("team", "s1")!!
        assertTrue(block.startsWith("[TeamCode todo] Your todo: 0 of 2 items done.\nPlan reference:\n  Plan: https://example.com/plan (v2)\nNext item: a"), block)
        assertTrue(block.contains("  → a ") && block.contains("  · b "))
        assertFalse(block.contains("Do the next item now") || block.contains("the turn goes on"), "a wake block gives no order to go on")
        assertEquals(block, service.context(ada, "s1"))
        store.list = store.list!!.copy(status = TodoListStatus.DONE)
        assertNull(service.wakeBlock("team", "s1"), "a finished list says nothing more")
    }

    @Test fun theItemInProgressIsTheOneToFinish() {
        val list = TodoRules.create("0123456789abcdef", "s1", "team", "Plan", emptyList(), listOf(item("a", pr), item("b", pr)), 0)
        val started = TodoRules.start(list, "a", now = 1)
        val text = TodoMessages.context(started)
        assertTrue(text.contains("Next item: a — Item a") && text.contains("  → a ") && text.contains("  · b "), text)
    }

    @Test fun aListWithNothingOpenSaysSo() {
        val list = TodoRules.create("0123456789abcdef", "s1", "team", "Plan", emptyList(), listOf(item("a", pr)), 0)
        val done = list.copy(items = list.items.map { it.copy(status = TodoItemStatus.DONE) })
        assertTrue(TodoMessages.context(done).contains("No item remains open."))
        assertFalse(TodoMessages.context(done).contains("Call todo_show"))
    }

    @Test fun theResumeMessageGivesTheReferenceTheWholeListAndTheNextItemInFull() {
        val list = TodoRules.create("0123456789abcdef", "s1", "team", "Plan validated: https://example.com/a (v3). Ship Vestiaire.", emptyList(),
            listOf(item("one", pr), item("two", TodoCheck.ReleaseContains("acme/x", of = "one")), item("three", TodoCheck.Attested("watch 24 h"))), 0)
        val done = list.copy(items = list.items.map { if (it.id == "one") it.copy(status = TodoItemStatus.DONE, links = listOf(TodoLink("pr", "https://github.com/acme/x/pull/812", 812))) else it })
        val text = TodoMessages.resume(done.copy(items = done.items.map { if (it.id == "two") it.copy(proof = TodoProof(14 * 3_600_000L + 2 * 60_000L, false, "no published release contains 4e1c9a2")) else it }))
        assertTrue(text.startsWith("[TeamCode todo] 1 of 3 items done. Items remain and no wait is open, so the turn goes on.\nPlan reference:\n  Plan validated"), text)
        assertTrue(text.contains("Next item: two — Item two\n  $long"))
        assertTrue(text.contains("Last check (14:02): no published release contains 4e1c9a2"))
        assertTrue(text.contains("  ✓ one") && text.contains("PR #812 merged") && text.contains("  → two") && text.contains("  · three") && text.contains("(attested)"))
        assertTrue(text.endsWith("todo_drop. Each of these ends the turn properly."))
        assertTrue(text.length <= TodoMessages.MAX_CHARS)
    }
}
