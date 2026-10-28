package me.nathanfallet.teamcode.companion.domain.usecases.automations

import me.nathanfallet.teamcode.companion.domain.models.AutomationWebhook

/** How the sender proves the request: the HMAC of the body, or the secret in the URL. */
enum class WebhookProof { SIGNATURE, SECRET_URL }

/** Which definition the URL runs: the current one, or only the version it was copied at. */
enum class WebhookVersion { LATEST, PINNED }

/** What goes to the clipboard: the [url], and whether it holds the real secret or the [SECRET_PLACEHOLDER] to replace. */
data class WebhookLink(val url: String, val hasSecret: Boolean)

const val SECRET_PLACEHOLDER = "<secret>"

/**
 * The webhook URL for the chosen [proof] and [version], built from the account's own [origin] (never from an address the
 * server sent). The secret URL carries the secret only while [secret] is held (just after a creation or a rotation:
 * the server never returns it again); otherwise the URL ends with [SECRET_PLACEHOLDER].
 */
fun webhookLink(origin: String, webhook: AutomationWebhook, proof: WebhookProof, version: WebhookVersion, secret: String?): WebhookLink {
    val path = if (version == WebhookVersion.PINNED) webhook.versionedPath else webhook.path
    val base = origin.trimEnd('/') + path
    return when (proof) {
        WebhookProof.SIGNATURE -> WebhookLink(base, hasSecret = false)
        WebhookProof.SECRET_URL -> WebhookLink("$base/${secret ?: SECRET_PLACEHOLDER}", hasSecret = secret != null)
    }
}
