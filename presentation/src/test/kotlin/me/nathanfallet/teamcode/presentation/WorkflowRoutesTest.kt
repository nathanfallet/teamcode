package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.workflows.*
import kotlin.test.*

/** The workflow routes decode, authorize by credential and encode; the rules are the domain's (WorkflowService). */
class WorkflowRoutesTest {
    private class Store : WorkflowStore {
        var editors = WorkflowEditors()
        val saved = linkedMapOf<String, Workflow>()
        override suspend fun revalidate(actor: Principal) = Unit
        override suspend fun member(teamId: String, memberId: String) = Member(memberId, memberId, Role.MEMBER, false, false)
        override suspend fun editors(teamId: String) = editors
        override suspend fun saveEditors(actor: Principal, editors: WorkflowEditors) { this.editors = editors }
        override suspend fun find(teamId: String, id: String) = saved[id]
        override suspend fun list(teamId: String, after: String?, limit: Int) = WorkflowPage(saved.values.toList(), null)
        override suspend fun save(actor: Principal, id: String?, definition: WorkflowDefinition, expectedVersion: Int?, limit: Int, check: (Map<String, WorkflowDefinition>) -> Unit): Workflow {
            check(saved.filterKeys { it != id }.mapValues { it.value.definition })
            val old = id?.let { saved.getValue(it) }
            if (old != null && expectedVersion != null && old.version != expectedVersion) throw TeamFailure("workflow_conflict", 409, "stale")
            return Workflow(id ?: "wf${saved.size + 1}", "team", (old?.version ?: 0) + 1, definition, 1, 2, actor.member.id).also { saved[it.id] = it }
        }
        override suspend fun delete(actor: Principal, id: String) = saved.remove(id) ?: throw TeamFailure("not_found", 404, "x")
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = Unit
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? = when (token) {
                "reader" -> interactive.authenticate("admin")!!.copy(tokenId = "reader", serviceScopes = setOf(ServiceScope.CLOUD_READ))
                "writer" -> interactive.authenticate("admin")!!.copy(tokenId = "writer", serviceScopes = setOf(ServiceScope.CLOUD_WRITE, ServiceScope.ADMIN_CLOUD))
                else -> interactive.authenticate(token)
            }
        }
    }
    private val body = """{"workflow":{"name":"Fix a ticket","start":"work","inputs":[{"id":"ticket","label":"Ticket"}],
        |"nodes":[{"id":"work","kind":"agent","title":"Work","instruction":"Fix {{inputs.ticket}}","deliverable":{"type":"url","domain":"github.com"},"transitions":[{"on":"delivered"}]}]}}""".trimMargin()

    /** The same workflow as a replacement of version [version]. */
    private fun versioned(version: Int, name: String = "Fix a ticket"): String {
        val request = Serialization.json.decodeFromString<SaveWorkflowRequest>(body)
        return Serialization.json.encodeToString(SaveWorkflowRequest.serializer(), request.copy(workflow = request.workflow.copy(name = name), expectedVersion = version))
    }

    @Test fun aWorkflowIsSavedVersionedAndReadBackThroughTheWire() = testApplication {
        application { configureGateway(gateway, team = team(), workflows = WorkflowService(Store())) }
        suspend fun call(method: HttpMethod, path: String, token: String?, json: String? = null) = client.request(path) {
            this.method = method; if (token != null) bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type
        suspend fun view(response: HttpResponse): WorkflowView { val text = response.bodyAsText(); assertTrue(response.status.isSuccess(), text); return Serialization.json.decodeFromString(text) }
        val features = Serialization.json.decodeFromString<Capabilities>(call(HttpMethod.Get, "/api/v1/capabilities", "admin").bodyAsText()).features
        assertTrue(Workflows.CAPABILITY in features); assertTrue(Workflows.LIVE_CAPABILITY in features)
        assertEquals(HttpStatusCode.Unauthorized, call(HttpMethod.Get, "/api/v1/workflows", null).status)

        val created = call(HttpMethod.Post, "/api/v1/workflows", "admin", body)
        assertEquals(HttpStatusCode.Created, created.status)
        val createdBody = created.bodyAsText()
        val workflow = Serialization.json.decodeFromString<WorkflowView>(createdBody)
        assertEquals(1, workflow.version); assertEquals("url", workflow.nodes.single().deliverable!!.type); assertEquals("github.com", workflow.nodes.single().deliverable!!.domain)
        val saved = call(HttpMethod.Put, "/api/v1/workflows/${workflow.id}", "admin", versioned(1, "Fix tickets"))
        assertEquals(2, view(saved).version)
        assertEquals("workflow_conflict", code(call(HttpMethod.Put, "/api/v1/workflows/${workflow.id}", "admin", versioned(1))))
        assertEquals(listOf(workflow.id), Serialization.json.decodeFromString<WorkflowPageView>(call(HttpMethod.Get, "/api/v1/workflows", "admin").bodyAsText()).workflows.map { it.id })
        assertEquals(workflow.id, view(call(HttpMethod.Get, "/api/v1/workflows/${workflow.id}", "admin")).id)

        val settings = Serialization.json.decodeFromString<WorkflowSettingsView>(call(HttpMethod.Put, "/api/v1/workflows/settings", "admin", """{"mode":"members","memberIds":["bob"]}""").bodyAsText())
        assertEquals(listOf("bob"), settings.editors.memberIds); assertTrue(settings.canEdit)
        assertEquals("invalid_editors", code(call(HttpMethod.Put, "/api/v1/workflows/settings", "admin", """{"mode":"nobody"}""")))
        assertEquals(workflow.id, view(call(HttpMethod.Delete, "/api/v1/workflows/${workflow.id}", "admin")).id)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/workflows/${workflow.id}", "admin").status)
    }

    @Test fun badInputIsRefusedWithItsCodeAndKeysOnlyRead() = testApplication {
        application { configureGateway(gateway, team = team(), workflows = WorkflowService(Store())) }
        suspend fun call(method: HttpMethod, path: String, token: String, json: String? = null) = client.request(path) {
            this.method = method; bearerAuth(token)
            if (json != null) { contentType(ContentType.Application.Json); setBody(json) }
        }
        suspend fun code(response: HttpResponse) = Serialization.json.decodeFromString<ApiError>(response.bodyAsText()).error.type
        assertEquals("invalid_workflow", code(call(HttpMethod.Post, "/api/v1/workflows", "admin", body.replace("\"agent\"", "\"robot\""))))
        assertEquals("invalid_workflow", code(call(HttpMethod.Post, "/api/v1/workflows", "admin", body.replace("\"start\":\"work\"", "\"start\":\"ghost\""))))
        assertEquals("invalid_workflow", code(call(HttpMethod.Post, "/api/v1/workflows", "admin", body.replace("\"url\"", "\"video\""))))
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/workflows/not%20an%20id", "admin").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Get, "/api/v1/workflows?limit=500", "admin").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/workflows", "reader").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/workflows", "writer", body).status, "a key never saves a definition")
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, "/api/v1/workflows/settings", "writer", """{"mode":"all"}""").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Delete, "/api/v1/workflows/wf1", "writer").status)
    }
}
