package me.nathanfallet.teamcode.companion.domain.models

/** A push once decrypted (docs/notifications.md, "Push payload"): which server and member it is for, its text, what it is about. */
data class PushMessage(
    val id: String,
    val origin: String,
    val memberId: String,
    val title: String,
    val body: String,
    val sessionId: String?,
    val waitId: String?,
)
