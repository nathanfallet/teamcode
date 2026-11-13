package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.domain.cloud.CloudCreateOutcome
import me.nathanfallet.teamcode.domain.cloud.CloudSessionDraft
import me.nathanfallet.teamcode.domain.memory.*
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.domain.waits.WaitService
import me.nathanfallet.teamcode.domain.waits.WaitSessions
import me.nathanfallet.teamcode.infrastructure.database.SqlMemoryStore
import me.nathanfallet.teamcode.infrastructure.database.SqlWaitStore
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/** An agent's team entry is a proposal the session's owner answers (docs/memory.md, "Write rules"), on real MySQL with two replicas. */
class MemoryProposalPersistenceTest {
    private fun waker(r: AutomationReplica) = object : WaitSessions {
        override suspend fun authorize(actor: Principal, sessionId: String) = r.conversation.authorizeWaitAnswer(actor, sessionId)
        override suspend fun deliver(actor: Principal, sessionId: String, text: String, clientKey: String) = r.conversation.queueWaitAnswer(actor, sessionId, text, clientKey)
    }
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
    private fun team(title: String, tags: List<String> = emptyList()) = NewMemory(MemoryScope.TEAM, title, "About $title", "Details of $title.", tags)

    @Test @Timeout(240)
    fun aTeamEntryFromAnAgentIsAProposalTheOwnerAnswers() = runBlocking {
        AutomationWorld.run(93) { w ->
            val waitsA = SqlWaitStore(w.a.database); val waitsB = SqlWaitStore(w.b.database)
            val a = MemoryService(SqlMemoryStore(w.a.database), WaitBackedMemoryProposals(waitsA))
            val b = MemoryService(SqlMemoryStore(w.b.database), WaitBackedMemoryProposals(waitsB))
            val answers = WaitService(waitsA, waker(w.a))
            val session = (w.a.sessions.create(w.member, null, CloudSessionDraft("Memory", w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session.id

            // Through an agent's relay, a team entry is only a proposal: stored, not listed, with a wait for the owner.
            val proposed = a.create(w.member, team("Deploys are frozen on Fridays", listOf("ops", "external")), session, viaAgent = true)
            assertEquals(MemoryStatus.PROPOSED, proposed.status); assertEquals(MemoryOrigin.AGENT, proposed.origin)
            assertTrue(b.index(w.admin).entries.none { it.id == proposed.id } && b.search(w.admin, "frozen").isEmpty())
            failure("not_found") { b.read(w.admin, proposed.id) }
            val waitId = w.a.database.query { Memories.select(Memories.proposalWaitId).where { Memories.id eq proposed.id }.single()[Memories.proposalWaitId]!! }
            val wait = answers.get(w.member, waitId)
            assertEquals(session, wait.sessionId); assertEquals(w.member.member.id, wait.assigneeId)
            assertTrue("external" in wait.body!!, "the owner is warned about third-party content")
            assertEquals(listOf("accept", "reject"), wait.actions.map { it.id }); assertEquals(listOf("title", "summary", "content"), wait.fields.map { it.id })

            // The queue: an administrator sees every proposal, the author theirs, a key none.
            assertEquals(listOf(proposed.id), b.proposals(w.admin).map { it.id })
            assertEquals(listOf(proposed.id), b.proposals(w.member).map { it.id })
            assertEquals(MemoryStatus.PROPOSED, a.proposals(w.admin).single().status)

            // Nothing changes until the answer is settled; the owner edits the title and accepts.
            assertEquals(0, a.settle())
            answers.answer(w.member, waitId, "accept", mapOf("title" to JsonPrimitive("Deploys are frozen on Friday afternoons"), "summary" to JsonPrimitive("About Fridays"), "content" to JsonPrimitive("Details, corrected.")))
            assertEquals(1, b.settle()); assertEquals(0, a.settle(), "settled once, whichever replica")
            val accepted = a.read(w.admin, proposed.id)
            assertEquals(MemoryStatus.ACTIVE, accepted.status); assertEquals("Deploys are frozen on Friday afternoons", accepted.title); assertEquals("Details, corrected.", accepted.content)
            assertEquals(setOf("ops", "external"), accepted.tags.toSet())
            assertEquals(MemoryOrigin.AGENT, accepted.origin)
            assertTrue(b.proposals(w.admin).isEmpty())
            w.a.database.query { assertEquals(listOf("memory.proposed", "memory.accepted"), AuditEvents.select(AuditEvents.action).where { (AuditEvents.subjectId eq proposed.id) }.orderBy(AuditEvents.id to SortOrder.ASC).map { it[AuditEvents.action] }) }

            // An edit that would break a rule (a credential) is not applied: the owner accepted what was sent.
            val second = a.create(w.member, team("Rotate the staging password"), session, viaAgent = true)
            val secondWait = w.a.database.query { Memories.select(Memories.proposalWaitId).where { Memories.id eq second.id }.single()[Memories.proposalWaitId]!! }
            answers.answer(w.member, secondWait, "accept", mapOf("content" to JsonPrimitive("password = hunter2hunter2hunter2")))
            b.settle()
            assertEquals("Details of Rotate the staging password.", a.read(w.admin, second.id).content)

            // A rejection, and an administrator's decision from the queue, which also closes the wait.
            val third = a.create(w.member, team("Use tabs"), session, viaAgent = true)
            val thirdWait = w.a.database.query { Memories.select(Memories.proposalWaitId).where { Memories.id eq third.id }.single()[Memories.proposalWaitId]!! }
            answers.answer(w.member, thirdWait, "reject", emptyMap()); a.settle()
            failure("not_found") { a.read(w.admin, third.id) }
            val fourth = a.create(w.member, team("Use spaces"), session, viaAgent = true)
            failure("forbidden") { a.decide(w.member, fourth.id, accept = true) }
            assertEquals(MemoryStatus.ACTIVE, b.decide(w.admin, fourth.id, accept = true).status)
            assertEquals("cancelled", w.a.database.query { Waits.select(Waits.status).where { Waits.id eq Memories.select(Memories.proposalWaitId).where { Memories.id eq fourth.id }.single()[Memories.proposalWaitId]!! }.single()[Waits.status] })
            failure("memory_not_proposed") { a.decide(w.admin, fourth.id, accept = false) }
            val fifth = a.create(w.member, team("Withdrawn"), session, viaAgent = true)
            assertEquals(MemoryStatus.REJECTED, a.decide(w.member, fifth.id, accept = false).status, "the author may withdraw their own")

            // Not answered in 14 days: rejected and the wait cancelled; rejected entries go after 30 days.
            val stale = a.create(w.member, team("Forgotten proposal"), session, viaAgent = true)
            val day = 86_400_000L
            w.a.database.query { Memories.update({ Memories.id eq stale.id }) { it[updatedAt] = System.currentTimeMillis() - 15 * day } }
            assertEquals(1, a.settle())
            failure("not_found") { a.read(w.admin, stale.id) }
            assertEquals(MemoryStatus.REJECTED.wire, w.a.database.query { Memories.select(Memories.status).where { Memories.id eq stale.id }.single()[Memories.status] })
            w.a.database.query { Memories.update({ Memories.id eq stale.id }) { it[updatedAt] = System.currentTimeMillis() - 31 * day } }
            a.settle()
            assertEquals(0L, w.a.database.query { Memories.selectAll().where { Memories.id eq stale.id }.count() })

            // A person's own team entry stays direct; a personal one from an agent stays direct; a limit protects the room.
            assertEquals(MemoryStatus.ACTIVE, a.create(w.member, team("By a person")).status)
            assertEquals(MemoryStatus.ACTIVE, a.create(w.member, NewMemory(MemoryScope.PERSONAL, "Mine", "S", "C"), session, viaAgent = true).status)
            repeat(20) { a.create(w.member, team("Pending $it"), session, viaAgent = true) }
            failure("memory_proposals_limit") { a.create(w.member, team("One too many"), session, viaAgent = true) }
            // A proposal needs a session of the caller's.
            failure("memory_proposal_session") { b.create(w.member, team("No session"), null, viaAgent = true) }
            failure("not_found") { b.create(w.admin, team("Someone else's session"), session, viaAgent = true) }
            // A service with no proposal waits (no cloud) refuses.
            failure("memory_team_forbidden") { MemoryService(SqlMemoryStore(w.a.database)).create(w.member, team("Nowhere"), session, viaAgent = true) }
        }
    }
}
