package me.nathanfallet.teamcode.companion.domain.usecases.auth

import me.nathanfallet.teamcode.companion.domain.models.CompanionException

private val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]")
private val ORIGIN = Regex("""(https?)://([A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(:[0-9]{1,5})?""", RegexOption.IGNORE_CASE)

/**
 * The server origin typed by the member, normalized: `https://` added when no scheme is given, host lower-cased, a
 * trailing slash removed. Only `https`, or `http` on loopback, and nothing after the port (no path, query, fragment or
 * credentials), the rule of the typed client.
 */
fun normalizeOrigin(input: String): String {
    val trimmed = input.trim().removeSuffix("/")
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val match = ORIGIN.matchEntire(withScheme) ?: throw CompanionException.InvalidOrigin()
    val scheme = match.groupValues[1].lowercase()
    val host = match.groupValues[2].lowercase()
    val port = match.groupValues[3]
    if (host.isEmpty() || host.startsWith('.') || host.endsWith('.') || ".." in host) throw CompanionException.InvalidOrigin()
    val portNumber = port.drop(1).takeIf { it.isNotEmpty() }?.toInt()
    if (portNumber != null && portNumber !in 1..65535) throw CompanionException.InvalidOrigin()
    if (scheme == "http" && host !in LOOPBACK) throw CompanionException.InvalidOrigin()
    // One spelling per server: the scheme's default port dropped, leading zeros gone.
    val canonicalPort = portNumber?.takeUnless { (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }?.let { ":$it" }.orEmpty()
    return "$scheme://$host$canonicalPort"
}
