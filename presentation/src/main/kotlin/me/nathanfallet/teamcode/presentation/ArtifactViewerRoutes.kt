package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import me.nathanfallet.teamcode.domain.artifacts.*
import me.nathanfallet.teamcode.domain.team.*

internal const val ARTIFACT_GALLERY_PATH = "/dashboard/artifacts"

/** `/artifact/<id>/…`: everything below the viewer page is an artifact's content, sandboxed whatever answers it. */
internal fun isArtifactContent(path: String) = path.startsWith("/artifact/") && path.indexOf('/', ARTIFACT_PREFIX) >= 0
/** `/artifact/<id>`: the viewer page, the one URL people see and share. */
internal fun isArtifactViewer(path: String) = path.startsWith("/artifact/") && path.length > ARTIFACT_PREFIX && path.indexOf('/', ARTIFACT_PREFIX) < 0
private const val ARTIFACT_PREFIX = "/artifact/".length

/**
 * Set on every response under `/artifact/<id>/` (all statuses, framework errors included) by the gateway's interceptor:
 * the sandbox gives the content an opaque origin even when it is opened on its own, so it never acts with the
 * dashboard's session on this host.
 */
internal const val ARTIFACT_CONTENT_CSP = "sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox; default-src 'none'; " +
    "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob: https://cdnjs.cloudflare.com https://cdn.jsdelivr.net/npm/ https://unpkg.com https://cdn.tailwindcss.com https://code.jquery.com; " +
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' data: https://fonts.gstatic.com; img-src 'self' data: blob:; media-src 'self' data: blob:; " +
    "connect-src 'self'; form-action 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'"
internal val ARTIFACT_CONTENT_HEADERS = listOf(
    "Content-Security-Policy" to ARTIFACT_CONTENT_CSP,
    "Referrer-Policy" to "no-referrer",
    "X-Content-Type-Options" to "nosniff",
    "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), usb=(), payment=(), display-capture=()",
    // Relative fetches come from the sandbox's opaque origin; access is in the path's ticket, never in a cookie.
    "Access-Control-Allow-Origin" to "*",
    "X-Robots-Tag" to "noindex, nofollow",
)
/** The viewer page: a dashboard page that frames only this host's content and is never framed itself. */
internal const val ARTIFACT_VIEWER_CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none';"

private val VIEWER_SCRIPT = requireNotNull(object {}.javaClass.getResourceAsStream("/me/nathanfallet/teamcode/presentation/artifact-viewer.js")) { "Missing artifact viewer resource" }
    .use { it.readBytes().decodeToString() }

private val LOGIN_NEXT = Regex("/artifact/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\\?v=[0-9]{1,9})?")
/** A sign-in may continue to an artifact's viewer page, and nowhere else. */
internal fun loginNext(value: String?): String? = value?.takeIf { LOGIN_NEXT.matches(it) }

/**
 * The viewer page `/artifact/<id>`, its content `/artifact/<id>/<ticket>/<path>`, and the dashboard's gallery and
 * forms. The viewer authenticates itself (the session cookie, also set for `/artifact`), so a public artifact opens
 * without signing in; content is authorized by its ticket only.
 */
