package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.*
import kotlin.test.*

class VariantRoutesTest {
    /** In-memory stand-in that keeps the secrets it receives so the test can prove they never come back out. */
    private class Registry : VariantService {
        val variants = linkedMapOf<String, Variant>()
        val secrets = mutableListOf<String>()
        val assignments = mutableListOf<VariantAssignment>()
        val exclusions = mutableListOf<Pair<String, VariantTarget>>()
        var writes = 0
        private fun write(actor: Principal) { actor.requireAdmin(); writes++ }
        override suspend fun list(actor: Principal): List<Variant> { actor.requireAdmin(); return variants.values.toList() }
        override suspend fun create(actor: Principal, variant: NewVariant): Variant {
            write(actor); VariantService.validate(variant)
            variant.sharedSecret?.let { secrets += it.reveal() }
            val id = "00000000-0000-0000-0000-${variants.size.toString().padStart(12, '0')}"
            return Variant(id, variant.kind, variant.target, variant.name, variant.binding, 1, variant.endpoint, variant.config, variant.sharedSecret != null, 1, 1).also { variants[id] = it }
        }
        override suspend fun update(actor: Principal, id: String, update: VariantUpdate): Variant {
            write(actor)
            val current = variants[id] ?: throw TeamFailure("not_found", 404, "Variant not found.")
            VariantService.validate(current, update)
            update.sharedSecret?.let { secrets += it.reveal() }
            return current.copy(name = update.name, config = update.config, version = current.version + 1).also { variants[id] = it }
        }
        override suspend fun delete(actor: Principal, id: String) { write(actor); variants.remove(id) ?: throw TeamFailure("not_found", 404, "Variant not found.") }
        override suspend fun assign(actor: Principal, memberId: String, variantId: String, memberSecret: SecretValue?): VariantAssignment {
            write(actor)
            val variant = variants[variantId] ?: throw TeamFailure("not_found", 404, "Variant not found.")
            VariantService.validateMemberSecret(variant.binding, memberSecret)
            memberSecret?.let { secrets += it.reveal() }
            return VariantAssignment(memberId, variant.slot, variant.id, variant.name, variant.version, AssignmentSource.DIRECT, memberSecret != null, 1).also { assignments += it }
        }
        override suspend fun unassign(actor: Principal, memberId: String, target: VariantTarget) { write(actor); assignments.removeAll { it.memberId == memberId && it.target == target } }
        override suspend fun bulkAssign(actor: Principal, variantId: String): BulkAssignment { write(actor); return BulkAssignment(1, 0, 0) }
        override suspend fun exclude(actor: Principal, memberId: String, target: VariantTarget) { write(actor); VariantService.validateTarget(target); exclusions += memberId to target }
        override suspend fun include(actor: Principal, memberId: String, target: VariantTarget) { write(actor); exclusions.remove(memberId to target) }
        override suspend fun assignments(actor: Principal, memberId: String?): List<MemberVariantAssignments> {
            actor.requireAdmin()
            return listOf(MemberVariantAssignments("admin-id", "<admin>", false, assignments.filter { it.memberId == "admin-id" }, exclusions.filter { it.first == "admin-id" }.map { it.second }))
                .filter { memberId == null || it.memberId == memberId }
        }
    }
    private val gateway = object : GatewayService {
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) = error("unused")
    }
    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String) =
                if (token == "service") interactive.authenticate("admin")!!.copy(serviceScopes = ServiceScope.entries.toSet()) else interactive.authenticate(token)
        }
    }

    @Test fun apiIsAdminOnlyAndSecretsAreWriteOnly() = testApplication {
        val registry = Registry()
        application { configureGateway(gateway, team = team(), variants = registry) }
        val create = """{"kind":"MCP","target":"tracker","name":"team","endpoint":"https://mcp.example.test","config":{"workspace":"core"},"secret":"synthetic-team-credential"}"""
        for (token in listOf("member", "service", "temporary")) {
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/variants") { bearerAuth(token) }.status, token)
            assertEquals(HttpStatusCode.Forbidden, client.post("/api/v1/variants") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(create) }.status, token)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/variant-assignments") { bearerAuth(token) }.status, token)
        }
        assertEquals(0, registry.writes)
        val created = client.post("/api/v1/variants") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(create) }
        assertEquals(HttpStatusCode.Created, created.status)
        val id = registry.variants.keys.single()
        val assigned = client.put("/api/v1/members/admin-id/variants") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"variantId":"$id"}""") }
        assertEquals(HttpStatusCode.OK, assigned.status)
        val personal = client.post("/api/v1/variants") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"kind":"ENV","target":"NPM_TOKEN","name":"own","binding":"PER_MEMBER"}""") }
        assertEquals(HttpStatusCode.Created, personal.status)
        val personalId = registry.variants.keys.last()
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/members/admin-id/variants") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"variantId":"$personalId"}""") }.status)
        assertEquals(HttpStatusCode.OK, client.put("/api/v1/members/admin-id/variants") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"variantId":"$personalId","memberSecret":"synthetic-member-value"}""") }.status)
        assertEquals(HttpStatusCode.NoContent, client.put("/api/v1/members/admin-id/variant-exclusions") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"kind":"ENV","target":"GITHUB_TOKEN"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/members/admin-id/variant-exclusions") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"kind":"OTHER","target":"X"}""") }.status)
        assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/variants") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"kind":"ENV","target":"lower","name":"x","secret":"v"}""") }.status)
        val responses = listOf(created.bodyAsText(), assigned.bodyAsText(), client.get("/api/v1/variants") { bearerAuth("admin") }.bodyAsText(),
            client.get("/api/v1/variant-assignments") { bearerAuth("admin") }.bodyAsText(), client.get("/api/v1/members/admin-id/variants") { bearerAuth("admin") }.bodyAsText())
        assertEquals(listOf("synthetic-team-credential", "synthetic-member-value"), registry.secrets)
        for (body in responses) for (secret in registry.secrets) assertFalse(body.contains(secret), body)
        assertTrue(responses[2].contains("\"hasSharedSecret\":true"))
        assertTrue(responses[4].contains("\"hasMemberSecret\":true") && responses[4].contains("GITHUB_TOKEN"))
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/members/admin-id/variant-exclusions/ENV/GITHUB_TOKEN") { bearerAuth("admin") }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/members/admin-id/variants/MCP/tracker") { bearerAuth("admin") }.status)
        assertEquals(HttpStatusCode.OK, client.post("/api/v1/variants/$id/bulk-assign") { bearerAuth("admin") }.status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/variants/$id") { bearerAuth("admin") }.status)
        assertTrue(client.get("/api/v1/capabilities") { bearerAuth("admin") }.bodyAsText().contains("team.variants.v1"))
    }

    @Test fun dashboardRequiresOriginAndCsrfEscapesContentAndNeverShowsSecrets() = testApplication {
        val registry = Registry()
        application { configureGateway(gateway, team = team(), publicOrigin = "http://localhost", variants = registry) }
        suspend fun submit(path: String, body: String, origin: String = "http://localhost", csrf: String = "session") = client.post(path) {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=$csrf&$body")
        }
        val form = "kind=ENV&target=GITHUB_TOKEN&name=%3Cscript%3Eorg%3C%2Fscript%3E&binding=SHARED&secret=synthetic-dashboard-secret"
        assertEquals(HttpStatusCode.Forbidden, submit("/dashboard/variants", form, origin = "https://attacker.test").status)
        assertEquals(HttpStatusCode.Forbidden, submit("/dashboard/variants", form, csrf = "wrong").status)
        assertEquals(0, registry.writes)
        assertEquals(HttpStatusCode.Forbidden, client.get("/dashboard/variants") { header(HttpHeaders.Cookie, "teamcode_session=member") }.status)
        assertEquals(HttpStatusCode.Found, submit("/dashboard/variants", form).status)
        val mcp = "kind=MCP&target=tracker&name=team&binding=SHARED&endpoint=https%3A%2F%2Fmcp.example.test&config=workspace%3Dcore%0Aregion%3Deu"
        assertEquals(HttpStatusCode.Found, submit("/dashboard/variants", mcp).status)
        assertEquals(mapOf("workspace" to "core", "region" to "eu"), registry.variants.values.last().config)
        assertEquals(HttpStatusCode.BadRequest, submit("/dashboard/variants", "kind=MCP&target=tracker&name=bad&binding=SHARED&endpoint=https%3A%2F%2Fx.test&config=novalue").status)
        val id = registry.variants.keys.first()
        assertEquals(HttpStatusCode.Found, submit("/dashboard/variants/assign", "member=admin-id&variant=$id").status)
        assertEquals(HttpStatusCode.Found, submit("/dashboard/variants/exclude", "member=admin-id&kind=MCP&target=tracker").status)
        val html = client.get("/dashboard/variants") { header(HttpHeaders.Cookie, "teamcode_session=admin") }
        assertEquals("no-store, no-transform", html.headers[HttpHeaders.CacheControl])
        val body = html.bodyAsText()
        assertFalse(body.contains("synthetic-dashboard-secret"))
        assertTrue(body.contains("&lt;script&gt;org&lt;/script&gt;")); assertFalse(body.contains("<script>org</script>"))
        assertTrue(body.contains("&lt;admin&gt;")); assertTrue(body.contains("excluded"))
        assertTrue(body.contains("shared secret stored")); assertTrue(body.contains("workspace=core"))
        assertEquals(HttpStatusCode.Found, submit("/dashboard/variants/include", "member=admin-id&kind=MCP&target=tracker").status)
        assertEquals(HttpStatusCode.Found, submit("/dashboard/variants/unassign", "member=admin-id&kind=ENV&target=GITHUB_TOKEN").status)
        assertEquals(HttpStatusCode.Found, submit("/dashboard/variants/$id/bulk-assign", "").status)
        assertEquals(HttpStatusCode.Found, submit("/dashboard/variants/$id/delete", "").status)
        assertTrue(registry.exclusions.isEmpty()); assertTrue(registry.assignments.isEmpty())
    }

    @Test fun anEmptySecretIsRefusedInsteadOfSilentlyKeepingTheOldOne() = testApplication {
        val registry = Registry()
        application { configureGateway(gateway, team = team(), variants = registry) }
        val create = """{"kind":"MCP","target":"tracker","name":"team","endpoint":"https://mcp.example.test","secret":"synthetic-team-credential"}"""
        assertEquals(HttpStatusCode.Created, client.post("/api/v1/variants") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody(create) }.status)
        val id = registry.variants.keys.single()
        val empty = client.patch("/api/v1/variants/$id") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"expectedVersion":1,"name":"team","secret":""}""") }
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        val kept = client.patch("/api/v1/variants/$id") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"expectedVersion":1,"name":"renamed"}""") }
        assertEquals(HttpStatusCode.OK, kept.status)
        val personal = client.post("/api/v1/variants") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"kind":"ENV","target":"NPM_TOKEN","name":"own","binding":"PER_MEMBER"}""") }
        val personalId = registry.variants.keys.last()
        assertEquals(HttpStatusCode.BadRequest, client.put("/api/v1/members/admin-id/variants") { bearerAuth("admin"); contentType(ContentType.Application.Json); setBody("""{"variantId":"$personalId","memberSecret":""}""") }.status)
    }

    @Test fun membersReadTheirOwnEnvironmentWithAnInteractiveLoginOnly() = testApplication {
        val asked = mutableListOf<Pair<String, String>>()
        val source = me.nathanfallet.teamcode.domain.cloud.CloudVariableSource { team, member ->
            asked += team to member
            me.nathanfallet.teamcode.domain.cloud.CloudVariables(mapOf("NPM_TOKEN" to "synthetic-npm-value"), listOf("BROKEN_VALUE"))
        }
        application { configureGateway(gateway, team = team(), environment = ResolveOwnEnvironmentUseCase(source)) }
        assertTrue("team.environment.v1" in client.get("/api/v1/capabilities") { bearerAuth("member") }.bodyAsText())
        // A service key never carries a member's secrets, and a temporary password reads nothing either.
        for (token in listOf("service", "temporary")) assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/environment") { bearerAuth(token) }.status, token)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/environment").status)
        assertTrue(asked.isEmpty(), "Refused callers are never resolved")
        // Naming another member is not possible: the header acting for someone else is ignored, the caller's own are read.
        val response = client.get("/api/v1/environment") { bearerAuth("member"); header("TeamCode-On-Behalf-Of", "someone-else") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store, no-transform", response.headers[HttpHeaders.CacheControl])
        val view = me.nathanfallet.teamcode.api.Serialization.json.decodeFromString<me.nathanfallet.teamcode.api.models.EnvironmentVariablesView>(response.bodyAsText())
        assertEquals(mapOf("NPM_TOKEN" to "synthetic-npm-value"), view.variables)
        assertEquals(listOf("BROKEN_VALUE"), view.unavailable)
        assertFalse("synthetic-npm-value" in view.toString())
        assertEquals(listOf("default" to "admin-id"), asked)
    }

    @Test fun dashboardFormExplainsVariantsAndPicksFieldsByType() = testApplication {
        val registry = Registry()
        application { configureGateway(gateway, team = team(), publicOrigin = "http://localhost", variants = registry) }
        suspend fun submit(body: String) = client.post("/dashboard/variants") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=session&$body")
        }
        val body = client.get("/dashboard/variants") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        for (text in listOf("What is a variant?", "Where it reaches a session", "/mcp/&lt;name&gt;", "Environment variable", "MCP server", "Variable name", "Server name", "Endpoint", "Credential (optional)", "Who provides the value"))
            assertTrue(body.contains(text), text)
        assertTrue(body.contains("variant-form"))
        // The fields of each type are marked so the page shows only the selected type's.
        for (field in listOf("env_target", "secret_env")) assertTrue(body.contains("name=\"$field\""), field)
        for (field in listOf("mcp_target", "endpoint", "config", "secret_mcp")) assertTrue(body.contains("name=\"$field\""), field)

        // Hidden fields of the other type are submitted by browsers and must not leak into the variant.
        assertEquals(HttpStatusCode.Found, submit("kind=ENV&env_target=NPM_TOKEN&mcp_target=ignored&name=ci&binding=SHARED&endpoint=https%3A%2F%2Fx.test&config=a%3Db&secret_env=v&secret_mcp=other").status)
        registry.variants.values.last().let { assertEquals("NPM_TOKEN", it.target); assertNull(it.endpoint); assertTrue(it.config.isEmpty()) }
        assertEquals(HttpStatusCode.Found, submit("kind=MCP&env_target=ignored&mcp_target=tracker&name=team&binding=SHARED&endpoint=https%3A%2F%2Fmcp.example.test&secret_env=&secret_mcp=cred").status)
        registry.variants.values.last().let { assertEquals("tracker", it.target); assertTrue(it.hasSharedSecret) }
        assertEquals(HttpStatusCode.Found, submit("kind=ENV&env_target=OWN_TOKEN&name=own&binding=PER_MEMBER&secret_env=leftover").status)
        registry.variants.values.last().let { assertEquals(VariantBinding.PER_MEMBER, it.binding); assertFalse(it.hasSharedSecret) }

        // Per-type validation still answers with a message about the selected type.
        assertEquals(HttpStatusCode.BadRequest, submit("kind=ENV&env_target=PATH&name=bad&binding=SHARED&secret_env=v").status)
        assertEquals(HttpStatusCode.BadRequest, submit("kind=ENV&name=bad&binding=SHARED&secret_env=v").status)
        assertEquals(HttpStatusCode.BadRequest, submit("kind=ENV&env_target=EMPTY&name=bad&binding=SHARED").status)
        assertEquals(HttpStatusCode.BadRequest, submit("kind=MCP&mcp_target=tracker&name=bad&binding=SHARED").status)
        assertEquals(HttpStatusCode.BadRequest, submit("kind=MCP&mcp_target=tracker&name=bad&binding=SHARED&endpoint=http%3A%2F%2Fplain.test").status)
        assertEquals(3, registry.variants.size)
    }
}
