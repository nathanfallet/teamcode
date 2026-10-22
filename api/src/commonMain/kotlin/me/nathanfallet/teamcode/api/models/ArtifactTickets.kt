package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Asks for a view ticket of [version] (the current one when null), for an app that shows the artifact itself. */
@Serializable
data class IssueArtifactTicketRequest(val version: Int? = null)

/**
 * A view ticket for one version: [contentUrl] serves its entry page, and the files beside it, until [expiresAt] (epoch
 * milliseconds) or until the member loses access or signs out, whichever comes first. It carries no credential.
 */
@Serializable
data class ArtifactTicketView(val artifactId: String, val version: Int, val contentUrl: String, val expiresAt: Long)
