package me.nathanfallet.teamcode.companion.domain.usecases.auth

import me.nathanfallet.teamcode.companion.domain.models.CompanionException

// IPv6 loopback is left out: Android's network security config cannot allow cleartext to it.
private val LOOPBACK = setOf("localhost", "127.0.0.1")
private val ORIGIN = Regex("""(https?)://([A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(:[0-9]{1,5})?""", RegexOption.IGNORE_CASE)

/** Characters a keyboard, an autocompletion or a paste leaves around a typed value without showing them. */
private const val INVISIBLE = "\u200B\u200C\u200D\u2060\uFEFF"

/**
 * A typed value (an origin, a link) without what surrounds it: spaces, line breaks and invisible characters at either
 * end, often added by a mobile keyboard's autocompletion or by a paste.
 */
fun String.trimTyped(): String = trim { it.isWhitespace() || it in INVISIBLE }

/**
 * The server origin typed by the member, normalized: surrounding spaces and line breaks removed ([trimTyped]),
 * `https://` added when no scheme is given, host lower-cased, a trailing slash removed, and the trailing dot of a
 * fully qualified host (a phone keyboard types one after a double space). Only `https`, or `http` on loopback, and
 * nothing after the port (no path, query, fragment or credentials), the rule of the typed client.
 */
fun normalizeOrigin(input: String): String {
    val trimmed = input.trimTyped().removeSuffix("/").trimTyped()
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val match = ORIGIN.matchEntire(withScheme) ?: throw CompanionException.InvalidOrigin()
    val scheme = match.groupValues[1].lowercase()
    val host = match.groupValues[2].lowercase().let { if (it.endsWith('.')) it.dropLast(1) else it }
    val port = match.groupValues[3]
    if (host.isEmpty() || host.startsWith('.') || host.endsWith('.') || ".." in host) throw CompanionException.InvalidOrigin()
    val portNumber = port.drop(1).takeIf { it.isNotEmpty() }?.toInt()
    if (portNumber != null && portNumber !in 1..65535) throw CompanionException.InvalidOrigin()
    if (scheme == "http" && host !in LOOPBACK) throw CompanionException.InvalidOrigin()
    // One spelling per server: the scheme's default port dropped, leading zeros gone.
    val canonicalPort = portNumber?.takeUnless { (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }?.let { ":$it" }.orEmpty()
    return "$scheme://$host$canonicalPort"
}
