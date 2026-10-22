package me.nathanfallet.teamcode.companion.domain.models

/** What a server announced before sign-in, shown with its origin so the member knows where the password goes. */
data class ServerInfo(
    val origin: String,
    val serverVersion: String,
    val apiVersion: Int,
    val features: Set<String>,
)
