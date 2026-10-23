package me.nathanfallet.teamcode.companion.domain.usecases.links

import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.ARTIFACT_ID
import me.nathanfallet.teamcode.companion.domain.usecases.auth.normalizeOrigin

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
 * viewer URL itself, `<server>/artifact/<id>` with an optional `?v=<version>`. The link's origin must match an
 * account's exactly; the active account wins when several share it. A link never carries a secret, so the worst a
 * crafted one does is offer a sign-in to another server, whose origin is shown. Null for anything else.
 */
class ResolveLinkUseCase(private val accounts: AccountRepository) {
    operator fun invoke(link: String): LinkTarget? {
        val trimmed = link.trim()
        val url = if (trimmed.startsWith(SCHEME, ignoreCase = true)) {
            val query = trimmed.substring(SCHEME.length).substringBefore('#')
            val encoded = query.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
            decode(encoded) ?: return null
        } else trimmed
        val match = VIEWER.matchEntire(url) ?: return null
        val origin = try { normalizeOrigin(match.groupValues[1]) } catch (_: CompanionException.InvalidOrigin) { return null }
        val artifactId = match.groupValues[2]
        if (!ARTIFACT_ID.matches(artifactId)) return null
        val version = match.groupValues[3].takeIf { it.isNotEmpty() }?.let { it.toIntOrNull()?.takeIf { v -> v >= 1 } ?: return null }
        val candidates = accounts.accounts.value.filter { it.origin == origin }
        if (candidates.isEmpty()) return LinkTarget.UnknownServer(origin)
        val signedIn = candidates.filter { it.signedIn }
        val account = signedIn.firstOrNull { it.id == accounts.activeAccountId.value } ?: signedIn.firstOrNull()
            ?: return LinkTarget.SignInAgain(origin, (candidates.firstOrNull { it.id == accounts.activeAccountId.value } ?: candidates.first()).username)
        return LinkTarget.Artifact(account.id, artifactId, version)
    }

    private companion object {
        const val SCHEME = "teamcode://open?"
        val VIEWER = Regex("""(https?://[^/?#@\s]+)/artifact/([0-9a-f-]{36})/?(?:\?v=([0-9]{1,9}))?""")

        /** Percent-decoding of one query value; null for a malformed escape or bytes that are not UTF-8. */
        fun decode(value: String): String? {
            val bytes = ArrayList<Byte>(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                when {
                    c == '%' -> {
                        if (i + 2 >= value.length) return null
                        bytes += (value.substring(i + 1, i + 3).toIntOrNull(16) ?: return null).toByte()
                        i += 3
                    }
                    c == '+' -> { bytes += ' '.code.toByte(); i++ }
                    else -> { c.toString().encodeToByteArray().forEach { bytes += it }; i++ }
                }
            }
            return try { bytes.toByteArray().decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { null }
        }
    }
}
