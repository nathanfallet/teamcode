package me.nathanfallet.teamcode.domain.waits

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.team.ServiceScope
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.ada
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.admin
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.bob
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.po
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.principal
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.review
import kotlin.test.*

class WaitServiceTest {
    private fun code(block: suspend () -> Unit) = assertFailsWith<TeamFailure> { runBlocking { block() } }.code

    @Test fun theSessionsOwnerIsAskedByDefaultAndAnotherMemberOnRequest() = runBlocking {
        val store = WaitFixtures.FakeStore(); val service = WaitService(store)
        val own = service.create(principal(ada), review, null).wait
        assertEquals("ada", own.assigneeId); assertEquals(WaitKind.HUMAN_REQUEST, own.kind); assertEquals("Fix the login", own.sessionName)
        val product = service.create(principal(ada), review.copy(assigneeId = "po"), null).wait
        assertEquals("po", product.assigneeId, "a product question goes to the product owner")
        assertEquals("ada", product.createdById, "the session stays the developer's")
        assertEquals(listOf("primary"), product.actions.mapNotNull { it.style }.take(1))
    }

    @Test fun onlyTheSessionsOwnerAsksAndTheAssigneeMustBeActive() {
        val service = WaitService(WaitFixtures.FakeStore())
        assertEquals("not_found", code { service.create(principal(bob), review, null) }, "bob does not see ada's session")
        assertEquals("wait_session_owner_only", code { service.create(principal(admin), review, null) })
        assertEquals("invalid_assignee", code { service.create(principal(ada), review.copy(assigneeId = "gone"), null) })
        assertEquals("invalid_assignee", code { service.create(principal(ada), review.copy(assigneeId = "nobody"), null) })
        assertEquals("credential_scope", code { service.create(principal(ada, setOf(ServiceScope.CLOUD_READ)), review, null) })
    }

    @Test fun noActionMeansOneDoneButton() = runBlocking {
        val wait = WaitService(WaitFixtures.FakeStore()).create(principal(ada), WaitDefinition("s1", "Log in to the shop for me"), null).wait
        assertEquals(listOf("done"), wait.actions.map { it.id })
    }

    @Test fun theSameIdempotencyKeyReturnsTheFirstWait() = runBlocking {
        val service = WaitService(WaitFixtures.FakeStore())
        val first = service.create(principal(ada), review, "k1"); val again = service.create(principal(ada), review, "k1")
        assertTrue(first.changed); assertFalse(again.changed); assertEquals(first.wait.id, again.wait.id)
        assertFailsWith<IllegalArgumentException> { service.create(principal(ada), review, "bad key!") }
        Unit
    }

    @Test fun aSessionHoldsAtMostTwentyOpenWaits() = runBlocking {
        val service = WaitService(WaitFixtures.FakeStore())
        repeat(WaitRules.MAX_OPEN_PER_SESSION) { service.create(principal(ada), review, null) }
        assertEquals("wait_limit", code { service.create(principal(ada), review, null) })
    }

    @Test fun theAssigneeAnswersAndTheFirstAnswerWins() = runBlocking {
        val store = WaitFixtures.FakeStore(); val service = WaitService(store)
        val id = service.create(principal(ada), review.copy(assigneeId = "po"), null).wait.id
        assertEquals("wait_assignee_only", code { service.answer(principal(ada), id, "approve", emptyMap()) }, "the asker does not answer for the one asked")
        val done = service.answer(principal(po), id, "request_changes", mapOf("feedback" to JsonPrimitive("Split step 2")))
        assertEquals(WaitStatus.RESOLVED, done.status); assertEquals("po", done.answer!!.authorId)
        assertEquals("wait_closed", code { service.answer(principal(po), id, "approve", emptyMap()) })
        assertEquals("wait_closed", code { service.cancel(principal(ada), id) })
    }

