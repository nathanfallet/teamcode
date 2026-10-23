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
import me.nathanfallet.teamcode.companion.domain.usecases.auth.normalizeOrigin

/** An artifact id as the server writes it. */
val ARTIFACT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

class LoadArtifactsUseCase(private val artifacts: ArtifactRepository, private val features: FeaturesRepository, private val guard: AccountGuard) {
    suspend operator fun invoke(accountId: String, scope: ArtifactScope, sessionId: String? = null, before: String? = null): ArtifactPage = guard(accountId) {
        if (Features.ARTIFACTS !in features.features(accountId)) throw CompanionException.Unsupported(Features.ARTIFACTS)
        artifacts.artifacts(accountId, scope, sessionId, before)
    }
}

/** An artifact, its versions (newest first), and its viewer page on the account's server, the link the app shares. */
data class ArtifactDetails(val artifact: Artifact, val versions: List<ArtifactVersion>, val viewerUrl: String)

class GetArtifactUseCase(private val artifacts: ArtifactRepository, private val accounts: AccountRepository, private val guard: AccountGuard) {
    /** The viewer URL is built from the account's origin, never taken from the answer: the app opens and shares only its own server's page. */
    suspend operator fun invoke(accountId: String, artifactId: String): ArtifactDetails = guard(accountId) {
        if (!ARTIFACT_ID.matches(artifactId)) throw CompanionException.NotFound()
        val origin = accounts.accounts.value.firstOrNull { it.id == accountId }?.origin ?: throw CompanionException.Unauthorized()
        ArtifactDetails(artifacts.artifact(accountId, artifactId), artifacts.versions(accountId, artifactId), "$origin/artifact/$artifactId")
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
        if (prefix == null) throw CompanionException.ForeignContent()
        if (ticket.artifactId != artifactId || (version != null && ticket.version != version)) throw CompanionException.Server(200, "invalid_ticket")
        OpenedArtifact(ticket, prefix)
    }

    companion object {
        private val TICKET = Regex("[A-Za-z0-9_.~-]{1,1024}")

        /**
         * `<origin>/artifact/<id>/<ticket>/` when [contentUrl] is a page under it, with no query, fragment, `..` or
         * escaped character in the ticket; null otherwise.
         */
        fun contentPrefix(origin: String, artifactId: String, contentUrl: String): String? {
            // The server spells its own origin as configured (case, an explicit default port): compared, and kept, in the
            // canonical form, the one web views report the pages they navigate to in.
            val canonical = canonical(contentUrl) ?: return null
            val base = "$origin/artifact/$artifactId/"
            if (!canonical.startsWith(base)) return null
            val ticket = canonical.removePrefix(base).substringBefore('/')
            if (!TICKET.matches(ticket) || ticket.all { it == '.' }) return null
            val prefix = "$base$ticket/"
            return prefix.takeIf { canonical.startsWith(it) && allows(prefix, canonical) }
        }

        /** Whether the viewer may load [url] for the ticket's [prefix]: a page under it, never a path that climbs out. */
        fun allows(prefix: String, url: String): Boolean {
            val canonical = canonical(url) ?: return false
            if (!canonical.startsWith(prefix)) return false
            val path = canonical.removePrefix(prefix).substringBefore('#').substringBefore('?').lowercase()
            // Dots, slashes and backslashes, escaped or not, once or twice (`%252e`); a plain escaped percent is a file name.
            val once = path.replace("%25", "%")
            return path.split('/').none { it == ".." || it == "." } && listOf("%2e", "%2f", "%5c").none { it in once } && '\\' !in path
        }

        /** [url] with its origin normalized (lower-case host, default port dropped); null without a valid web origin. */
        private fun canonical(url: String): String? {
            val schemeEnd = url.indexOf("://").takeIf { it > 0 } ?: return null
            val authorityEnd = url.indexOf('/', schemeEnd + 3).takeIf { it > 0 } ?: return null
            val served = url.substring(0, authorityEnd)
            if ('@' in served) return null
            val origin = try { normalizeOrigin(served) } catch (_: CompanionException.InvalidOrigin) { return null }
            return origin + url.substring(authorityEnd)
        }
    }
}
