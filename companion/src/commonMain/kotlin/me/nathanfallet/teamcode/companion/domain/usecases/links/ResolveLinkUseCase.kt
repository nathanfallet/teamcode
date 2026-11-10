package me.nathanfallet.teamcode.companion.domain.usecases.links

import me.nathanfallet.teamcode.companion.domain.models.CompanionException
import me.nathanfallet.teamcode.companion.domain.repositories.AccountRepository
import me.nathanfallet.teamcode.companion.domain.usecases.artifacts.ARTIFACT_ID
import me.nathanfallet.teamcode.companion.domain.usecases.auth.normalizeOrigin
import me.nathanfallet.teamcode.companion.domain.usecases.auth.trimTyped

/** Where a link leads. */
sealed interface LinkTarget {
    /** An artifact of an account signed in to the link's server. */
    data class Artifact(val accountId: String, val artifactId: String, val version: Int?) : LinkTarget
    /** The link's server has only signed-out accounts: sign in again as [username] first. */
    data class SignInAgain(val origin: String, val username: String) : LinkTarget
    /** No account on the link's server: offer to sign in to it, saying it is a new server. */
    data class UnknownServer(val origin: String) : LinkTarget
    /** A tapped notification about a session. */
    data class Session(val accountId: String, val sessionId: String) : LinkTarget
    /** A tapped notification about a wait outside any session: the account's waits. */
    data class Waits(val accountId: String) : LinkTarget
    /** A tapped notification about nothing in particular (the test push): the app opens on the account. */
    data class Home(val accountId: String) : LinkTarget
}

/**
 * The link a notification opens: `teamcode://notification?origin=<encoded origin>&member=<id>[&session=<id>][&wait=<id>]`.
 * It names the server and member, not the account id, because the iOS service extension that builds it does not see the
 * app's accounts. A crafted one only opens a screen of an account already signed in on this device.
 */
object NotificationLinks {
    const val SCHEME = "teamcode://notification?"
    private val ID = Regex("[A-Za-z0-9_-]{1,64}")

    fun link(origin: String, memberId: String, sessionId: String?, waitId: String?): String = buildString {
        append(SCHEME).append("origin=").append(encode(origin)).append("&member=").append(encode(memberId))
        sessionId?.let { append("&session=").append(encode(it)) }
        waitId?.let { append("&wait=").append(encode(it)) }
    }

    internal fun parse(link: String): Triple<String, String, Pair<String?, String?>>? {
        if (!link.startsWith(SCHEME, ignoreCase = true)) return null
        val values = link.substring(SCHEME.length).substringBefore('#').split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "")
            if (key.isEmpty()) null else key to part.substringAfter('=')
        }
        fun single(key: String): String? = values.filter { it.first == key }.let { if (it.size > 1) return null else it.singleOrNull()?.second }
        val origin = single("origin")?.let { ResolveLinkUseCase.decode(it) } ?: return null
        val member = single("member")?.takeIf(ID::matches) ?: return null
        val session = single("session")?.let { it.takeIf(ID::matches) ?: return null }
        val wait = single("wait")?.let { it.takeIf(ID::matches) ?: return null }
        return Triple(origin, member, session to wait)
    }

    private fun encode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = (byte.toInt() and 0xff).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") append(c)
            else append('%').append(HEX[byte.toInt() shr 4 and 0xf]).append(HEX[byte.toInt() and 0xf])
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/**
 * Resolves an artifact link: `teamcode://open?url=<encoded viewer URL>` (the viewer page's "Open in app") or the
 * viewer URL itself, `<server>/artifact/<id>` with an optional `?v=<version>`. The link's origin must match an
 * account's exactly; the active account wins when several share it. A link never carries a secret, so the worst a
 * crafted one does is offer a sign-in to another server, whose origin is shown. Null for anything else.
 */
class ResolveLinkUseCase(private val accounts: AccountRepository) {
    operator fun invoke(link: String): LinkTarget? {
        val trimmed = link.trimTyped()
        if (trimmed.startsWith(NotificationLinks.SCHEME, ignoreCase = true)) return notification(trimmed)
        val url = if (trimmed.startsWith(SCHEME, ignoreCase = true)) {
            val query = trimmed.substring(SCHEME.length).substringBefore('#')
            val encoded = query.split('&').firstOrNull { it.startsWith("url=") }?.removePrefix("url=") ?: return null
            decode(encoded) ?: return null
        } else trimmed
        val match = VIEWER.matchEntire(url.substringBefore('#')) ?: return null
        val origin = try { normalizeOrigin(match.groupValues[1]) } catch (_: CompanionException.InvalidOrigin) { return null }
        val artifactId = match.groupValues[2]
        if (!ARTIFACT_ID.matches(artifactId)) return null
        // Other query parameters (a tracker added by a chat app) are ignored; `v` must be a version when present.
        val versions = match.groupValues[3].split('&').filter { it.startsWith("v=") }.map { it.removePrefix("v=") }
        if (versions.size > 1) return null
        val version = versions.singleOrNull()?.let { v -> v.takeIf { it.length <= 9 }?.toIntOrNull()?.takeIf { it >= 1 } ?: return null }
        val candidates = accounts.accounts.value.filter { it.origin == origin }
        if (candidates.isEmpty()) return LinkTarget.UnknownServer(origin)
        val signedIn = candidates.filter { it.signedIn }
        val account = signedIn.firstOrNull { it.id == accounts.activeAccountId.value } ?: signedIn.firstOrNull()
            ?: return LinkTarget.SignInAgain(origin, (candidates.firstOrNull { it.id == accounts.activeAccountId.value } ?: candidates.first()).username)
        return LinkTarget.Artifact(account.id, artifactId, version)
    }

    /** A notification of an account signed in on its server as its member; signed out, it asks for the password first. */
    private fun notification(link: String): LinkTarget? {
        val (rawOrigin, member, target) = NotificationLinks.parse(link) ?: return null
        val origin = try { normalizeOrigin(rawOrigin) } catch (_: CompanionException.InvalidOrigin) { return null }
        val candidates = accounts.accounts.value.filter { it.origin == origin && it.memberId == member }
        val account = candidates.firstOrNull { it.signedIn }
            ?: return candidates.firstOrNull()?.let { LinkTarget.SignInAgain(origin, it.username) }
        val (session, wait) = target
        return when {
            session != null -> LinkTarget.Session(account.id, session)
            wait != null -> LinkTarget.Waits(account.id)
            else -> LinkTarget.Home(account.id)
        }
    }

    internal companion object {
        const val SCHEME = "teamcode://open?"
        private val VIEWER = Regex("""(https?://[^/?#@\s]+)/artifact/([0-9a-f-]{36})/?(?:\?([^#\s]*))?""", RegexOption.IGNORE_CASE)

        /** Percent-decoding of one query value; null for a malformed escape or bytes that are not UTF-8. */
        internal fun decode(value: String): String? {
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
