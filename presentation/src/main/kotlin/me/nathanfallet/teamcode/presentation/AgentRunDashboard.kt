package me.nathanfallet.teamcode.presentation

import me.nathanfallet.teamcode.api.formatUnits
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import me.nathanfallet.teamcode.domain.team.*

/** Server-rendered operational metadata; transcripts and process controls are outside this registry. */
internal fun Route.agentRunDashboardRoutes(runs: AgentRunService, origin: String,
    /** Whether a team's artifacts are on, to link a launch to what it published; null without artifacts. */
    artifactsActive: (suspend (teamId: String) -> Boolean)? = null) {
    get("/dashboard/sessions") {
        val all = call.request.queryParameters["all"] == "true"
        val member = call.request.queryParameters["member"]
        val result = runs.list(call.principal, member, all, call.request.queryParameters["before"])
        val rows = result.runs.joinToString("") { run -> """<tr><td><a href="/dashboard/sessions/${run.id}">${run.id}</a><br>${escape(run.username)} · ${escape(run.agent)} · ${escape(run.workload)}</td><td>${run.state}${activityLabel(run)}<br><small>Started ${instant(run.startedAtMillis)}<br>Heartbeat ${instant(run.heartbeatAtMillis)}</small></td><td>${run.activeRequests} active · ${run.queuedRequests} waiting<br>${run.completed} completed / ${run.attempts} admissions<br>${formatUnits(run.chargedAndReservedUnits)} credits</td></tr>""" }
        val filters = if (call.principal.member.role.isAdministrator) """<p><a href="/dashboard/sessions">My launches</a> · <a href="/dashboard/sessions?all=true">All team launches</a></p>""" else ""
        val query = if (all) "all=true&" else member?.let { "member=${escape(it)}&" }.orEmpty()
        val next = result.nextBeforeId?.let { "<p><a href='/dashboard/sessions?${query}before=$it'>Older launches</a></p>" }.orEmpty()
        call.respondText(call.page("Agent sessions", """<details class='help'><summary>How this works</summary><p>Each current <code>teamcode run claude/codex</code> launch registers one wrapper. RUNNING means a recent wrapper heartbeat; STALE means no recent heartbeat, not proof the agent stopped. Ended and revoked launches remain visible. Native conversations and subagents are tracked separately inside each launch.</p></details>$filters${if (result.runs.isEmpty()) "<div class='empty'><strong>No agent launch yet.</strong></div>" else """<table><thead><tr><th>Launch</th><th>Wrapper observations</th><th>Gateway activity</th></tr></thead><tbody>$rows</tbody></table>"""}$next"""), ContentType.Text.Html)
    }
    get("/dashboard/sessions/{id}") {
        val detail = runs.get(call.principal, call.parameters["id"].orEmpty())
        val run = detail.run
        val rows = detail.conversations.joinToString("") { context ->
            """<tr><td><small>${escape(context.conversationKey)}<br>Session ${escape(context.sessionKey)}</small></td><td>${escape(context.requestedModel)} → ${escape(context.provider)} / ${escape(context.model)}<br><small>${instant(context.lastSeenAtMillis)}</small></td><td>${escape(context.pace ?: "No fresh directive")}<br><small>Bridge received: ${context.receivedByCliAtMillis?.let(::instant) ?: "not recorded"}<br>Hook emitted: ${context.emittedToHookAtMillis?.let(::instant) ?: "not recorded"}</small></td></tr>"""
        }
        val published = if (artifactsActive?.invoke(call.principal.teamId) == true)
            """<p><a href="$ARTIFACT_GALLERY_PATH?scope=${if (call.principal.canReadTeam) "all" else "team"}&amp;run=${escape(run.id.encodeURLParameter())}">Artifacts published from this launch</a></p>""" else ""
        val policy = if (call.principal.member.role.isAdministrator && run.endedAtMillis == null) """<h2>Run limits</h2><form method="post" action="/dashboard/sessions/${run.id}/policy">${csrf(call.principal)}<label>Lifetime credit cap (0 = no extra cap) <input type="number" name="budget" min="0" max="100000000000" value="${run.policy.budgetUnits}" required></label><label>Concurrent request cap (0 = no extra cap) <input type="number" name="concurrent" min="0" max="32" value="${run.policy.maxConcurrent}" required></label><label><input type="checkbox" name="paused" ${if (run.policy.paused) "checked" else ""}> Pause new admissions</label><button>Save run limits</button></form>""" else ""
        call.respondText(call.page("Agent launch ${run.id}", """<p>${escape(run.username)} · ${escape(run.agent)} · ${escape(run.workload)} · ${run.state}${activityLabel(run)}</p><div class="notice">${formatUnits(run.chargedAndReservedUnits)} charged/reserved credits · ${run.activeRequests} active · ${run.queuedRequests} waiting · ${run.completed} completed / ${run.attempts} admissions</div><p>Started ${instant(run.startedAtMillis)} · heartbeat ${instant(run.heartbeatAtMillis)} · wrapper ended ${run.endedAtMillis?.let(::instant) ?: "not recorded"} · exit code ${run.exitCode?.toString() ?: "unknown"}</p><p>Run limits add to member/group/pool limits and apply to requests carrying this launch ID. Pausing or ending a run prevents new admissions; active streams finish normally. Unregistered direct requests still share the member's hard limits.</p>$published<h2>Native conversations</h2><p>Only opaque hashes and latest route metadata are stored. Receipts show bridge/hook emission, not that a model read or obeyed guidance.${if (detail.observationOnly) " Observation-only mode is enabled; proposed guidance is informational and automatic delivery is suppressed." else ""}${if (run.contextsTruncated) " The bounded conversation list is truncated." else ""}</p><table><thead><tr><th>Conversation</th><th>Latest route</th><th>Guidance</th></tr></thead><tbody>$rows</tbody></table>$policy"""), ContentType.Text.Html)
    }
    post("/dashboard/sessions/{id}/policy") {
        val input = call.form(origin.trimEnd('/'))
        val budget = input["budget"]?.toLongOrNull() ?: throw TeamFailure("invalid_run_policy", 400, "Provide an integer credit cap.")
        val maximum = input["concurrent"]?.toIntOrNull() ?: throw TeamFailure("invalid_run_policy", 400, "Provide an integer concurrency cap.")
        val saved = runs.configure(call.principal, call.parameters["id"].orEmpty(), RunPolicy(budget, maximum, input["paused"] == "on"))
        call.respondRedirect("/dashboard/sessions/${saved.id}")
    }
}

private fun instant(millis: Long) = java.time.Instant.ofEpochMilli(millis).toString()

/** What the agent's hooks last said, for a launch with a live wrapper; the agent's words and content never reach the server. */
private fun activityLabel(run: AgentRun): String = when (run.activity) {
    RunActivity.WAITING_FOR_HUMAN -> " · <strong>waiting for a person</strong> since ${instant(run.activitySinceMillis ?: run.heartbeatAtMillis)}"
    RunActivity.WORKING -> " · working"
    null -> ""
}
