package me.nathanfallet.teamcode.presentation

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.Instant
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.webhooks.*

private val webhookId = Regex("[a-zA-Z0-9-]{1,36}")
private fun ApplicationCall.webhookId() = parameters["id"]!!.also { if (!webhookId.matches(it)) throw TeamFailure("not_found", 404, "Webhook not found.") }

internal fun WebhookSubscription.view() = WebhookView(id, url, types.map { it.wire }.sorted(), description, enabled, disabledReason, createdBy, createdAt, updatedAt, version, failures)
private fun WebhookDelivery.view() = WebhookDeliveryView(id, eventSeq, eventType, state.wire, attempts, nextAttemptAt.takeIf { state == DeliveryState.PENDING }, lastStatus, lastError, createdAt, deliveredAt, test)
private fun CreatedWebhook.view() = CreatedWebhookView(webhook.view(), secret)
private fun types(names: List<String>) = names.map { TeamEventType.parse(it) ?: throw TeamFailure("invalid_webhook", 400, "Unknown event type '${it.take(64)}'.") }.toSet()

/** Administrators and their `admin:cloud` keys; the service rechecks the right on every call. */
internal fun Route.webhookRoutes(webhooks: WebhookService) {
    route("/api/v1/webhooks") {
        get { call.respond(webhooks.list(call.principal).map { it.view() }) }
        post {
            val input = call.jsonBody<CreateWebhookRequest>(8_192)
            call.respond(HttpStatusCode.Created, webhooks.create(call.principal, input.url, types(input.types), input.description).view())
        }
        get("/{id}") { call.respond(webhooks.get(call.principal, call.webhookId()).view()) }
        put("/{id}") {
            val input = call.jsonBody<UpdateWebhookRequest>(8_192)
            call.respond(webhooks.update(call.principal, call.webhookId(), input.version, input.url, types(input.types), input.description, input.enabled).view())
        }
        delete("/{id}") { webhooks.delete(call.principal, call.webhookId()); call.respond(HttpStatusCode.NoContent) }
        get("/{id}/deliveries") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            call.respond(webhooks.deliveries(call.principal, call.webhookId(), limit).map { it.view() })
        }
        post("/{id}/test") { call.respond(HttpStatusCode.Accepted, webhooks.test(call.principal, call.webhookId()).view()) }
        post("/{id}/rotate-secret") { call.respond(webhooks.rotateSecret(call.principal, call.webhookId()).view()) }
    }
}

private const val WEBHOOK_HELP = "Each event of the types you choose is POSTed to your URL as JSON (<code>{id, type, createdAt, data}</code>, <code>data</code> being the event of the event feed) with the headers <code>TeamCode-Delivery</code>, <code>TeamCode-Event</code>, <code>TeamCode-Timestamp</code> and <code>TeamCode-Signature: v1=&lt;hex&gt;</code>, the HMAC-SHA256 of <code>&lt;timestamp&gt;.&lt;body&gt;</code> keyed with the secret. Answer with a 2xx within 10 seconds. A network error, a 5xx, 408 or 429 is retried 7 times with growing waits (10 s to 6 h); another answer is final. Twenty final failures in a row disable the webhook. A webhook receives the whole team's events, so only administrators manage them."

private fun typeBoxes(selected: Set<TeamEventType>) = TeamEventType.entries.joinToString("") {
    """<label><input type="checkbox" name="types" value="${it.wire}" ${if (it in selected || selected.isEmpty()) "checked" else ""}> ${it.wire}</label> """
}
private fun time(millis: Long?) = millis?.let { Instant.ofEpochMilli(it).toString() } ?: "-"

