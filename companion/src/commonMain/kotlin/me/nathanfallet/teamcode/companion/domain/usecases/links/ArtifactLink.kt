package me.nathanfallet.teamcode.companion.domain.usecases.links

import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.ARTIFACT_ID
import me.nathanfallet.teamcode.companion.domain.usecases.auth.normalizeOrigin

/**
 * An artifact's viewer URL, `<origin>/artifact/<id>` with an optional `?v=<version>`, read into its parts: the
 * server's [origin] in its canonical spelling, the artifact's lower-case [artifactId], and the [version] asked for.
 */
data class ArtifactLink(val origin: String, val artifactId: String, val version: Int?) {
    companion object {
        // Scheme and authority (no credentials), then exactly `/artifact/<36 characters>`, an optional trailing slash,
        // an optional query and fragment. The id's characters are checked again as a UUID below.
        private val VIEWER = Regex("""([A-Za-z][A-Za-z0-9+.-]*://[^/?#@\\\s]+)/artifact/([0-9A-Fa-f-]{36})/?(?:\?([^#]*))?(?:#.*)?""")

        /**
         * The viewer URL's parts; null for anything else: another path (a content URL under a ticket, a prefix before
         * `/artifact`, an escaped or dotted segment), an id that is not a UUID, credentials in the authority, a scheme
         * other than `https` (or `http` on loopback), a `v` that is not one version, or a space, control or invisible
         * character anywhere (the URL a member would read is then not the one opened). Other query parameters and the
         * fragment are ignored.
         */
        fun parse(url: String): ArtifactLink? {
            if (url.any { it.isISOControl() || it.isWhitespace() || it.category == CharCategory.FORMAT }) return null
            val match = VIEWER.matchEntire(url) ?: return null
            val origin = try { normalizeOrigin(match.groupValues[1]) } catch (_: CompanionException.InvalidOrigin) { return null }
            val artifactId = match.groupValues[2].lowercase()
            if (!ARTIFACT_ID.matches(artifactId)) return null
            val versions = match.groupValues[3].split('&').filter { it.startsWith("v=") }.map { it.removePrefix("v=") }
            if (versions.size > 1) return null
            val version = versions.singleOrNull()?.let { v -> v.takeIf { it.length <= 9 && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it >= 1 } ?: return null }
            return ArtifactLink(origin, artifactId, version)
        }
    }
}