internal fun Route.artifactViewerRoutes(viewing: ArtifactViewing, artifacts: ArtifactService, team: TeamService, publicOrigin: String) {
    val origin = publicOrigin.trimEnd('/')

    get("/artifact/{id}") {
        val id = call.parameters["id"]!!
        val requested = call.request.queryParameters["v"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } ?: throw TeamFailure("not_found", 404, "Version not found.") }
        val viewer = call.request.cookies["teamcode_session"]?.let { team.authenticate(it) }?.takeIf { it.serviceScopes == null }
        if (viewer?.member?.passwordChangeRequired == true) return@get call.respondRedirect("/dashboard/password")
        viewer?.let { call.attributes.put(PRINCIPAL, it) }
        // Turned off or not set up: no artifact exists for anyone, signed in or not.
        val available = if (viewer != null) viewing.availability(viewer.teamId) else viewing.serverAvailability()
        if (!available.active) throw TeamFailure("not_found", 404, "Artifact not found.")
        val shell = try { viewing.shell(viewer, id, requested) } catch (error: TeamFailure) {
            // Not public: sign in first, then 404 if the member may not read it; whether it exists is never revealed.
            if (error.status == 404 && viewer == null && ArtifactService.ID.matches(id))
                return@get call.respondRedirect("/dashboard/login?next=" + ("/artifact/$id" + (requested?.let { "?v=$it" } ?: "")).encodeURLParameter())
            throw error
        }
        call.respondText(viewerPage(shell, viewer, requested != null), ContentType.Text.Html)
    }

    get("/artifact/{id}/{ticket}/{path...}") {
        val id = call.parameters["id"]!!
        val segments = call.parameters.getAll("path").orEmpty().let { if (it.lastOrNull() == "") it.dropLast(1) else it }
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) throw TeamFailure("not_found", 404, "Not found.")
        val path = segments.joinToString("/")
        val ticket = call.parameters["ticket"]!!
        val head = viewing.content(id, ticket, path, null, readBytes = false)
        val etag = "\"${head.file.sha256}${if (head.entry) "-page" else ""}\""
        // Cache headers go only on a file that is sent: an error after this point stays `no-store`.
        fun cacheable() {
            // No longer than the grant's recheck, so a revoked viewer's browser stops showing the content as soon as the server does.
            call.response.header(HttpHeaders.CacheControl, "private, max-age=30, no-transform")
            call.response.header(HttpHeaders.ETag, etag)
        }
        if (call.request.headers.getAll(HttpHeaders.IfNoneMatch).orEmpty().flatMap { it.split(',') }.any { it.trim() == etag || it.trim() == "*" })
            return@get cacheable().also { call.respond(HttpStatusCode.NotModified) }
        val content = try { viewing.content(id, ticket, path, call.request.headers[HttpHeaders.Range]) } catch (error: ArtifactRangeFailure) {
            call.response.header(HttpHeaders.ContentRange, "bytes */${error.size}")
            return@get call.respondText("The requested range cannot be served.", ContentType.Text.Plain, HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        cacheable()
        val bytes = content.bytes!!
        if (content.entry) {
            if (content.file.path.lowercase().endsWith(".md"))
                return@get call.respondText(ArtifactPages.markdown(bytes.decodeToString(), content.file.path.substringAfterLast('/')), ContentType.Text.Html.withCharset(Charsets.UTF_8))
            val (page, utf8) = ArtifactPages.html(bytes)
            return@get call.respondBytes(page, if (utf8) ContentType.Text.Html.withCharset(Charsets.UTF_8) else ContentType.Text.Html)
        }
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        // Only `.html`/`.htm` are HTML; Markdown that is not the entry page is shown as text.
        val type = if (content.file.contentType.startsWith("text/markdown")) ContentType.Text.Plain.withCharset(Charsets.UTF_8) else ContentType.parse(content.file.contentType)
        val range = content.range
        if (range != null) {
            call.response.header(HttpHeaders.ContentRange, "bytes ${range.first}-${range.last}/${content.totalSize}")
            return@get call.respondBytes(bytes, type, HttpStatusCode.PartialContent)
        }
        call.respondBytes(bytes, type)
    }

    get("/dashboard/assets/artifact-viewer.js") {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(VIEWER_SCRIPT, ContentType.parse("application/javascript"))
    }

    get(ARTIFACT_GALLERY_PATH) {
        val principal = call.principal
        if (!viewing.availability(principal.teamId).active) {
            // Off or not set up: administrators are sent to the settings; for members the page does not exist.
            if (principal.member.role.isAdministrator) return@get call.respondRedirect(ARTIFACT_SETTINGS_PATH)
            throw TeamFailure("not_found", 404, "Page not found.")
        }
        val scope = call.request.queryParameters["scope"]?.let { ArtifactScope.parse(it) ?: throw TeamFailure("invalid_artifact_query", 400, "Choose mine, shared, team or all.") } ?: ArtifactScope.TEAM
        val page = artifacts.list(principal, ArtifactQuery(scope, before = call.request.queryParameters["before"]))
        val tabs = listOfNotNull(ArtifactScope.TEAM to "Everything I can read", ArtifactScope.MINE to "Mine", ArtifactScope.SHARED to "Shared with me",
            (ArtifactScope.ALL to "All (administrators)").takeIf { principal.canReadTeam })
        val nav = """<p class="tabs">""" + tabs.joinToString(" · ") { (value, label) ->
            if (value == scope) "<strong>${escape(label)}</strong>" else """<a href="$ARTIFACT_GALLERY_PATH?scope=${value.wire}">${escape(label)}</a>"""
        } + "</p>"
        val cards = if (page.artifacts.isEmpty()) empty("No artifact here yet", "<p>Agents publish artifacts with <code>teamcode artifacts publish</code>; they appear here.</p>")
        else """<div class="cards">""" + page.artifacts.joinToString("") { card(it) } + "</div>"
        val more = page.nextBefore?.let { """<p><a href="$ARTIFACT_GALLERY_PATH?scope=${scope.wire}&amp;before=${escape(it.encodeURLParameter())}">Older artifacts</a></p>""" }.orEmpty()
        call.respondText(call.page("Artifacts", nav + cards + more), ContentType.Text.Html)
    }

    get("$ARTIFACT_GALLERY_PATH/{id}/live") {
        val version = viewing.latest(call.principal, call.parameters["id"]!!)
        call.respondText("""{"version":$version}""", ContentType.Application.Json)
    }
    post("$ARTIFACT_GALLERY_PATH/{id}/access") {
        val input = call.form(origin)
        val id = call.parameters["id"]!!
        val pinned = input["publicVersion"]?.takeIf { it.isNotEmpty() && it != "latest" }?.let { it.toIntOrNull() ?: throw TeamFailure("invalid_artifact_access", 400, "Choose the latest version or one version.") }
        artifacts.setAccess(call.principal, id, input["access"].orEmpty(), pinned == null, pinned)
        call.respondRedirect("/artifact/$id")
    }
    post("$ARTIFACT_GALLERY_PATH/{id}/members") {
        val input = call.form(origin)
        val id = call.parameters["id"]!!
        artifacts.share(call.principal, id, input["member"].orEmpty(), input["role"] ?: "viewer")
        call.respondRedirect("/artifact/$id")
    }
    post("$ARTIFACT_GALLERY_PATH/{id}/members/{member}/remove") {
        call.form(origin)
        val id = call.parameters["id"]!!
        artifacts.unshare(call.principal, id, call.parameters["member"]!!)
        call.respondRedirect("/artifact/$id")
    }
    post("$ARTIFACT_GALLERY_PATH/{id}/delete") {
        val input = call.form(origin)
        val id = call.parameters["id"]!!
        val artifact = artifacts.artifact(call.principal, id)
        if (input["confirm"]?.trim() != artifact.title.ifEmpty { "Untitled" }) throw TeamFailure("artifact_delete_unconfirmed", 400, "Type the artifact's title exactly to delete it.")
        artifacts.delete(call.principal, id)
        call.respondRedirect(ARTIFACT_GALLERY_PATH)
    }
}

