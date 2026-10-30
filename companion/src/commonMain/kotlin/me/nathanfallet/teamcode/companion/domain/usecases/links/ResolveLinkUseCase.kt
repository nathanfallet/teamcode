package me.nathanfallet.teamcode.companion.domain.usecases.links

import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.artifactViewerUrl
import me.nathanfallet.teamcode.companion.domain.usecases.auth.trimTyped

/** Where a link leads. */
sealed interface LinkTarget {
    /** An artifact of an account signed in to the link's server. */
    data class Artifact(val accountId: String, val artifactId: String, val version: Int?) : LinkTarget
    /** The link's server has only signed-out accounts: sign in again as [username] first. */
    data class SignInAgain(val origin: String, val username: String) : LinkTarget
    /** No account on the link's server: offer to sign in to it, saying it is a new server. */
    data class UnknownServer(val origin: String) : LinkTarget
}

/**
 * Resolves an artifact link: `teamcode://open?url=<encoded viewer URL>` (the viewer page's "Open in app") or the
 * viewer URL itself, `<server>/artifact/<id>` with an optional `?v=<version>` ([ArtifactLink]). The link's origin must match an
 * account's exactly; the active account wins when several share it. A link never carries a secret, so the worst a
 * crafted one does is offer a sign-in to another server, whose origin is shown. Null for anything else.
 */
class ResolveLinkUseCase(private val accounts: AccountRepository) {
    operator fun invoke(link: String): LinkTarget? {
        val trimmed = link.trimTyped()
        val url = if (trimmed.startsWith(SCHEME, ignoreCase = true)) {
            val query = trimmed.substring(SCHEME.length).substringBefore('#')
            val encoded = query.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
            decode(encoded) ?: return null
        } else trimmed
        val (origin, artifactId, version) = ArtifactLink.parse(url) ?: return null
        val candidates = accounts.accounts.value.filter { it.origin == origin }
        if (candidates.isEmpty()) return LinkTarget.UnknownServer(origin)
        val signedIn = candidates.filter { it.signedIn }
        val account = signedIn.firstOrNull { it.id == accounts.activeAccountId.value } ?: signedIn.firstOrNull()
            ?: return LinkTarget.SignInAgain(origin, (candidates.firstOrNull { it.id == accounts.activeAccountId.value } ?: candidates.first()).username)
        return LinkTarget.Artifact(account.id, artifactId, version)
    }

    /**
     * A link clicked in a conversation of [accountId]: the artifact when it is a viewer URL ([ArtifactLink]) on that
     * account's own server, the account being signed in; null for anything else, which opens outside as any link.
     * Never another server, even one the member has an account on, and none of the typed-link conveniences
     * (`teamcode://`, trimming): the URL must be exactly the one the member saw.
     */
    fun inConversation(link: String, accountId: String): LinkTarget.Artifact? {
        val parsed = ArtifactLink.parse(link) ?: return null
        val account = accounts.accounts.value.firstOrNull { it.id == accountId }?.takeIf { it.signedIn && it.origin == parsed.origin } ?: return null
        return LinkTarget.Artifact(account.id, parsed.artifactId, parsed.version)
    }

    /** The viewer page of [target] on its account's server, built from the account's origin and the parsed id, never from the link. */
    fun viewerUrl(target: LinkTarget.Artifact): String? {
        val origin = accounts.accounts.value.firstOrNull { it.id == target.accountId }?.origin ?: return null
        return artifactViewerUrl(origin, target.artifactId) + (target.version?.let { "?v=$it" } ?: "")
    }

    private companion object {
        const val SCHEME = "teamcode://open?"

        /** Percent-decoding of one query value; null for a malformed escape or bytes that are not UTF-8. */
        fun decode(value: String): String? {
            val bytes = ArrayList<Byte>(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '%' -> {
                        if (i + 2 >= value.length) return null
                        val hex = value.substring(i + 1, i + 3)
                        // Two hexadecimal digits exactly: toIntOrNull alone would take a sign ("%-1").
                        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                        bytes += hex.toInt(16).toByte()
                        i += 3
                    }
                    c == '+' -> { bytes += ' '.code.toByte(); i++ }
                    else -> {
                        // The whole unescaped run at once, so a surrogate pair is encoded as one character.
                        val end = (i until value.length).firstOrNull { value[it] == '%' || value[it] == '+' } ?: value.length
                        value.substring(i, end).encodeToByteArray().forEach { bytes += it }
                        i = end
                    }
                }
            }
            return try { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { null }
        }
    }
}
