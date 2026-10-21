package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import java.time.ZoneOffset
import me.nathanfallet.teamcode.api.formatUnits
import me.nathanfallet.teamcode.api.models.accountLabel
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure

private const val REVIEW_HELP = "Interrupted requests keep their conservative charge until request-specific provider evidence confirms the actual usage. A quota percentage is not a request usage record. Corrections preserve the original member, group, pool and billing day; they never claim that a response was delivered."
internal fun Route.usageReconciliationDashboardRoutes(service: UsageReconciliationService, origin: String) {
    get("/dashboard/usage") {
        val member = call.request.queryParameters["member"]
        val result = service.pending(call.principal, member, call.request.queryParameters["before"])
        val rows = result.requests.joinToString("") { item ->
            """<tr><td><a href="/dashboard/usage/${escape(item.id)}">${escape(item.id)}</a><br>${Instant.ofEpochMilli(item.createdAtMillis)}<br>${if (item.state == "ACTIVE") "Expired lease · usage unknown" else "Usage unknown"}</td><td>${escape(item.username)}<br><a href="/dashboard/usage?member=${escape(item.memberId)}">This member</a><br>${escape(accountLabel(item.accountName, item.accountEmail))} · ${escape(item.provider)}</td><td>${formatUnits(item.units)} credits<br>Billing day ${billingDay(item.dayNumber)}<br>Provider request ${escape(item.providerRequestId ?: "not captured")}</td></tr>"""
        }
        val older = result.nextBeforeId?.let { olderUsage("/dashboard/usage", it, member) }.orEmpty()
        call.respondText(call.page("Pending usage review", """<p><a href='/dashboard/usage'>All pending requests</a> · <a href='/dashboard/usage/history'>Reconciliation history</a></p><p>$REVIEW_HELP</p><p>$older</p><table><thead><tr><th>Request</th><th>Member / account</th><th>Conservative charge</th></tr></thead><tbody>$rows</tbody></table>${if (result.requests.isEmpty()) "<div class='empty'><strong>No requests need review on this page.</strong></div>" else ""}"""), ContentType.Text.Html)
    }
    get("/dashboard/usage/history") {
        val member = call.request.queryParameters["member"]
        val result = service.history(call.principal, member, call.request.queryParameters["before"])
        val rows = result.reconciliations.joinToString("") { item ->
            """<tr><td><a href="/dashboard/usage/${escape(item.id)}">${escape(item.id)}</a><br>${Instant.ofEpochMilli(item.reconciledAtMillis)}</td><td>${formatUnits(item.previousUnits)} → ${formatUnits(item.actualUnits)} credits<br>Billing day ${billingDay(item.dayNumber)}</td><td>${escape(item.actorId)}<br>${escape(item.reference)}<br>${escape(item.reason)}</td></tr>"""
        }
        val older = result.nextBeforeId?.let { olderUsage("/dashboard/usage/history", it, member) }.orEmpty()
        call.respondText(call.page("Usage reconciliation history", """<p><a href='/dashboard/usage'>Pending requests</a> · <a href='/dashboard/usage/history'>Latest corrections</a></p><p>$older</p><table><thead><tr><th>Correction</th><th>Original / verified charge</th><th>Actor and evidence</th></tr></thead><tbody>$rows</tbody></table>"""), ContentType.Text.Html)
    }
    get("/dashboard/usage/{id}") {
        val actor = call.principal
        val result = service.get(actor, call.parameters["id"].orEmpty())
        val content = result.pending?.let { item ->
            """<p>$REVIEW_HELP</p><p>Request <code>${escape(item.id)}</code> · ${escape(item.username)} · ${escape(accountLabel(item.accountName, item.accountEmail))} (${escape(item.provider)})</p><p>Provider correlation: <code>${escape(item.providerRequestId ?: "Not captured")}</code><br>Charged/reserved: ${formatUnits(item.units)} credits · original billing day ${billingDay(item.dayNumber)} · home pool ${escape(item.poolId.ifEmpty { "shared" })} · physical source ${escape(item.sourcePoolId.ifEmpty { "shared" })}<br>Run ${escape(item.runId ?: "not registered")}</p><p>Use verified total team credits: input and cache tokens count once, output tokens count five times. For Responses, input already includes cached tokens. No transcript or provider credential belongs in the evidence fields. A completed correction is final; inspect the record before submitting.</p><form method="post">${csrf(actor)}<input type="hidden" name="expectedUnits" value="${item.units}"><input type="hidden" name="expectedProviderRequestId" value="${escape(item.providerRequestId.orEmpty())}"><label>Verified total request credits <input type="number" name="actualUnits" min="0" max="100000000" required></label><label>Evidence source <select name="source" required><option value="">Choose request-specific provider evidence</option><option value="PROVIDER_RECORD">Provider usage record</option><option value="PROVIDER_CONFIRMATION">Provider confirmation</option></select></label><label>Evidence reference <input name="reference" maxlength="240" required></label><label>Reason <input name="reason" maxlength="240" required></label><button>Reconcile usage</button></form>"""
        } ?: result.reconciliation?.let { item ->
            """<p>Usage reconciled on ${Instant.ofEpochMilli(item.reconciledAtMillis)} by ${escape(item.actorId)}.</p><p>Request <code>${escape(item.id)}</code> · member ${escape(item.memberId)} · account ${escape(item.accountId)}<br>Original billing day ${billingDay(item.dayNumber)} · home pool ${escape(item.poolId.ifEmpty { "shared" })} · physical source ${escape(item.sourcePoolId.ifEmpty { "shared" })}<br>Provider correlation ${escape(item.providerRequestId ?: "not captured")} · run ${escape(item.runId ?: "not registered")}</p><p>Original groups: ${if (item.groupIds.isEmpty()) "none" else item.groupIds.joinToString(", ") { escape(it) }}</p><p>${formatUnits(item.previousUnits)} → ${formatUnits(item.actualUnits)} credits · prior state ${if (item.previousState == "ACTIVE") "Expired lease" else "Unknown usage"}</p><p>Evidence: ${evidenceLabel(item.source)} · ${escape(item.reference)}<br>Reason: ${escape(item.reason)}</p><p>Completion counters remain unchanged. The original attribution and this correction are retained.</p>"""
        } ?: throw TeamFailure("not_found", 404, "Usage review not found.")
        call.respondText(call.page("Usage review", "<p><a href='/dashboard/usage'>Pending requests</a> · <a href='/dashboard/usage/history'>History</a></p>" + content), ContentType.Text.Html)
    }
    post("/dashboard/usage/{id}") {
        val input = call.form(origin.trimEnd('/'))
        val id = call.parameters["id"].orEmpty()
        val expected = input["expectedUnits"]?.toLongOrNull() ?: throw invalidUsageForm()
        val actual = input["actualUnits"]?.toLongOrNull() ?: throw invalidUsageForm()
        service.reconcile(call.principal, id, UsageReconciliationChange(expected, actual, usageEvidence(input["source"].orEmpty()),
            input["reference"].orEmpty(), input["reason"].orEmpty(), input["expectedProviderRequestId"]?.takeIf { it.isNotEmpty() }))
        call.respondRedirect("/dashboard/usage/$id")
    }
}
private fun invalidUsageForm() = TeamFailure("invalid_reconciliation", 400, "Original and verified request credits must be integers.")
private fun billingDay(day: Long) = Instant.ofEpochMilli(day * 86_400_000L).atOffset(ZoneOffset.UTC).toLocalDate()
private fun olderUsage(path: String, before: String, member: String?) = "<a href='$path?before=${escape(before)}${member?.let { "&amp;member=${escape(it)}" }.orEmpty()}'>Older records</a>"

private fun evidenceLabel(source: UsageEvidence) = when (source) { UsageEvidence.PROVIDER_RECORD -> "Provider usage record"; UsageEvidence.PROVIDER_CONFIRMATION -> "Provider confirmation" }
