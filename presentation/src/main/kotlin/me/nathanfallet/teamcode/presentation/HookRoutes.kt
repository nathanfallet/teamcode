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
import me.nathanfallet.teamcode.domain.hooks.*
import me.nathanfallet.teamcode.domain.hooks.Hook
import me.nathanfallet.teamcode.domain.skills.SkillDecision
import me.nathanfallet.teamcode.domain.skills.SkillFile
import me.nathanfallet.teamcode.domain.skills.SkillFileMode
import me.nathanfallet.teamcode.domain.team.*

/** Base64 of the 1 MiB content bound plus JSON framing for up to 50 files. */
private const val MAX_PUBLISH_BODY_BYTES = 2 * 1024 * 1024

private fun HookDefinition.view() = HookDefinitionView(events.map { it.wire }.sorted(), matcher, command, timeoutSeconds)
private fun Hook.view() = HookView(id, name, pinnedVersion, createdAt, updatedAt)
private fun HookVersion.view(pinned: String?) = HookVersionView(version, digest, definition.view(), sizeBytes, fileCount, publishedBy, publishedAt, version == pinned)
private fun HookAssignment.view() = HookAssignmentView(memberId, decision.wire, updatedAt)
private fun EffectiveHook.view() = EffectiveHookView(name, version, digest, definition.view(), sizeBytes, fileCount)
private fun HookDownload.view() = HookArtifactView(hook.name, hook.version, hook.digest, hook.definition.view(), entries.zip(files).map { (entry, file) ->
    SkillArtifactFile(entry.path, entry.mode.wire, entry.size, entry.sha256, Base64.getEncoder().encodeToString(file.content))
})

private fun HookDefinitionView.domain(): HookDefinition {
    if (events.size > HookEvent.entries.size) throw TeamFailure("invalid_hook_definition", 400, "List each event once.")
    val parsed = events.map { HookEvent.of(it) ?: throw TeamFailure("invalid_hook_definition", 400,
        "Unknown event $it. Use ${HookEvent.entries.joinToString(", ") { e -> e.wire }}.") }
    if (parsed.toSet().size != parsed.size) throw TeamFailure("invalid_hook_definition", 400, "List each event once.")
    return HookDefinition(parsed.toSet(), matcher, command, timeoutSeconds)
}
private fun PublishHookVersionRequest.files(): List<SkillFile> {
    if (files.size > HookArtifacts.MAX_FILES) throw TeamFailure("invalid_hook_artifact", 400, "An artifact contains 1–${HookArtifacts.MAX_FILES} files.")
    return files.map { upload ->
        val content = try { Base64.getDecoder().decode(upload.contentBase64) }
            catch (_: IllegalArgumentException) { throw TeamFailure("invalid_hook_artifact", 400, "File content must be standard padded base64.") }
        val mode = when (upload.mode) {
            "file" -> SkillFileMode.FILE
            "executable" -> SkillFileMode.EXECUTABLE
            "symlink" -> throw TeamFailure("invalid_hook_artifact", 400, "Symbolic links cannot be published. Publish the target file instead.")
            else -> throw TeamFailure("invalid_hook_artifact", 400, "A file mode is file or executable.")
        }
        SkillFile(upload.path, mode, content)
    }
}
private fun decision(value: String) = SkillDecision.entries.find { it.wire == value }
    ?: throw TeamFailure("invalid_hook_assignment", 400, "A decision is allow or deny.")

private suspend inline fun <reified T> ApplicationCall.hookJson(limit: Int): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) throw TeamFailure("invalid_body", 400, "A JSON body is required.")
    val bytes = receiveChannel().readBounded(limit)
    return try { Serialization.json.decodeFromString<T>(bytes.decodeToString()) }
    catch (_: IllegalArgumentException) { throw TeamFailure("invalid_body", 400, "Invalid request body.") }
}
private val ApplicationCall.hookName get() = parameters["name"]!!

