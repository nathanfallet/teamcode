package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.team.*

private fun ApplicationCall.auditCursor(): Long? {
    val values = request.queryParameters.getAll("before") ?: return null
    val value = values.singleOrNull()?.toLongOrNull()
        ?: throw TeamFailure("invalid_cursor", 400, "Use one positive audit event identifier.")
    AuditService.validateCursor(value)
    return value
}
internal fun Route.auditRoutes(audit: AuditService) {
    get("/api/v1/audit") {
        val result = audit.list(call.principal, call.auditCursor())
        call.respond(AuditPageView(result.events.map { AuditEventView(it.id, it.actorId, it.action, it.subjectId, it.occurredAt, it.effectiveMemberId, it.credentialId, it.relatedMemberId) }, result.nextBeforeId))
    }
    get("/dashboard/audit") {
        val result = audit.list(call.principal, call.auditCursor())
        val rows = result.events.joinToString("") { event ->
            """<tr><td>${event.id}<br><small>${Instant.ofEpochMilli(event.occurredAt)}</small></td><td>${escape(event.actorId)}${event.effectiveMemberId?.takeIf { it != event.actorId }?.let { "<br><small>for ${escape(it)}</small>" } ?: ""}</td><td>${escape(event.action)}</td><td>${escape(event.subjectId)}${event.relatedMemberId?.let { "<br><small>with ${escape(it)}</small>" } ?: ""}</td></tr>"""
        }
        val older = result.nextBeforeId?.let { """<a href="/dashboard/audit?before=$it">Older events</a>""" } ?: ""
        call.respondText(page("Audit history", """<p>Administrative and authentication events, newest first. Actor and subject are stable member/resource identifiers; system operations use the actor <code>system</code>. The journal contains metadata only.</p><p><a href="/dashboard/audit">Latest events</a> $older</p><table><thead><tr><th>Event / UTC</th><th>Actor ID</th><th>Action</th><th>Subject ID</th></tr></thead><tbody>$rows</tbody></table>${if (result.events.isEmpty()) "<p>No events on this page.</p>" else ""}"""), ContentType.Text.Html)
    }
}
