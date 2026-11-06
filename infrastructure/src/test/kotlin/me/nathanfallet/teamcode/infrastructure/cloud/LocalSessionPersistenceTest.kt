package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.todos.*
import me.nathanfallet.teamcode.domain.waits.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.junit.jupiter.api.Timeout
import java.util.UUID
import kotlin.test.*

/**
 * The local session of a `teamcode run` (docs/cloud-sessions.md, "Local sessions") on real MySQL with two replicas: opened once per
 * launch, hosted by the launch itself on whatever device it runs on, ended with it, confined to waits, todo and artifacts, and the
 * Stop hook of the run hears the answer to a wait that the server queued.
 */
class LocalSessionPersistenceTest {
    private val long = "Do this item completely: what, where, why and what is out of scope, enough for a new agent to do it alone."
    private fun item(id: String) = TodoItemDefinition(id, "Item $id", long, TodoDoneWhen("It is merged", listOf(TodoCheck.PrMerged("acme/backend"))))

    @Test @Timeout(300)
    fun aLocalSessionLivesAndDiesWithItsLaunch() = runBlocking {
        AutomationWorld.run(93) { w ->
            w.a.database.query {
                assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(CloudSessions, withLogs = false), "The reviewed migrations match the Exposed tables")
            }
            val runsA = AgentRunServiceImpl(w.a.database); val runsB = AgentRunServiceImpl(w.b.database)
            val password = "automation owner replacement password"
            val laptop = w.team.login("automation-owner", password, "Laptop", "1f0e0d0c-0b0a-4009-8807-060504030201", "macos", "26")
            val other = w.team.login("automation-owner", password, "Desktop", "2f0e0d0c-0b0a-4009-8807-060504030201", "linux", "6.8")
            val authenticate = AuthenticateRunnerUseCase(w.a.sessionStore, w.a.cluster, logins = { w.team.authenticate(it) })
            val inference = RunnerInferenceUseCase(w.a.sessionStore, SqlCloudInferenceCredentialStore(w.a.database))
            val waitStore = SqlWaitStore(w.a.database)
            val todoStore = SqlTodoStore(w.a.database)
            val waker = object : WaitSessions {
                override suspend fun authorize(actor: Principal, sessionId: String) = w.a.conversation.authorizeWaitAnswer(actor, sessionId)
                override suspend fun deliver(actor: Principal, sessionId: String, text: String, clientKey: String) = w.a.conversation.queueWaitAnswer(actor, sessionId, text, clientKey)
            }
            val waits = WaitService(waitStore, waker)
            val todos = TodoService(todoStore, WaitBackedDropWaits(waitStore), LocalWakeService(w.a.sessionStore, w.a.conversations, { team, session -> todoStore.hasOpenWait(team, session) }, sleep = { delay(10) }))
            val owner = laptop.principal
            fun draft(run: String, agent: String = "claude") = LocalSessionDraft(run, "claude in teamcode", agent)
            suspend fun newRun(principal: Principal = owner, service: AgentRunService = runsA): String =
                UUID.randomUUID().toString().also { service.start(principal, it, "claude", "interactive") }

            // --- Only the member's own, running launch gets a session.
            refusedWith("run_not_found") { w.a.sessions.createLocal(owner, draft(UUID.randomUUID().toString())) }
            val adminRun = newRun(w.admin)
            refusedWith("run_not_found") { w.a.sessions.createLocal(owner, draft(adminRun)) }
            val endedRun = newRun(); runsA.end(owner, endedRun, 0)
            refusedWith("run_not_found") { w.a.sessions.createLocal(owner, draft(endedRun)) }
            val run = newRun()
            refusedWith("invalid_agent") { w.a.sessions.createLocal(owner, draft(run, "gemini")) }
            refusedWith("invalid_run") { w.a.sessions.createLocal(owner, draft("not-a-uuid")) }

            // --- Two replicas opening the same launch at once make one session.
            val opened = coroutineScope { listOf(w.a, w.b).map { r -> async(Dispatchers.IO) { r.sessions.createLocal(owner, draft(run)) } }.awaitAll() }
            val session = opened.map { (it as? CloudCreateOutcome.Created)?.session ?: (it as CloudCreateOutcome.Replayed).session }.map { it.id }.distinct().single()
            assertEquals(1L, w.a.database.query { CloudSessions.selectAll().where { (CloudSessions.teamId eq w.teamId) and (CloudSessions.runId eq run) }.count() })
            val created = w.b.sessionStore.find(w.teamId, session)!!
            assertEquals(CloudSessionKind.LOCAL to run, created.kind to created.runId)
            assertEquals("" to "", created.profileId to created.clusterId, "No profile and no cluster")
            assertEquals(CloudSessionState.CREATING to w.member.member.id, created.state to created.ownerId)
            assertEquals(session, (w.b.sessions.createLocal(owner, draft(run)) as CloudCreateOutcome.Replayed).session.id, "A second call returns the first session")

            // --- Listed as its own kind, never among the cloud sessions of a client that does not know it.
            fun ids(vararg kinds: CloudSessionKind) = runBlocking { w.b.sessions.list(owner, CloudSessionQuery(kinds = kinds.toSet())).sessions.map { it.id } }
            assertEquals(listOf(session), ids(CloudSessionKind.LOCAL))
            assertTrue(session !in ids(CloudSessionKind.CLOUD))
            assertTrue(session in ids())
            val feed = w.a.database.query { TeamEvents.selectAll().where { (TeamEvents.subjectId eq session) and (TeamEvents.type eq "cloud.session.created") }.single()[TeamEvents.data] }
            assertTrue("\"kind\":\"local\"" in feed, feed)

            // --- It takes no pause, resume, transfer or message: the launch is the session.
            refusedWith("local_session") { w.a.sessions.pause(owner, null, session, false) }
            refusedWith("local_session") { w.a.sessions.resume(owner, null, session) }
            refusedWith("local_not_transferable") { CloudTransferService.transferable(created) }
            refusedWith("github_access_unsupported") { w.a.sessions.setGitHubAccess(owner, null, session, CloudGitHubAccess.NONE) }
            refusedWith("local_session") { w.a.conversation.send(owner, session, InputRequest("hello", true, "k1", "d1", null)) }

            // --- The launch is its host, on whatever device it runs; another member's login or a stale epoch is not.
            refusedWith("not_found") { w.b.sessions.host(w.admin, session) }
            val hosted = w.b.sessions.host(other.principal, session)
            assertEquals(1, hosted.currentEpoch)
            val identity = authenticate(laptop.token, w.teamId, session, 1)
            assertEquals(session, identity.sessionId)
            assertEquals(session, authenticate(other.token, w.teamId, session, 1).sessionId, "Any device of the owner hosts a local session")
            refusedWith("execution_fenced") { authenticate(laptop.token, w.teamId, session, 2) }
            w.a.runner.register(identity.teamId, identity.sessionId, identity.epoch, identity.podName)
            w.b.runner.heartbeat(w.teamId, session, 1, ready = true)
            assertEquals(CloudSessionState.READY, w.a.sessionStore.find(w.teamId, session)!!.state)

            // --- Its credential is the confined one: no inference, but waits, todo and artifacts of this session.
            refusedWith("inference_unavailable") { inference.issue(w.teamId, session, 1) }
            val credential = inference.issueRemote(w.teamId, session, 1)
            val confined = assertNotNull(w.team.authenticate(credential.token))
            assertEquals(session, todoStore.executionSession(confined.tokenId))
            assertTrue(ServiceScope.TODO_SESSION in confined.serviceScopes!! && ServiceScope.WAITS_SESSION in confined.serviceScopes!! && ServiceScope.INFERENCE !in confined.serviceScopes!!)

            // --- An open todo holds the stop; an open wait lets it pass and the Stop hook hears the answer.
            todos.create(owner, session, "The plan", emptyList(), listOf(item("a"), item("b")))
            assertEquals(TodoStopAction.VERIFY, todos.stop(owner, session, TodoStopInput(TodoStopPhase.BEGIN)).action, "Open items hold the stop")
            refusedWith("secret_unsupported_local") {
                waits.create(owner, WaitDefinition(session, "Shop access", fields = listOf(WaitField("pw", FieldType.SECRET, "Password", required = true))), null)
            }
            val ask = WaitDefinition(session, "Which database?", actions = listOf(WaitAction("postgres", "Postgres"), WaitAction("mysql", "MySQL")))
            val wait = waits.create(owner, ask, "local-ask-1").wait
            val allowed = todos.stop(owner, session, TodoStopInput(TodoStopPhase.BEGIN))
            assertEquals(TodoStopAction.ALLOW to "wait_open", allowed.action to allowed.why, "A wait that is open lets the stop pass")
            val held = todos.wake(owner, session, 200)
            assertEquals(true to emptyList(), held.open to held.messages, "Nothing answered yet: the hook keeps holding")
            assertEquals(WaitStatus.RESOLVED, waits.answer(w.admin, wait.id, "mysql", emptyMap()).status)
            val heard = todos.wake(owner, session, 5_000)
            assertEquals(1, heard.messages.size, "The answer reaches the hook once")
            assertTrue(heard.messages.single().startsWith("[TeamCode wait answered]") && "mysql" in heard.messages.single().lowercase(), heard.messages.single())
            val again = todos.wake(owner, session, 0)
            assertEquals(false to emptyList(), again.open to again.messages, "Handed over once; nothing is open now")
            assertEquals(CloudInputStatus.CONSUMED, w.a.conversations.inputs(session, null, 10).inputs.single().status)
            assertEquals(CloudSessionState.READY, w.a.sessionStore.find(w.teamId, session)!!.state, "A local session is never woken as a paused one")

            // --- Terminal closed: the answer stays queued on the wait and is read by the next hook of a resumed session.
            val closed = waits.create(owner, ask.copy(title = "Which cache?"), "local-ask-2").wait
            waits.answer(w.admin, closed.id, "postgres", emptyMap())
            assertEquals(CloudInputStatus.QUEUED, w.a.conversations.inputs(session, null, 10).inputs.first().status, "No hook reads it: it stays queued")
            assertEquals(WaitStatus.RESOLVED, waits.get(owner, closed.id).status)
            assertEquals(1, todos.wake(owner, session, 0).messages.size, "The next look (a resumed run) reads it")

            // --- A launch that is killed leaves the session to the server, which ends it after 90 s without a heartbeat.
            w.pass(); w.pass()
            assertEquals(CloudSessionState.READY, w.a.sessionStore.find(w.teamId, session)!!.state, "A beating launch keeps its session")
            w.a.database.query { CloudExecutions.update({ CloudExecutions.sessionId eq session }) { it[lastHeartbeatAt] = (nowMillis() - 120_000) } }
            w.pass(); w.pass()
            val lost = w.a.sessionStore.find(w.teamId, session)!!
            assertEquals(CloudSessionState.TERMINATED to "run_lost", lost.state to lost.terminatedReason)
            assertEquals(ExecutionState.ENDED, w.a.sessionStore.execution(session, 1)!!.state)
            assertEquals(0L, w.a.database.query { CloudInferenceCredentials.selectAll().where { CloudInferenceCredentials.sessionId eq session }.count() }, "Its credential died with it")
            assertNull(SqlTeamService(w.b.database).authenticate(credential.token))
            assertEquals(emptyList(), waits.list(owner, null, "open", null, 50, session = session).waits, "Its waits ended with it")

            // --- A clean end: the launch deletes its session, the server ends it and the execution.
            val run2 = newRun(owner, runsB)
            val second = (w.b.sessions.createLocal(owner, draft(run2)) as CloudCreateOutcome.Created).session
            val id2 = authenticate(laptop.token, w.teamId, w.b.sessions.host(owner, second.id).id, 1)
            w.a.runner.register(id2.teamId, id2.sessionId, id2.epoch, id2.podName); w.a.runner.heartbeat(w.teamId, second.id, 1, ready = true)
            w.a.sessions.delete(owner, null, second.id)
            w.pass(); w.pass()
            assertEquals(CloudSessionState.TERMINATED to "deleted", w.a.sessionStore.find(w.teamId, second.id)!!.let { it.state to it.terminatedReason })

            // --- A launch that never took its execution does not leave a session behind.
            val run3 = newRun()
            val orphan = (w.a.sessions.createLocal(owner, draft(run3)) as CloudCreateOutcome.Created).session
            w.a.database.query { CloudSessions.update({ CloudSessions.id eq orphan.id }) { it[createdAt] = nowMillis() - 300_000 } }
            w.pass(); w.pass()
            assertEquals(CloudSessionState.TERMINATED, w.a.sessionStore.find(w.teamId, orphan.id)!!.state)
        }
    }
}
