package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.automations.*
import me.nathanfallet.teamcode.domain.cloud.*
import org.junit.jupiter.api.Timeout
import kotlin.test.*

/**
 * A run creates its session as the owner, outside the owner's running and retained caps, and ends when the turn that
 * took its prompt ends, on real MySQL with two replicas.
 */
class AutomationSessionPersistenceTest {
    @Test @Timeout(240)
    fun aRunStartsItsSessionAsTheOwnerOutsideTheirCapsAndEndsWithItsTurn() = runBlocking {
        AutomationWorld.run(67) { w ->
            w.a.admin.saveSettings(w.admin, CloudSettings(maxRunningSessionsPerMember = 1, maxRetainedSessionsPerMember = 1, maxRunningAutomationsPerMember = 1))
            // The owner already holds their only running and retained session.
            val own = (w.a.sessions.create(w.member, null, CloudSessionDraft("Mine", w.profile.id, null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
            w.pass(); w.pass()
            assertEquals(1, w.a.sessionStore.find(w.teamId, own.id)!!.currentEpoch)

            val created = w.a.automations.create(w.member, w.webhook())
            val id = created.automation.id
            w.a.automations.enable(w.member, id)
            val run = w.a.automations.trigger(w.signed(id, created.secret!!, """{"ticket":42}""", "first")).run
            assertEquals(1, w.b.dispatcher.run(), "One queued run claimed")
            val started = assertNotNull(w.a.store.run(w.teamId, id, run.id))
            assertEquals(RunStatus.STARTING, started.status)
            val session = assertNotNull(w.a.sessionStore.find(w.teamId, started.sessionId!!))
            assertEquals(id, session.automationId); assertEquals(w.member.member.id, session.ownerId)
            assertEquals(mapOf(CloudAutomationSessions.LABEL to id), session.labels)
            assertEquals(CloudGitHubAccess.OWNER, session.githubAccess, "Enabling lent the owner's GitHub link")
            assertEquals(w.a.store.ownerPrincipal(id)!!.tokenId, session.createdBy.credentialId, "Created with the owner credential")
            val prompt = w.a.conversations.inputs(session.id, null, 10).inputs.single()
            assertEquals("Fix ticket 42", prompt.text); assertEquals(w.member.member.id, prompt.authorId)

            // It starts although the owner's own session holds their running place.
            w.pass(); w.pass()
            assertEquals(1, w.a.sessionStore.find(w.teamId, session.id)!!.currentEpoch, "Outside the member's running cap")

            // A second run waits for the automation cap.
            val second = w.b.automations.trigger(w.signed(id, created.secret!!, """{"ticket":43}""", "second")).run
            w.a.dispatcher.run()
            assertEquals(RunStatus.QUEUED, w.a.store.run(w.teamId, id, second.id)!!.status)

            // The runner takes the prompt; the run ends when the turn that took it ends.
            val pod = CloudSessionService.podName(session.id, 1)
            w.a.cluster.start(pod); w.pass()
            w.a.runner.register(w.teamId, session.id, 1, pod)
            w.b.runner.heartbeat(w.teamId, session.id, 1, ready = true)
            val delivered = w.b.agent.inputs(w.teamId, session.id, 1, 0)
            assertEquals(listOf(prompt.id), delivered.map { it.id })
            w.a.agent.acknowledge(w.teamId, session.id, 1, listOf(RunnerInputAck(prompt.id, true, null)))
            w.a.dispatcher.run()
            assertEquals(RunStatus.STARTING, w.a.store.run(w.teamId, id, run.id)!!.status, "Consumed, but its turn has not ended")
            w.b.agent.events(w.teamId, session.id, 1, listOf(RunnerEvent(1, CloudEventType.TURN, """{"phase":"started"}""", TurnMarker.STARTED)))
            w.a.dispatcher.run()
            assertEquals(RunStatus.RUNNING, w.a.store.run(w.teamId, id, run.id)!!.status)
            w.b.agent.events(w.teamId, session.id, 1, listOf(RunnerEvent(2, CloudEventType.TURN, """{"phase":"completed"}""", TurnMarker.ENDED)))
            w.b.dispatcher.run()
            assertEquals(RunStatus.SUCCEEDED, w.a.store.run(w.teamId, id, run.id)!!.status)
            assertEquals(0, w.a.store.find(w.teamId, id)!!.consecutiveFailures)

            // The place is free again: the second run starts at its next attempt, replayed safely if its record is retried.
            w.a.store.retry(second.id, 0)
            w.a.dispatcher.run()
            val next = w.a.store.run(w.teamId, id, second.id)!!
            assertEquals(RunStatus.STARTING, next.status)
            val again = CloudAutomationSessions(w.b.sessions).create(w.a.store.ownerPrincipal(id)!!, w.a.store.find(w.teamId, id)!!, next, "Fix ticket 43", "automation-run:${next.id}")
            assertEquals(next.sessionId, again.first, "The session's Idempotency-Key returns the same session")
        }
    }
}
