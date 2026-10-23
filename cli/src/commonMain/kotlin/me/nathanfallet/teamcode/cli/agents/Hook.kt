package me.nathanfallet.teamcode.cli.agents

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.*
import kotlinx.coroutines.CancellationException
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
                val emission = bridge.prepare(input)
                if (emission != null) {
                    println(emission.output)
                    written = true
                }
                if (emission != null) bridge.acknowledge(emission)
                // Last, and after the output: guidance never waits for it. The agent still waits for this process to exit (at most 1.5 s).
                if (state != null && run != null) {
                    if (!written) { println("{}"); written = true }
                    HookActivity.report(client, run, state)
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Optional advice must not block tools or reveal diagnostics. */ }
        finally { if (!written) println("{}") }
    }
}
