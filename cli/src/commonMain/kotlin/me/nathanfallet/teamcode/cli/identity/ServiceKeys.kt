package me.nathanfallet.teamcode.cli.identity

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import kotlin.time.Instant
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.CreateServiceKeyRequest
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk

class ServiceKeys : SuspendingNoOpCliktCommand(name = "keys") {
    override fun help(context: Context) = "Manage scoped service/CI credentials. An interactive login is required."
    init { subcommands(KeyList(), KeyCreate(), KeyRevoke()) }
}
private class KeyList : SuspendingCliktCommand(name = "list") {
    private val member by option("--member", help = "Another member ID; requires administrator access.")
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            for (key in client.serviceKeys(member)) echo("${key.id}\t${key.name}\t${key.scopes.joinToString(",")}\texpires ${if (key.neverExpires) "never" else Instant.fromEpochMilliseconds(key.expiresAt).toString()}\tcreated by ${key.createdByName ?: "-"}")
        }
    }
}
private class KeyCreate : SuspendingCliktCommand(name = "create") {
    private val member by option("--member", help = "Another member ID; requires administrator access.")
    private val name by option("--name").required()
    private val scopes by option("--scopes", help = "Comma-separated inference,usage:read,guidance,mcp,skills:read,cloud:read,cloud:write permissions (administrators: skills:publish, admin:cloud, team:read).").default("inference")
    private val days by option("--days", help = "Lifetime in days, 1–90 (default 30). Exclusive with --no-expiry.").int()
    private val noExpiry by option("--no-expiry", help = "Create a key that never expires; revoke it to end it. Administrators only, for themselves or with --member. Exclusive with --days.").flag()
    private val json by option("--json", help = "Return the one-time credential and metadata as JSON.").flag()
    override suspend fun run() {
        if (noExpiry && days != null) throw UsageError("--days and --no-expiry cannot be combined.")
        val lifetime = if (noExpiry) null else days ?: 30
        val created = sdk(loadConfig()).use { it.createServiceKey(CreateServiceKeyRequest(name, scopes.split(',').map { value -> value.trim() }.filter { value -> value.isNotEmpty() }.distinct(), lifetime), member) }
        echo("Save this one-time credential in your CI secret store. It cannot be recovered later.", err = true)
        echo(if (json) Serialization.json.encodeToString(created) else created.accessToken)
    }
}
private class KeyRevoke : SuspendingCliktCommand(name = "revoke") {
    private val id by argument(help = "Service key ID to revoke.")
    override suspend fun run() { sdk(loadConfig()).use { it.revokeServiceKey(id) }; echo("Service key revoked.") }
}
