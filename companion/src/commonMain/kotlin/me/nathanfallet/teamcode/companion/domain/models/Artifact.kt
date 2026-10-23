package me.nathanfallet.teamcode.companion.domain.models

/**
 * An artifact the member can read. [url] is the viewer page, the one link to share. [access] is `personal`, `team`
 * or `public`; [originKind] says what published it (`local_run`, `cloud_session`, `api`...). Times in epoch milliseconds.
 */
data class Artifact(
    val id: String,
    val url: String,
    val title: String,
    val description: String,
    val icon: String,
    val access: String,
    val ownerName: String,
    val currentVersion: Int,
    val originKind: String,
    val originSessionId: String?,
    val updatedAt: Long,
)

data class ArtifactPage(val artifacts: List<Artifact>, val nextBefore: String?)

data class ArtifactVersion(val number: Int, val createdAt: Long, val fileCount: Int, val totalBytes: Long)

/** A view ticket: [contentUrl] serves one version's entry page and its files until [expiresAt], without any credential. */
data class ArtifactTicket(val artifactId: String, val version: Int, val contentUrl: String, val expiresAt: Long)

/** The gallery's tabs: the member's own artifacts, or everything they can read in the team. */
enum class ArtifactScope(val wire: String) { MINE("mine"), TEAM("team") }