internal fun Route.webhookDashboardRoutes(webhooks: WebhookService, origin: String) {
    val site = origin.trimEnd('/')
    fun secretNotice(secret: String) = """<div class="notice"><strong>Signing secret</strong> (shown once, store it now): <code>${escape(secret)}</code></div>"""
    suspend fun ApplicationCall.list(notice: String = "") {
        val actor = principal
        val rows = webhooks.list(actor).joinToString("") { hook ->
            """<tr><td><a href="/dashboard/webhooks/${escape(hook.id)}">${escape(hook.url)}</a><br><small>${escape(hook.description)}</small></td><td>${hook.types.joinToString(", ") { escape(it.wire) }}</td><td>${if (hook.enabled) "Enabled" else "Disabled" + (hook.disabledReason?.let { " · ${escape(it)}" } ?: "")}</td></tr>"""
        }
        val create = """<h2>Add a webhook</h2><form method="post" action="/dashboard/webhooks">${csrf(actor)}<label>URL (https) <input name="url" maxlength="2048" required></label><label>Description <input name="description" maxlength="200"></label><p>${typeBoxes(emptySet())}</p><button>Add webhook</button></form>"""
        respondText(page("Webhooks", """$notice<details class='help'><summary>How webhooks work</summary><p>$WEBHOOK_HELP</p></details><table><thead><tr><th>URL</th><th>Events</th><th>State</th></tr></thead><tbody>$rows</tbody></table>${if (rows.isEmpty()) "<div class='empty'><strong>No webhooks yet.</strong></div>" else ""}$create"""), ContentType.Text.Html)
    }
    suspend fun ApplicationCall.detail(id: String, notice: String = "") {
        val actor = principal
        val hook = webhooks.get(actor, id)
        val base = "/dashboard/webhooks/${escape(hook.id)}"
        val log = webhooks.deliveries(actor, id, 50).joinToString("") { d ->
            """<tr><td>${time(d.createdAt)}</td><td>${escape(d.eventType)}${if (d.eventSeq != null) " #${d.eventSeq}" else ""}</td><td>${escape(d.state.wire)}</td><td>${d.attempts}${if (d.state == DeliveryState.PENDING) " · next ${time(d.nextAttemptAt)}" else ""}</td><td>${d.lastStatus?.toString() ?: ""} ${escape(d.lastError.orEmpty())}</td></tr>"""
        }
        val edit = """<h2>Settings</h2><form method="post" action="$base">${csrf(actor)}<input type="hidden" name="version" value="${hook.version}"><label>URL (https) <input name="url" value="${escape(hook.url)}" maxlength="2048" required></label><label>Description <input name="description" value="${escape(hook.description)}" maxlength="200"></label><p>${typeBoxes(hook.types)}</p><label><input type="checkbox" name="enabled" ${if (hook.enabled) "checked" else ""}> Enabled</label><button>Save webhook</button></form>"""
        val actions = """<h2>Actions</h2><form method="post" action="$base/test">${csrf(actor)}<button>Send a test</button></form><form method="post" action="$base/rotate-secret">${csrf(actor)}<button>Rotate the secret</button></form><form method="post" action="$base/delete">${csrf(actor)}<button>Delete webhook</button></form>"""
        respondText(page("Webhook", """$notice<details class='help'><summary>How webhooks work</summary><p>$WEBHOOK_HELP</p></details><div class="notice">${if (hook.enabled) "Enabled" else "Disabled" + (hook.disabledReason?.let { " · ${escape(it)}" } ?: "")} · ${hook.failures} failed deliveries in a row · created ${time(hook.createdAt)}</div>$edit$actions<h2>Deliveries</h2><p><a href="$base">Refresh</a> · the 50 latest, kept 14 days. Errors are short codes, never the receiver's answer.</p><table><thead><tr><th>Created (UTC)</th><th>Event</th><th>State</th><th>Attempts</th><th>Last answer</th></tr></thead><tbody>$log</tbody></table>"""), ContentType.Text.Html)
    }
    get("/dashboard/webhooks") { call.list() }
    post("/dashboard/webhooks") {
        val input = call.form(site)
        val created = webhooks.create(call.principal, input["url"].orEmpty(), types(input.getAll("types").orEmpty()), input["description"].orEmpty())
        call.detail(created.webhook.id, secretNotice(created.secret))
    }
    get("/dashboard/webhooks/{id}") { call.detail(call.webhookId()) }
    post("/dashboard/webhooks/{id}") {
        val input = call.form(site); val id = call.webhookId()
        webhooks.update(call.principal, id, input["version"]?.toIntOrNull() ?: throw TeamFailure("invalid_webhook", 400, "Reload the page."), input["url"].orEmpty(),
            types(input.getAll("types").orEmpty()), input["description"].orEmpty(), input["enabled"] == "on")
        call.respondRedirect("/dashboard/webhooks/$id")
    }
    post("/dashboard/webhooks/{id}/test") { call.form(site); val id = call.webhookId(); webhooks.test(call.principal, id); call.respondRedirect("/dashboard/webhooks/$id") }
    post("/dashboard/webhooks/{id}/rotate-secret") {
        call.form(site); val id = call.webhookId()
        call.detail(id, secretNotice(webhooks.rotateSecret(call.principal, id).secret))
    }
    post("/dashboard/webhooks/{id}/delete") { call.form(site); webhooks.delete(call.principal, call.webhookId()); call.respondRedirect("/dashboard/webhooks") }
}
