package me.nathanfallet.teamcode.presentation

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import java.util.Base64
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.skills.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

class SkillRoutesTest {
    /** In-memory catalog with the same delivery-relevant semantics as the SQL store. */
    private class MemoryStore : SkillStore {
        val skills = mutableMapOf<String, Skill>()
        val versions = mutableMapOf<String, MutableList<Pair<SkillVersion, List<SkillFile>>>>()
        val decisions = mutableMapOf<Pair<String, String>, SkillDecision>()
        val reports = mutableListOf<SkillInstallationReport>()
        private fun skill(name: String) = skills[name] ?: throw TeamFailure("not_found", 404, "Skill not found.")
        private fun version(name: String, label: String) = versions[name]?.find { it.first.version == label }?.first ?: throw TeamFailure("not_found", 404, "Skill version not found.")
        override suspend fun catalog(actor: Principal): List<Skill> { actor.requireAdmin(); return skills.values.toList() }
        override suspend fun versions(actor: Principal, name: String): Pair<Skill, List<SkillVersion>> { actor.requireAdmin(); return skill(name) to versions[name].orEmpty().map { it.first } }
        override suspend fun publish(actor: Principal, name: String, version: String, artifact: SkillArtifact): SkillPublication {
            val skill = skills.getOrPut(name) { Skill("id-$name", name, null, false, 1, 1) }
            versions[name]?.find { it.first.version == version }?.let { (existing, _) ->
                if (existing.digest != artifact.digest) throw TeamFailure("skill_version_conflict", 409, "Conflict.")
                return SkillPublication(skill, existing, false)
            }
            val record = SkillVersion("$name@$version", skill.id, version, artifact.digest, artifact.sizeBytes, artifact.entries.size, actor.member.id, 1, false)
            versions.getOrPut(name) { mutableListOf() } += record to artifact.files
            return SkillPublication(skill, record, true)
        }
        override suspend fun pin(actor: Principal, name: String, version: String): Skill {
            actor.requireAdmin(); version(name, version)
            return skill(name).copy(pinnedVersion = version).also { skills[name] = it }
        }
        override suspend fun unpin(actor: Principal, name: String): Skill { actor.requireAdmin(); return skill(name).copy(pinnedVersion = null).also { skills[name] = it } }
        override suspend fun retire(actor: Principal, name: String): Skill { actor.requireAdmin(); return skill(name).copy(retired = true, pinnedVersion = null).also { skills[name] = it } }
        override suspend fun restore(actor: Principal, name: String): Skill { actor.requireAdmin(); return skill(name).copy(retired = false).also { skills[name] = it } }
        override suspend fun retireVersion(actor: Principal, name: String, version: String): SkillVersion { actor.requireAdmin(); return version(name, version).copy(retired = true) }
        override suspend fun assignments(actor: Principal, name: String): List<SkillAssignment> {
            actor.requireAdmin(); val id = skill(name).id
            return decisions.filterKeys { it.first == name }.map { (key, decision) -> SkillAssignment(id, key.second, decision, 1) }
        }
        override suspend fun assign(actor: Principal, name: String, memberId: String, decision: SkillDecision?): SkillAssignment? {
            actor.requireAdmin(); val id = skill(name).id
            if (decision == null) { decisions.remove(name to memberId); return null }
            decisions[name to memberId] = decision
            return SkillAssignment(id, memberId, decision, 1)
        }
        override suspend fun candidates(actor: Principal, memberId: String) = skills.values.map { skill ->
            SkillCandidate(skill, skill.pinnedVersion?.let { version(skill.name, it) }, decisions[skill.name to memberId])
        }
        override suspend fun files(actor: Principal, versionId: String) = versions.values.flatten().single { it.first.id == versionId }.second
        override suspend fun reportInstallations(actor: Principal, reports: List<SkillInstallationReport>) { this.reports += reports }
        override suspend fun installations(actor: Principal, memberId: String): List<SkillInstallation> {
            if (actor.member.id != memberId) actor.requireAdmin()
            return reports.map { SkillInstallation("device", "Laptop <b>", it.name, it.version, it.digest, it.status, 1) }
        }
    }

