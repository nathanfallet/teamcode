package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.automations.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.automations.RunPage
import kotlin.test.*

/** The automation routes decode, authorize by credential and encode; the rules are the domain's (AutomationService). */
class AutomationRoutesTest {
    private class Store : AutomationStore {
        val automations = linkedMapOf<String, Automation>()
        val secrets = mutableMapOf<String, String?>()
        val runs = linkedMapOf<String, Pair<String, AutomationRun>>()
        override suspend fun now() = 1_800_000_000_000L
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String) = Member(memberId, memberId, Role.MEMBER, false, false)
        override suspend fun create(actor: Principal, automation: Automation, sealedSecret: String?, limit: Int) = automation.also { automations[it.id] = it; secrets[it.id] = sealedSecret }
        override suspend fun update(actor: Principal, id: String, expectedVersion: Int, definition: AutomationDefinition, nextRunAt: Long?) =
            automations.getValue(id).copy(definition = definition, version = expectedVersion + 1).also { automations[id] = it }
        override suspend fun setEnabled(actor: Principal, id: String, enabled: Boolean, nextRunAt: Long?) =
            automations.getValue(id).copy(enabled = enabled, nextRunAt = nextRunAt, disabledReason = if (enabled) null else "disabled").also { automations[id] = it }
        override suspend fun rotateSecret(actor: Principal, id: String, sealedSecret: String, previousValidUntil: Long) = automations.getValue(id)
        override suspend fun delete(actor: Principal, id: String) { automations.remove(id) }
        override suspend fun find(teamId: String, id: String) = automations[id]
        override suspend fun list(teamId: String, owner: String?, before: String?, limit: Int) = AutomationPage(automations.values.filter { owner == null || it.ownerId == owner }, null)
        override suspend fun count(teamId: String, owner: String) = automations.size
        override suspend fun forTrigger(id: String) = automations[id]?.let { it to SealedSecrets(secrets[id], null, null) }
        override suspend fun runs(teamId: String, automationId: String, query: RunQuery) = RunPage(runs.values.map { it.second }.filter { it.automationId == automationId }, null)
        override suspend fun run(teamId: String, automationId: String, runId: String) = runs.values.map { it.second }.firstOrNull { it.id == runId }
        override suspend fun enqueue(actor: Principal?, automationId: String, run: NewRun): RunOutcome {
            runs[run.deliveryKey]?.let { return RunOutcome.Replayed(it.second) }
            val automation = automations.getValue(automationId)
            val created = AutomationRun(run.id, automationId, automation.teamId, automation.ownerId, automation.version, run.source, run.status, run.reason, null, null,
                run.scheduledFor, now(), null, null, false, run.initiatorId, run.variables)
            runs[run.deliveryKey] = run.deliveryKey to created
            return RunOutcome.Created(created)
        }
        override suspend fun auditPreview(actor: Principal, automationId: String) = Unit
        override suspend fun wouldSkip(automationId: String): String? = null
        override suspend fun requestCancel(actor: Principal, automationId: String, runId: String) = run("default", automationId, runId)!!.copy(status = RunStatus.CANCELLED)
        override suspend fun dueCron(limit: Int) = emptyList<Automation>()
        override suspend fun advance(automationId: String, expected: Long, next: Long?, run: NewRun?) = false
        override suspend fun claimQueued(limit: Int, leaseMillis: Long) = emptyList<DispatchableRun>()
        override suspend fun working(teamId: String, ownerId: String) = 0
        override suspend fun ownerPrincipal(automationId: String): Principal? = null
        override suspend fun retry(runId: String, at: Long) = Unit
        override suspend fun started(runId: String, sessionId: String, profileVersion: Int) = Unit
        override suspend fun running(runId: String) = Unit
        override suspend fun finish(runId: String, status: RunStatus, reason: String?) = Unit
        override suspend fun disableBySystem(automationId: String, reason: String) = Unit
        override suspend fun active(limit: Int) = emptyList<ActiveRun>()
        override suspend fun progress(sessionIds: List<String>) = emptyMap<String, SessionProgress>()
        override suspend fun releasable(automationId: String, keep: Int) = emptyList<String>()
        override suspend fun purge(limit: Int) = 0
    }
    private val directory = object : AutomationDirectory {
        override suspend fun profile(teamId: String, profileId: String) =
            CloudProfile("p1", teamId, "dev", 4, CloudProfileSpec("c1", "ns", "image"), setOf("admin-id", "member-id"), 0, 0).takeIf { profileId == "p1" }
        override suspend fun settings(teamId: String) = CloudSettings()
    }
    private val sealer = object : SecretSealer {
        override fun seal(plaintext: ByteArray, context: String) = "$context|" + plaintext.decodeToString()
        override fun open(envelope: String, context: String) = envelope.removePrefix("$context|").takeIf { envelope.startsWith("$context|") }?.encodeToByteArray()
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "reader" -> interactive.authenticate("admin")!!.copy(member = Member("admin-id", "admin", Role.MEMBER, false, false), tokenId = "reader", serviceScopes = setOf(ServiceScope.CLOUD_READ))
                "writer" -> interactive.authenticate("admin")!!.copy(member = Member("admin-id", "admin", Role.MEMBER, false, false), tokenId = "writer", serviceScopes = setOf(ServiceScope.CLOUD_WRITE))
                else -> interactive.authenticate(token)
            }
        }
    }
    private val webhook = """{"automation":{"name":"Ticket","trigger":{"kind":"webhook"},"action":{"kind":"cloud","profileId":"p1"},"prompt":"Fix {{ticket}}",
        |"variables":[{"name":"ticket","path":"$.ticket","type":"int"}],"overlap":"allow"}}""".trimMargin()

    @Test fun anOwnerCreatesArmsTestsAndReadsTheHistoryOfAWebhookAutomation() = testApplication {
        val store = Store()
        application { configureGateway(gateway, team = team(), automations = AutomationService(store, directory, sealer)) }
        suspend fun call(method: HttpMethod, path: String, token: String?, json: String? = null, headers: Map<String, String> = emptyMap()) = client.request(path) {
            this.method = method; if (token != null) bearerAuth(token); headers.forEach { (k, v) -> header(k, v) }
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        assertTrue(Automations.CAPABILITY in Serialization.json.decodeFromString<Capabilities>(call(HttpMethod.Get, "/api/v1/capabilities", "admin").bodyAsText()).features)

        val created = call(HttpMethod.Post, "/api/v1/automations", "admin", webhook)
        assertEquals(HttpStatusCode.Created, created.status)
        val view = Serialization.json.decodeFromString<CreatedAutomationView>(created.bodyAsText())
        val id = view.automation.id
        assertTrue(view.secret!!.startsWith("whsec_")); assertFalse(view.automation.enabled)
        assertEquals("/api/v1/automations/$id/v/1/trigger", view.automation.webhook!!.versionedPath)
        assertNull(Serialization.json.decodeFromString<AutomationView>(call(HttpMethod.Get, "/api/v1/automations/$id", "admin").bodyAsText()).webhook!!.previousSecretValidUntil)
        assertFalse(call(HttpMethod.Get, "/api/v1/automations/$id", "admin").bodyAsText().contains(view.secret!!), "The secret is shown once")
        assertTrue(call(HttpMethod.Post, "/api/v1/automations/$id/enable", "admin").let { it.status == HttpStatusCode.OK })

        // A dry run renders; a real test needs its Idempotency-Key and replays with it.
        val dry = call(HttpMethod.Post, "/api/v1/automations/$id/run", "admin", """{"payload":{"ticket":7},"dryRun":true}""")
        assertEquals("Fix 7", Serialization.json.decodeFromString<AutomationPreviewView>(dry.bodyAsText()).renderedPrompt)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/automations/$id/run", "admin", """{"payload":{"ticket":7}}""").status)
        val real = call(HttpMethod.Post, "/api/v1/automations/$id/run", "admin", """{"payload":{"ticket":7}}""", mapOf("Idempotency-Key" to "k1"))
        assertEquals(HttpStatusCode.Accepted, real.status)
        assertEquals("test", Serialization.json.decodeFromString<AutomationRunView>(real.bodyAsText()).source)
        assertEquals("true", call(HttpMethod.Post, "/api/v1/automations/$id/run", "admin", """{"payload":{"ticket":7}}""", mapOf("Idempotency-Key" to "k1")).headers["Idempotency-Replayed"])
        val history = Serialization.json.decodeFromString<AutomationRunPageView>(call(HttpMethod.Get, "/api/v1/automations/$id/runs?source=test", "admin").bodyAsText())
        assertEquals(1, history.runs.size)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/api/v1/automations/$id/runs?status=done", "admin").status)
        assertEquals("invalid_automation", Serialization.json.decodeFromString<ApiError>(call(HttpMethod.Post, "/api/v1/automations", "admin",
            webhook.replace("\"allow\"", "\"sometimes\"")).bodyAsText()).error.type)
    }

    @Test fun theWebhookNeedsNoLoginButItsProofAndKeysAreLimitedToTheirScope() = testApplication {
        val store = Store()
        val service = AutomationService(store, directory, sealer)
        application { configureGateway(gateway, team = team(), automations = service) }
        suspend fun call(method: HttpMethod, path: String, token: String?, json: String? = null, headers: Map<String, String> = emptyMap()) = client.request(path) {
            this.method = method; if (token != null) bearerAuth(token); headers.forEach { (k, v) -> header(k, v) }
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        val created = Serialization.json.decodeFromString<CreatedAutomationView>(call(HttpMethod.Post, "/api/v1/automations", "admin", webhook).bodyAsText())
        val id = created.automation.id; val secret = created.secret!!
        call(HttpMethod.Post, "/api/v1/automations/$id/enable", "admin")

        val body = """{"ticket":42}"""
        val seconds = (store.now() / 1000).toString()
        val signed = mapOf(AutomationHeaders.TIMESTAMP to seconds, AutomationHeaders.SIGNATURE to AutomationRules.signature(secret, seconds, body.encodeToByteArray()), "TeamCode-Delivery" to "d1")
        val accepted = call(HttpMethod.Post, "/api/v1/automations/$id/trigger", null, body, signed)
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertEquals("queued", Serialization.json.decodeFromString<AutomationTriggerAcceptedView>(accepted.bodyAsText()).status)
        assertEquals("true", call(HttpMethod.Post, "/api/v1/automations/$id/v/latest/trigger", null, body, signed).headers["Idempotency-Replayed"])
        assertEquals(HttpStatusCode.Accepted, call(HttpMethod.Post, "/api/v1/automations/$id/v/1/trigger/$secret", null, body, mapOf("TeamCode-Delivery" to "d2")).status)
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Post, "/api/v1/automations/$id/v/2/trigger/$secret", null, body).status, "Another version")
        for (refused in listOf(
            call(HttpMethod.Post, "/api/v1/automations/$id/trigger", null, body),
            call(HttpMethod.Post, "/api/v1/automations/$id/trigger/whsec_wrongwrongwrongwrongwrong", null, body),
            call(HttpMethod.Post, "/api/v1/automations/unknown/trigger", null, body, signed),
        )) {
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
            assertEquals("invalid_signature", Serialization.json.decodeFromString<ApiError>(refused.bodyAsText()).error.type)
        }
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/automations/$id/trigger", null).status, "Only POST is exempt from login")
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/automations", null).status)

        // A `cloud:read` key reads and dry-runs; only a `cloud:write` key really tests; definitions stay interactive.
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/automations/$id/runs", "reader").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/v1/automations/$id/run", "reader", """{"payload":{"ticket":1},"dryRun":true}""").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/automations/$id/run", "reader", """{"payload":{"ticket":1}}""", mapOf("Idempotency-Key" to "r")).status)
        assertEquals(HttpStatusCode.Accepted, call(HttpMethod.Post, "/api/v1/automations/$id/run", "writer", """{"payload":{"ticket":1}}""", mapOf("Idempotency-Key" to "w")).status)
        for (path in listOf("/api/v1/automations", "/api/v1/automations/$id/enable", "/api/v1/automations/$id/webhook/rotate-secret"))
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, path, "writer", webhook).status, "$path is interactive")
    }

    @Test fun theClassifierOpensOnlyTheReadsTestsAndCancelToKeys() {
        val id = "00000000-0000-4000-8000-000000000000"
        assertEquals(CredentialAction.CLOUD_READ, credentialAction("GET", "/api/v1/automations/$id/runs/$id", emptySet()))
        assertEquals(CredentialAction.CLOUD_READ, credentialAction("POST", "/api/v1/automations/preview", emptySet()))
        assertEquals(CredentialAction.CLOUD_WRITE, credentialAction("POST", "/api/v1/automations/$id/runs/$id/cancel", emptySet()))
        for (path in listOf("/api/v1/automations", "/api/v1/automations/$id/enable", "/api/v1/automations/$id/disable", "/api/v1/automations/$id/webhook/rotate-secret"))
            assertEquals(CredentialAction.INTERACTIVE, credentialAction("POST", path, emptySet()), path)
        assertTrue(automationTriggerPath.matches("/api/v1/automations/$id/v/12/trigger/whsec_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"))
        for (path in listOf("/api/v1/automations/$id/trigger/../../members", "/api/v1/automations/$id/v/0/trigger", "/api/v1/automations/$id/run"))
            assertFalse(automationTriggerPath.matches(path), path)
    }
}
