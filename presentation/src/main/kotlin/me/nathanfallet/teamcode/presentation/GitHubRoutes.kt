package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.time.Instant
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.github.*
import me.nathanfallet.teamcode.domain.team.*

internal const val GITHUB_CALLBACK_PATH = "/dashboard/github/callback"
private const val BROWSER_COOKIE = "teamcode_github_link"

private fun GitHubApp?.view(callbackUrl: String) = GitHubAppView(this != null, this?.appId, this?.clientId, this?.slug, this?.name, this?.version, this?.updatedAt, callbackUrl)
private fun GitHubLinkStatus.view() = GitHubLinkView(memberId, state?.wire ?: GitHubLinkStates.NOT_LINKED, appConfigured, link?.identity?.login, link?.identity?.userId,
    link?.linkedAt, link?.refreshedAt, link?.accessExpiresAt)
private val identifier = Regex("[a-zA-Z0-9-]{1,36}")
private fun ApplicationCall.memberId() = parameters["memberId"]!!.also { if (!identifier.matches(it)) throw TeamFailure("member_not_found", 404, "Member not found.") }

/**
 * GitHub App configuration (administrators) and members' links. The routes never return a GitHub token: tokens reach
 * only the runner of the member's own cloud sessions, through the runner channel.
 */
internal fun Route.githubRoutes(github: GitHubLinkService, team: TeamService, publicOrigin: String) {
    val origin = publicOrigin.trimEnd('/')
    val callback = origin + GITHUB_CALLBACK_PATH
    route("/api/v1/github") {
        get("/app") { call.respond(github.app(call.principal).view(callback)) }
        put("/app") {
            val input = call.jsonBody<SaveGitHubAppRequest>()
            call.respond(github.saveApp(call.principal, input.version, GitHubAppInput(input.appId, input.clientId, input.clientSecret, input.privateKey)).view(callback))
        }
        get("/link") { call.respond(github.status(call.principal).view()) }
        delete("/link") { github.unlink(call.principal); call.respond(HttpStatusCode.NoContent) }
        post("/link/device") {
            val link = github.startDevice(call.principal)
            call.respond(HttpStatusCode.Created, GitHubDeviceLinkView(link.id, link.userCode, link.verificationUri, link.expiresAt, link.intervalSeconds))
        }
        post("/link/device/{id}") {
            val id = call.parameters["id"]!!.takeIf { identifier.matches(it) } ?: throw TeamFailure("github_link_not_found", 404, "Not found.")
            call.respond(when (val progress = github.pollDevice(call.principal, id)) {
                is GitHubDeviceLinkProgress.Pending -> GitHubDeviceLinkPollView("pending", progress.intervalSeconds)
                is GitHubDeviceLinkProgress.Linked -> GitHubDeviceLinkPollView("linked", link = progress.status.view())
                GitHubDeviceLinkProgress.Expired -> GitHubDeviceLinkPollView("expired")
                GitHubDeviceLinkProgress.Denied -> GitHubDeviceLinkPollView("denied")
            })
        }
        get("/links") { call.respond(github.links(call.principal).map { it.view() }) }
        get("/links/{memberId}") { call.respond(github.status(call.principal, call.memberId()).view()) }
        delete("/links/{memberId}") { github.unlink(call.principal, call.memberId()); call.respond(HttpStatusCode.NoContent) }
    }

    get("/dashboard/github") {
        val actor = call.principal
        val own = github.status(actor)
        val member = when {
            !own.appConfigured -> "<p>Your administrator has not configured the team's GitHub App yet.</p>"
            own.state == GitHubLinkState.LINKED && own.link != null -> """<p>Linked as <strong>${escape(own.link!!.identity.login)}</strong> since ${escape(Instant.fromEpochMilliseconds(own.link!!.linkedAt).toString())}. Your cloud sessions use this account for Git and <code>gh</code>.</p><form action="/dashboard/github/unlink" method="post">${csrf(actor)}<button>Unlink GitHub</button></form>"""
            else -> (if (own.state == GitHubLinkState.RECONNECT_REQUIRED) "<p>Your GitHub access must be renewed: link your account again.</p>" else "<p>Link your GitHub account so your cloud sessions can clone and push with your own permissions.</p>") +
                """<form action="/dashboard/github/link" method="post">${csrf(actor)}<button>Link GitHub</button></form><p class="muted">From a terminal: <code>teamcode github link</code></p>"""
        }
        val administration = if (actor.member.role == Role.ADMIN && actor.serviceScopes == null) {
            val app = github.app(actor)
            val statuses = github.links(actor).associateBy { it.memberId }
            val rows = team.members(actor).joinToString("") { member ->
                val status = statuses[member.id]
                val unlink = if (status != null) """<form action="/dashboard/github/links/${escape(member.id)}/unlink" method="post">${csrf(actor)}<button>Unlink</button></form>""" else ""
                """<tr><td>${escape(member.username)}</td><td>${escape(status?.link?.identity?.login ?: "-")}</td><td>${escape(status?.state?.wire ?: GitHubLinkStates.NOT_LINKED)}</td><td>$unlink</td></tr>"""
            }
            """<h2>Team GitHub App</h2><p>Create a GitHub App owned by your organization with user-token expiration enabled, the repository permissions your sessions need (for example Contents and Pull requests: read and write) and <code>${escape(callback)}</code> as a callback URL. Enable Device Flow for <code>teamcode github link</code>. Install it on the repositories sessions may reach. Members' tokens can never exceed both the installation and the member's own access.</p>""" +
                (app?.let { "<p>Configured: App ${it.appId} (${escape(it.slug ?: it.clientId)}), version ${it.version}.</p>" } ?: "<p>No App is configured.</p>") +
                """<form action="/dashboard/github/app" method="post">${csrf(actor)}<input type="hidden" name="version" value="${app?.version ?: ""}"><label>App ID <input name="appId" inputmode="numeric" required value="${app?.appId ?: ""}"></label><label>Client ID <input name="clientId" required maxlength="64" value="${escape(app?.clientId ?: "")}"></label><label>Client secret${if (app != null) " (empty keeps the current one)" else ""} <input type="password" name="clientSecret" autocomplete="off" maxlength="256"${if (app == null) " required" else ""}></label><label>Private key (PEM)${if (app != null) " (empty keeps the current one)" else ""}<br><textarea name="privateKey" rows="6" cols="70" autocomplete="off"${if (app == null) " required" else ""}></textarea></label><button>Save GitHub App</button></form>""" +
                "<h2>Members' links</h2><table><thead><tr><th>Member</th><th>GitHub account</th><th>State</th><th></th></tr></thead><tbody>$rows</tbody></table>"
        } else ""
        call.respondText(page("GitHub", member + administration), ContentType.Text.Html)
    }
    post("/dashboard/github/link") {
        call.form(origin)
        val link = github.startWeb(call.principal, callback)
        // Lax, unlike the session cookie: it must come back on GitHub's top-level redirect to the callback.
        call.response.cookies.append(Cookie(BROWSER_COOKIE, link.browserNonce, path = GITHUB_CALLBACK_PATH, httpOnly = true, secure = origin.startsWith("https://"),
            maxAge = (GitHubLinkService.WEB_ATTEMPT_MILLIS / 1000).toInt(), extensions = mapOf("SameSite" to "Lax")))
        call.respondText(page("Link GitHub", """<p>Continue on GitHub to authorize the team's App. You will come back here afterwards.</p><p><a href="${escape(link.authorizationUrl)}">Continue to GitHub</a></p>"""), ContentType.Text.Html)
    }
    // Reached by GitHub's redirect, without the strict session cookie: the single-use state and the browser cookie
    // identify the attempt, its member and the browser that started it.
    get(GITHUB_CALLBACK_PATH) {
        val q = call.request.queryParameters
        val status = github.completeWeb(q["state"], q["code"], q["error"], call.request.cookies[BROWSER_COOKIE], callback)
        // Cleared only once the attempt matched: an unrelated request to this public route must not break a pending link.
        call.response.cookies.append(Cookie(BROWSER_COOKIE, "", path = GITHUB_CALLBACK_PATH, httpOnly = true, secure = origin.startsWith("https://"), maxAge = 0, extensions = mapOf("SameSite" to "Lax")))
        call.respondText(page("GitHub linked", """<p>Linked as <strong>${escape(status.link!!.identity.login)}</strong>. Your cloud sessions now use this account for Git and <code>gh</code>.</p><p><a href="/dashboard/github">Back to GitHub settings</a></p>"""), ContentType.Text.Html)
    }
    post("/dashboard/github/unlink") {
        call.form(origin); github.unlink(call.principal); call.respondRedirect("/dashboard/github")
    }
    post("/dashboard/github/links/{memberId}/unlink") {
        call.form(origin); github.unlink(call.principal, call.memberId()); call.respondRedirect("/dashboard/github")
    }
    post("/dashboard/github/app") {
        val input = call.form(origin)
        github.saveApp(call.principal, input["version"]?.takeIf { it.isNotBlank() }?.let { it.toIntOrNull() ?: -1 },
            GitHubAppInput(input["appId"]?.trim()?.toLongOrNull() ?: -1, input["clientId"].orEmpty().trim(),
                input["clientSecret"]?.trim()?.takeIf { it.isNotEmpty() }, input["privateKey"]?.trim()?.takeIf { it.isNotEmpty() }))
        call.respondRedirect("/dashboard/github")
    }
}
