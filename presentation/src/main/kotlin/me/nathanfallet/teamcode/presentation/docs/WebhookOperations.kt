package me.nathanfallet.teamcode.presentation.docs

import io.ktor.http.HttpStatusCode
import me.nathanfallet.teamcode.api.models.*

private const val SIGNATURE_TEXT = "Each delivery is a `POST` of `{id, type, createdAt, data}` (`data` is the event of the " +
    "event feed) with `TeamCode-Delivery`, `TeamCode-Event`, `TeamCode-Timestamp` and `TeamCode-Signature: v1=<hex>`, " +
    "the HMAC-SHA256 of `<timestamp>.<body>` keyed with the secret."

/** Team-wide webhooks (administrators and `admin:cloud` keys). */
internal fun ApiCatalog.webhookOperations() {
    get("/api/v1/webhooks") {
        operationId = "listWebhooks"; tag(ApiTags.WEBHOOKS)
        summary = "List the team's webhooks"
        description = "Administrators and an administrator's `admin:cloud` key. $SIGNATURE_TEXT"
        responses { ok<List<WebhookView>>("The webhooks, oldest first.") }
    }
    post("/api/v1/webhooks") {
        operationId = "createWebhook"; tag(ApiTags.WEBHOOKS)
        summary = "Add a webhook"
        description = "Subscribes an `https` URL to event types of the event feed (`cloud.turn`, `run.stale`, ...) for the whole team, " +
            "from now on. The signing secret is in the response and cannot be read again. At most 20 per team. $SIGNATURE_TEXT " +
            "Answer with a 2xx within 10 seconds; network errors, 5xx, 408 and 429 are retried 7 times (10 s to 6 h)."
        body<CreateWebhookRequest>("The URL, event types and an optional description.")
        responses {
            ok<CreatedWebhookView>("Created.", HttpStatusCode.Created)
            badRequest("An invalid URL (not https, credentials, link-local or metadata host), an empty or unknown type (`invalid_webhook`).")
            conflict("The team has 20 webhooks (`webhook_limit`).")
        }
    }
    get("/api/v1/webhooks/{id}") {
        operationId = "getWebhook"; tag(ApiTags.WEBHOOKS)
        summary = "Read a webhook"
        parameters { pathId("id", "The webhook's identifier.") }
        responses { ok<WebhookView>("The webhook."); notFound("No such webhook (`not_found`).") }
    }
    put("/api/v1/webhooks/{id}") {
        operationId = "updateWebhook"; tag(ApiTags.WEBHOOKS)
        summary = "Change a webhook"
        description = "`version` is the one being replaced. Re-enabling restarts at the newest event and clears the failure count: nothing from the time it was off is replayed."
        parameters { pathId("id", "The webhook's identifier.") }
        body<UpdateWebhookRequest>("The new settings.")
        responses {
            ok<WebhookView>("Saved.")
            badRequest("Invalid settings (`invalid_webhook`).")
            notFound("No such webhook (`not_found`).")
            conflict("The webhook changed meanwhile (`stale_version`).")
        }
    }
    delete("/api/v1/webhooks/{id}") {
        operationId = "deleteWebhook"; tag(ApiTags.WEBHOOKS)
        summary = "Delete a webhook"
        description = "Its delivery log goes with it."
        parameters { pathId("id", "The webhook's identifier.") }
        responses { empty("Deleted."); notFound("No such webhook (`not_found`).") }
    }
    get("/api/v1/webhooks/{id}/deliveries") {
        operationId = "listWebhookDeliveries"; tag(ApiTags.WEBHOOKS)
        summary = "The delivery log"
        description = "The newest first, kept 14 days. `lastError` is a short code (`timeout`, `dns`, `tls`, `connect`, `host_not_allowed`, `secret_unreadable`, `expired`; `invalid_url` only in deliveries logged by older servers), never the receiver's answer."
        parameters { pathId("id", "The webhook's identifier."); queryNumber("limit", "1 to 200 (50 by default).") }
        responses { ok<List<WebhookDeliveryView>>("The deliveries."); notFound("No such webhook (`not_found`).") }
    }
    post("/api/v1/webhooks/{id}/test") {
        operationId = "testWebhook"; tag(ApiTags.WEBHOOKS)
        summary = "Send a test delivery"
        description = "Queues one signed `webhook.test` delivery, attempted once and not retried; read its result in the delivery log."
        parameters { pathId("id", "The webhook's identifier.") }
        responses {
            ok<WebhookDeliveryView>("Queued.", HttpStatusCode.Accepted)
            notFound("No such webhook (`not_found`).")
            error(HttpStatusCode.TooManyRequests, "Three tests in ten seconds (`webhook_test_rate`).")
        }
    }
    post("/api/v1/webhooks/{id}/rotate-secret") {
        operationId = "rotateWebhookSecret"; tag(ApiTags.WEBHOOKS)
        summary = "Rotate the signing secret"
        description = "The new secret is in the response and cannot be read again; deliveries still queued are signed with it."
        parameters { pathId("id", "The webhook's identifier.") }
        responses { ok<CreatedWebhookView>("The webhook and its new secret."); notFound("No such webhook (`not_found`).") }
    }
}
