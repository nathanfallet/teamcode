package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.waits.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*

/** The wait routes decode, authorize by credential and encode; the rules are the domain's (WaitService). */
class WaitRoutesTest {
    private class Store : WaitStore {
        val waits = linkedMapOf<String, Wait>()
        val keys = mutableMapOf<String, String>()
        override suspend fun now() = 1_800_000_000_000L
        /** Token `exec-token` stands for a session execution bound to s1. */
        override suspend fun executionSession(tokenId: String) = "s1".takeIf { tokenId == "exec-token" }
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String) = Member(memberId, memberId, Role.MEMBER, false, false)
        override suspend fun session(teamId: String, sessionId: String) = WaitSession(sessionId, teamId, "admin-id", "Fix the login", "claude").takeIf { sessionId == "s1" }
        override suspend fun create(actor: Principal, wait: Wait, idempotencyKey: String?, limit: Int): WaitChange {
            idempotencyKey?.let { key -> keys[key]?.let { return WaitChange(waits.getValue(it), false) } }
            waits[wait.id] = wait; idempotencyKey?.let { keys[it] = wait.id }
            return WaitChange(wait, true)
        }
        override suspend fun find(teamId: String, id: String) = waits[id]
        override suspend fun list(teamId: String, query: WaitQuery) = WaitPage(waits.values.filter { (query.assignee == null || it.assigneeId == query.assignee) && (query.status == null || it.status == query.status) && (query.resolvers == null || it.resolver in query.resolvers!!) }, null)
        override suspend fun eventTarget(id: String) = waits[id]?.takeIf { it.resolver == WaitResolver.EVENT }?.let { EventTarget(it.id, it.timer?.tokenHash, it.status == WaitStatus.OPEN) }
        override suspend fun dueWaits(limit: Int) = emptyList<DueWait>()
        val woken = mutableListOf<String>()
        override suspend fun resolve(id: String, cause: ResolveCause, resolution: WaitResolution, openTurnFailed: Boolean, wake: Boolean): WaitChange? {
            val wait = waits[id] ?: return null
            if (wait.status != WaitStatus.OPEN) return WaitChange(wait, false)
            woken += id
            return WaitChange(wait.copy(status = WaitStatus.RESOLVED, answer = WaitAnswer(resolution.action, resolution.values, "", resolution.at), resolvedAt = resolution.at).also { waits[id] = it }, true)
        }
        override suspend fun pendingWakes(limit: Int) = emptyList<Wait>()
        override suspend fun wakeDelivered(id: String) = Unit
        val kept = mutableListOf<WaitSecret>()
        override suspend fun serveSecrets(teamId: String, sessionId: String, ownerId: String) = ServedSecrets(kept.associate { it.envName to it.value })
        override suspend fun purgeSecrets(limit: Int) = 0
        override suspend fun answer(actor: Principal, id: String, answer: WaitAnswer, secrets: List<WaitSecret>): WaitChange {
            val wait = waits.getValue(id)
            if (wait.status != WaitStatus.OPEN) return WaitChange(wait, false)
            kept += secrets
            return WaitChange(wait.copy(status = WaitStatus.RESOLVED, answer = answer, resolvedAt = answer.answeredAt).also { waits[id] = it }, true)
        }
        override suspend fun cancel(actor: Principal, id: String, at: Long): WaitChange {
            val wait = waits.getValue(id)
            if (wait.status != WaitStatus.OPEN) return WaitChange(wait, false)
            return WaitChange(wait.copy(status = WaitStatus.CANCELLED, resolvedAt = at).also { waits[id] = it }, true)
        }
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
                // An execution credential as the runner holds one (`tcex_`): inference and waits:session, bound to s1 by the store.
                "exec-token" -> interactive.authenticate("admin")!!.copy(member = Member("admin-id", "admin", Role.MEMBER, false, false), tokenId = "exec-token", serviceScopes = setOf(ServiceScope.INFERENCE, ServiceScope.WAITS_SESSION))
                "exec-unbound" -> interactive.authenticate("admin")!!.copy(member = Member("admin-id", "admin", Role.MEMBER, false, false), tokenId = "exec-unbound", serviceScopes = setOf(ServiceScope.WAITS_SESSION))
                "exec-old" -> interactive.authenticate("admin")!!.copy(member = Member("admin-id", "admin", Role.MEMBER, false, false), tokenId = "exec-old", serviceScopes = setOf(ServiceScope.INFERENCE, ServiceScope.ARTIFACTS_WRITE))
                else -> interactive.authenticate(token)
            }
        }
    }
    private val review = """{"wait":{"sessionId":"s1","title":"Review the plan","refs":[{"kind":"artifact","id":"a1"}],
        |"fields":[{"id":"feedback","type":"text","label":"Feedback"}],
        |"actions":[{"id":"approve","label":"Approve"},{"id":"request_changes","label":"Request changes","requires":["feedback"]}]}}""".trimMargin()

    @Test fun aWaitIsAskedListedAnsweredOnceAndNeverAgain() = testApplication {
        application { configureGateway(gateway, team = team(), waits = WaitService(Store())) }
        suspend fun call(method: HttpMethod, path: String, token: String?, json: String? = null, headers: Map<String, String> = emptyMap()) = client.request(path) {
            this.method = method; if (token != null) bearerAuth(token); headers.forEach { (k, v) -> header(k, v) }
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        assertTrue(Waits.CAPABILITY in Serialization.json.decodeFromString<Capabilities>(call(HttpMethod.Get, "/api/v1/capabilities", "admin").bodyAsText()).features)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/waits", null).status)

        val created = call(HttpMethod.Post, "/api/v1/waits", "admin", review, mapOf("Idempotency-Key" to "k1"))
        assertEquals(HttpStatusCode.Created, created.status)
        val wait = Serialization.json.decodeFromString<WaitView>(created.bodyAsText())
        assertEquals("human_request", wait.kind); assertEquals("open", wait.status); assertEquals("admin-id", wait.assigneeId); assertEquals("Fix the login", wait.sessionName)
        val replay = call(HttpMethod.Post, "/api/v1/waits", "admin", review, mapOf("Idempotency-Key" to "k1"))
        assertEquals(HttpStatusCode.OK, replay.status); assertEquals("true", replay.headers["Idempotency-Replayed"])

        val listed = Serialization.json.decodeFromString<WaitPageView>(call(HttpMethod.Get, "/api/v1/waits", "admin").bodyAsText())
        assertEquals(listOf(wait.id), listed.waits.map { it.id })
        assertEquals(wait.id, Serialization.json.decodeFromString<WaitView>(call(HttpMethod.Get, "/api/v1/waits/${wait.id}", "admin").bodyAsText()).id)

        assertEquals("invalid_answer", Serialization.json.decodeFromString<ApiError>(call(HttpMethod.Post, "/api/v1/waits/${wait.id}/answer", "admin", """{"action":"request_changes"}""").bodyAsText()).error.type)
        val answered = call(HttpMethod.Post, "/api/v1/waits/${wait.id}/answer", "admin", """{"action":"request_changes","values":{"feedback":"Split step 2"}}""")
        assertEquals(HttpStatusCode.OK, answered.status)
        val done = Serialization.json.decodeFromString<WaitView>(answered.bodyAsText())
        assertEquals("resolved", done.status); assertEquals("request_changes", done.answer!!.action)
        val again = call(HttpMethod.Post, "/api/v1/waits/${wait.id}/answer", "admin", """{"action":"approve"}""")
        assertEquals(HttpStatusCode.Conflict, again.status); assertEquals("wait_closed", Serialization.json.decodeFromString<ApiError>(again.bodyAsText()).error.type)
        assertEquals(0, Serialization.json.decodeFromString<WaitPageView>(call(HttpMethod.Get, "/api/v1/waits", "admin").bodyAsText()).waits.size, "open waits only by default")
        assertEquals(1, Serialization.json.decodeFromString<WaitPageView>(call(HttpMethod.Get, "/api/v1/waits?status=all", "admin").bodyAsText()).waits.size)
    }

    @Test fun badInputIsRefusedWithItsCodeAndKeysAreLimitedToTheirScope() = testApplication {
        application { configureGateway(gateway, team = team(), waits = WaitService(Store())) }
        suspend fun call(method: HttpMethod, path: String, token: String, json: String? = null) = client.request(path) {
            this.method = method; bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type
        assertEquals("invalid_wait", code(call(HttpMethod.Post, "/api/v1/waits", "admin", review.replace("\"text\"", "\"slider\""))))
        assertEquals("invalid_wait", code(call(HttpMethod.Post, "/api/v1/waits", "admin", review.replace("\"text\"", "\"secret\"").replace("\"label\":\"Feedback\"", "\"label\":\"Feedback\",\"default\":\"x\""))), "a secret has no default")
        assertEquals("not_found", code(call(HttpMethod.Post, "/api/v1/waits", "admin", review.replace("\"s1\"", "\"s2\""))))
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/waits/not%20an%20id", "admin").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/api/v1/waits?status=pending", "admin").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/api/v1/waits?limit=500", "admin").status)

        val id = Serialization.json.decodeFromString<WaitView>(call(HttpMethod.Post, "/api/v1/waits", "writer", review).bodyAsText()).id
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/waits", "reader").status, "a cloud:read key lists")
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/waits", "reader", review).status, "a cloud:read key cannot ask")
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/waits/$id/answer", "reader", """{"action":"approve"}""").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/waits/$id/cancel", "reader").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/v1/waits/$id/cancel", "writer").status)
    }

    @Test fun anExecutionCredentialAsksAboutItsOwnSessionAndNeverAnswers() = testApplication {
        application { configureGateway(gateway, team = team(), waits = WaitService(Store())) }
        suspend fun call(method: HttpMethod, path: String, token: String, json: String? = null) = client.request(path) {
            this.method = method; bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type
        val created = call(HttpMethod.Post, "/api/v1/waits", "exec-token", review)
        assertEquals(HttpStatusCode.Created, created.status)
        val id = Serialization.json.decodeFromString<WaitView>(created.bodyAsText()).id
        assertEquals(listOf(id), Serialization.json.decodeFromString<WaitPageView>(call(HttpMethod.Get, "/api/v1/waits?session=s1", "exec-token").bodyAsText()).waits.map { it.id })
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/waits/$id", "exec-token").status)
        assertEquals("credential_scope", code(call(HttpMethod.Post, "/api/v1/waits/$id/answer", "exec-token", """{"action":"approve"}""")))
        assertEquals("not_found", code(call(HttpMethod.Post, "/api/v1/waits", "exec-token", review.replace("\"s1\"", "\"s2\""))))
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/v1/waits/$id/cancel", "exec-token").status)
        // The scope without a session, and the scopes executions had before: nothing.
        assertEquals("credential_scope", code(call(HttpMethod.Get, "/api/v1/waits", "exec-unbound")))
        assertEquals("credential_scope", code(call(HttpMethod.Get, "/api/v1/waits", "exec-old")))
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/api/v1/waits?session=not%20an%20id", "admin").status)
    }

    @Test fun aTimeAndAnEventWaitAreAskedListedOnRequestAndTheEventAddressNeedsNoLogin() = testApplication {
        val store = Store()
        application { configureGateway(gateway, team = team(), waits = WaitService(store), publicOrigin = "https://teamcode.example") }
        suspend fun call(method: HttpMethod, path: String, token: String?, json: String? = null, headers: Map<String, String> = emptyMap()) = client.request(path) {
            this.method = method; if (token != null) bearerAuth(token); headers.forEach { (k, v) -> header(k, v) }
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type
        fun view(text: String) = Serialization.json.decodeFromString<WaitView>(text)

        val time = call(HttpMethod.Post, "/api/v1/waits", "admin", """{"wait":{"sessionId":"s1","title":"Resume at 14:30","resolver":"time","after":120,"resumeMessage":"Check CI"}}""")
        assertEquals(HttpStatusCode.Created, time.status)
        val timed = view(time.bodyAsText())
        assertEquals("time", timed.resolver); assertEquals("timed", timed.kind); assertEquals(1_800_000_000_000L + 120_000, timed.dueAt); assertNull(timed.eventUrl); assertEquals("Check CI", timed.resumeMessage)
        val iso = view(call(HttpMethod.Post, "/api/v1/waits", "admin", """{"wait":{"sessionId":"s1","title":"Later","resolver":"time","at":"2027-01-15T08:20:00Z"}}""").bodyAsText())
        assertEquals(java.time.Instant.parse("2027-01-15T08:20:00Z").toEpochMilli(), iso.dueAt)
        val millis = view(call(HttpMethod.Post, "/api/v1/waits", "admin", """{"wait":{"sessionId":"s1","title":"Later","resolver":"time","at":1800000300000}}""").bodyAsText())
        assertEquals(1_800_000_300_000L, millis.dueAt)
        assertEquals("invalid_wait", code(call(HttpMethod.Post, "/api/v1/waits", "admin", """{"wait":{"sessionId":"s1","title":"x","resolver":"time","at":"tomorrow"}}""")))
        assertEquals("invalid_wait", code(call(HttpMethod.Post, "/api/v1/waits", "admin", """{"wait":{"sessionId":"s1","title":"x","resolver":"time","after":5}}""")))
        assertEquals("invalid_wait", code(call(HttpMethod.Post, "/api/v1/waits", "admin", """{"wait":{"sessionId":"s1","title":"x","resolver":"event","source":"carrier-pigeon"}}""")))

        val event = call(HttpMethod.Post, "/api/v1/waits", "admin", """{"wait":{"sessionId":"s1","title":"CI of the PR","resolver":"event","source":"webhook","timeoutSeconds":3600,"onTimeout":"turn_failed"}}""")
        val ci = view(event.bodyAsText())
        val url = assertNotNull(ci.eventUrl)
        assertTrue(url.startsWith("https://teamcode.example/api/v1/waits/${ci.id}/event?token=wev_"), url)
        assertEquals(1_800_000_000_000L + 3_600_000, ci.timeoutAt); assertEquals("turn_failed", ci.onTimeout); assertEquals("webhook", ci.eventSource)
        assertNull(view(call(HttpMethod.Get, "/api/v1/waits/${ci.id}", "admin").bodyAsText()).eventUrl, "the secret is shown once")
        assertFalse(call(HttpMethod.Get, "/api/v1/waits/${ci.id}", "admin").bodyAsText().contains(url.substringAfter("token=")))

        fun ids(path: String) = runBlocking { Serialization.json.decodeFromString<WaitPageView>(call(HttpMethod.Get, path, "admin").bodyAsText()).waits.map { it.id } }
        assertEquals(emptyList(), ids("/api/v1/waits"), "Waiting for you shows none of them")
        assertEquals(setOf(timed.id, iso.id, millis.id), ids("/api/v1/waits?resolver=time").toSet())
        assertEquals(listOf(ci.id), ids("/api/v1/waits?resolver=event"))
        assertEquals(4, ids("/api/v1/waits?resolver=all").size)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/api/v1/waits?resolver=robot", "admin").status)
        assertEquals("resolves_by_itself", code(call(HttpMethod.Post, "/api/v1/waits/${timed.id}/answer", "admin", """{"action":"done"}""")))

        // The event address: no credential, the secret in the query or in a header.
        val token = url.substringAfter("token=")
        val path = "/api/v1/waits/${ci.id}/event"
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "$path?token=wev_wrong", null, "{}").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, path, null, "{}").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "/api/v1/waits/unknown/event?token=$token", null, "{}").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "$path?token=$token", null, "[1]").status)
        assertEquals(HttpStatusCode.PayloadTooLarge, call(HttpMethod.Post, "$path?token=$token", null, "{\"note\":\"" + "x".repeat(17 * 1024) + "\"}").status)
        val accepted = call(HttpMethod.Post, path, null, """{"result":"failure","url":"https://ci.example/7"}""", mapOf("TeamCode-Wait-Token" to token))
        assertEquals(HttpStatusCode.Accepted, accepted.status)
        assertEquals(listOf(ci.id), store.woken)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Post, "$path?token=$token", null, "{}").status, "a second call, once resolved")
        assertEquals("event_received", view(call(HttpMethod.Get, "/api/v1/waits/${ci.id}", "admin").bodyAsText()).answer!!.action)
    }

    @Test fun theEventAddressIsRateLimitedAndStillRevealsNothing() = testApplication {
        val store = Store()
        application { configureGateway(gateway, team = team(), waits = WaitService(store)) }
        repeat(30) { assertEquals(HttpStatusCode.NotFound, client.post("/api/v1/waits/w1/event?token=nope") { setBody("{}") }.status) }
        val limited = client.post("/api/v1/waits/w1/event?token=nope") { setBody("{}") }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status); assertEquals("60", limited.headers["Retry-After"])
    }

    @Test fun aSecretValueIsInNoResponseAndNoLog() = testApplication {
        val store = Store(); val sentinel = "S3NTINEL-pa55w0rd-routes"
        val captured = java.io.ByteArrayOutputStream(); val stdout = System.out; val stderr = System.err
        System.setOut(java.io.PrintStream(captured)); System.setErr(java.io.PrintStream(captured))
        val bodies = mutableListOf<String>()
        try {
            application { configureGateway(gateway, team = team(), waits = WaitService(store)) }
            suspend fun call(method: HttpMethod, path: String, token: String, json: String? = null) = client.request(path) {
                this.method = method; bearerAuth(token)
                if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
            }.also { bodies += it.bodyAsText() }
            val ask = """{"wait":{"sessionId":"s1","title":"Shop access","fields":[{"id":"shop_password","type":"secret","label":"Shop password","required":true,"oneTime":true}]}}"""
            val created = Serialization.json.decodeFromString<WaitView>(call(HttpMethod.Post, "/api/v1/waits", "admin", ask).also { assertEquals(HttpStatusCode.Created, it.status) }.bodyAsText())
            assertTrue(created.fields.single().oneTime)
            val answered = call(HttpMethod.Post, "/api/v1/waits/${created.id}/answer", "admin", """{"action":"done","values":{"shop_password":"$sentinel"}}""")
            assertEquals(HttpStatusCode.OK, answered.status)
            val view = Serialization.json.decodeFromString<WaitView>(answered.bodyAsText())
            assertEquals(JsonPrimitive("provided"), view.answer!!.values["shop_password"])
            call(HttpMethod.Get, "/api/v1/waits/${created.id}", "admin"); call(HttpMethod.Get, "/api/v1/waits?status=all", "admin")
            call(HttpMethod.Post, "/api/v1/waits/${created.id}/answer", "admin", """{"action":"done","values":{"shop_password":"$sentinel"}}""")
            // A refusal never repeats it either.
            call(HttpMethod.Post, "/api/v1/waits/${created.id}/answer", "admin", """{"action":"other","values":{"shop_password":"$sentinel"}}""")
            assertEquals(sentinel, store.kept.single().value, "the store did get the value, once")
            assertFalse(AnswerWaitRequest("done", mapOf("shop_password" to JsonPrimitive(sentinel))).toString().contains(sentinel))
        } finally { System.setOut(stdout); System.setErr(stderr) }
        assertTrue(bodies.size >= 6)
        bodies.forEach { assertFalse(sentinel in it, "a response body: $it") }
        assertFalse(sentinel in captured.toString(), "the log capture")
    }
}
