package me.nathanfallet.teamcode.cli.status

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.formatUnits
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Everything `teamcode status` shows, read from the routes the dashboard uses. [capacity] and [accounts] are null when this
 * server or credential does not serve them (capacity disabled, older server, unexpected body, failed read); [pacing] is
 * null when no pacing advice applies or it cannot be read.
 */
@Serializable
data class StatusReport(val gateway: GatewayStatus, val capacity: CapacityView? = null, val pacing: DirectiveView? = null, val accounts: List<PoolAccountStatusView>? = null)

class Status : SuspendingCliktCommand(name = "status") {
    private val json by option("--json", help = "Print the report as JSON.").flag()
    override fun help(context: Context) =
        "Show the gateway's queue, your daily allowance today and the subscription accounts you can be routed to. Members see their own figures and neutral account labels; administrators see account names."
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            val report = readStatusReport(client)
            echo(if (json) Serialization.json.encodeToString(report) else renderStatus(report, Clock.System.now().toEpochMilliseconds()))
        }
    }
}

internal suspend fun readStatusReport(client: TeamCodeClient): StatusReport {
    val gateway = client.status()
    // Everything after the gateway line is optional: a server older than these routes (404, or a body of another shape),
    // a key without the scope or a failing read leaves its section out instead of failing `status`, which worked before it
    // showed quotas.
    val capacity = optional { client.capacity() }
    val pacing = optional { client.directive().directive }
    val accounts = optional { client.poolAccounts() }
    return StatusReport(gateway, capacity, pacing, accounts)
}

private suspend fun <T> optional(read: suspend () -> T): T? =
    try { read() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }

/** Plain text, no color: the gateway line, one block for the caller's own allowance, one table for the accounts. */
internal fun renderStatus(report: StatusReport, now: Long): String = buildString {
    val g = report.gateway
    appendLine("Mode: ${g.mode} | Active: ${g.activeRequests} | Waiting: ${g.queuedRequests} | Completed: ${g.completedRequests}")
    appendLine()
    val c = report.capacity
    if (c == null) appendLine("Your quota: unavailable from this server or credential.") else quota(c, report.pacing, now)
    appendLine()
    val accounts = report.accounts
    when {
        accounts == null -> append("Pool accounts: unavailable from this server or credential.")
        accounts.isEmpty() -> append("Pool accounts: none in your pools.")
        else -> {
            appendLine("Pool accounts")
            append(table(listOf("ACCOUNT", "PROVIDER", "STATE", "5H", "WEEKLY", "OTHER", "USABLE NOW"), accounts.map { account ->
                val five = account.windows.firstOrNull { it.window == "five_hour" }
                val week = account.windows.firstOrNull { it.window == "seven_day" }
                val other = account.windows.filter { it.window != "five_hour" && it.window != "seven_day" }
                    .joinToString(", ") { "${clean(it.window.removePrefix("model:"))} ${percent(it.utilization)}" }
                listOf(clean(account.label), clean(account.provider), account.state, window(five, now), window(week, now), other.ifEmpty { "-" }, usable(account, now))
            }))
        }
    }
}.trimEnd()

private fun StringBuilder.quota(c: CapacityView, pacing: DirectiveView?, now: Long) {
    val reserved = c.reservedUnits.coerceIn(0, c.chargedAndReservedUnits)
    val used = c.chargedAndReservedUnits - reserved
    val remaining = (c.dailyUnits - c.chargedAndReservedUnits).coerceAtLeast(0)
    appendLine("Your quota today (resets ${at(c.resetsAtMillis, now)})")
    val source = when {
        c.budgetOverride != null -> "temporary override until ${at(c.budgetOverride!!.expiresAtMillis, now)}, normal ${formatUnits(c.baseDailyUnits)}"
        c.hardCapUnits > 0 && c.baseDailyUnits == c.hardCapUnits -> "personal cap"
        else -> "pool share: weight ${c.weight} of ${c.poolTotalWeight} × ${formatUnits(c.poolDailyUnits)}"
    }
    appendLine("  Allocated   ${formatUnits(c.dailyUnits)} credits ($source)")
    appendLine("  Used        ${formatUnits(used)}")
    appendLine("  Reserved    ${formatUnits(reserved)} (${c.activeRequests} in flight, ${c.queuedRequests} waiting)")
    appendLine("  Remaining   ${formatUnits(remaining)}${if (c.dailyUnits > 0) " (${remaining * 100 / c.dailyUnits}%)" else ""}")
    appendLine("  Concurrency ${c.activeRequests} / ${c.maxConcurrent}")
    for (group in c.groups) {
        val budget = if (group.dailyUnits == 0L) "no credit cap" else "${formatUnits(group.chargedAndReservedUnits)} / ${formatUnits(group.dailyUnits)} credits"
        val slots = if (group.maxConcurrent == 0) "no concurrency cap" else "${group.activeRequests} / ${group.maxConcurrent} active"
        appendLine("  Group       ${clean(group.name)}: $budget, $slots${if (group.paused) ", admission paused" else ""}")
    }
    appendLine("  Pacing      " + if (pacing == null) "none" else
        "${pacing.pace.lowercase()}: at most ${pacing.maxConcurrent} concurrent, ${pacing.minIntervalMillis} ms between requests, until ${at(pacing.expiresAtMillis, now)}")
}

private fun usable(account: PoolAccountStatusView, now: Long): String {
    if (account.usable) return "yes"
    val reason = account.unavailableReason ?: return "no"
    val until = account.cooldownUntilMillis
    return if (reason == "cooling_down" && until != null) "no, cooling down until ${at(until, now)}" else "no, ${clean(reason.replace('_', ' '))}"
}

private fun window(value: AccountWindowView?, now: Long): String = when {
    value == null -> "-"
    value.resetsAtMillis == null -> percent(value.utilization)
    else -> "${percent(value.utilization)} (reset in ${duration(value.resetsAtMillis!! - now)})"
}

private fun table(header: List<String>, rows: List<List<String>>): String {
    val widths = header.indices.map { column -> (rows.map { it[column] } + header[column]).maxOf { it.length } }
    return (listOf(header) + rows).joinToString("\n") { row -> row.mapIndexed { i, cell -> if (i == row.lastIndex) cell else cell.padEnd(widths[i]) }.joinToString("  ") }
}

/** Server text is shown on one line without terminal control characters. */
private fun clean(value: String) = value.map { if (it.isISOControl()) ' ' else it }.joinToString("").take(80)
private fun percent(ratio: Double) = "${(ratio * 100).toInt().coerceAtLeast(0)}%"
private fun at(millis: Long, now: Long): String {
    val time = Instant.fromEpochMilliseconds(millis).toString().take(16).replace('T', ' ')
    return "$time UTC, in ${duration(millis - now)}"
}
internal fun duration(millis: Long): String {
    if (millis <= 0) return "0m"
    val minutes = (millis + 59_999) / 60_000
    val days = minutes / 1440; val hours = minutes % 1440 / 60; val rest = minutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${rest}m"
        else -> "${rest}m"
    }
}
