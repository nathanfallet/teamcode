package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.util.Base64
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.skills.*
import me.nathanfallet.teamcode.domain.team.*

/** Base64 of the 5 MiB content bound plus JSON framing for up to 200 files. */
private const val MAX_PUBLISH_BODY_BYTES = 8 * 1024 * 1024
private const val MAX_REPORT_BODY_BYTES = 131_072

private fun Skill.view() = SkillView(id, name, pinnedVersion, retired, createdAt, updatedAt)
private fun SkillVersion.view(pinned: String?) = SkillVersionView(version, digest, sizeBytes, fileCount, publishedBy, publishedAt, retired, version == pinned)
private fun SkillAssignment.view() = SkillAssignmentView(memberId, decision.wire, updatedAt)
private fun EffectiveSkill.view() = EffectiveSkillView(name, version, digest, sizeBytes, fileCount)
private fun SkillInstallation.view() = SkillInstallationView(deviceId, deviceName, name, version, digest, status.wire, reportedAt)
private fun SkillDownload.view() = SkillArtifactView(skill.name, skill.version, skill.digest, entries.zip(files).map { (entry, file) ->
    SkillArtifactFile(entry.path, entry.mode.wire, entry.size, entry.sha256, Base64.getEncoder().encodeToString(file.content))
})

private fun fileMode(value: String) = when (value) {
    "file" -> SkillFileMode.FILE
    "executable" -> SkillFileMode.EXECUTABLE
    "symlink" -> throw TeamFailure("invalid_skill_artifact", 400, "Symbolic links cannot be published. Publish the target file instead.")
    else -> throw TeamFailure("invalid_skill_artifact", 400, "A file mode is file or executable.")
}
private fun decision(value: String) = SkillDecision.entries.find { it.wire == value }
    ?: throw TeamFailure("invalid_skill_assignment", 400, "A decision is allow or deny.")
private fun status(value: String) = SkillInstallationStatus.entries.find { it.wire == value }
    ?: throw TeamFailure("invalid_skill_installation", 400, "A status is installed, failed, conflict or removed.")

private suspend inline fun <reified T> ApplicationCall.boundedJson(limit: Int): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) throw TeamFailure("invalid_body", 400, "A JSON body is required.")
    val bytes = receiveChannel().readBounded(limit)
    return try { Serialization.json.decodeFromString<T>(bytes.decodeToString()) }
    catch (_: IllegalArgumentException) { throw TeamFailure("invalid_body", 400, "Invalid request body.") }
}
private fun PublishSkillVersionRequest.files(): List<SkillFile> {
    if (files.size > SkillArtifacts.MAX_FILES) throw TeamFailure("invalid_skill_artifact", 400, "An artifact contains 1–${SkillArtifacts.MAX_FILES} files.")
    return files.map { upload ->
        val content = try { Base64.getDecoder().decode(upload.contentBase64) }
            catch (_: IllegalArgumentException) { throw TeamFailure("invalid_skill_artifact", 400, "File content must be standard padded base64.") }
        SkillFile(upload.path, fileMode(upload.mode), content)
    }
}
private val ApplicationCall.skillName get() = parameters["name"]!!