private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT).withZone(ZoneOffset.UTC)
private fun time(millis: Long) = TIME.format(Instant.ofEpochMilli(millis))
internal fun byteSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
}
private fun originLabel(artifact: Artifact) = when (artifact.origin.kind) {
    "local_run" -> "local run" + (artifact.origin.runId?.let { " ${it.take(8)}" } ?: "")
    "cloud_session" -> "cloud session" + (artifact.origin.sessionId?.let { " ${it.take(8)}" } ?: "")
    else -> "API"
}

private fun card(artifact: Artifact): String {
    val chips = listOfNotNull("""<span class="chip">${escape(artifact.access.wire)}</span>""", artifact.icon.takeIf { it.isNotEmpty() }?.let { """<span class="chip">${escape(it)}</span>""" }).joinToString(" ")
    return """<div class="card"><p class="card-title">$chips</p><h3><a href="/artifact/${escape(artifact.id)}">${escape(artifact.title.ifEmpty { "Untitled" })}</a></h3>""" +
        (artifact.description.takeIf { it.isNotEmpty() }?.let { "<p>${escape(it)}</p>" } ?: "") +
        """<small>${escape(artifact.ownerName)} · v${artifact.currentVersion} · ${escape(originLabel(artifact))} · ${time(artifact.updatedAt)}</small></div>"""
}

