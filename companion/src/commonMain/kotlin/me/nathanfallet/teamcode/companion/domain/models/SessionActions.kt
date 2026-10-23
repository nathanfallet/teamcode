package me.nathanfallet.teamcode.companion.domain.models

/** The server's acknowledgement of a message or an interrupt. */
data class InputReceipt(
    val inputId: String,
    /** `queued`, `delivered`, `consumed`, `cancelled` or `rejected`. */
    val status: String,
    /** Accepting it resumed a paused session. */
    val woke: Boolean,
    val reason: String?,
)

/** What the new-session form sends; optional fields are left out when empty or when the server does not take them. */
data class NewSession(
    val name: String,
    val profileId: String,
    val model: String?,
    val effort: String?,
    val initialMessage: String?,
) {
    /** What the server receives: the name, model and effort trimmed, empty fields and a blank first message left out. */
    fun normalized(): NewSession = NewSession(name.trim(), profileId, model?.trim()?.ifEmpty { null }, effort?.trim()?.ifEmpty { null }, initialMessage?.takeIf { it.isNotBlank() })
}

/**
 * A message the member sent, shown at once and kept until the transcript records it (its `message.user` event carries
 * [inputId]) or the server refuses it. [clientMessageId] stays the same across retries: the server answers a retry
 * with the first acknowledgement, so a message is never sent twice.
 */
data class PendingMessage(
    val clientMessageId: String,
    val text: String,
    val state: State,
    val inputId: String? = null,
    val woke: Boolean = false,
    /** Why it failed or was refused: a stable code, or the domain's sentence for a network failure. */
    val reason: String? = null,
) {
    enum class State { SENDING, SENT, FAILED, REFUSED }
}