internal fun Route.hookRoutes(hooks: HookCatalog, team: TeamService, origin: String) {
    val store = hooks.manage
    get("/api/v1/hooks") { call.respond(store.catalog(call.principal).map { it.view() }) }
    get("/api/v1/hooks/{name}/versions") {
        val (hook, versions) = store.versions(call.principal, call.hookName)
        call.respond(HookDetailView(hook.view(), versions.map { it.view(hook.pinnedVersion) }))
    }
    post("/api/v1/hooks/{name}/versions") {
        val name = call.hookName
        hooks.publish.authorize(call.principal) // Before buffering the upload.
        val input = call.hookJson<PublishHookVersionRequest>(MAX_PUBLISH_BODY_BYTES)
        val result = hooks.publish(call.principal, name, input.version, input.definition.domain(), input.files())
        call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK,
            PublishHookVersionResponse(result.hook.view(), result.version.view(result.hook.pinnedVersion), result.created))
    }
    get("/api/v1/hooks/{name}/versions/{version}") {
        val (version, files) = store.read(call.principal, call.hookName, call.parameters["version"]!!)
        call.respond(HookArtifactView(call.hookName, version.version, version.digest, version.definition.view(), files.map { file ->
            SkillArtifactFile(file.path, file.mode.wire, file.content.size, me.nathanfallet.teamcode.domain.skills.SkillArtifacts.sha256(file.content),
                Base64.getEncoder().encodeToString(file.content))
        }))
    }
    put("/api/v1/hooks/{name}/pin") {
        val name = call.hookName
        call.principal.requireAdmin() // Refuse before reading the body; service keys never pin a hook.
        val input = call.hookJson<PinHookRequest>(4_096)
        call.respond(store.pin(call.principal, name, input.version).view())
    }
    delete("/api/v1/hooks/{name}/pin") { call.respond(store.unpin(call.principal, call.hookName).view()) }
    get("/api/v1/hooks/{name}/assignments") { call.respond(store.assignments(call.principal, call.hookName).map { it.view() }) }
    put("/api/v1/hooks/{name}/assignments/{memberId}") {
        val name = call.hookName
        val input = call.hookJson<SetHookAssignmentRequest>(4_096)
        call.respond(store.assign(call.principal, name, call.parameters["memberId"]!!, decision(input.decision))!!.view())
    }
    delete("/api/v1/hooks/{name}/assignments/{memberId}") {
        store.assign(call.principal, call.hookName, call.parameters["memberId"]!!, null)
        call.respond(HttpStatusCode.NoContent)
    }

    for (base in listOf("/api/v1/hook-delivery", "/api/v1/members/{memberId}/hook-delivery")) get(base) {
        val member = call.parameters["memberId"] ?: call.principal.member.id
        call.respond(HookManifestView(member, hooks.effective(call.principal, member).map { it.view() }))
    }
    get("/api/v1/hook-delivery/{name}/{version}") {
        val name = call.hookName
        call.respond(hooks.download(call.principal, name, call.parameters["version"]!!).view())
    }

    hookDashboard(hooks, team, origin.trimEnd('/'))
}

private fun date(value: Long) = Instant.ofEpochMilli(value).toString()
private fun HookDefinition.html() = "<code>${escape(events.map { it.wire }.sorted().joinToString(", "))}</code>" +
    (matcher?.let { "<br><small>matcher <code>${escape(it)}</code></small>" } ?: "") +
    "<br><small>runs <code>${escape(command)}</code>, ${timeoutSeconds} s</small>"

