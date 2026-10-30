package me.nathanfallet.teamcode.domain.waits

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.ada
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.bob
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.principal
import me.nathanfallet.teamcode.domain.waits.WaitFixtures.review
import kotlin.test.*

/** A cloud session's execution credential (`waits:session`) asks about its own session only, and never answers. */
class WaitExecutionTest {
    private val execution = principal(ada).copy(tokenId = "tcex-1", serviceScopes = setOf(ServiceScope.INFERENCE, ServiceScope.WAITS_SESSION))
    private fun code(block: suspend () -> Unit) = assertFailsWith<TeamFailure> { runBlocking { block() } }.code
    private fun fixture() = WaitFixtures.FakeStore().also { it.executions["tcex-1"] = "s1" }

    @Test fun itAsksListsReadsAndWithdrawsOnItsOwnSession() = runBlocking {
        val service = WaitService(fixture())
        val wait = service.create(execution, review.copy(assigneeId = "po"), null).wait
        assertEquals("po", wait.assigneeId, "asked of another member, still listed: the session filter ignores who is asked")
        assertEquals(listOf(wait.id), service.list(execution, null, "open", null, 50).waits.map { it.id })
        assertEquals(wait.id, service.get(execution, wait.id).id)
        assertEquals(WaitStatus.CANCELLED, service.cancel(execution, wait.id).status)
    }

    @Test fun itNeverAnswers() = runBlocking {
        val service = WaitService(fixture())
        val id = service.create(execution, review, null).wait.id
        assertEquals("credential_scope", code { service.answer(execution, id, "approve", emptyMap()) }, "the owner is the default assignee: the scope is the guard")
        assertEquals(WaitStatus.OPEN, service.get(execution, id).status)
    }

    @Test fun anotherSessionIsNotFound() = runBlocking {
        val store = fixture(); val service = WaitService(store)
        // Same owner, another session of hers.
        store.sessions["s3"] = WaitSession("s3", "team", "ada", "Other")
        val other = service.create(principal(ada), review.copy(sessionId = "s3"), null).wait.id
        assertEquals("not_found", code { service.create(execution, review.copy(sessionId = "s3"), null) })
        assertEquals("not_found", code { service.get(execution, other) })
        assertEquals("not_found", code { service.cancel(execution, other) })
        assertEquals(emptyList(), service.list(execution, "all", "all", null, 50).waits.map { it.id }.filter { it == other }, "whatever it asks for, it lists its own session")
    }

    @Test fun aKeyWithTheScopeButNoSessionGetsNothing() {
        val key = execution.copy(tokenId = "ci-key")
        val service = WaitService(fixture())
        assertEquals("credential_scope", code { service.list(key, null, "open", null, 50) })
        assertEquals("credential_scope", code { service.create(key, review, null) })
    }

    @Test fun otherCredentialsAreUnchanged() {
        val service = WaitService(fixture())
        // The execution scopes of today, without waits:session: still refused, as before.
        val old = execution.copy(serviceScopes = setOf(ServiceScope.INFERENCE, ServiceScope.ARTIFACTS_WRITE))
        assertEquals("credential_scope", code { service.list(old, null, "open", null, 50) })
        // A cloud key keeps its usual reach, and may name a session it owns.
        val key = principal(ada, setOf(ServiceScope.CLOUD_WRITE))
        runBlocking { service.create(key, review, null); assertEquals(1, service.list(key, null, "open", null, 50, session = "s1").waits.size) }
        assertEquals("not_found", code { service.list(principal(bob), null, "open", null, 50, session = "s1") })
    }
}