internal fun Route.skillRoutes(skills: SkillCatalog, team: TeamService, origin: String) {
    val store = skills.manage
    // Catalog administration and CI publication.
    get("/api/v1/skills") { call.respond(store.catalog(call.principal).map { it.view() }) }
    get("/api/v1/skills/{name}/versions") {
        val (skill, versions) = store.versions(call.principal, call.skillName)
        call.respond(SkillDetailView(skill.view(), versions.map { it.view(skill.pinnedVersion) }))
    }
    post("/api/v1/skills/{name}/versions") {
        val name = call.skillName
        skills.publish.authorize(call.principal) // Before buffering up to 8 MiB.
        val input = call.boundedJson<PublishSkillVersionRequest>(MAX_PUBLISH_BODY_BYTES)
        val result = skills.publish(call.principal, name, input.version, input.files())
        call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK,
            PublishSkillVersionResponse(result.skill.view(), result.version.view(result.skill.pinnedVersion), result.created))
    }
    put("/api/v1/skills/{name}/pin") {
        val name = call.skillName
        val input = call.boundedJson<PinSkillRequest>(4_096)
        call.respond(store.pin(call.principal, name, input.version).view())
    }
    delete("/api/v1/skills/{name}/pin") { call.respond(store.unpin(call.principal, call.skillName).view()) }
    delete("/api/v1/skills/{name}") { call.respond(store.retire(call.principal, call.skillName).view()) }
    post("/api/v1/skills/{name}/restore") { call.respond(store.restore(call.principal, call.skillName).view()) }
    delete("/api/v1/skills/{name}/versions/{version}") {
        val name = call.skillName
        val retired = store.retireVersion(call.principal, name, call.parameters["version"]!!)
        call.respond(retired.view(null))
    }
    get("/api/v1/skills/{name}/assignments") { call.respond(store.assignments(call.principal, call.skillName).map { it.view() }) }
    put("/api/v1/skills/{name}/assignments/{memberId}") {
        val name = call.skillName
        val input = call.boundedJson<SetSkillAssignmentRequest>(4_096)
        call.respond(store.assign(call.principal, name, call.parameters["memberId"]!!, decision(input.decision))!!.view())
    }
    delete("/api/v1/skills/{name}/assignments/{memberId}") {
        store.assign(call.principal, call.skillName, call.parameters["memberId"]!!, null)
        call.respond(HttpStatusCode.NoContent)
    }

    // Member delivery: manifest, artifacts and installation status.
    for (base in listOf("/api/v1/skill-delivery", "/api/v1/members/{memberId}/skill-delivery")) get(base) {
        val member = call.parameters["memberId"] ?: call.principal.member.id
        call.respond(SkillManifestView(member, skills.effective(call.principal, member).map { it.view() }))
    }
    get("/api/v1/skill-delivery/{name}/{version}") {
        val name = call.skillName
        call.respond(skills.download(call.principal, name, call.parameters["version"]!!).view())
    }
    put("/api/v1/skill-delivery/installations") {
        val input = call.boundedJson<ReportSkillInstallationsRequest>(MAX_REPORT_BODY_BYTES)
        skills.report(call.principal, input.installations.map { SkillInstallationReport(it.name, it.version, it.digest, status(it.status)) })
        call.respond(HttpStatusCode.NoContent)
    }
    for (base in listOf("/api/v1/skill-delivery/installations", "/api/v1/members/{memberId}/skill-installations")) get(base) {
        val member = call.parameters["memberId"] ?: call.principal.member.id
        call.respond(store.installations(call.principal, member).map { it.view() })
    }

    skillDashboard(skills, team, origin.trimEnd('/'))
}

private fun date(value: Long) = Instant.ofEpochMilli(value).toString()

