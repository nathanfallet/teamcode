package me.nathanfallet.teamcode.presentation

import me.nathanfallet.teamcode.domain.team.CredentialAction

/** Classify transport operations; unrecognized/new endpoints require an interactive login. */
internal fun credentialAction(method: String, path: String, inferencePaths: Set<String>): CredentialAction = when {
    method == "GET" && path == "/api/v1/capabilities" -> CredentialAction.DISCOVERY
    method == "GET" && path == "/api/v1/auth/me" -> CredentialAction.IDENTITY
    method == "POST" && path == "/api/v1/auth/logout" -> CredentialAction.LOGOUT
    method == "POST" && path in inferencePaths -> CredentialAction.INFERENCE
    method == "POST" && Regex("/api/v1/requests/[0-9a-f-]{36}/cancel").matches(path) -> CredentialAction.INFERENCE
    path == MCP_PATH -> CredentialAction.MCP
    method == "GET" && path == "/api/v1/mcp/usage" -> CredentialAction.USAGE_READ
    method == "GET" && path == "/api/v1/capacity" -> CredentialAction.USAGE_READ
    method == "GET" && path == "/api/v1/directives/current" -> CredentialAction.GUIDANCE
    method == "POST" && path in setOf("/api/v1/directives/current", "/api/v1/directives/claim", "/api/v1/directives/receipt") -> CredentialAction.GUIDANCE
    else -> CredentialAction.INTERACTIVE
}
