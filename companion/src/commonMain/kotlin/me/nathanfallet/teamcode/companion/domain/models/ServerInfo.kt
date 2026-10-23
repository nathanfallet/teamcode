package me.nathanfallet.teamcode.companion.domain.models

/**
 * A server found at [origin] before sign-in, shown with its origin so the member knows where the password goes.
 * TeamCode keeps its version and capabilities behind authentication: [apiVersion] is only known before sign-in when
 * the server announces it to anonymous callers, and [ServerCapabilities] are read with the session the sign-in creates.
 */
data class ServerInfo(
    val origin: String,
    val apiVersion: Int? = null,
)

/** What an authenticated `GET /api/v1/capabilities` announces. */
data class ServerCapabilities(
    val serverVersion: String,
    val apiVersion: Int,
    val features: Set<String>,
)
