package me.nathanfallet.teamcode.cli.sessions

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.formatUnits
import me.nathanfallet.teamcode.cli.cloud.parseLabels
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk

/** Local launch metadata; cloud execution/attachment belongs to the separate V2 API. */
class Sessions : SuspendingNoOpCliktCommand(name = "sessions") {
    override fun help(context: Context) = "Inspect registered Claude/Codex launches and their observed gateway activity."
    init { subcommands(ListRuns(), ShowRun()) }
}
private class ListRuns : SuspendingCliktCommand(name = "list") {
    private val member by option("--member", help = "Another member requires interactive administrator access.")
    private val all by option("--all", help = "All team launches; interactive administrators only.").flag()
    private val before by option("--before", help = "The nextBeforeId returned by an earlier page.")
    private val labels by option("--label", help = "key=value; repeatable. Only launches carrying all these labels.").multiple()
    private val json by option("--json", help = "Return one bounded API page as JSON.").flag()
    override suspend fun run() {
        val filter = parseLabels(labels)
        sdk(loadConfig()).use { client ->
            // An older server ignores the filter: never print an unfiltered list as if it were filtered.
            if (filter.isNotEmpty() && "team.runs.labels.v1" !in client.capabilities().features)
                throw CliktError("This server does not record run labels; list without --label.")
            val page = client.runs(member, all, before, filter)
            if (json) echo(Serialization.json.encodeToString(page)) else {
                for (run in page.runs) echo("${run.id}\t${run.username}\t${run.agent}\t${run.workload}\t${run.state}\t${run.activeRequests} active / ${run.queuedRequests} waiting\t${formatUnits(run.chargedAndReservedUnits)} credits" + run.activity?.let { "\tactivity=$it" }.orEmpty() +
                    run.labels.entries.sortedBy { it.key }.joinToString("") { "\t${it.key}=${it.value}" })
                page.nextBeforeId?.let { echo("Older launches: teamcode sessions list --before $it" + filter.entries.sortedBy { e -> e.key }.joinToString("") { e -> " --label ${e.key}=${e.value}" } + if (all) " --all" else member?.let { " --member $it" }.orEmpty(), err = true) }
            }
        }
    }
}
private class ShowRun : SuspendingCliktCommand(name = "show") {
    private val id by argument(help = "Run UUID.")
    override suspend fun run() { sdk(loadConfig()).use { echo(Serialization.json.encodeToString(it.run(id))) } }
}
