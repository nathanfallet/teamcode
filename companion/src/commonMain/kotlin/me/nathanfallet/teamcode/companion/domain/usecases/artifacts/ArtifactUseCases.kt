package me.nathanfallet.teamcode.companion.domain.usecases.artifacts

import me.nathanfallet.teamcode.companion.domain.models.Artifact
import me.nathanfallet.teamcode.companion.domain.models.ArtifactPage
import me.nathanfallet.teamcode.companion.domain.models.ArtifactScope
import me.nathanfallet.teamcode.companion.domain.models.ArtifactTicket
import me.nathanfallet.teamcode.companion.domain.models.ArtifactVersion
import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.repositories.ArtifactRepository
import me.nathanfallet.teamcode.companion.domain.repositories.Features
import me.nathanfallet.teamcode.companion.domain.repositories.FeaturesRepository
import me.nathanfallet.teamcode.companion.domain.usecases.auth.AccountGuard

/** An artifact id as the server writes it. */
val ARTIFACT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

class LoadArtifactsUseCase(private val artifacts: ArtifactRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, scope: ArtifactScope, sessionId: String? = null, before: String? = null): ArtifactPage = guard(accountId) {
        if (Features.ARTIFACTS !in features.features(accountId)) throw CompanionException.Unsupported(Features.ARTIFACTS)
        artifacts.artifacts(accountId, scope, sessionId, before)
    }
}

/** The artifact and its versions, newest first. */
class GetArtifactUseCase(private val artifacts: ArtifactRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, artifactId: String): Pair<Artifact, List<ArtifactVersion>> = guard(accountId) {
        if (!ARTIFACT_ID.matches(artifactId)) throw CompanionException.NotFound()
        artifacts.artifact(accountId, artifactId) to artifacts.versions(accountId, artifactId)
    }

    /** The artifact alone, to see whether a newer version was published. */
    suspend fun current(accountId: String, artifactId: String): Artifact = guard(accountId) { artifacts.artifact(accountId, artifactId) }
}

/** A ticket checked for this account's server, and the directory the viewer may navigate in. */
data class OpenedArtifact(val ticket: ArtifactTicket, val prefix: String)

/**
 * A ticket to show one version in the app. Its content URL must be on the account's own server and under this
 * artifact's content prefix, so a server can never point the viewer elsewhere; [ArtifactTicket.contentUrl]'s
 * directory is then the only place the viewer may navigate ([contentPrefix]).
 */
class OpenArtifactUseCase(
    private val artifacts: ArtifactRepository,
    private val features: FeaturesRepository,
    private val accounts: AccountRepository,
    private val guard: AccountGuard,
) {
    suspend operator fun invoke(accountId: String, artifactId: String, version: Int?): OpenedArtifact = guard(accountId) {
        if (!ARTIFACT_ID.matches(artifactId)) throw CompanionException.NotFound()
        if (Features.ARTIFACT_TICKETS !in features.features(accountId)) throw CompanionException.Unsupported(Features.ARTIFACT_TICKETS)
        val origin = accounts.accounts.value.firstOrNull { it.id == accountId }?.origin ?: throw CompanionException.Unauthorized()
        val ticket = artifacts.ticket(accountId, artifactId, version)
        val prefix = contentPrefix(origin, artifactId, ticket.contentUrl)
        if (ticket.artifactId != artifactId || (version != null && ticket.version != version) || prefix == null) throw CompanionException.Server(200, "invalid_content_url")
        OpenedArtifact(ticket, prefix)
    }

    companion object {
        private val TICKET = Regex("[A-Za-z0-9_.~-]{1,1024}")

        /**
         * `<origin>/artifact/<id>/<ticket>/` when [contentUrl] is a page under it, with no query, fragment, `..` or
         * escaped character in the ticket; null otherwise.
         */
        fun contentPrefix(origin: String, artifactId: String, contentUrl: String): String? {
            val base = "$origin/artifact/$artifactId/"
            if (!contentUrl.startsWith(base)) return null
            val ticket = contentUrl.removePrefix(base).substringBefore('/')
            if (!TICKET.matches(ticket) || ticket.all { it == '.' }) return null
            val prefix = "$base$ticket/"
            return prefix.takeIf { contentUrl.startsWith(it) && allows(prefix, contentUrl) }
        }

        /** Whether the viewer may load [url] for the ticket's [prefix]: a page under it, never a path that climbs out. */
        fun allows(prefix: String, url: String): Boolean {
            if (!url.startsWith(prefix)) return false
            val path = url.removePrefix(prefix).substringBefore('#').substringBefore('?')
            return path.split('/').none { it == ".." || it == "." } && "%2e" !in path.lowercase() && "%2f" !in path.lowercase() && '\\' !in path
        }
    }
}
