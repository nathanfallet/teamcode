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
        override suspend fun now() = 1_000L
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun executionSession(tokenId: String): String? = null
        override suspend fun sessionOwner(teamId: String, sessionId: String) = "ada".takeIf { sessionId == "s1" }
        override suspend fun find(teamId: String, sessionId: String) = list
        override suspend fun create(list: TodoList) { this.list = list }
        override suspend fun <T> mutate(teamId: String, sessionId: String, change: (TodoList) -> Pair<TodoList, T>): T { val (next, value) = change(list!!); list = next; return value }
        override suspend fun artifactVersion(teamId: String, artifactId: String) = artifacts[artifactId]
        override suspend fun waitState(teamId: String, sessionId: String, waitId: String) = waits[waitId]
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
}
