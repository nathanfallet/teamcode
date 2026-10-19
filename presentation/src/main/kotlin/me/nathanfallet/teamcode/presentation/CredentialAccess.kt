package me.nathanfallet.teamcode.presentation

import me.nathanfallet.teamcode.domain.team.CredentialAction

private val cancelPath = Regex("/api/v1/requests/[0-9a-f-]{36}/cancel")
private val skillPublishPath = Regex("/api/v1/skills/[a-z0-9-]{1,64}/versions")
private val skillArtifactPath = Regex("/api/v1/skill-delivery/[a-z0-9-]{1,64}/[^/]{1,64}")

/** Classify transport operations; unrecognized/new endpoints require an interactive login. */
internal fun credentialAction(method: String, path: String, inferencePaths: Set<String>): CredentialAction = when {
    method == "GET" && path == "/api/v1/capabilities" -> CredentialAction.DISCOVERY
    method == "GET" && path == "/api/v1/auth/me" -> CredentialAction.IDENTITY
    method == "POST" && path == "/api/v1/auth/logout" -> CredentialAction.LOGOUT
    method == "POST" && path in inferencePaths -> CredentialAction.INFERENCE
    method == "POST" && cancelPath.matches(path) -> CredentialAction.INFERENCE
    method == "GET" && path == "/api/v1/capacity" -> CredentialAction.USAGE_READ
    method == "GET" && path == "/api/v1/directives/current" -> CredentialAction.GUIDANCE
    method == "POST" && path in setOf("/api/v1/directives/current", "/api/v1/directives/claim", "/api/v1/directives/receipt") -> CredentialAction.GUIDANCE
    method == "POST" && skillPublishPath.matches(path) -> CredentialAction.SKILLS_PUBLISH
    method == "GET" && path == "/api/v1/skill-delivery" -> CredentialAction.SKILLS_READ
    method == "GET" && skillArtifactPath.matches(path) -> CredentialAction.SKILLS_READ
    else -> CredentialAction.INTERACTIVE
}