private fun Route.skillDashboard(skills: SkillCatalog, team: TeamService, origin: String) {
    val store = skills.manage
    get("/dashboard/skills") {
        val actor = call.principal
        if (!actor.member.role.isAdministrator) return@get call.respondRedirect("/dashboard/members/${actor.member.id}/skills")
        val rows = store.catalog(actor).joinToString("") { skill ->
            """<tr><td><a href="/dashboard/skills/${escape(skill.name)}">${escape(skill.name)}</a></td><td>${skill.pinnedVersion?.let { "<code>${escape(it)}</code>" } ?: "<small>Not pinned: not delivered</small>"}</td><td>${if (skill.retired) "Retired" else "Active"}</td><td><small>${date(skill.updatedAt)}</small></td></tr>"""
        }
        call.respondText(call.page("Skill catalog", """<details class='help'><summary>How this works</summary><p>Skills are published by CI through the API with a <code>skills:publish</code> service key owned by an administrator. A version is delivered only after an administrator pins it, and only to members with an explicit <em>allow</em> decision. Delivered files can be copied by their recipients: protect tools and data at the service that owns them.</p></details><table><thead><tr><th>Skill</th><th>Pinned version</th><th>State</th><th>Updated (UTC)</th></tr></thead><tbody>$rows</tbody></table>${if (rows.isEmpty()) "<div class='empty'><strong>No skill has been published yet.</strong></div>" else ""}<p><a href="/dashboard/members/${escape(actor.member.id)}/skills">My delivered skills</a></p>"""), ContentType.Text.Html)
    }
    get("/dashboard/skills/{name}") {
        val actor = call.principal
        val (skill, versions) = store.versions(actor, call.skillName)
        val base = "/dashboard/skills/${escape(skill.name)}"
        val versionRows = versions.joinToString("") { version ->
            val pinned = version.version == skill.pinnedVersion
            val actions = when {
                pinned -> "<small>Approved and delivered</small>"
                version.retired -> "<small>Retired</small>"
                skill.retired -> ""
                else -> """<form method="post" action="$base/pin">${csrf(actor)}<input type="hidden" name="version" value="${escape(version.version)}"><button>Pin this version</button></form><form method="post" action="$base/versions/${escape(version.version)}/retire">${csrf(actor)}<button>Retire version</button></form>"""
            }
            """<tr><td><code>${escape(version.version)}</code>${if (pinned) " <small>Pinned</small>" else ""}<br><small>${escape(version.digest)}</small></td><td>${version.fileCount} files<br><small>${version.sizeBytes} bytes</small></td><td>${escape(version.publishedBy)}<br><small>${date(version.publishedAt)}</small></td><td>$actions</td></tr>"""
        }
        val decisions = store.assignments(actor, skill.name).associate { it.memberId to it.decision }
        val memberRows = team.members(actor).joinToString("") { member ->
            val current = decisions[member.id]
            fun option(value: String, label: String, selected: Boolean) = """<option value="$value" ${if (selected) "selected" else ""}>$label</option>"""
            """<tr><td>${escape(member.username)}<br><a href="/dashboard/members/${escape(member.id)}/skills">Delivered skills and installations</a></td><td><form method="post" action="$base/assignments/${escape(member.id)}">${csrf(actor)}<select name="decision">${option("none", "Not assigned", current == null)}${option("allow", "Yes", current == SkillDecision.ALLOW)}${option("deny", "No", current == SkillDecision.DENY)}</select><button>Save</button></form></td></tr>"""
        }
        val controls = if (skill.retired) """<form method="post" action="$base/restore">${csrf(actor)}<button>Restore skill</button></form>"""
            else """${if (skill.pinnedVersion != null) """<form method="post" action="$base/unpin">${csrf(actor)}<button>Unpin (stop delivery)</button></form>""" else ""}<form method="post" action="$base/retire">${csrf(actor)}<button>Remove from catalog</button></form>"""
        call.respondText(call.page("Skill ${skill.name}", """<details class='help'><summary>How this works</summary><p>${if (skill.retired) "Retired: not delivered and closed to publication. Restoring does not pin a version." else skill.pinnedVersion?.let { "Delivering <code>${escape(it)}</code>." } ?: "No pinned version: nothing is delivered."} Update or roll back by pinning another immutable version.</p></details>$controls<h2>Versions</h2><table><thead><tr><th>Version / digest</th><th>Content</th><th>Published by / UTC</th><th>Manage</th></tr></thead><tbody>$versionRows</tbody></table><h2>Access</h2><p>Only <em>Yes</em> delivers the pinned version. <em>Not assigned</em> currently delivers nothing; it will let group decisions apply once groups exist, while an explicit <em>Yes</em> or <em>No</em> keeps precedence.</p><table><thead><tr><th>Member</th><th>Decision</th></tr></thead><tbody>$memberRows</tbody></table><p><a href="/dashboard/skills">Back to catalog</a></p>"""), ContentType.Text.Html)
    }
    post("/dashboard/skills/{name}/pin") {
        val input = call.form(origin)
        call.respondRedirect("/dashboard/skills/${store.pin(call.principal, call.skillName, input["version"].orEmpty()).name}")
    }
    post("/dashboard/skills/{name}/unpin") { call.form(origin); call.respondRedirect("/dashboard/skills/${store.unpin(call.principal, call.skillName).name}") }
    post("/dashboard/skills/{name}/retire") { call.form(origin); call.respondRedirect("/dashboard/skills/${store.retire(call.principal, call.skillName).name}") }
    post("/dashboard/skills/{name}/restore") { call.form(origin); call.respondRedirect("/dashboard/skills/${store.restore(call.principal, call.skillName).name}") }
    post("/dashboard/skills/{name}/versions/{version}/retire") {
        call.form(origin)
        val name = call.skillName
        store.retireVersion(call.principal, name, call.parameters["version"]!!)
        call.respondRedirect("/dashboard/skills/$name")
    }
    post("/dashboard/skills/{name}/assignments/{memberId}") {
        val input = call.form(origin)
        val name = call.skillName
        val value = input["decision"].orEmpty()
        store.assign(call.principal, name, call.parameters["memberId"]!!, if (value == "none") null else decision(value))
        call.respondRedirect("/dashboard/skills/$name")
    }
    get("/dashboard/members/{memberId}/skills") {
        val actor = call.principal
        val member = call.parameters["memberId"]!!
        val delivered = skills.effective(actor, member).joinToString("") { skill ->
            """<tr><td>${escape(skill.name)}</td><td><code>${escape(skill.version)}</code><br><small>${escape(skill.digest)}</small></td><td>${skill.fileCount} files<br><small>${skill.sizeBytes} bytes</small></td></tr>"""
        }
        val installed = store.installations(actor, member).joinToString("") { item ->
            """<tr><td>${escape(item.deviceName)}</td><td>${escape(item.name)}</td><td>${item.version?.let { "<code>${escape(it)}</code>" } ?: ""}</td><td>${escape(item.status.wire)}</td><td><small>${date(item.reportedAt)}</small></td></tr>"""
        }
        call.respondText(call.page("Delivered skills", """<p>Pinned versions this member is currently allowed to install. Installation status is reported by each registered CLI device and does not grant access.</p><table><thead><tr><th>Skill</th><th>Version / digest</th><th>Content</th></tr></thead><tbody>$delivered</tbody></table>${if (delivered.isEmpty()) "<div class='empty'><strong>No skill is delivered to this member.</strong></div>" else ""}<h2>Installations by device</h2><table><thead><tr><th>Device</th><th>Skill</th><th>Version</th><th>Status</th><th>Reported (UTC)</th></tr></thead><tbody>$installed</tbody></table>${if (installed.isEmpty()) "<div class='empty'><strong>No installation reported yet. CLI synchronization is planned.</strong></div>" else ""}"""), ContentType.Text.Html)
    }
}
