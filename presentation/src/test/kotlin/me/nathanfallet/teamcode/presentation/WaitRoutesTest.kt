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
import kotlin.test.*

/** The wait routes decode, authorize by credential and encode; the rules are the domain's (WaitService). */
class WaitRoutesTest {
    private class Store : WaitStore {
        val waits = linkedMapOf<String, Wait>()
        val keys = mutableMapOf<String, String>()
        override suspend fun now() = 1_800_000_000_000L
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String) = Member(memberId, memberId, Role.MEMBER, false, false)
        override suspend fun session(teamId: String, sessionId: String) = WaitSession(sessionId, teamId, "admin-id", "Fix the login").takeIf { sessionId == "s1" }
        override suspend fun create(actor: Principal, wait: Wait, idempotencyKey: String?, limit: Int): WaitChange {
            idempotencyKey?.let { key -> keys[key]?.let { return WaitChange(waits.getValue(it), false) } }
            waits[wait.id] = wait; idempotencyKey?.let { keys[it] = wait.id }
            return WaitChange(wait, true)
        }
        override suspend fun find(teamId: String, id: String) = waits[id]
        override suspend fun list(teamId: String, query: WaitQuery) = WaitPage(waits.values.filter { (query.assignee == null || it.assigneeId == query.assignee) && (query.status == null || it.status == query.status) }, null)
        override suspend fun answer(actor: Principal, id: String, answer: WaitAnswer): WaitChange {
            val wait = waits.getValue(id)
            if (wait.status != WaitStatus.OPEN) return WaitChange(wait, false)
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
        assertEquals("not_available_yet", code(call(HttpMethod.Post, "/api/v1/waits", "admin", review.replace("\"text\"", "\"secret\""))))
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
}