    @Test fun anAnswerNeedsItsActionsRequiredFields() {
        val store = WaitFixtures.FakeStore(); val service = WaitService(store)
        val id = runBlocking { service.create(principal(ada), review, null).wait.id }
        assertEquals("invalid_answer", code { service.answer(principal(ada), id, "request_changes", emptyMap()) })
        assertEquals("invalid_answer", code { service.answer(principal(ada), id, "request_changes", mapOf("feedback" to JsonPrimitive("  "))) })
        assertEquals("invalid_answer", code { service.answer(principal(ada), id, "unknown", emptyMap()) })
        assertEquals("invalid_answer", code { service.answer(principal(ada), id, "approve", mapOf("other" to JsonPrimitive("x"))) })
        assertEquals(WaitStatus.OPEN, store.waits.getValue(id).status, "a refused answer changes nothing")
        assertEquals(WaitStatus.RESOLVED, runBlocking { service.answer(principal(ada), id, "approve", emptyMap()) }.status)
    }

    @Test fun anAdministratorAnswersForSomeoneAndItIsAudited() = runBlocking {
        val store = WaitFixtures.FakeStore(); val service = WaitService(store)
        val id = service.create(principal(ada), review, null).wait.id
        assertEquals("not_found", code { service.answer(principal(bob), id, "approve", emptyMap()) }, "an unrelated member does not see it")
        service.answer(principal(admin), id, "approve", emptyMap())
        assertEquals(listOf("wait.answered:$id:admin"), store.audit)
        val other = service.create(principal(ada), review, null).wait.id
        assertEquals("not_found", code { service.answer(principal(admin, setOf(ServiceScope.CLOUD_WRITE)), other, "approve", emptyMap()) }, "an administrator's cloud:write key acts as the member alone")
    }

    @Test fun theAskerOrTheAssigneeCancels() = runBlocking {
        val store = WaitFixtures.FakeStore(); val service = WaitService(store)
        val id = service.create(principal(ada), review.copy(assigneeId = "po"), null).wait.id
        assertEquals(WaitStatus.CANCELLED, service.cancel(principal(ada), id).status)
        assertEquals("wait_closed", code { service.cancel(principal(po), id) })
    }

    @Test fun aSessionsPermissionRequestIsAnsweredThroughTheSession() = runBlocking {
        val store = WaitFixtures.FakeStore(); val service = WaitService(store)
        val wait = service.create(principal(ada), review, null).wait
        store.waits[wait.id] = wait.copy(source = WaitSource.SESSION, kind = WaitKind.PERMISSION, requestId = "req1")
        assertEquals("answer_through_session", code { service.answer(principal(ada), wait.id, "approve", emptyMap()) })
        assertEquals("cancel_through_session", code { service.cancel(principal(ada), wait.id) })
    }

    @Test fun membersListTheirOwnWaitsAndAdministratorsListEveryones() = runBlocking {
        val store = WaitFixtures.FakeStore(); val service = WaitService(store)
        service.create(principal(ada), review, null)
        store.clock += 1; service.create(principal(ada), review.copy(assigneeId = "po"), null)
        assertEquals(1, service.list(principal(ada), null, "open", null, 50).waits.size)
        assertEquals(1, service.list(principal(po), null, "open", null, 50).waits.size)
        assertEquals("forbidden", code { service.list(principal(ada), "po", "open", null, 50) })
        assertEquals(2, service.list(principal(admin), WaitService.ALL, "open", null, 50).waits.size)
        assertEquals("invalid_request", code { service.list(principal(ada), null, "pending", null, 50) })
        assertEquals("invalid_request", code { service.list(principal(ada), null, "open", null, 101) })
    }

    @Test fun listingPagesNewestFirstWithoutRepeats() = runBlocking {
        val store = WaitFixtures.FakeStore(); val service = WaitService(store)
        val ids = (1..5).map { store.clock += 1; service.create(principal(ada), review, null).wait.id }
        val first = service.list(principal(ada), null, "open", null, 2)
        assertEquals(ids.reversed().take(2), first.waits.map { it.id })
        val second = service.list(principal(ada), null, "open", first.nextBefore, 2)
        assertEquals(ids.reversed().drop(2).take(2), second.waits.map { it.id })
        assertNull(service.list(principal(ada), null, "open", second.nextBefore, 2).nextBefore)
    }

    @Test fun readingNeedsInvolvement() = runBlocking {
        val service = WaitService(WaitFixtures.FakeStore())
        val id = service.create(principal(ada), review.copy(assigneeId = "po"), null).wait.id
        listOf(ada, po, admin).forEach { assertEquals(id, service.get(principal(it), id).id) }
        assertEquals("not_found", code { service.get(principal(bob), id) })
    }
}
