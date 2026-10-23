package me.nathanfallet.teamcode.companion.infrastructure.repositories

import me.nathanfallet.teamcode.api.models.ArtifactView
import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.ArtifactPage
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.models.ArtifactTicket
import me.nathanfallet.teamcode.companion.domain.models.ArtifactVersion
import me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository

class RemoteArtifactRepository(private val calls: AccountCalls) : ArtifactRepository {
    override suspend fun artifacts(accountId: String, scope: ArtifactScope, sessionId: String?, before: String?): ArtifactPage = calls.withAccount(accountId) { client ->
        val page = client.artifacts(scope = scope.wire, sessionId = sessionId, limit = PAGE, before = before)
        ArtifactPage(page.artifacts.map { it.domain() }, page.nextBefore)
    }

    override suspend fun artifact(accountId: String, artifactId: String): Artifact =
        calls.withAccount(accountId, resource = true) { it.artifact(artifactId).domain() }

    override suspend fun versions(accountId: String, artifactId: String): List<ArtifactVersion> = calls.withAccount(accountId, resource = true) { client ->
        client.artifactVersions(artifactId).map { ArtifactVersion(it.number, it.createdAt, it.fileCount, it.totalBytes) }.sortedByDescending { it.number }
    }

    override suspend fun ticket(accountId: String, artifactId: String, version: Int?): ArtifactTicket = calls.withAccount(accountId, resource = true) { client ->
        val ticket = client.issueArtifactTicket(artifactId, version)
        ArtifactTicket(ticket.artifactId, ticket.version, ticket.contentUrl, ticket.expiresAt)
    }

    private fun ArtifactView.domain() = Artifact(
        id = id, url = url, title = title, description = description, icon = icon, access = access, ownerName = ownerName,
        currentVersion = currentVersion, originKind = originKind, originSessionId = originSessionId, updatedAt = updatedAt,
    )

    private companion object {
        const val PAGE = 50
    }
}