/** The viewer: a bar with the title, version, sharing and files, and the content in a sandboxed frame below. */
private fun viewerPage(shell: ArtifactShell, viewer: Principal?, pinned: Boolean): String {
    val artifact = shell.artifact
    val version = shell.version
    val id = escape(artifact.id)
    val title = artifact.title.ifEmpty { "Untitled" }
    val administrator = viewer?.member?.role?.isAdministrator == true && shell.member
    val owner = artifact.relation == "owner"
    val manageAccess = shell.member && (owner || administrator)
    val invite = shell.member && (owner || artifact.relation == ArtifactMemberRole.EDITOR.wire)
    val byline = if (shell.member) "${escape(artifact.ownerName)} · " else ""
    // Only members learn that newer versions exist: a public link pinned to an older one says nothing about them.
    val latest = if (!shell.member || version.number == artifact.currentVersion) "" else """ · <a href="/artifact/$id">latest is v${artifact.currentVersion}</a>"""
    val panels = buildList {
        if (shell.member && shell.versions.size > 1) add("""<details><summary>Versions</summary><div class="panel"><ul>""" + shell.versions.joinToString("") {
            val current = if (it.number == version.number) " aria-current=\"page\"" else ""
            """<li><a href="/artifact/$id?v=${it.number}"$current>v${it.number}</a> · ${time(it.createdAt)} · ${it.fileCount} file(s), ${byteSize(it.totalBytes)}</li>"""
        } + "</ul></div></details>")
        add("""<details><summary>Files</summary><div class="panel"><table><thead><tr><th>Path</th><th class="num">Size</th><th>Type</th></tr></thead><tbody>""" +
            version.files.joinToString("") { """<tr><td>${escape(it.path)}${if (it.role == "page") " <span class=\"chip\">page</span>" else ""}</td><td class="num">${byteSize(it.size)}</td><td>${escape(it.contentType)}</td></tr>""" } +
            "</tbody></table></div></details>")
        if (shell.member && viewer != null && (manageAccess || invite)) add(sharingPanel(shell, viewer, manageAccess, invite))
        if (shell.member && viewer != null && (owner || administrator)) add("""<details><summary>Delete</summary><div class="panel"><form method="post" action="$ARTIFACT_GALLERY_PATH/$id/delete">${csrf(viewer)}<p>Deleting removes every version and its files for everyone, at once and for good. Type the title (${escape(title)}) to confirm.</p><label>Title <input name="confirm" required autocomplete="off"></label><button class="danger">Delete this artifact</button></form></div></details>""")
    }
    val banner = if (!shell.member) """<p class="notice warn" role="note">Content published by a member of a TeamCode team. It is not verified; be careful with what it asks you to do.</p>""" else ""
    val live = if (shell.member && !pinned) """<p id="artifact-newer" class="notice" hidden>A newer version is published. <a href="/artifact/$id">Show it</a></p><script src="/dashboard/assets/artifact-viewer.js" defer></script>""" else ""
    val back = if (shell.member) """<a class="brand" href="$ARTIFACT_GALLERY_PATH" title="Artifacts"><span class="glyph" aria-hidden="true"></span></a>""" else """<span class="brand"><span class="glyph" aria-hidden="true"></span></span>"""
    val bar = """<header class="viewer-bar" data-artifact="$id" data-version="${version.number}">$back<div class="viewer-title"><h1>${escape(title)}</h1>""" +
        """<small>$byline<span class="chip">${escape(artifact.access.wire)}</span> v${version.number} · ${time(version.createdAt)}$latest</small></div>""" +
        """<div class="viewer-panels">${panels.joinToString("")}</div></header>"""
    val frame = """<iframe class="viewer-frame" src="/artifact/$id/${escape(shell.ticket)}/${escape(version.entry)}" sandbox="allow-scripts allow-popups allow-popups-to-escape-sandbox" referrerpolicy="no-referrer" title="${escape(title)}"></iframe>"""
    return viewerShell(title, bar + banner + live + frame)
}

private fun sharingPanel(shell: ArtifactShell, viewer: Principal, manageAccess: Boolean, invite: Boolean): String {
    val artifact = shell.artifact
    val id = escape(artifact.id)
    val access = if (!manageAccess) "" else {
        fun option(value: String, label: String) = """<option value="$value"${if (artifact.access.wire == value) " selected" else ""}>${escape(label)}</option>"""
        val versions = """<option value="latest"${if (artifact.publicFollowsLatest) " selected" else ""}>The latest version</option>""" +
            shell.versions.joinToString("") { """<option value="${it.number}"${if (!artifact.publicFollowsLatest && artifact.publicVersion == it.number) " selected" else ""}>Version ${it.number} only</option>""" }
        """<form method="post" action="$ARTIFACT_GALLERY_PATH/$id/access">${csrf(viewer)}<label>Who can see it <select name="access">${option("personal", "Only me and invited members")}${option("team", "Every member of the team")}${option("public", "Anyone with the link")}</select></label>""" +
            """<label>A public link shows <select name="publicVersion">$versions</select></label><p><small>The link stays ${escape("/artifact/${artifact.id}")} whatever the access.</small></p><button>Save access</button></form>"""
    }
    val members = shell.sharing?.members.orEmpty()
    val list = if (members.isEmpty()) "<p>Not shared with any member.</p>" else "<ul>" + members.joinToString("") { member ->
        val remove = if (invite) """ <form class="inline" method="post" action="$ARTIFACT_GALLERY_PATH/$id/members/${escape(member.memberId)}/remove">${csrf(viewer)}<button class="link">Remove</button></form>""" else ""
        """<li>${escape(member.username)} <span class="chip">${escape(member.role.wire)}</span>${if (member.reason == "session_transfer") " <small>session transfer</small>" else ""}$remove</li>"""
    } + "</ul>"
    val add = if (!invite) "" else """<form method="post" action="$ARTIFACT_GALLERY_PATH/$id/members">${csrf(viewer)}<label>Invite a member <input name="member" required maxlength="64" placeholder="Username or GitHub login"></label><label>Role <select name="role"><option value="viewer">Viewer</option><option value="editor">Editor</option></select></label><button>Invite</button></form>"""
    return """<details><summary>Share</summary><div class="panel">$access$list$add</div></details>"""
}