    private fun team(): TeamService {
        val interactive = FakeTeam()
        return object : TeamService by interactive {
            override suspend fun authenticate(token: String): Principal? {
                val scopes = when (token) {
                    "service-publish" -> setOf(ServiceScope.SKILLS_PUBLISH)
                    "service-read" -> setOf(ServiceScope.SKILLS_READ)
                    "service-inference" -> setOf(ServiceScope.INFERENCE)
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
    private fun upload(path: String, content: String, mode: String = "file") = SkillFileUpload(path, mode, Base64.getEncoder().encodeToString(content.encodeToByteArray()))
    private fun publishBody(version: String, files: List<SkillFileUpload>) = Serialization.json.encodeToString(PublishSkillVersionRequest(version, files))

    @Test fun publicationDeliveryAndRollbackThroughTheApi() = testApplication {
        val store = MemoryStore()
        application { configureGateway(NoGateway(), team = team(), skills = SkillCatalog(store)) }
        suspend fun call(method: HttpMethod, path: String, token: String, body: String? = null) = client.request(path) {
            this.method = method; bearerAuth(token)
            if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        }
        val files = listOf(upload("SKILL.md", "---\nname: review\n---\nOne"), upload("scripts/run.sh", "#!/bin/sh", "executable"))

        // Transport classification: only skills:publish reaches publication, and it grants nothing else.
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/skills/review/versions", "service-inference", publishBody("1", files)).status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/skills/review/versions", "service-read", publishBody("1", files)).status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/skills/review/versions", "member", publishBody("1", files)).status)
        // Authorization precedes reading the body: a member's malformed upload is refused, not parsed.
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Post, "/api/v1/skills/review/versions", "member", "{not json").status)
        val created = call(HttpMethod.Post, "/api/v1/skills/review/versions", "service-publish", publishBody("1", files))
        assertEquals(HttpStatusCode.Created, created.status)
        val published = Serialization.json.decodeFromString<PublishSkillVersionResponse>(created.bodyAsText())
        assertTrue(published.created); assertFalse(published.version.pinned)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Post, "/api/v1/skills/review/versions", "service-publish", publishBody("1", files)).status)
        assertEquals(HttpStatusCode.Conflict, call(HttpMethod.Post, "/api/v1/skills/review/versions", "service-publish", publishBody("1", files.take(1))).status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/skills", "service-publish").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, "/api/v1/skills/review/pin", "service-publish", """{"version":"1"}""").status)

        // Bounds and unsafe content are refused before persistence.
        for ((body, code) in listOf(
            publishBody("2", listOf(upload("SKILL.md", "x"), upload("../escape.md", "x"))) to "invalid_skill_path",
            publishBody("2", listOf(upload("SKILL.md", "x"), upload("link.md", "SKILL.md", "symlink"))) to "invalid_skill_artifact",
            publishBody("2", listOf(SkillFileUpload("SKILL.md", "file", "not base64!"))) to "invalid_skill_artifact",
            publishBody("../2", files) to "invalid_skill_version",
        )) {
            val response = call(HttpMethod.Post, "/api/v1/skills/review/versions", "admin", body)
            assertEquals(HttpStatusCode.BadRequest, response.status, body)
            assertEquals(code, Serialization.json.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        }
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Post, "/api/v1/skills/Bad_Name/versions", "admin", publishBody("1", files)).status)
        val tooLarge = publishBody("2", listOf(upload("SKILL.md", "x"), SkillFileUpload("big.bin", "file", Base64.getEncoder().encodeToString(ByteArray(SkillArtifacts.MAX_ARTIFACT_BYTES)))))
        assertEquals(HttpStatusCode.PayloadTooLarge, call(HttpMethod.Post, "/api/v1/skills/review/versions", "admin", tooLarge).status)
        assertEquals(1, store.versions.getValue("review").size)

        assertEquals(HttpStatusCode.Created, call(HttpMethod.Post, "/api/v1/skills/review/versions", "admin", publishBody("2", listOf(upload("SKILL.md", "Two")))).status)
        assertEquals("""{"memberId":"admin-id","skills":[]}""", call(HttpMethod.Get, "/api/v1/skill-delivery", "admin").bodyAsText())
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/v1/skills/review/pin", "admin", """{"version":"1"}""").status)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/v1/skills/review/assignments/admin-id", "admin", """{"decision":"allow"}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "/api/v1/skills/review/assignments/admin-id", "admin", """{"decision":"maybe"}""").status)

        // The member's manifest and artifact, verifiable from the response alone.
        val manifest = Serialization.json.decodeFromString<SkillManifestView>(call(HttpMethod.Get, "/api/v1/skill-delivery", "service-read").bodyAsText())
        assertEquals(listOf("1"), manifest.skills.map { it.version })
        val artifact = Serialization.json.decodeFromString<SkillArtifactView>(call(HttpMethod.Get, "/api/v1/skill-delivery/review/1", "service-read").bodyAsText())
        val entries = artifact.files.map { file ->
            val bytes = Base64.getDecoder().decode(file.contentBase64)
            assertEquals(SkillArtifacts.sha256(bytes), file.sha256)
            SkillFileEntry(file.path, SkillFileMode.entries.single { it.wire == file.mode }, bytes.size, file.sha256)
        }
        assertEquals(artifact.digest, SkillArtifacts.digest(entries))
        assertEquals(manifest.skills.single().digest, artifact.digest)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/skill-delivery/review/2", "service-read").status)
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Get, "/api/v1/skill-delivery", "service-inference").status)

        // Update and rollback are pins; the rolled-back artifact is byte-identical.
        call(HttpMethod.Put, "/api/v1/skills/review/pin", "admin", """{"version":"2"}""")
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Get, "/api/v1/skill-delivery/review/2", "admin").status)
        call(HttpMethod.Put, "/api/v1/skills/review/pin", "admin", """{"version":"1"}""")
        assertEquals(Serialization.json.encodeToString(artifact), call(HttpMethod.Get, "/api/v1/skill-delivery/review/1", "admin").bodyAsText())

        // Installation reports are interactive and do not change access.
        assertEquals(HttpStatusCode.Forbidden, call(HttpMethod.Put, "/api/v1/skill-delivery/installations", "service-read", """{"installations":[]}""").status)
        assertEquals(HttpStatusCode.BadRequest, call(HttpMethod.Put, "/api/v1/skill-delivery/installations", "admin", """{"installations":[{"name":"review","status":"broken"}]}""").status)
        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Put, "/api/v1/skill-delivery/installations", "admin", """{"installations":[{"name":"review","version":"1","status":"conflict"}]}""").status)
        assertEquals("conflict", Serialization.json.parseToJsonElement(call(HttpMethod.Get, "/api/v1/skill-delivery/installations", "admin").bodyAsText()).jsonArray.single().jsonObject["status"]!!.jsonPrimitive.content)

        // A removed assignment is no longer served.
        assertEquals(HttpStatusCode.NoContent, call(HttpMethod.Delete, "/api/v1/skills/review/assignments/admin-id", "admin").status)
        assertEquals(HttpStatusCode.NotFound, call(HttpMethod.Get, "/api/v1/skill-delivery/review/1", "admin").status)
        assertEquals("""{"memberId":"admin-id","skills":[]}""", call(HttpMethod.Get, "/api/v1/skill-delivery", "admin").bodyAsText())
        assertTrue(call(HttpMethod.Get, "/api/v1/capabilities", "admin").bodyAsText().contains("team.skills.v1"))
    }

    @Test fun dashboardIsAdministratorOnlyEscapedAndCsrfProtected() = testApplication {
        val store = MemoryStore()
        application { configureGateway(NoGateway(), team = team(), publicOrigin = "http://localhost", skills = SkillCatalog(store)) }
        val browser = createClient { followRedirects = false }
        val admin = Principal(Member("admin-id", "admin", Role.ADMIN, false, false), "session", "default")
        val catalog = SkillCatalog(store)
        assertEquals("invalid_skill_version", assertFailsWith<TeamFailure> {
            catalog.publish(admin, "review", "1<b>", listOf(SkillFile("SKILL.md", SkillFileMode.FILE, "x".encodeToByteArray())))
        }.code)
        catalog.publish(admin, "review", "1", listOf(SkillFile("SKILL.md", SkillFileMode.FILE, "x".encodeToByteArray())))
        suspend fun page(path: String, session: String) = browser.get(path) { header(HttpHeaders.Cookie, "teamcode_session=$session") }
        suspend fun form(path: String, origin: String, body: String) = browser.post(path) {
            header(HttpHeaders.Cookie, "teamcode_session=admin"); header(HttpHeaders.Origin, origin)
            contentType(ContentType.Application.FormUrlEncoded); setBody(body)
        }

        assertTrue(page("/dashboard/skills", "admin").bodyAsText().contains("/dashboard/skills/review"))
        assertEquals("/dashboard/members/admin-id/skills", page("/dashboard/skills", "member").headers[HttpHeaders.Location])
        assertEquals(HttpStatusCode.Forbidden, page("/dashboard/skills/review", "member").status)
        assertTrue(page("/dashboard/skills/review", "admin").bodyAsText().contains("Pin this version"))

        assertEquals(HttpStatusCode.Forbidden, form("/dashboard/skills/review/pin", "https://attacker.test", "csrf=session&version=1").status)
        assertEquals(HttpStatusCode.Forbidden, form("/dashboard/skills/review/pin", "http://localhost", "csrf=wrong&version=1").status)
        assertNull(store.skills.getValue("review").pinnedVersion)
        assertEquals(HttpStatusCode.Found, form("/dashboard/skills/review/pin", "http://localhost", "csrf=session&version=1").status)
        assertEquals("1", store.skills.getValue("review").pinnedVersion)
        assertEquals(HttpStatusCode.Found, form("/dashboard/skills/review/assignments/admin-id", "http://localhost", "csrf=session&decision=allow").status)
        assertEquals(SkillDecision.ALLOW, store.decisions.getValue("review" to "admin-id"))
        assertEquals(HttpStatusCode.Found, form("/dashboard/skills/review/assignments/admin-id", "http://localhost", "csrf=session&decision=none").status)
        assertTrue(store.decisions.isEmpty())

        store.reports += SkillInstallationReport("review", "1", null, SkillInstallationStatus.INSTALLED)
        val delivered = page("/dashboard/members/admin-id/skills", "admin").bodyAsText()
        assertTrue(delivered.contains("Laptop &lt;b&gt;")); assertFalse(delivered.contains("Laptop <b>"))
        assertEquals(HttpStatusCode.Found, form("/dashboard/skills/review/retire", "http://localhost", "csrf=session").status)
        assertTrue(store.skills.getValue("review").retired)
    }
}
