package me.nathanfallet.teamcode.domain.todos

import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class TodoRulesTest {
    private val repo = "acme/backend"
    private val long = "Do this item completely: what, where, why and what is out of scope, enough for a new agent to do it alone."
    private fun item(id: String, vararg deps: String, checks: List<TodoCheck> = listOf(TodoCheck.PrMerged(repo))) =
        TodoItemDefinition(id, "Item $id", long, TodoDoneWhen("It is merged", checks), deps.toList())
    private fun list(vararg items: TodoItemDefinition) = TodoRules.create("7f3a9c10aa", "s1", "team", "The plan", emptyList(), items.toList(), 100L)
    private fun code(block: () -> Unit) = assertFailsWith<TeamFailure> { block() }.code
    private fun pass(list: TodoList, id: String) = list.item(id)!!.doneWhen.checks.map { TodoCheckResult(it, true, "ok") }
    private fun done(list: TodoList, id: String) = (TodoRules.complete(list, id, pass(list, id), 200L) as TodoCompletion.Done).list

    private val plan get() = list(
        item("a"), item("b", "a"),
        item("c", "b", checks = listOf(TodoCheck.ReleaseContains(repo, of = "b"), TodoCheck.Command("test \${release:c.tag} = x"))),
    )

    @Test fun aListIsActiveAtOnceWithItsItemsInOrder() {
        val created = plan
        assertEquals(TodoListStatus.ACTIVE, created.status)
        assertEquals(listOf("a", "b", "c"), created.items.map { it.id })
        assertTrue(created.items.all { it.status == TodoItemStatus.TODO && it.origin == TodoOrigin.PLAN })
    }

    @Test fun creationRefusesWhatCannotBeChecked() {
        assertEquals("check_required", code { list(item("a", checks = emptyList())) })
        assertEquals("description_too_short", code { list(item("a").copy(description = "short")) })
        assertEquals("duplicate_item", code { list(item("a"), item("a")) })
        assertEquals("unknown_dependency", code { list(item("a", "ghost")) })
        assertEquals("unknown_dependency", code { list(item("a", checks = listOf(TodoCheck.ReleaseContains(repo, of = "ghost")))) })
        assertEquals("dependency_cycle", code { list(item("a", "b"), item("b", "a")) })
        assertEquals("dependency_cycle", code { list(item("a", "a")) })
        assertEquals("invalid_todo", code { list(item("Bad Id")) })
        assertEquals("invalid_todo", code { list() })
        assertEquals("too_many_items", code { list(*(1..51).map { item("i$it") }.toTypedArray()) })
        assertEquals("invalid_check", code { list(item("a", checks = listOf(TodoCheck.PrMerged("nope")))) })
        assertEquals("invalid_check", code { list(item("a", checks = listOf(TodoCheck.ReleaseContains(repo)))) })
        assertEquals("invalid_check", code { list(item("a", checks = listOf(TodoCheck.Command("true", timeoutSeconds = 500)))) })
    }

    @Test fun aGuessedPrNumberOrVersionIsRefused() {
        assertEquals("guessed_number", code { list(item("a", checks = listOf(TodoCheck.Command("gh api repos/acme/backend/pulls/1310")))) })
        assertEquals("guessed_number", code { list(item("a", checks = listOf(TodoCheck.Command("gh release view --repo x/y releases/tag/v1.2.3")))) })
        list(item("a", checks = listOf(TodoCheck.Command("gh api repos/acme/backend/pulls/\${pr:a.number}"))))
    }

    @Test fun oneListPerSession() {
        TodoRules.requireNoList(null)
        assertEquals("todo_exists", code { TodoRules.requireNoList(plan) })
    }

    @Test fun nextIsTheFirstTodoWhoseDependenciesAreDone() {
        var l = plan
        assertEquals("a", TodoRules.next(l)?.id)
        l = done(l, "a")
        assertEquals("b", TodoRules.next(l)?.id)
        l = TodoRules.start(l, "b")
        assertNull(TodoRules.next(l), "b is doing and c waits for it")
        l = done(l, "b")
        assertEquals("c", TodoRules.next(l)?.id)
    }

    @Test fun startRefusesOpenDependenciesAndKeepsOneItemInProgress() {
        val l = list(item("a"), item("b"), item("c", "a"))
        assertEquals("dependency_open", code { TodoRules.start(l, "c") })
        val started = TodoRules.start(TodoRules.start(l, "a"), "b")
        assertEquals(TodoItemStatus.TODO, started.item("a")!!.status)
        assertEquals(TodoItemStatus.DOING, started.item("b")!!.status)
    }

    @Test fun aBackgroundAgentKeepsSeveralItemsInProgress() {
        val l = list(item("a"), item("b"))
        val started = TodoRules.start(TodoRules.start(l, "a", background = true, agent = "scout", now = 5L), "b", background = true, agent = "other", now = 6L)
        assertEquals(listOf(TodoItemStatus.DOING, TodoItemStatus.DOING), started.items.map { it.status })
        assertTrue(started.item("a")!!.notes.single().text.contains("scout"))
    }

    @Test fun anItemIsDoneOnlyWhenEveryCheckPasses() {
        val l = plan
        val checks = l.item("c")!!.doneWhen.checks
        val unknown = TodoRules.complete(l, "c", listOf(TodoCheckResult(checks[0], true, "release v2"), TodoCheckResult(checks[1], null, "waiting for the release")), 300L)
        val refused = assertIs<TodoCompletion.Refused>(unknown)
        assertEquals("not_done", refused.failure.code)
        assertEquals(TodoItemStatus.TODO, refused.list.item("c")!!.status)
        assertNull(refused.list.item("c")!!.proof!!.passed, "unknown is never done")
        val failed = assertIs<TodoCompletion.Refused>(TodoRules.complete(l, "c", checks.map { TodoCheckResult(it, false, "OPEN, 2 checks failing") }, 300L))
        assertEquals(false, failed.list.item("c")!!.proof!!.passed)
        val missing = assertIs<TodoCompletion.Refused>(TodoRules.complete(l, "c", listOf(TodoCheckResult(checks[0], true, "ok")), 300L))
        assertTrue(missing.failure.message!!.contains("command"))
        val ok = assertIs<TodoCompletion.Done>(TodoRules.complete(l, "c", checks.map { TodoCheckResult(it, true, "merged", listOf("https://x/1")) }, 300L))
        val finished = ok.list.item("c")!!
        assertEquals(TodoItemStatus.DONE, finished.status)
        assertEquals(true, finished.proof!!.passed)
        assertEquals(listOf("https://x/1"), finished.proof!!.urls)
    }

    @Test fun anItemAddedOnTheWayIsPlacedAfterTheOneInProgressOrItsDependencies() {
        var l = TodoRules.start(plan, "a")
        l = TodoRules.add(l, item("extra"), "The plan needs a migration")
        assertEquals(listOf("a", "extra", "b", "c"), l.items.map { it.id })
        assertEquals(TodoOrigin.DISCOVERED, l.item("extra")!!.origin)
        assertEquals("The plan needs a migration", l.item("extra")!!.originReason)
        l = TodoRules.add(l, item("late", "c"), "After the release")
        assertEquals("late", l.items.last().id)
        assertEquals("duplicate_item", code { TodoRules.add(l, item("late"), "again") })
        assertEquals("unknown_dependency", code { TodoRules.add(l, item("x", "ghost"), "why") })
        assertEquals("invalid_todo", code { TodoRules.add(l, item("y"), " ") })
        val appended = TodoRules.add(plan, item("tail"), "No item in progress")
        assertEquals("tail", appended.items.last().id)
    }

    @Test fun conditionsAreFrozenAndNotesOnlyAppend() {
        var l = TodoRules.note(plan, "a", "Tried the first approach", 150L)
        l = TodoRules.note(l, "a", "Second approach", 160L)
        assertEquals(listOf("Tried the first approach", "Second approach"), l.item("a")!!.notes.map { it.text })
        assertEquals(plan.item("a")!!.doneWhen, l.item("a")!!.doneWhen)
        assertEquals("unknown_item", code { TodoRules.note(l, "ghost", "x", 1L) })
    }

    @Test fun anItemIsDroppedOnlyOnTheOwnersYes() {
        var l = TodoRules.requestDrop(plan, "b", "No longer makes sense")
        assertEquals(TodoItemStatus.DROP_REQUESTED, l.item("b")!!.status)
        assertEquals("drop_pending", code { TodoRules.requestDrop(l, "b", "again") })
        assertEquals("drop_pending", code { TodoRules.start(l, "b") })
        val refused = TodoRules.resolveDrop(l, "b", approved = false)
        assertEquals(TodoItemStatus.TODO, refused.item("b")!!.status)
        l = TodoRules.resolveDrop(l, "b", approved = true)
        assertEquals(TodoItemStatus.DROPPED, l.item("b")!!.status)
        assertEquals(3, l.items.size, "nothing is deleted")
        assertEquals("no_drop_request", code { TodoRules.resolveDrop(l, "a", true) })
        assertEquals("c", TodoRules.next(done(l, "a"))?.id, "a dropped dependency no longer blocks")
    }

    @Test fun aDoneItemCannotBeDropped() {
        val l = done(list(item("a")), "a")
        assertEquals("item_closed", code { TodoRules.requestDrop(l, "a", "why") })
    }

    @Test fun anAttestedItemStaysDoneAtTheFinalVerification() {
        val attested = listOf(TodoCheck.Attested("Published the summary"))
        var l = list(item("a", checks = attested), item("b"))
        l = done(done(l, "a"), "b")
        // The hook has no proof to give for an attested check at this phase and reports it as not passed.
        val noProof = TodoRules.settle(l, mapOf("a" to listOf(TodoCheckResult(attested[0], false, "This check is attested: give proof.text")), "b" to pass(l, "b")), 400L)
        assertEquals(TodoListStatus.DONE, noProof.status)
        assertEquals(TodoItemStatus.DONE, noProof.item("a")!!.status)
        // Another check of the same item still reopens it.
        val mixed = list(item("c", checks = attested + TodoCheck.PrMerged(repo)))
        val closed = done(mixed, "c")
        val reverted = TodoRules.settle(closed, mapOf("c" to listOf(TodoCheckResult(attested[0], false, "no proof"), TodoCheckResult(TodoCheck.PrMerged(repo), false, "PR reverted"))), 400L)
        assertEquals(TodoItemStatus.TODO, reverted.item("c")!!.status)
        assertEquals("PR reverted", reverted.item("c")!!.proof!!.text)
    }

    @Test fun theListIsDoneOnlyAfterTheFinalVerification() {
        var l = list(item("a"), item("b"))
        l = done(done(l, "a"), "b")
        assertTrue(l.allClosed)
        val regress = TodoRules.settle(l, mapOf("a" to pass(l, "a").map { it.copy(passed = false, observed = "PR reverted") }, "b" to pass(l, "b")), 400L)
        assertEquals(TodoListStatus.ACTIVE, regress.status)
        assertEquals(TodoItemStatus.TODO, regress.item("a")!!.status, "a reverted PR reopens its item")
        assertEquals(TodoItemStatus.DONE, regress.item("b")!!.status)
        val unknown = TodoRules.settle(l, mapOf("a" to pass(l, "a").map { it.copy(passed = null) }), 400L)
        assertEquals(TodoListStatus.DONE, unknown.status, "only a check that fails reopens, an unanswered one does not")
        assertEquals(TodoListStatus.DONE, TodoRules.settle(l, emptyMap(), 400L).status)
        val open = list(item("a"))
        assertEquals(TodoListStatus.ACTIVE, TodoRules.settle(open, emptyMap(), 1L).status)
    }

    @Test fun aClosedListRefusesChanges() {
        val finished = TodoRules.settle(done(list(item("a")), "a"), emptyMap(), 1L)
        assertEquals("todo_closed", code { TodoRules.add(finished, item("b"), "why") })
        assertEquals("todo_closed", code { TodoRules.start(finished, "a") })
    }

    @Test fun theStallCounterCountsStopsWithoutProgressAndAsksAtThree() {
        var l = plan
        var stop = TodoRules.stop(l)
        assertFalse(stop.progressed, "the first blocked stop has nothing to compare with")
        assertEquals(1, stop.list.stall)
        stop = TodoRules.stop(stop.list)
        assertEquals(2, stop.list.stall)
        assertFalse(stop.escalate)
        stop = TodoRules.stop(stop.list)
        assertEquals(3, stop.list.stall)
        assertTrue(stop.escalate)
        l = TodoRules.resetStall(stop.list)
        assertEquals(0, l.stall)
    }

    @Test fun progressResetsTheCounter() {
        var stop = TodoRules.stop(plan)
        stop = TodoRules.stop(stop.list)
        assertEquals(2, stop.list.stall)
        val noted = TodoRules.note(stop.list, "a", "Working on it", 5L)
        stop = TodoRules.stop(noted)
        assertTrue(stop.progressed)
        assertEquals(0, stop.list.stall)
        stop = TodoRules.stop(TodoRules.add(stop.list, item("more"), "Found"))
        assertTrue(stop.progressed, "an added item is progress")
        val finished = TodoRules.stop(done(stop.list, "a"))
        assertTrue(finished.progressed, "an item done is progress")
        assertFalse(TodoRules.stop(finished.list).progressed)
    }

    @Test fun markersLinkAPrWhateverItsNumber() {
        val marker = TodoRules.marker("7f3a9c10aa", "backend-endpoint")
        assertEquals("<!-- teamcode-todo: 7f3a9c10/backend-endpoint -->", marker)
        assertEquals(listOf("7f3a9c10" to "backend-endpoint"), TodoRules.markers("Fixes things\n\n$marker\n"))
        assertTrue(TodoRules.markers("no marker here").isEmpty())
    }

    @Test fun aListMostlyAttestedIsFlagged() {
        val attested = listOf(TodoCheck.Attested("research done"))
        assertFalse(TodoRules.mostlyAttested(list(item("a"), item("b"), item("c", checks = attested))))
        assertTrue(TodoRules.mostlyAttested(list(item("a"), item("b", checks = attested), item("c", checks = attested))))
    }

    @Test fun aLinkIsFrozenButMayBeCompletedOrReplacedAfterAnUnmergedClose() {
        val l = list(item("a"))
        val pr = TodoLink("pr", "https://github.com/acme/backend/pull/7", number = 7)
        var linked = TodoRules.link(l, "a", pr, false, 10L)
        assertEquals(listOf(pr.copy(at = 10L)), linked.item("a")!!.links)
        // The same PR learns its merge commit; another PR is refused.
        linked = TodoRules.link(linked, "a", pr.copy(sha = "4e1c9a2"), false, 20L)
        assertEquals("4e1c9a2", linked.item("a")!!.links.single().sha); assertEquals(7, linked.item("a")!!.links.single().number)
        assertEquals("already_linked", code { TodoRules.link(linked, "a", TodoLink("pr", "https://github.com/acme/backend/pull/8"), false, 30L) })
        val replaced = TodoRules.link(linked, "a", TodoLink("pr", "https://github.com/acme/backend/pull/8", number = 8), true, 40L)
        assertEquals(8, replaced.item("a")!!.links.single().number)
        assertTrue(replaced.item("a")!!.notes.single().text.contains("pull/7"), "the change is noted")
        // Other kinds are independent; a link has bounds.
        val both = TodoRules.link(linked, "a", TodoLink("release", "https://github.com/acme/backend/releases/tag/v1.2.0", tag = "v1.2.0"), false, 50L)
        assertEquals(setOf("pr", "release"), both.item("a")!!.links.map { it.kind }.toSet())
        assertEquals("invalid_link", code { TodoRules.link(l, "a", TodoLink("pr", "http://insecure/1"), false, 1L) })
        assertEquals("invalid_link", code { TodoRules.link(l, "a", TodoLink("tweet", "x"), false, 1L) })
        assertEquals("unknown_item", code { TodoRules.link(l, "ghost", pr, false, 1L) })
    }
}
