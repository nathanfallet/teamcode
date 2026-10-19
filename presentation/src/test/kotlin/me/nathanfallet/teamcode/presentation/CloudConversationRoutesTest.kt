package me.nathanfallet.teamcode.presentation

import io.ktor.client.HttpClient
import io.ktor.client.plugins.defaultRequest
import io.ktor.server.engine.embeddedServer
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
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
        override suspend fun settings(teamId: String) = CloudSettings()
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
        override suspend fun insertInput(new: NewInput, pendingLimit: Int): InputOutcome {
            val input = new.input
            inputs.values.find { it.sessionId == input.sessionId && it.authorId == input.authorId && it.clientKey != null && it.clientKey == input.clientKey }?.let {
                if (it.requestDigest != input.requestDigest) throw TeamFailure("idempotency_conflict", 422, "conflict")
                return InputOutcome.Replayed(it)
            }
            inputs[input.id] = input
            return InputOutcome.Accepted(input)
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
            input.copy(status = if (ack.consumed) CloudInputStatus.CONSUMED else CloudInputStatus.REJECTED, eventSeq = seq).also { inputs[it.id] = it }
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
            "service-colleague" -> Principal(colleague, "colleague-key", "default", ServiceScope.entries.toSet())
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
            if (token == "projected-pod-token-1") TokenIdentity("teamcode", "default", "tc-s1-e1", "uid-1") else null
    }

    /** A real server: the test engine buffers a streamed response until it completes, which an endless stream never does. */
    @Test fun transcriptMessagesStreamAndRunnerAgentChannel() = runBlocking {
        val store = Sessions(listOf(admin, colleague, other))
        val conversations = Conversations()
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
            assertTrue(listOf("cloud.transcript.v1", "cloud.messages.v1", "cloud.attach.v1", "cloud.access.v1", "cloud.sessions.initial-message.v1").all { it in features })

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
            assertEquals(HttpStatusCode.Forbidden, send("service-colleague", """{"text":"hi"}""").status, "Service keys never reach cloud routes")
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
                assertEquals(RunnerAgentView("claude", null, "opus", null, "none"), runner.runnerAgent(RunnerAgentRequest("default", "s1", 1)))
                val delivered = runner.runnerInputs(RunnerInputsRequest("default", "s1", 1, 0)).inputs.single()
                assertEquals(ack.id, delivered.id); assertEquals("Fix the retry", delivered.text)
                runner.runnerAcknowledge(RunnerInputAckRequest("default", "s1", 1, listOf(RunnerInputAckView(ack.id, "consumed"))))
                assertEquals("consumed", member.cloudInput("s1", ack.id).status)
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
            } finally { runner.close(); member.close() }
        } finally { client.close(); server.stop(0, 1_000) }
    }

    @Test fun attachmentsAndPolicyFollowMemberAndGroupRules() = testApplication {
        val store = Sessions(listOf(admin, colleague, other))
        val conversations = Conversations()
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
}
