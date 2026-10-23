package me.nathanfallet.teamcode.cli.agents

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.nathanfallet.teamcode.cli.config.ConfigStore
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.platform.environment
import me.nathanfallet.teamcode.cli.platform.readHookInput
import me.nathanfallet.teamcode.cli.sdk
import okio.Path.Companion.toPath

internal class Hook : SuspendingCliktCommand(name = "hook") {
    private val config by option("--config", help = "Absolute path to the private TeamCode configuration.")
    override fun help(context: Context) = "Read a bounded Codex/Claude hook payload from stdin and emit session guidance as JSON."
    override suspend fun run() {
        var written = false
        try {
            val input = readHookInput() ?: return
            val path = config ?: configPath()
            require(path.toPath().isAbsolute)
            val saved = ConfigStore(path).load() ?: return
            sdk(saved).use { client ->
                val bridge = HookGuidance(client)
                val state = HookActivity.state(input)
                val run = HookActivity.runId(environment("TEAMCODE_RUN_ID")).takeIf { environment("TEAMCODE_ACTIVITY") == "1" }
                // Concurrent with the guidance lookup: the agent waits for the slower of the two, not their sum (the report is bounded at 1.5 s).
                coroutineScope {
                    val report = if (state != null && run != null) async { HookActivity.report(client, run, state) } else null
                    val emission = bridge.prepare(input)
                    if (emission != null) {
                        println(emission.output)
                        written = true
                        bridge.acknowledge(emission)
                    }
                    report?.await()
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Optional advice must not block tools or reveal diagnostics. */ }
        finally { if (!written) println("{}") }
    }
}
