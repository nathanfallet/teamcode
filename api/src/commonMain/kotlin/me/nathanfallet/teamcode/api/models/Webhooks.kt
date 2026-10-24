package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** A team-wide webhook. [types] are the event types of the caller-wide feed; [disabledReason] is `disabled` or `too_many_failures`. */
@Serializable
data class WebhookView(
    val id: String, val url: String, val types: List<String>, val description: String, val enabled: Boolean, val disabledReason: String?,
    val createdBy: String, val createdAt: Long, val updatedAt: Long, val version: Int, val consecutiveFailures: Int,
)

/** [secret] (`whsec_...`) is returned by creation and rotation only: store it, it cannot be read again. */
@Serializable
data class CreatedWebhookView(val webhook: WebhookView, val secret: String)

@Serializable
data class CreateWebhookRequest(val url: String, val types: List<String>, val description: String = "")

/** [version] is the one being replaced; an omitted [enabled] keeps the current state, so editing a paused or auto-disabled webhook never re-enables it. */
@Serializable
data class UpdateWebhookRequest(val version: Int, val url: String, val types: List<String>, val description: String = "", val enabled: Boolean? = null)

/** One attempt series for one event; [state] is `pending`, `delivered` or `failed`. [lastError] is a short code (`timeout`, `dns`, `tls`, `connect`, `host_not_allowed`, `secret_unreadable`, `expired`; `invalid_url` only in deliveries logged by older servers), never a response body. */
@Serializable
data class WebhookDeliveryView(
    val id: String, val eventSeq: Long?, val eventType: String, val state: String, val attempts: Int, val nextAttemptAt: Long?,
    val lastStatus: Int?, val lastError: String?, val createdAt: Long, val deliveredAt: Long?, val test: Boolean,
)
