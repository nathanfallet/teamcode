package me.nathanfallet.teamcode.presentation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.server.engine.embeddedServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.CloudStreamEnd
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.client.TeamCodeException
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

/** Transport contract of transcript, message, attach and runner agent routes over in-memory ports. */
class CloudConversationRoutesTest {
    private val admin = Member("admin-id", "admin", Role.ADMIN, false, false)
    private val colleague = Member("colleague-id", "colleague", Role.MEMBER, false, false)
    private val other = Member("other-id", "other", Role.MEMBER, false, false)

    private class Sessions(val members: List<Member>) : CloudSessionStore {
        val sessions = linkedMapOf<String, CloudSession>()
        val runs = mutableMapOf<Pair<String, Int>, CloudExecution>()
        var clock = 1_000L
        val profile = CloudProfile("profile-id", "default", "toolchain", 1, CloudProfileSpec("cluster-id", "teamcode", "registry.example/toolchain:1"), members.map { it.id }.toSet(), 1, 1)
        // Each read moves a second forward, so the stream's periodic recheck happens within a test.
        override suspend fun now() = clock.also { clock += 1_000 }
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String) = members.find { it.id == memberId }
        override suspend fun profile(teamId: String, profileId: String) = profile
        override suspend fun profileVersion(teamId: String, profileId: String, version: Int) = profile.spec
        override suspend fun cluster(teamId: String, clusterId: String) = CloudCluster("cluster-id", "default", "lab", "https://k8s.example.test", listOf("teamcode"), 10, "standard", 1, 0, null, 1, 1)
        var settings = CloudSettings()
        override suspend fun settings(teamId: String) = settings
        override suspend fun insert(session: CloudSession, actor: CloudActor, idempotency: IdempotencyRequest?, retainedLimit: Int) =
            CloudCreateOutcome.Created(session.copy(generation = 1).also { sessions[it.id] = it })
        override suspend fun find(teamId: String, id: String) = sessions[id]
        override suspend fun list(teamId: String, query: CloudSessionQuery) = CloudSessionPage(sessions.values.toList(), null)
        override suspend fun executions(teamId: String, sessionId: String) = emptyList<CloudExecution>()
        override suspend fun execution(sessionId: String, epoch: Int) = runs[sessionId to epoch]
        override suspend fun compareAndSet(expected: CloudSession, next: CloudSession, write: CloudWrite): CloudSession? {
            if (sessions[expected.id]?.generation != expected.generation) return null
            return next.copy(generation = expected.generation + 1).also { sessions[it.id] = it }
        }
        override suspend fun claimDue(owner: String, leaseMillis: Long, limit: Int) = emptyList<CloudSession>()
        override suspend fun release(sessionId: String, owner: String, reconcileAt: Long?) = Unit
        override suspend fun startExecution(expected: CloudSession, next: CloudSession, execution: CloudExecution, leaseOwner: String, memberRunningLimit: Int) = StartOutcome.Conflict
        override suspend fun heartbeat(sessionId: String, epoch: Int, runnerReady: Boolean) = true
        override suspend fun liveObjects(clusterId: String) = LiveObjects(emptySet(), emptySet())
        override suspend fun namespaces(clusterId: String) = emptySet<String>()
        override suspend fun recordGarbageCollection(clusterId: String, orphanedVolumes: Int) = Unit
    }

    /** Ordering and idempotency semantics of the MySQL store, kept minimal (MySQL behavior is covered in infrastructure). */
    private class Conversations : CloudConversationStore {
        val events = mutableListOf<CloudEvent>()
        val inputs = linkedMapOf<String, CloudInput>()
        val runnerSeqs = mutableMapOf<Pair<String, Int>, Long>()
        var policy = CloudAccessPolicy.DEFAULT
        val attachments = mutableMapOf<String, CloudAttachment>()
        var clock = 10_000L
        override suspend fun now() = clock++
        private fun append(sessionId: String, epoch: Int, type: CloudEventType, data: String): Long {
            val seq = (events.filter { it.sessionId == sessionId }.maxOfOrNull { it.seq } ?: 0) + 1
            events += CloudEvent(sessionId, seq, type, clock++, epoch, data)
            return seq
        }
        override suspend fun appendRunnerEvents(sessionId: String, epoch: Int, events: List<RunnerEvent>): RunnerEventsAck {
            var through = runnerSeqs[sessionId to epoch] ?: 0
            for (event in events) { if (event.sequence != through + 1) continue; append(sessionId, epoch, event.type, event.data); through = event.sequence }
            runnerSeqs[sessionId to epoch] = through
            return RunnerEventsAck(through, lastSeq(sessionId))
        }
        override suspend fun events(sessionId: String, after: Long?, before: Long?, limit: Int): CloudEventPage {
            val all = events.filter { it.sessionId == sessionId }
            val page = all.filter { it.seq > (after ?: 0) && (before == null || it.seq < before) }.let { if (before != null) it.takeLast(limit) else it.take(limit) }
            return CloudEventPage(page, all.firstOrNull()?.seq, all.lastOrNull()?.seq ?: 0, page.lastOrNull()?.seq?.takeIf { it < (all.lastOrNull()?.seq ?: 0) }, null)
        }
        override suspend fun lastSeq(sessionId: String) = events.filter { it.sessionId == sessionId }.maxOfOrNull { it.seq } ?: 0
        /** The requests a test says are in the transcript (the MySQL lookup is covered in infrastructure). */
        val requests = mutableMapOf<String, PermissionRequestRecord>()
        /** The session as the store's lock would read it (the sessions fake of the test). */
        var sessionOf: (String) -> CloudSession? = { null }
        /** The files a message carries, as the store's lock would find them (the MySQL checks are covered in infrastructure). */
        var bind: (NewInput) -> List<CloudFileRef> = { emptyList() }
        override suspend fun insertInput(new: NewInput, pendingLimit: Int): InputOutcome {
            val input = new.input
            inputs.values.find { it.sessionId == input.sessionId && it.authorId == input.authorId && it.clientKey != null && it.clientKey == input.clientKey }?.let {
                if (it.requestDigest != input.requestDigest) throw TeamFailure("idempotency_conflict", 422, "conflict")
                return InputOutcome.Replayed(it)
            }
            new.permissionCheck?.let { check ->
                val session = sessionOf(input.sessionId)!!
                check(AnswerContext(new.permissionRequestId?.let(requests::get), session.state, session.desiredState, session.currentEpoch))
            }
            new.permissionRequestId?.let { requestId ->
                if (inputs.values.any { it.sessionId == input.sessionId && it.kind == CloudInputKind.PERMISSION && !it.status.final && "\"requestId\":\"$requestId\"" in it.text.orEmpty() })
                    throw TeamFailure("permission_request_answered", 409, "pending")
            }
            val stored = input.copy(files = bind(new))
            inputs[input.id] = stored
            return InputOutcome.Accepted(stored)
        }
        override suspend fun input(sessionId: String, id: String) = inputs[id]?.takeIf { it.sessionId == sessionId }
        override suspend fun inputs(sessionId: String, beforeId: String?, limit: Int) = CloudInputPage(inputs.values.filter { it.sessionId == sessionId }.reversed().take(limit), null)
        override suspend fun clearWake(sessionId: String) = Unit
        override suspend fun pendingWakes(limit: Int) = emptyList<Pair<String, String>>()
        override suspend fun deliver(sessionId: String, epoch: Int, limit: Int) = inputs.values.filter { it.sessionId == sessionId && it.status == CloudInputStatus.QUEUED }.take(limit).map {
            it.copy(status = CloudInputStatus.DELIVERED, deliveredEpoch = epoch, attempts = it.attempts + 1).also { delivered -> inputs[it.id] = delivered }
        }
        override suspend fun acknowledge(sessionId: String, epoch: Int, acks: List<RunnerInputAck>) = acks.mapNotNull { ack ->
            val input = inputs[ack.id]?.takeIf { it.status == CloudInputStatus.DELIVERED && it.deliveredEpoch == epoch } ?: return@mapNotNull null
            val seq = append(sessionId, 0, CloudEventType.USER_MESSAGE, buildJsonObject { put("text", CloudConversationRules.bound(input.text.orEmpty())) }.toString())
            input.copy(status = if (ack.consumed) CloudInputStatus.CONSUMED else CloudInputStatus.REJECTED, reason = ack.reason, eventSeq = seq).also { inputs[it.id] = it }
        }
        override suspend fun cancelEnded(limit: Int) = 0
        override suspend fun attach(attachment: CloudAttachment, expectedOwnerId: String) = attachment.also { attachments[it.id] = it }
        override suspend fun renew(sessionId: String, id: String, memberId: String, leaseUntil: Long) =
            attachments[id]?.takeIf { it.memberId == memberId }?.copy(leaseUntil = leaseUntil)?.also { attachments[id] = it }
        override suspend fun detach(sessionId: String, id: String, memberId: String) { attachments.remove(id) }
        override suspend fun attachments(sessionId: String, now: Long) = attachments.values.toList()
        override suspend fun accessPolicy(teamId: String) = policy
        override suspend fun saveAccessPolicy(actor: Principal, expectedVersion: Int, rules: List<CloudAccessRule>) = CloudAccessPolicy(expectedVersion + 1, rules).also { policy = it }
        override suspend fun purgeTranscripts(limit: Int) = 0
    }

    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) =
            receive(GenerationHead(200, "text/event-stream"), emptyFlow())
    }
    private fun team(): TeamService = object : TeamService by FakeTeam() {
        override suspend fun authenticate(token: String): Principal? = when (token) {
            "admin" -> Principal(admin, "admin-session", "default")
            "colleague" -> Principal(colleague, "colleague-session", "default")
            "other" -> Principal(other, "other-session", "default")
            "service-colleague" -> Principal(colleague, "colleague-key", "default", ServiceScope.entries.toSet() - setOf(ServiceScope.CLOUD_READ, ServiceScope.CLOUD_WRITE, ServiceScope.ADMIN_CLOUD, ServiceScope.TEAM_READ))
            "colleague-read" -> Principal(colleague, "colleague-read-key", "default", setOf(ServiceScope.CLOUD_READ))
            "admin-write" -> Principal(admin, "admin-write-key", "default", setOf(ServiceScope.CLOUD_WRITE))
            "admin-read" -> Principal(admin, "admin-read-key", "default", setOf(ServiceScope.TEAM_READ))
            "admin-usage" -> Principal(admin, "admin-usage-key", "default", setOf(ServiceScope.USAGE_READ, ServiceScope.CLOUD_READ))
            "admin-read-write" -> Principal(admin, "admin-read-write-key", "default", setOf(ServiceScope.TEAM_READ, ServiceScope.CLOUD_WRITE))
            else -> null
        }
    }
    private val cluster = object : ClusterPort {
        override suspend fun storageClass(clusterId: String, name: String?) = StorageClassObservation("standard", "Delete")
        override suspend fun ensureClaim(clusterId: String, request: WorkspaceRequest) = Unit
        override suspend fun claim(clusterId: String, namespace: String, name: String): ClaimObservation? = null
        override suspend fun deleteClaim(clusterId: String, namespace: String, name: String) = Unit
        override suspend fun volume(clusterId: String, name: String): VolumeObservation? = null
        override suspend fun orphanedVolumes(clusterId: String) = emptyList<VolumeObservation>()
        override suspend fun createPod(clusterId: String, request: PodRequest) = Unit
        override suspend fun pod(clusterId: String, namespace: String, name: String): PodObservation? = null
        override suspend fun deletePod(clusterId: String, namespace: String, name: String, force: Boolean) = Unit
        override suspend fun managed(clusterId: String, namespace: String) = ManagedObjects(emptyList(), emptyList())
        override suspend fun reviewToken(clusterId: String, token: String, audience: String) =
            when (token) {
                "projected-pod-token-1" -> TokenIdentity("teamcode", "default", "tc-s1-e1", "uid-1")
                "projected-pod-token-2" -> TokenIdentity("teamcode", "default", "tc-s2-e1", "uid-2")
                else -> null
            }
    }

    /** A real server: the test engine buffers a streamed response until it completes, which an endless stream never does. */
    @Test fun transcriptMessagesStreamAndRunnerAgentChannel() = runBlocking {
        val store = Sessions(listOf(admin, colleague, other))
        val conversations = Conversations().also { c -> c.sessionOf = { store.sessions[it] } }
        val access = CloudAccessService(conversations, store)
        val service = CloudConversationService(conversations, store, access, sleep = { delay(20) })
        val sessions = CloudSessionService(store, access = access, initialMessages = service)
        val conversation = CloudConversation(service, access,
            RunnerConversationUseCase(store, conversations, sleep = { delay(20) }))
        val server = embeddedServer(io.ktor.server.cio.CIO, port = 0, host = "127.0.0.1") {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(object : CloudAdminStore {
                override suspend fun clusters(actor: Principal) = emptyList<CloudCluster>()
                override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput) = error("unused")
                override suspend fun profiles(actor: Principal) = emptyList<CloudProfile>()
                override suspend fun member(actor: Principal, memberId: String): Member? = null
                override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) = error("unused")
                override suspend fun settings(actor: Principal) = CloudSettings()
                override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings
            }, cluster), cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, cluster)),
                cloudConversation = conversation, publicOrigin = "http://localhost")
        }.start()
        val origin = "http://localhost:${server.engine.resolvedConnectors().first().port}"
        val client = HttpClient(io.ktor.client.engine.cio.CIO) { expectSuccess = false; defaultRequest { url(origin) } }
        fun sdk(token: String) = TeamCodeClient(origin, { token }) { config -> HttpClient(io.ktor.client.engine.cio.CIO) { config(this) } }
        try {
            val created = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Run", "profile-id", null, "opus", null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
            store.sessions.clear()
            store.sessions["s1"] = created.copy(id = "s1", currentEpoch = 1, generation = 1, state = CloudSessionState.READY)
            store.runs["s1" to 1] = CloudExecution("s1", 1, "tc-s1-e1", ExecutionState.RUNNING, 1)

            val features = Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features
            assertTrue(listOf("cloud.transcript.v1", "cloud.messages.v1", "cloud.attach.v1", "cloud.access.v1", "cloud.sessions.initial-message.v1", "cloud.permissions.v1", "cloud.transcript.live.v1", "cloud.sessions.waiting.v1").all { it in features })

            // An initial message of the full 32,000 characters fits the creation body and is queued (replay: CloudIdlePersistenceTest).
            val longest = "é".repeat(CloudConversationRules.MAX_MESSAGE_CHARS)
            suspend fun create(text: String, key: String) = client.post("/api/v1/cloud/sessions") {
                bearerAuth("colleague"); contentType(ContentType.Application.Json); header(CloudHeaders.IDEMPOTENCY_KEY, key)
                setBody(Serialization.json.encodeToString(CreateCloudSessionRequest("Initial", "profile-id", initialMessage = text)))
            }
            val withMessage = create(longest, "initial-1")
            assertEquals(HttpStatusCode.Accepted, withMessage.status, withMessage.bodyAsText())
            val initialId = Serialization.json.decodeFromString<CloudSessionView>(withMessage.bodyAsText()).id
            assertEquals(listOf(longest), conversations.inputs.values.filter { it.sessionId == initialId }.map { it.text })
            assertEquals(HttpStatusCode.BadRequest, create(longest + "x", "initial-2").status, "Longer than a message")
            assertFalse(store.sessions.values.any { it.name == "Initial" && it.id != initialId }, "A refused message creates no session")
            conversations.inputs.values.removeIf { it.sessionId == initialId }

            suspend fun send(token: String, body: String, key: String? = null, onBehalf: String? = null) = client.post("/api/v1/cloud/sessions/s1/messages") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
                key?.let { header(CloudHeaders.IDEMPOTENCY_KEY, it) }; onBehalf?.let { header(CloudHeaders.ON_BEHALF_OF, it) }
            }
            assertEquals(HttpStatusCode.Forbidden, send("service-colleague", """{"text":"hi"}""").status, "Keys without a cloud scope never reach cloud routes")
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/cloud/sessions/s1/events") { bearerAuth("colleague-read") }.status, "cloud:read reads the owner's transcript")
            assertEquals(HttpStatusCode.Forbidden, send("colleague-read", """{"text":"hi"}""").status, "but cannot send")
            assertEquals(HttpStatusCode.NotFound, send("admin-write", """{"text":"hi"}""").status, "An administrator's key without admin:cloud acts as its owner only")
            assertEquals(HttpStatusCode.NotFound, send("other", """{"text":"hi"}""").status, "Another member's session is invisible")
            assertEquals(HttpStatusCode.BadRequest, send("admin", """{"text":"hi"}""", onBehalf = "colleague-id").status, "Messages are never delegated")
            assertEquals(HttpStatusCode.BadRequest, send("colleague", """{"text":"hi","clientMessageId":"b"}""", key = "a").status)
            val first = send("colleague", """{"text":"Fix the retry"}""", key = "task-1")
            assertEquals(HttpStatusCode.Accepted, first.status)
            val ack = Serialization.json.decodeFromString<CloudInputView>(first.bodyAsText())
            assertEquals("queued", ack.status); assertNull(first.headers[CloudHeaders.IDEMPOTENCY_REPLAYED])
            val replay = send("colleague", """{"clientMessageId":"task-1","text":"Fix the retry"}""")
            assertEquals("true", replay.headers[CloudHeaders.IDEMPOTENCY_REPLAYED], "The body alias and the header are one key")
            assertEquals(ack.id, Serialization.json.decodeFromString<CloudInputView>(replay.bodyAsText()).id)
            assertEquals(HttpStatusCode.UnprocessableEntity, send("colleague", """{"text":"Other text"}""", key = "task-1").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/cloud/access") { bearerAuth("colleague") }.status)

            // Runner agent channel: configuration, delivery, acknowledgement, and sanitized events.
            val runner = sdk("projected-pod-token-1")
            val member = sdk("colleague")
            try {
                assertEquals(RunnerAgentView("claude", null, "opus", null, "none", permissionRelay = true, liveText = true), runner.runnerAgent(RunnerAgentRequest("default", "s1", 1)),
                    "This server takes permission requests and live text deltas, so the runner relays them, without a deadline by default")
                store.settings = CloudSettings(permissionDeadlineMinutes = 15)
                assertEquals(15, runner.runnerAgent(RunnerAgentRequest("default", "s1", 1)).permissionDeadlineMinutes, "The team's deadline reaches the runner")
                store.settings = CloudSettings()
                val delivered = runner.runnerInputs(RunnerInputsRequest("default", "s1", 1, 0)).inputs.single()
                assertEquals(ack.id, delivered.id); assertEquals("Fix the retry", delivered.text)
                runner.runnerAcknowledge(RunnerInputAckRequest("default", "s1", 1, listOf(RunnerInputAckView(ack.id, "consumed"))))
                assertEquals("consumed", member.cloudInput("s1", ack.id).status)

                // A permission answer reaches the runner as the fixed JSON it parses; a bad behavior or a read-only key is refused.
                suspend fun answer(token: String, body: String) = client.post("/api/v1/cloud/sessions/s1/permissions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
                conversations.requests["req-1"] = PermissionRequestRecord("req-1", question = true, listOf(QuestionShape(2, false), QuestionShape(3, true)), epoch = 1)
                conversations.requests["tool-1"] = PermissionRequestRecord("tool-1", question = false, null, epoch = 1)
                assertEquals(HttpStatusCode.BadRequest, answer("colleague", """{"requestId":"req-1","behavior":"maybe"}""").status)
                assertEquals(HttpStatusCode.BadRequest, answer("colleague", """{"requestId":"req-1","behavior":"deny","answers":[{"question":0,"options":[1]}]}""").status, "Answers go with allow")
                assertEquals(HttpStatusCode.BadRequest, answer("colleague", """{"requestId":"req-1","behavior":"allow","answers":[{"question":0,"options":[2]},{"question":1,"options":[0]}]}""").status, "An option the question does not have")
                assertEquals(HttpStatusCode.BadRequest, answer("colleague", """{"requestId":"req-1","behavior":"allow","answers":[{"question":0,"options":[1]}]}""").status, "Each question is answered")
                assertEquals(HttpStatusCode.BadRequest, answer("colleague", """{"requestId":"req-1","behavior":"allow","answers":[{"question":0,"options":[0,1]},{"question":1,"options":[0]}]}""").status, "One option for a single-choice question")
                assertEquals(HttpStatusCode.BadRequest, answer("colleague", """{"requestId":"req-1","behavior":"allow","answers":[{"question":0,"options":[0]},{"question":2,"text":"Green"}]}""").status, "A question the request does not have")
                assertEquals(HttpStatusCode.BadRequest, answer("colleague", """{"requestId":"tool-1","behavior":"allow","answers":[{"question":0,"options":[0]}]}""").status, "A tool use takes no answers")
                assertEquals(HttpStatusCode.Conflict, answer("colleague", """{"requestId":"req-9","behavior":"allow"}""").status, "No such request in the transcript")
                assertEquals(HttpStatusCode.Forbidden, answer("colleague-read", """{"requestId":"req-1","behavior":"allow"}""").status)
                assertEquals(HttpStatusCode.NotFound, answer("other", """{"requestId":"req-1","behavior":"allow"}""").status)
                // A member the policy lets send messages answers a question, but approving a tool use stays the owner's or an administrator's.
                conversations.policy = CloudAccessPolicy(1, listOf(CloudAccessRule(CloudAccessSubject.MemberSubject(other.id), setOf(CloudPermission.TRANSCRIPT, CloudPermission.MESSAGE), CloudAccessScope.TEAM)))
                val refused = answer("other", """{"requestId":"tool-1","behavior":"allow"}""")
                assertEquals(HttpStatusCode.Forbidden, refused.status); assertTrue("permission_answer_denied" in refused.bodyAsText())
                assertEquals(HttpStatusCode.Forbidden, answer("other", """{"requestId":"tool-1","behavior":"deny"}""").status, "Denying is the owner's decision too")
                assertEquals(HttpStatusCode.Accepted, answer("other", """{"requestId":"req-1","behavior":"allow","answers":[{"question":0,"options":[0]},{"question":1,"options":[0,2]}]}""").status)
                conversations.policy = CloudAccessPolicy.DEFAULT
                val second = answer("colleague", """{"requestId":"req-1","behavior":"deny"}""")
                assertEquals(HttpStatusCode.Conflict, second.status, "One answer per request at a time"); assertTrue("permission_request_answered" in second.bodyAsText())
                assertEquals(HttpStatusCode.Accepted, answer("admin", """{"requestId":"tool-1","behavior":"deny","message":"Not today."}""").status, "An administrator decides a tool use")
                conversations.requests["old-1"] = PermissionRequestRecord("old-1", question = false, null, PermissionRequestState.EXPIRED, epoch = 1)
                val expired = answer("colleague", """{"requestId":"old-1","behavior":"allow"}""")
                assertEquals(HttpStatusCode.Conflict, expired.status, "An expired request was already denied"); assertTrue("permission_request_expired" in expired.bodyAsText())
                conversations.requests["big-1"] = PermissionRequestRecord("big-1", question = false, null, epoch = 1, inputLeftOut = true)
                val unseen = answer("colleague", """{"requestId":"big-1","behavior":"allow"}""")
                assertEquals(HttpStatusCode.BadRequest, unseen.status, "Nobody approves an input nobody could read"); assertTrue("permission_input_unseen" in unseen.bodyAsText())
                assertEquals(HttpStatusCode.Accepted, answer("colleague", """{"requestId":"big-1","behavior":"deny"}""").status, "but it can be denied")
                conversations.inputs.values.removeIf { it.kind == CloudInputKind.PERMISSION }
                val answered = member.answerCloudPermission("s1", AnswerCloudPermissionRequest("req-1", "allow", answers = listOf(CloudQuestionAnswer(1, text = "Purple"), CloudQuestionAnswer(0, listOf(1)))), "answer-1")
                assertEquals("permission", answered.kind); assertEquals("queued", answered.status); assertFalse(answered.woke)
                assertEquals(answered.id, member.answerCloudPermission("s1", AnswerCloudPermissionRequest("req-1", "allow", answers = listOf(CloudQuestionAnswer(1, text = "Purple"), CloudQuestionAnswer(0, listOf(1)))), "answer-1").id, "Replayed")
                assertEquals(422, assertFailsWith<TeamCodeException> { member.answerCloudPermission("s1", AnswerCloudPermissionRequest("req-1", "deny"), "answer-1") }.statusCode)
                val relayed = runner.runnerInputs(RunnerInputsRequest("default", "s1", 1, 0)).inputs.single()
                assertEquals("permission", relayed.kind)
                assertEquals("""{"requestId":"req-1","behavior":"allow","answers":[{"question":0,"options":[1]},{"question":1,"text":"Purple"}]}""", relayed.text, "By index, sorted by question")
                assertEquals(HttpStatusCode.Accepted, answer("colleague", """{"requestId":"tool-1","behavior":"allow"}""").status, "The owner decides a tool use")
                runner.runnerInputs(RunnerInputsRequest("default", "s1", 1, 0))
                val events = listOf(
                    RunnerEventView(1, "turn", buildJsonObject { put("phase", "started") }),
                    RunnerEventView(2, "message.agent", buildJsonObject {
                        put("text", "Token sk-ant-${"x".repeat(40)} and Authorization: Bearer ${"y".repeat(30)}")
                        put("thinking", "private reasoning")
                        putJsonArray("content") { addJsonObject { put("type", "thinking"); put("thinking", "hidden") }; addJsonObject { put("type", "text"); put("text", "kept") } }
                    }),
                    RunnerEventView(3, "tool.result", buildJsonObject { put("output", "z".repeat(40_000)) }),
                )
                assertEquals(3, runner.runnerEvents(RunnerEventsRequest("default", "s1", 1, events)).acceptedThrough)
                assertEquals(3, runner.runnerEvents(RunnerEventsRequest("default", "s1", 1, events)).acceptedThrough, "A retried batch is idempotent")
                assertEquals(CloudSessionState.ACTIVE, store.sessions.getValue("s1").state, "A started turn makes the session active")
                // A session waiting for a person says so in its view, in the list as in a read; a wait of an older execution does not show.
                assertNull(member.cloudSession("s1").waiting)
                store.sessions["s1"] = store.sessions.getValue("s1").copy(humanWait = CloudHumanWait(CloudHumanWait.Reason.QUESTION, "req-1", since = 7, eventSeq = 9, epoch = 1))
                assertEquals(CloudWaitingView("question", "req-1", since = 7, eventSeq = 9), member.cloudSession("s1").waiting)
                assertEquals(CloudWaitingView("question", "req-1", since = 7, eventSeq = 9), member.cloudSessions().sessions.single { it.id == "s1" }.waiting)
                store.sessions["s1"] = store.sessions.getValue("s1").copy(humanWait = CloudHumanWait(CloudHumanWait.Reason.PERMISSION, null, since = 7, eventSeq = 9, epoch = 0))
                assertNull(member.cloudSession("s1").waiting)
                store.sessions["s1"] = store.sessions.getValue("s1").copy(humanWait = null)
                assertEquals(409, assertFailsWith<TeamCodeException> { runner.runnerEvents(RunnerEventsRequest("default", "s1", 2, events)) }.statusCode)
                assertEquals(400, assertFailsWith<TeamCodeException> { runner.runnerEvents(RunnerEventsRequest("default", "s1", 1, listOf(RunnerEventView(4, "input", JsonObject(emptyMap()))))) }.statusCode)

                val page = member.cloudEvents("s1", after = 0)
                assertEquals(listOf("message.user", "turn", "message.agent", "tool.result"), page.events.map { it.type })
                val agentText = page.events[2].data.toString()
                assertFalse("sk-ant-x" in agentText || "y".repeat(30) in agentText || "private reasoning" in agentText || "hidden" in agentText, agentText)
                assertTrue("kept" in agentText && CloudConversationRules.MASK in agentText)
                assertTrue((page.events[3].data["output"] as JsonPrimitive).content.endsWith(CloudConversationRules.TRUNCATION_MARK))
                assertEquals(4, page.lastSeq)

                // The stream resumes after Last-Event-ID, delivers each event once, in order, and ends with the session.
                val seen = mutableListOf<Long>()
                val end = withTimeout(20_000) {
                    val following = async { member.followCloudEvents("s1", 2) { seen += it.seq } }
                    while (seen.size < 2) delay(20)
                    runner.runnerEvents(RunnerEventsRequest("default", "s1", 1, listOf(RunnerEventView(4, "turn", buildJsonObject { put("phase", "completed") }))))
                    while (seen.size < 3) delay(20)
                    store.sessions["s1"] = store.sessions.getValue("s1").copy(state = CloudSessionState.TERMINATED)
                    following.await()
                }
                assertEquals(CloudStreamEnd.ENDED, end)
                assertEquals(listOf(3L, 4L, 5L), seen)
                assertEquals(CloudSessionState.TERMINATED, store.sessions.getValue("s1").state)
                assertEquals(409, assertFailsWith<TeamCodeException> { member.attachCloudSession("s1") }.statusCode, "An ended session cannot be attached")
                assertEquals(409, assertFailsWith<TeamCodeException> { member.answerCloudPermission("s1", AnswerCloudPermissionRequest("req-2", "allow")) }.statusCode, "Nothing waits in an ended session")

                // team:read reads another member's session, transcript, inputs and stream, and cannot write or delegate.
                val auditor = sdk("admin-read")
                try {
                    assertEquals("s1", auditor.cloudSession("s1").id)
                    assertEquals(5, auditor.cloudEvents("s1", after = 0).lastSeq)
                    assertEquals(ack.id, auditor.cloudInput("s1", ack.id).id)
                    val replayed = mutableListOf<Long>()
                    assertEquals(CloudStreamEnd.ENDED, withTimeout(20_000) { auditor.followCloudEvents("s1", 0) { replayed += it.seq } })
                    assertEquals(listOf(1L, 2L, 3L, 4L, 5L), replayed)
                } finally { auditor.close() }
                for (path in listOf("messages", "interrupt", "permissions", "attachments", "pause", "resume"))
                    assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/cloud/sessions/s1/$path") { bearerAuth("admin-read"); contentType(ContentType.Application.Json); setBody("""{"text":"hi"}""") }.status, path)
                assertEquals(HttpStatusCode.Forbidden, send("admin-read", """{"text":"hi"}""", onBehalf = "colleague-id").status)
                assertEquals(HttpStatusCode.NotFound, send("admin-read-write", """{"text":"hi"}""").status, "team:read adds no write right to cloud:write")
                assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cloud/sessions/s1/events") { bearerAuth("admin-usage") }.status, "usage:read and cloud:read stay the owner's own view")
            } finally { runner.close(); member.close() }
        } finally { client.close(); server.stop(0, 1_000) }
    }

    @Test fun attachmentsAndPolicyFollowMemberAndGroupRules() = testApplication {
        val store = Sessions(listOf(admin, colleague, other))
        val conversations = Conversations().also { c -> c.sessionOf = { store.sessions[it] } }
        val groups = object : CloudGroupDirectory {
            override suspend fun groupsOf(teamId: String, memberId: String) = if (memberId == other.id) setOf("reviewers") else emptySet()
            override suspend fun exists(teamId: String, groupId: String) = groupId == "reviewers"
        }
        val access = CloudAccessService(conversations, store, groups)
        val sessions = CloudSessionService(store, access = access)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(object : CloudAdminStore {
                override suspend fun clusters(actor: Principal) = emptyList<CloudCluster>()
                override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput) = error("unused")
                override suspend fun profiles(actor: Principal) = emptyList<CloudProfile>()
                override suspend fun member(actor: Principal, memberId: String): Member? = null
                override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) = error("unused")
                override suspend fun settings(actor: Principal) = CloudSettings()
                override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings
            }, cluster), cloudConversation = CloudConversation(CloudConversationService(conversations, store, access), access, RunnerConversationUseCase(store, conversations)),
                publicOrigin = "http://localhost")
        }
        val created = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Run", "profile-id", null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        store.sessions[created.id] = created.copy(state = CloudSessionState.READY)
        suspend fun put(token: String, body: String) = client.put("/api/v1/cloud/access") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals(HttpStatusCode.Forbidden, put("colleague", """{"version":0,"rules":[]}""").status)
        assertEquals(HttpStatusCode.BadRequest, put("admin", """{"version":0,"rules":[{"subject":"nobody","permissions":["transcript"]}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, put("admin", """{"version":0,"rules":[{"subject":"everyone","permissions":["create"],"scope":"team"}]}""").status)
        assertEquals(HttpStatusCode.UnprocessableEntity, put("admin", """{"version":0,"rules":[{"subject":"group:unknown","permissions":["transcript"]}]}""").status)
        val saved = put("admin", """{"version":0,"rules":[{"subject":"everyone","permissions":["attach","create","message","transcript"]},{"subject":"group:reviewers","permissions":["attach","transcript"],"scope":"team"}]}""")
        assertEquals(HttpStatusCode.OK, saved.status)
        assertEquals(1, Serialization.json.decodeFromString<CloudAccessPolicyView>(saved.bodyAsText()).version)

        val reviewer = TeamCodeClient("http://localhost", { "other" }) { config -> createClient { config(this) } }
        try {
            val lease = reviewer.attachCloudSession(created.id)
            assertEquals(listOf("transcript"), lease.permissions, "A read-only group rule gives a read-only attachment")
            assertEquals(lease.id, reviewer.renewCloudAttachment(created.id, lease.id).id)
            assertEquals(403, assertFailsWith<TeamCodeException> { reviewer.sendCloudMessage(created.id, SendCloudMessageRequest("hi")) }.code.let { if (it == "cloud_permission_denied") 403 else 0 })
            reviewer.detachCloudSession(created.id, lease.id)
            assertEquals("attachment_expired", assertFailsWith<TeamCodeException> { reviewer.renewCloudAttachment(created.id, lease.id) }.code)
            assertEquals(404, assertFailsWith<TeamCodeException> { reviewer.pauseCloudSession(created.id) }.statusCode, "Policy rules never widen lifecycle routes")
        } finally { reviewer.close() }
    }

    /** The team's bucket in memory. */
    private class Bucket : me.nathanfallet.teamcode.domain.artifacts.ArtifactObjectStore {
        val objects = linkedMapOf<String, ByteArray>()
        override suspend fun put(connection: me.nathanfallet.teamcode.domain.artifacts.ArtifactStorageConnection, key: String, bytes: ByteArray, contentType: String) { objects[key] = bytes }
        override suspend fun get(connection: me.nathanfallet.teamcode.domain.artifacts.ArtifactStorageConnection, key: String, range: LongRange?, maxBytes: Int) =
            objects[key]?.let { me.nathanfallet.teamcode.domain.artifacts.StoredObject(it, it.size.toLong(), null, null) }
        override suspend fun head(connection: me.nathanfallet.teamcode.domain.artifacts.ArtifactStorageConnection, key: String) =
            objects[key]?.let { me.nathanfallet.teamcode.domain.artifacts.ObjectHead(it.size.toLong(), null) }
        override suspend fun list(connection: me.nathanfallet.teamcode.domain.artifacts.ArtifactStorageConnection, prefix: String, continuationToken: String?, maxKeys: Int) =
            me.nathanfallet.teamcode.domain.artifacts.ObjectListing(objects.filterKeys { it.startsWith(prefix) }.map { me.nathanfallet.teamcode.domain.artifacts.ListedObject(it.key, it.value.size.toLong()) }, null)
        override suspend fun delete(connection: me.nathanfallet.teamcode.domain.artifacts.ArtifactStorageConnection, keys: List<String>): List<String> { keys.forEach(objects::remove); return emptyList() }
    }

    /** The file rows in memory, with the store's key replay and unsent bound (quotas and binding against MySQL: CloudFilePersistenceTest). */
    private class Files(val conversations: Conversations) : CloudFileStore {
        val files = linkedMapOf<String, CloudFile>()
        override suspend fun now() = conversations.clock
        override suspend fun begin(file: CloudFile, expectedOwnerId: String, announcedBytes: Long): CloudFileBegin {
            files.values.find { it.sessionId == file.sessionId && it.authorId == file.authorId && file.clientKey != null && it.clientKey == file.clientKey }?.let { return CloudFileBegin.Existing(it) }
            if (files.values.count { it.sessionId == file.sessionId && it.inputId == null && it.deletingSince == null } >= CloudFileRules.MAX_UNSENT_FILES_PER_SESSION) throw CloudFileRules.quota("full")
            files[file.id] = file
            return CloudFileBegin.Started
        }
        override suspend fun complete(id: String, name: String, contentType: String, size: Long, sha256: String, requestDigest: String?) =
            files.getValue(id).copy(name = name, contentType = contentType, size = size, sha256 = sha256, requestDigest = requestDigest, completedAt = now()).also { files[id] = it }
        override suspend fun abandon(id: String) { files[id]?.let { files[id] = it.copy(deletingSince = now()) } }
        override suspend fun deliverable(sessionId: String, epoch: Int, id: String) = files[id]?.takeIf { file ->
            val input = file.inputId?.let { conversations.inputs[it] }
            file.sessionId == sessionId && file.completedAt != null && file.deletingSince == null && input?.status == CloudInputStatus.DELIVERED && input.deliveredEpoch == epoch
        }
        override suspend fun expire(limit: Int) = 0
        override suspend fun deleting(limit: Int) = files.values.filter { it.deletingSince != null }
        override suspend fun forget(ids: List<String>) { ids.forEach(files::remove) }
        override suspend fun purgeUsage(day: Long) = 0
    }

    private val connection = me.nathanfallet.teamcode.domain.artifacts.ArtifactStorageConnection("https://s3.example.test", "auto", "bucket", "prefix", true, "key", "secret",
        me.nathanfallet.teamcode.domain.artifacts.ServerSideEncryption.NONE)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(2_000) { it.toByte() }
    private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun filesFollowMessageRightsAndReachOnlyTheirExecution() = testApplication {
        val store = Sessions(listOf(admin, colleague, other))
        val conversations = Conversations().also { c -> c.sessionOf = { store.sessions[it] } }
        val files = Files(conversations)
        // The fake binds like the MySQL store: the author's own unsent files of the session.
        conversations.bind = { new -> new.fileIds.map { id ->
            val file = files.files[id]?.takeIf { it.sessionId == new.input.sessionId && it.authorId == new.input.authorId && it.inputId == null && it.completedAt != null }
                ?: throw TeamFailure("file_unavailable", 409, "unavailable")
            files.files[id] = file.copy(inputId = new.input.id)
            CloudFileRef(file.id, file.name, file.size, file.contentType!!, file.sha256!!)
        } }
        val bucket = Bucket()
        var configured = false
        val storage = object : CloudFileStorage {
            override suspend fun active(teamId: String) = connection.takeIf { configured }
            override suspend fun any(teamId: String) = connection
        }
        val access = CloudAccessService(conversations, store)
        val service = CloudConversationService(conversations, store, access)
        val sessions = CloudSessionService(store, access = access)
        val fileService = CloudFileService(files, store, service, storage, bucket)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(object : CloudAdminStore {
                override suspend fun clusters(actor: Principal) = emptyList<CloudCluster>()
                override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput) = error("unused")
                override suspend fun profiles(actor: Principal) = emptyList<CloudProfile>()
                override suspend fun member(actor: Principal, memberId: String): Member? = null
                override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) = error("unused")
                override suspend fun settings(actor: Principal) = CloudSettings()
                override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings
            }, cluster), cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, cluster)),
                cloudConversation = CloudConversation(service, access, RunnerConversationUseCase(store, conversations, sleep = { }), files = fileService), publicOrigin = "http://localhost")
        }
        val created = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Run", "profile-id", null, "opus", null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        store.sessions.clear()
        for (id in listOf("s1", "s2")) {
            store.sessions[id] = created.copy(id = id, currentEpoch = 1, generation = 1, state = CloudSessionState.READY)
            store.runs[id to 1] = CloudExecution(id, 1, "tc-$id-e1", ExecutionState.RUNNING, 1)
        }
        val features = Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features
        assertTrue("cloud.files.v1" in features)

        suspend fun upload(token: String, bytes: ByteArray, name: String? = "photo.png", key: String? = null, session: String = "s1", onBehalf: String? = null, chunked: Boolean = false) =
            client.post("/api/v1/cloud/sessions/$session/files") {
                bearerAuth(token)
                name?.let { parameter("name", it) }
                key?.let { header(CloudHeaders.IDEMPOTENCY_KEY, it) }; onBehalf?.let { header(CloudHeaders.ON_BEHALF_OF, it) }
                // Without a length the body is streamed, so the limit is enforced while reading.
                if (chunked) setBody(object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                    override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) { channel.writeFully(bytes) }
                }) else setBody(bytes)
            }
        suspend fun HttpResponse.code() = Serialization.json.parseToJsonElement(bodyAsText()).jsonObject["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content
        suspend fun HttpResponse.file() = Serialization.json.decodeFromString<CloudFileView>(bodyAsText())

        // Without a tested bucket the team cannot send files, and says so.
        val off = Serialization.json.decodeFromString<CloudFilePolicyView>(client.get("/api/v1/cloud/files/policy") { bearerAuth("colleague") }.bodyAsText())
        assertEquals(false, off.available); assertEquals("file_storage_not_configured", off.reason)
        val unconfigured = upload("colleague", png)
        assertEquals(HttpStatusCode.Conflict, unconfigured.status); assertEquals("file_storage_not_configured", unconfigured.code())
        configured = true
        val policy = Serialization.json.decodeFromString<CloudFilePolicyView>(client.get("/api/v1/cloud/files/policy") { bearerAuth("colleague-read") }.bodyAsText())
        assertEquals(CloudFilePolicyView(true, null, 20 * 1024 * 1024L, 25 * 1024 * 1024L, 5, CloudFileRules.ALLOWED_TYPES), policy)

        // The same rights as a message: the owner, a cloud:write credential, never on behalf of someone.
        assertEquals(HttpStatusCode.NotFound, upload("other", png).status, "Another member's session is invisible")
        assertEquals(HttpStatusCode.Forbidden, upload("colleague-read", png).status, "cloud:read cannot upload")
        assertEquals(HttpStatusCode.Forbidden, upload("service-colleague", png).status)
        assertEquals("on_behalf_unsupported", upload("admin", png, onBehalf = "colleague-id").code())
        assertEquals(HttpStatusCode.NotFound, upload("admin-write", png).status, "An administrator's key acts as its owner only")
        assertTrue(files.files.isEmpty() && bucket.objects.isEmpty(), "A refused upload stores nothing")

        // The type comes from the bytes, the name is cleaned and given the type's extension.
        val stored = upload("colleague", png, name = "../../etc/shadow.exe")
        assertEquals(HttpStatusCode.Created, stored.status, stored.bodyAsText())
        val photo = stored.file()
        assertEquals("shadow.exe.png", photo.name); assertEquals("image/png", photo.contentType); assertEquals(png.size.toLong(), photo.size); assertEquals(sha(png), photo.sha256)
        assertEquals(listOf("prefix/session-files/default/s1/${photo.id}/0"), bucket.objects.keys.toList())
        val text = upload("colleague", "# Notes\n\nÉté ✓\n".encodeToByteArray(), name = "C:\\Users\\me\\notes.md").file()
        assertEquals("notes.md", text.name); assertEquals("text/markdown", text.contentType)
        val exe = upload("colleague", byteArrayOf(0x4D, 0x5A, 0x00, 0x01, 0x02), name = "tool.png")
        assertEquals(HttpStatusCode.UnsupportedMediaType, exe.status); assertEquals("file_type_not_allowed", exe.code())
        assertEquals("file_type_not_allowed", upload("colleague", byteArrayOf(0x61, 0xC3.toByte(), 0x28), name = "bad.txt").code(), "Not UTF-8")
        assertEquals("empty_file", upload("colleague", ByteArray(0), name = "empty.txt").code())

        // Larger than the team allows: refused from the announced length, and while streaming without one.
        store.settings = CloudSettings(fileMaxMebibytes = 1)
        val big = ByteArray(1024 * 1024 + 1) { 'a'.code.toByte() }
        val announced = upload("colleague", big, name = "big.txt")
        assertEquals(HttpStatusCode.PayloadTooLarge, announced.status); assertEquals("file_too_large", announced.code())
        val streamed = upload("colleague", big, name = "big.txt", chunked = true)
        assertEquals(HttpStatusCode.PayloadTooLarge, streamed.status); assertEquals("file_too_large", streamed.code())
        assertTrue(files.files.values.filter { it.name == "big.txt" }.all { it.deletingSince != null }, "The parts of a refused upload are left to the sweep")
        store.settings = CloudSettings()
        fileService.sweep()
        assertTrue(bucket.objects.keys.none { key -> files.files.values.none { key.contains(it.id) } }, "The sweep removed the refused parts")

        // An idempotent retry returns the first file; other bytes under the same key are a conflict.
        val first = upload("colleague", png, name = "retry.png", key = "file-1")
        val again = upload("colleague", png, name = "retry.png", key = "file-1")
        assertEquals(HttpStatusCode.OK, again.status); assertEquals("true", again.headers[CloudHeaders.IDEMPOTENCY_REPLAYED]); assertEquals(first.file().id, again.file().id)
        assertEquals(HttpStatusCode.UnprocessableEntity, upload("colleague", png + byteArrayOf(1), name = "retry.png", key = "file-1").status)
        files.files.remove(first.file().id)

        // The upload quota answers 429 like a full message queue.
        repeat(CloudFileRules.MAX_UNSENT_FILES_PER_SESSION) { upload("colleague", png, name = "fill-$it.png", session = "s2") }
        val full = upload("colleague", png, session = "s2")
        assertEquals(HttpStatusCode.TooManyRequests, full.status); assertEquals("upload_quota", full.code())

        suspend fun send(body: String) = client.post("/api/v1/cloud/sessions/s1/messages") { bearerAuth("colleague"); contentType(ContentType.Application.Json); setBody(body) }
        assertEquals("invalid_files", send("""{"text":"six","fileIds":["a","b","c","d","e","f"]}""").code())
        assertEquals("invalid_files", send("""{"text":"twice","fileIds":["${photo.id}","${photo.id}"]}""").code())
        assertEquals("file_unavailable", send("""{"text":"","fileIds":["${files.files.values.first { it.sessionId == "s2" }.id}"]}""").code(), "Another session's file")
        val withPhoto = Serialization.json.decodeFromString<CloudInputView>(send("""{"text":"","fileIds":["${photo.id}"]}""").bodyAsText())
        assertEquals("queued", withPhoto.status, "A message with files may have no text")

        // A runner that does not download files never hands the agent the text alone: the message is rejected for it.
        val runner = TeamCodeClient("http://localhost", { "projected-pod-token-1" }) { config -> createClient { config(this) } }
        val member = TeamCodeClient("http://localhost", { "colleague" }) { config -> createClient { config(this) } }
        try {
            assertEquals(emptyList(), runner.runnerInputs(RunnerInputsRequest("default", "s1", 1, 0)).inputs)
            val rejected = member.cloudInput("s1", withPhoto.id)
            assertEquals("rejected", rejected.status); assertEquals(CloudFiles.UNSUPPORTED, rejected.reason)

            val withText = Serialization.json.decodeFromString<CloudInputView>(send("""{"text":"Read it","fileIds":["${text.id}"]}""").bodyAsText())
            assertEquals("file_unavailable", send("""{"text":"again","fileIds":["${text.id}"]}""").code(), "A file is carried by one message")
            suspend fun download(id: String, token: String = "projected-pod-token-1", session: String = "s1", epoch: Int = 1) =
                client.get("/api/v1/cloud/runner/files/$id") { bearerAuth(token); parameter("teamId", "default"); parameter("sessionId", session); parameter("epoch", epoch) }
            assertEquals(HttpStatusCode.NotFound, download(text.id).status, "Nothing to download before the message is delivered")
            val delivered = runner.runnerInputs(RunnerInputsRequest("default", "s1", 1, 0, acceptsFiles = true)).inputs.single()
            assertEquals(withText.id, delivered.id)
            assertEquals(listOf(RunnerFileView(text.id, "notes.md", text.size, "text/markdown", text.sha256)), delivered.files)
            val bytes = download(text.id)
            assertEquals(HttpStatusCode.OK, bytes.status)
            assertEquals("# Notes\n\nÉté ✓\n", bytes.bodyAsText()); assertEquals(text.sha256, bytes.headers["X-TeamCode-Sha256"]); assertEquals("attachment", bytes.headers[HttpHeaders.ContentDisposition])
            assertEquals(HttpStatusCode.Unauthorized, download(text.id, token = "nope").status)
            assertEquals(HttpStatusCode.NotFound, download(text.id, token = "projected-pod-token-2", session = "s2").status, "Another session's runner cannot download it")
            assertEquals(HttpStatusCode.NotFound, download(photo.id).status, "A file of a rejected message is gone")
            // An execution that is no longer current is fenced, whatever file it asks for.
            store.sessions["s1"] = store.sessions.getValue("s1").copy(currentEpoch = 2)
            store.runs["s1" to 2] = CloudExecution("s1", 2, "tc-s1-e2", ExecutionState.RUNNING, 1)
            val fenced = download(text.id)
            assertEquals(HttpStatusCode.Conflict, fenced.status); assertEquals("execution_fenced", fenced.code())
            store.sessions["s1"] = store.sessions.getValue("s1").copy(currentEpoch = 1)
            // Once the host acknowledges the message, its files are released (here by the fake store's acknowledgement: CloudFilePersistenceTest).
        } finally { runner.close(); member.close() }
    }

    /**
     * An agent that holds its owner's GitHub token follows the owner's instructions only (audit M2): a cloud
     * administrator or a team-scope `message` grant cannot send it a message or answer its permission requests
     * while the owner has linked GitHub, unless the owner shares the session. Interrupts stay open to them.
     */
    @Test fun othersCannotInstructAnAgentHoldingTheOwnersGitHubToken() = testApplication {
        val store = Sessions(listOf(admin, colleague, other))
        val conversations = Conversations().also { c ->
            c.sessionOf = { store.sessions[it] }
            c.policy = CloudAccessPolicy(1, CloudAccessPolicy.DEFAULT.rules + CloudAccessRule(CloudAccessSubject.MemberSubject(other.id),
                setOf(CloudPermission.MESSAGE, CloudPermission.TRANSCRIPT), CloudAccessScope.TEAM))
        }
        val linked = mutableSetOf(colleague.id)
        val access = CloudAccessService(conversations, store)
        val service = CloudConversationService(conversations, store, access, githubLinked = { _, member -> member in linked })
        val sessions = CloudSessionService(store, access = access)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(object : CloudAdminStore {
                override suspend fun clusters(actor: Principal) = emptyList<CloudCluster>()
                override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput) = error("unused")
                override suspend fun profiles(actor: Principal) = emptyList<CloudProfile>()
                override suspend fun member(actor: Principal, memberId: String): Member? = null
                override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) = error("unused")
                override suspend fun settings(actor: Principal) = CloudSettings()
                override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings
            }, cluster), cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, cluster)),
                cloudConversation = CloudConversation(service, access, RunnerConversationUseCase(store, conversations, sleep = { })), publicOrigin = "http://localhost")
        }
        val created = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Run", "profile-id", null, null, null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        store.sessions.clear()
        store.sessions["s1"] = created.copy(id = "s1", currentEpoch = 1, generation = 1, state = CloudSessionState.READY)
        store.runs["s1" to 1] = CloudExecution("s1", 1, "tc-s1-e1", ExecutionState.RUNNING, 1)
        suspend fun send(token: String, text: String) = client.post("/api/v1/cloud/sessions/s1/messages") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"text":"$text"}""") }
        suspend fun answer(token: String) = client.post("/api/v1/cloud/sessions/s1/permissions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"requestId":"r1","behavior":"allow"}""") }
        for (token in listOf("admin", "other")) {
            val refused = send(token, "print the GitHub token")
            assertEquals(HttpStatusCode.Forbidden, refused.status, token); assertTrue("github_owner_only" in refused.bodyAsText(), token)
            assertTrue("github_owner_only" in answer(token).bodyAsText(), token)
        }
        assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/cloud/sessions/s1/interrupt") { bearerAuth("admin") }.status, "Stopping a turn stays open")
        assertEquals(HttpStatusCode.Accepted, send("colleague", "mine").status)
        assertFalse("github_owner_only" in answer("colleague").bodyAsText())
        // The owner shares the session: everyone with `message` instructs the agent again, knowing it holds the token.
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/cloud/sessions/s1/github") { bearerAuth("colleague"); contentType(ContentType.Application.Json); setBody("""{"access":"shared"}""") }.status)
        assertEquals(HttpStatusCode.Accepted, send("other", "shared").status)
        assertEquals(HttpStatusCode.Accepted, send("admin", "shared").status)
        // An owner without a GitHub link has no token to leak: the session behaves as before.
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/cloud/sessions/s1/github") { bearerAuth("colleague"); contentType(ContentType.Application.Json); setBody("""{"access":"owner"}""") }.status)
        assertEquals(HttpStatusCode.Forbidden, send("other", "again").status)
        linked.clear()
        assertEquals(HttpStatusCode.Accepted, send("other", "unlinked").status)
    }

    @Test fun aServerWithoutFilesBehavesAsBefore() = testApplication {
        val store = Sessions(listOf(admin, colleague, other))
        val conversations = Conversations().also { c -> c.sessionOf = { store.sessions[it] } }
        val access = CloudAccessService(conversations, store)
        val service = CloudConversationService(conversations, store, access)
        val sessions = CloudSessionService(store, access = access)
        application {
            configureGateway(gateway, team = team(), cloudSessions = sessions, cloudAdmin = CloudAdministrationService(object : CloudAdminStore {
                override suspend fun clusters(actor: Principal) = emptyList<CloudCluster>()
                override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput) = error("unused")
                override suspend fun profiles(actor: Principal) = emptyList<CloudProfile>()
                override suspend fun member(actor: Principal, memberId: String): Member? = null
                override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) = error("unused")
                override suspend fun settings(actor: Principal) = CloudSettings()
                override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings
            }, cluster), cloudRunner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, cluster)),
                cloudConversation = CloudConversation(service, access, RunnerConversationUseCase(store, conversations, sleep = { })), publicOrigin = "http://localhost")
        }
        val created = (sessions.create(Principal(colleague, "colleague-session", "default"), null, CloudSessionDraft("Run", "profile-id", null, "opus", null, emptyMap(), null), null) as CloudCreateOutcome.Created).session
        store.sessions.clear()
        store.sessions["s1"] = created.copy(id = "s1", currentEpoch = 1, generation = 1, state = CloudSessionState.READY)
        store.runs["s1" to 1] = CloudExecution("s1", 1, "tc-s1-e1", ExecutionState.RUNNING, 1)
        val features = Serialization.json.decodeFromString<Capabilities>(client.get("/api/v1/capabilities") { bearerAuth("colleague") }.bodyAsText()).features
        assertFalse("cloud.files.v1" in features); assertTrue("cloud.messages.v1" in features)
        assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/cloud/sessions/s1/files") { bearerAuth("colleague"); setBody(png) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cloud/files/policy") { bearerAuth("colleague") }.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/cloud/runner/files/f1") { bearerAuth("projected-pod-token-1"); parameter("teamId", "default"); parameter("sessionId", "s1"); parameter("epoch", 1) }.status)
        suspend fun send(body: String) = client.post("/api/v1/cloud/sessions/s1/messages") { bearerAuth("colleague"); contentType(ContentType.Application.Json); setBody(body) }
        val named = send("""{"text":"hi","fileIds":["f1"]}""")
        assertEquals(HttpStatusCode.BadRequest, named.status); assertTrue("files_unsupported" in named.bodyAsText(), "Files are never dropped silently")
        assertEquals(HttpStatusCode.BadRequest, send("""{"text":""}""").status, "An empty text still needs files")
        assertEquals(HttpStatusCode.Accepted, send("""{"text":"hi"}""").status)
        // A runner that announces file support on this server still gets plain messages, exactly as before.
        val runner = TeamCodeClient("http://localhost", { "projected-pod-token-1" }) { config -> createClient { config(this) } }
        try {
            val delivered = runner.runnerInputs(RunnerInputsRequest("default", "s1", 1, 0, acceptsFiles = true)).inputs.single()
            assertEquals(RunnerInputView(delivered.id, "message", "hi", 1), delivered)
        } finally { runner.close() }
    }
}