private fun Route.hookDashboard(hooks: HookCatalog, team: TeamService, origin: String) {
    val store = hooks.manage
    get("/dashboard/hooks") {
        val actor = call.principal
        if (!actor.member.role.isAdministrator) return@get call.respondRedirect("/dashboard/members/${actor.member.id}/hooks")
        val rows = store.catalog(actor).joinToString("") { hook ->
            """<tr><td><a href="/dashboard/hooks/${escape(hook.name)}">${escape(hook.name)}</a></td><td>${hook.pinnedVersion?.let { "<code>${escape(it)}</code>" } ?: "<small>Not pinned: not delivered</small>"}</td><td><small>${date(hook.updatedAt)}</small></td></tr>"""
        }
        call.respondText(call.page("Team hooks", """<details class='help'><summary>How this works</summary><p>A team hook is a script that the members' agents (Claude Code, and Codex for the events it supports) run on an event such as <code>Stop</code> or <code>PreToolUse</code>. It runs on the member's computer with the member's rights, so it is delivered with three locks: CI publishes versions with a <code>hooks:publish</code> key owned by an administrator, but only an administrator pins a version here; only members with an explicit <em>allow</em> decision receive it; and each member approves each version on their computer (<code>teamcode hooks</code>) before <code>teamcode run</code> installs it. Cloud sessions run the pinned version for allowed owners without a local approval.</p></details><table><thead><tr><th>Hook</th><th>Pinned version</th><th>Updated (UTC)</th></tr></thead><tbody>$rows</tbody></table>${if (rows.isEmpty()) "<div class='empty'><strong>No hook has been published yet.</strong></div>" else ""}<p><a href="/dashboard/members/${escape(actor.member.id)}/hooks">My delivered hooks</a></p>"""), ContentType.Text.Html)
    }
    get("/dashboard/hooks/{name}") {
        val actor = call.principal
        val (hook, versions) = store.versions(actor, call.hookName)
        val base = "/dashboard/hooks/${escape(hook.name)}"
        val versionRows = versions.joinToString("") { version ->
            val pinned = version.version == hook.pinnedVersion
            val read = """<a href="$base/versions/${escape(version.version)}">Read the files</a>"""
            val action = if (pinned) "$read<br><small>Approved and delivered</small>" else
                """$read<form method="post" action="$base/pin">${csrf(actor)}<input type="hidden" name="version" value="${escape(version.version)}"><button>Pin this version</button></form>"""
            """<tr><td><code>${escape(version.version)}</code>${if (pinned) " <small>Pinned</small>" else ""}<br><small>${escape(version.digest)}</small></td><td>${version.definition.html()}</td><td>${version.fileCount} files<br><small>${version.sizeBytes} bytes</small></td><td>${escape(version.publishedBy)}<br><small>${date(version.publishedAt)}</small></td><td>$action</td></tr>"""
        }
        val decisions = store.assignments(actor, hook.name).associate { it.memberId to it.decision }
        val memberRows = team.members(actor).joinToString("") { member ->
            val current = decisions[member.id]
            fun option(value: String, label: String, selected: Boolean) = """<option value="$value" ${if (selected) "selected" else ""}>$label</option>"""
            """<tr><td>${escape(member.username)}</td><td><form method="post" action="$base/assignments/${escape(member.id)}">${csrf(actor)}<select name="decision">${option("none", "Not assigned", current == null)}${option("allow", "Yes", current == SkillDecision.ALLOW)}${option("deny", "No", current == SkillDecision.DENY)}</select><button>Save</button></form></td></tr>"""
        }
        val controls = if (hook.pinnedVersion != null) """<form method="post" action="$base/unpin">${csrf(actor)}<button>Unpin (stop delivery)</button></form>""" else ""
        call.respondText(call.page("Hook ${hook.name}", """<details class='help'><summary>How this works</summary><p>${hook.pinnedVersion?.let { "Delivering <code>${escape(it)}</code>." } ?: "No pinned version: nothing is delivered."} Pinning runs this version's script on the computers of every member with <em>Yes</em> once they approve it, and in their cloud sessions. Read the version's files before pinning it. Update or roll back by pinning another immutable version.</p></details>$controls<h2>Versions</h2><table><thead><tr><th>Version / digest</th><th>Definition</th><th>Content</th><th>Published by / UTC</th><th>Manage</th></tr></thead><tbody>$versionRows</tbody></table><h2>Access</h2><p>Only <em>Yes</em> delivers the pinned version.</p><table><thead><tr><th>Member</th><th>Decision</th></tr></thead><tbody>$memberRows</tbody></table><p><a href="/dashboard/hooks">Back to team hooks</a></p>"""), ContentType.Text.Html)
    }
    get("/dashboard/hooks/{name}/versions/{version}") {
        val (version, files) = store.read(call.principal, call.hookName, call.parameters["version"]!!)
        val name = escape(call.hookName)
        val sections = files.joinToString("") { file ->
            val text = file.content.decodeToString()
            val printable = text.encodeToByteArray().contentEquals(file.content) && text.none { it.isISOControl() && it !in "\n\r\t" }
            """<h2><code>${escape(file.path)}</code> <small>${file.mode.wire}, ${file.content.size} bytes</small></h2>${if (printable) "<pre>${escape(text)}</pre>" else "<p><small>Binary content not shown.</small></p>"}"""
        }
        call.respondText(call.page("Hook $name ${escape(version.version)}", """<p>${version.definition.html()}</p><p><small>${escape(version.digest)}</small></p>$sections<p><a href="/dashboard/hooks/$name">Back to the hook</a></p>"""), ContentType.Text.Html)
    }
    post("/dashboard/hooks/{name}/pin") {
        val input = call.form(origin)
        call.respondRedirect("/dashboard/hooks/${store.pin(call.principal, call.hookName, input["version"].orEmpty()).name}")
    }
    post("/dashboard/hooks/{name}/unpin") { call.form(origin); call.respondRedirect("/dashboard/hooks/${store.unpin(call.principal, call.hookName).name}") }
    post("/dashboard/hooks/{name}/assignments/{memberId}") {
        val input = call.form(origin)
        val name = call.hookName
        val value = input["decision"].orEmpty()
        store.assign(call.principal, name, call.parameters["memberId"]!!, if (value == "none") null else decision(value))
        call.respondRedirect("/dashboard/hooks/$name")
    }
    get("/dashboard/members/{memberId}/hooks") {
        val actor = call.principal
        val delivered = hooks.effective(actor, call.parameters["memberId"]!!).joinToString("") { hook ->
            """<tr><td>${escape(hook.name)}</td><td><code>${escape(hook.version)}</code><br><small>${escape(hook.digest)}</small></td><td>${hook.definition.html()}</td></tr>"""
        }
        call.respondText(call.page("Delivered hooks", """<p>Pinned hook versions this member is currently allowed to install. On their computer, <code>teamcode hooks</code> lists them and <code>teamcode hooks approve</code> accepts a version; <code>teamcode run</code> installs only approved versions.</p><table><thead><tr><th>Hook</th><th>Version / digest</th><th>Definition</th></tr></thead><tbody>$delivered</tbody></table>${if (delivered.isEmpty()) "<div class='empty'><strong>No hook is delivered to this member.</strong></div>" else ""}"""), ContentType.Text.Html)
    }
}
