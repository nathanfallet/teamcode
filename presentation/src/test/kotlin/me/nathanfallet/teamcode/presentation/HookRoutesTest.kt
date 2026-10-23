package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.util.Base64
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.hooks.*
import me.nathanfallet.teamcode.domain.hooks.Hook
import me.nathanfallet.teamcode.domain.skills.SkillDecision
import me.nathanfallet.teamcode.domain.skills.SkillFile
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class HookRoutesTest {
    /** In-memory catalog with the delivery-relevant semantics of the SQL store. */
    private class MemoryStore : HookStore {
        val hooks = mutableMapOf<String, Hook>()
        val versions = mutableMapOf<String, MutableList<Pair<HookVersion, List<SkillFile>>>>()
        val decisions = mutableMapOf<Pair<String, String>, SkillDecision>()
        private fun hook(name: String) = hooks[name] ?: throw TeamFailure("not_found", 404, "Hook not found.")
        private fun version(name: String, label: String) = versions[name]?.find { it.first.version == label }?.first ?: throw TeamFailure("not_found", 404, "Hook version not found.")
        override suspend fun catalog(actor: Principal): List<Hook> { actor.requireAdmin(); return hooks.values.toList() }
        override suspend fun versions(actor: Principal, name: String) = run { actor.requireAdmin(); hook(name) to versions[name].orEmpty().map { it.first } }
        override suspend fun publish(actor: Principal, name: String, version: String, artifact: HookArtifact, allMembers: Boolean): HookPublication {
            actor.requireHookPublisher()
            hooks.getOrPut(name) { Hook("id-$name", name, null, 1, 1) }
            if (allMembers && !hooks.getValue(name).allMembers) {
                if (actor.serviceScopes != null && hooks.getValue(name).pinnedVersion != null)
                    throw TeamFailure("hook_audience_requires_administrator", 403, "Pinned.")
                hooks[name] = hooks.getValue(name).copy(allMembers = true)
            }
            val hook = hooks.getValue(name)
            versions[name]?.find { it.first.version == version }?.let { (existing, _) ->
                if (existing.digest != artifact.digest) throw TeamFailure("hook_version_conflict", 409, "Conflict.")
                return HookPublication(hook, existing, false)
            }
            val record = HookVersion("$name@$version", hook.id, version, artifact.digest, artifact.definition, artifact.sizeBytes, artifact.entries.size, actor.member.id, 1)
            versions.getOrPut(name) { mutableListOf() } += record to artifact.files
            return HookPublication(hook, record, true)
        }
        override suspend fun pin(actor: Principal, name: String, version: String): Hook {
            actor.requireAdmin(); version(name, version)
            return hook(name).copy(pinnedVersion = version).also { hooks[name] = it }
        }
        override suspend fun unpin(actor: Principal, name: String): Hook { actor.requireAdmin(); return hook(name).copy(pinnedVersion = null).also { hooks[name] = it } }
        override suspend fun setAudience(actor: Principal, name: String, allMembers: Boolean): Hook {
            actor.requireAdmin(); return hook(name).copy(allMembers = allMembers).also { hooks[name] = it }
        }
        override suspend fun assignments(actor: Principal, name: String): List<HookAssignment> {
            actor.requireAdmin(); val id = hook(name).id
            return decisions.filterKeys { it.first == name }.map { (key, decision) -> HookAssignment(id, key.second, decision, 1) }
        }
        override suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?): HookAssignment? {
            actor.requireAdmin(); val id = hook(name).id
            if (decision == null) { decisions.remove(name to memberId); return null }
            decisions[name to memberId] = decision
            return HookAssignment(id, memberId, decision, 1)
        }
        override suspend fun candidates(actor: Principal, memberId: String) = hooks.values.map { hook ->
            HookCandidate(hook, hook.pinnedVersion?.let { version(hook.name, it) }, decisions[hook.name to memberId])
        }
        override suspend fun files(actor: Principal, versionId: String) = versions.values.flatten().single { it.first.id == versionId }.second
    }

    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? {
                val scopes = when (token) {
                    "hooks-publish" -> setOf(ServiceScope.HOOKS_PUBLISH)
                    "hooks-read" -> setOf(ServiceScope.HOOKS_READ)
                    "skills-publish" -> setOf(ServiceScope.SKILLS_PUBLISH)
                    "every-scope" -> ServiceScope.entries.toSet()
                    else -> return interactive.authenticate(token)
                }
                return interactive.authenticate("admin")!!.copy(serviceScopes = scopes)
            }
        }
    }
    private class NoGateway : GatewayService {
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) { error("No inference expected") }
        override suspend fun status() = GatewaySnapshot("test", 0, 0)
    }
    private val script = SkillFileUpload("bin/guard.sh", "executable", Base64.getEncoder().encodeToString("#!/bin/sh\nexit 0\n".encodeToByteArray()))
    private fun body(version: String, definition: HookDefinitionView = HookDefinitionView(listOf("Stop", "SubagentStop"), command = "bin/guard.sh", timeoutSeconds = 30)) =
        Serialization.json.encodeToString(PublishHookVersionRequest(version, definition, listOf(script)))

    @Test fun keysPublishOnlyAnAdministratorPinsAndAllowedMembersDownloadTheVerifiedVersion() = testApplication {
        val store = MemoryStore()
        application { configureGateway(NoGateway(), team = team(), hooks = HookCatalog(store)) }
        suspend fun call(method: HttpMethod, path: String, token: String, body: String? = null) = client.request(path) {
            this.method = method; bearerAuth(token)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        assertTrue("team.hooks.v1" in call(HttpMethod.Get, "/api/v1/capabilities", "member").bodyAsText())

        // Publication: only hooks:publish on an administrator's key, or an interactive administrator; a skills key cannot.
        for (token in listOf("skills-publish", "hooks-read", "member")) assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/hooks/stop-guard/versions", token, body("1")).status, token)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/hooks/stop-guard/versions", "member", "{not json").status)
        val created = call(HttpMethod.Post, "/api/v1/hooks/stop-guard/versions", "hooks-publish", body("1"))
        assertEquals(HttpStatusCode.Created, created.status)
        assertFalse(Serialization.json.decodeFromString<PublishHookVersionResponse>(created.bodyAsText()).version.pinned)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/v1/hooks/stop-guard/versions", "hooks-publish", body("1")).status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/hooks/stop-guard/versions", "hooks-publish",
            body("2", HookDefinitionView(listOf("OnEverything"), command = "bin/guard.sh"))).status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/hooks/stop-guard/versions", "hooks-publish",
            body("2", HookDefinitionView(listOf("Stop"), command = "bin/guard.sh; curl evil"))).status)

        // An administrator reads the code of an unpinned version before pinning it; nobody else can.
        val read = Serialization.json.decodeFromString<HookArtifactView>(call(HttpMethod.Get, "/api/v1/hooks/stop-guard/versions/1", "admin").bodyAsText())
        assertEquals("#!/bin/sh\nexit 0\n", Base64.getDecoder().decode(read.files.single().contentBase64).decodeToString())
        for (token in listOf("hooks-publish", "member")) assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/hooks/stop-guard/versions/1", token).status)
        // Pinning: interactive administrators only; no key, whatever its scopes, can deliver a hook.
        for (token in listOf("hooks-publish", "every-scope", "member"))
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, "/api/v1/hooks/stop-guard/pin", token, """{"version":"1"}""").status, token)
        assertNull(store.hooks.getValue("stop-guard").pinnedVersion)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/v1/hooks/stop-guard/pin", "admin", """{"version":"1"}""").status)

        // Delivery: an explicit allow, then the manifest and the files, readable with hooks:read.
        val empty = Serialization.json.decodeFromString<HookManifestView>(call(HttpMethod.Get, "/api/v1/hook-delivery", "member").bodyAsText())
        assertEquals(emptyList(), empty.hooks)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/v1/hooks/stop-guard/assignments/admin-id", "admin", """{"decision":"allow"}""").status)
        val manifest = Serialization.json.decodeFromString<HookManifestView>(call(HttpMethod.Get, "/api/v1/hook-delivery", "hooks-read").bodyAsText())
        val hook = manifest.hooks.single()
        assertEquals(listOf("Stop", "SubagentStop"), hook.definition.events)
        val artifact = Serialization.json.decodeFromString<HookArtifactView>(call(HttpMethod.Get, "/api/v1/hook-delivery/stop-guard/1", "hooks-read").bodyAsText())
        assertEquals(hook.digest, artifact.digest)
        assertEquals("executable", artifact.files.single().mode)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/hook-delivery", "skills-publish").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/hook-delivery/stop-guard/2", "member").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/hooks", "hooks-publish").status)

        // Unpinning stops delivery at once.
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "/api/v1/hooks/stop-guard/pin", "admin").status)
        assertEquals(emptyList(), Serialization.json.decodeFromString<HookManifestView>(call(HttpMethod.Get, "/api/v1/hook-delivery", "member").bodyAsText()).hooks)
    }

    @Test fun aKeyProposesAllMembersBeforeThePinAndOnlyAnAdministratorWidensAPinnedHook() = testApplication {
        val store = MemoryStore()
        application { configureGateway(NoGateway(), team = team(), publicOrigin = "http://localhost", hooks = HookCatalog(store)) }
        suspend fun call(method: HttpMethod, path: String, token: String, body: String? = null) = client.request(path) {
            this.method = method; bearerAuth(token)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        fun forEveryone(version: String) = Serialization.json.encodeToString(
            PublishHookVersionRequest(version, HookDefinitionView(listOf("Stop"), command = "bin/guard.sh"), listOf(script), allMembers = true))
        assertTrue("team.hooks.all-members.v1" in call(HttpMethod.Get, "/api/v1/capabilities", "member").bodyAsText())

        val created = call(HttpMethod.Post, "/api/v1/hooks/stop-guard/versions", "hooks-publish", forEveryone("1"))
        assertEquals(HttpStatusCode.Created, created.status)
        assertTrue(Serialization.json.decodeFromString<PublishHookVersionResponse>(created.bodyAsText()).hook.allMembers)
        // Nothing runs before the pin, then everyone without a decision receives it.
        assertEquals(emptyList(), Serialization.json.decodeFromString<HookManifestView>(call(HttpMethod.Get, "/api/v1/hook-delivery", "member").bodyAsText()).hooks)
        call(HttpMethod.Put, "/api/v1/hooks/stop-guard/pin", "admin", """{"version":"1"}""")
        assertEquals(listOf("stop-guard"), Serialization.json.decodeFromString<HookManifestView>(call(HttpMethod.Get, "/api/v1/hook-delivery", "member").bodyAsText()).hooks.map { it.name })

        // On a pinned hook, the audience is an administrator's: no key reaches the route, and a key's publication is refused.
        for (token in listOf("hooks-publish", "every-scope", "member"))
            assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, "/api/v1/hooks/stop-guard/audience", token, """{"allMembers":false}""").status, token)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/v1/hooks/stop-guard/audience", "admin", """{"allMembers":false}""").status)
        val refused = call(HttpMethod.Post, "/api/v1/hooks/stop-guard/versions", "hooks-publish", forEveryone("2"))
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(refused.bodyAsText().contains("hook_audience_requires_administrator"))
        assertFalse(store.hooks.getValue("stop-guard").allMembers)

        // The dashboard sets it back, behind the CSRF check.
        val browser = createClient { followRedirects = false }
        suspend fun form(body: String) = browser.post("/dashboard/hooks/stop-guard/audience") {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, "http://localhost")
            contentType(ContentType.Application.FormUrlEncoded); setBody(body)
        }
        assertEquals(HttpStatusCode.Forbidden, form("csrf=wrong&audience=all").status)
        assertEquals(HttpStatusCode.Found, form("csrf=session&audience=all").status)
        assertTrue(store.hooks.getValue("stop-guard").allMembers)
        val page = browser.get("/dashboard/hooks/stop-guard") { header(HttpHeaders.Cookie, "teamcode_session=admin") }.bodyAsText()
        assertTrue(page.contains("""<option value="all" selected>"""))
    }
}
