package me.nathanfallet.teamcode.cli.accounts

import com.github.ajalt.clikt.core.CliktError
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization

/** Parsed credentials are untrusted until the server verifies provider identity. */
class ImportCredentials(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long, val provider: String = "anthropic") {
    override fun toString(): String = "ImportCredentials([REDACTED])"
}

sealed interface ImportEntry {
    val label: String
    class Ready(override val label: String, val credentials: ImportCredentials) : ImportEntry {
        override fun toString() = "Ready(label=$label, credentials=[REDACTED])"
    }
    data class Rejected(override val label: String, val reason: String) : ImportEntry
}

/** Reads the account list only. Proxy keys, routing, policy and source identities are never trusted/imported. */
fun parseTeamClaudeConfig(source: String): List<ImportEntry> {
    if (source.encodeToByteArray().size > MAX_IMPORT_BYTES) throw CliktError("TeamClaude configuration is too large.")
    val root = try { Serialization.json.parseToJsonElement(source) as? JsonObject }
        catch (_: IllegalArgumentException) { null }
    if (root == null) throw CliktError("TeamClaude configuration must be a JSON object.")
    val accounts = root["accounts"] as? JsonArray ?: throw CliktError("TeamClaude configuration must have an accounts array.")
    if (accounts.size > 1000) throw CliktError("Too many accounts in TeamClaude configuration.")
    return accounts.mapIndexed { index, entry ->
        val account = entry as? JsonObject ?: return@mapIndexed ImportEntry.Rejected("Account ${index + 1}", "Account must be an object.")
        val label = account.string("name")?.let(::safeLabel)?.takeIf { it.isNotEmpty() } ?: "Account ${index + 1}"
        val provider = account.string("provider") ?: "anthropic"
        val type = account.string("type")
        if (provider !in setOf("anthropic", "codex") || type != "oauth" || account["upstream"]?.let { it != JsonNull } == true) {
            return@mapIndexed ImportEntry.Rejected(label, "Only native Anthropic or Codex OAuth accounts are supported by this import format.")
        }
        val access = account.string("accessToken")
        val refresh = account.string("refreshToken")
        val expires = (account["expiresAt"] as? JsonPrimitive)?.longOrNull
        if (access.isNullOrBlank() || refresh.isNullOrBlank() || expires == null || expires <= 0) {
            return@mapIndexed ImportEntry.Rejected(label, "Missing or invalid OAuth credentials; reconnect the source account first.")
        }
        ImportEntry.Ready(label, ImportCredentials(access, refresh, expires, provider))
    }
}

private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun safeLabel(value: String): String = value.filter { it.code >= 32 && it.code != 127 && !it.isISOControl() }.take(120)
const val MAX_IMPORT_BYTES: Int = 4 * 1024 * 1024
