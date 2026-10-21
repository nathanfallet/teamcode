package me.nathanfallet.teamcode.cli.hooks

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.SuspendingNoOpCliktCommand
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.argument
import me.nathanfallet.teamcode.api.models.EffectiveHookView
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.platform.runInteractiveProcess
import me.nathanfallet.teamcode.cli.platform.servicePlatform
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.client.TeamCodeClient
import okio.ByteString.Companion.decodeBase64
import okio.Path
import okio.Path.Companion.toPath

/** The private directory of team hooks: approvals and installed versions. */
internal fun teamHooksRoot(): Path = requireNotNull(configPath().toPath().parent) / "hooks"

/** POSIX `chmod 700` through the system tool; executables stay owner-only like the rest of the configuration. */
internal suspend fun chmodOwnerOnly(paths: List<Path>) {
    val code = runInteractiveProcess("chmod", listOf("700") + paths.map { it.toString() }, emptyMap(), quiet = true)
    check(code == 0) { "The hook files could not be made executable." }
}

internal fun teamHookSync(client: TeamCodeClient, root: Path = teamHooksRoot()) = TeamHookSync(
    { client.hookManifest().hooks }, { client.hookArtifact(it.name, it.version) },
    HookApprovals(root / "approvals.json"), LocalHookInstaller(root / "installed", makeExecutable = ::chmodOwnerOnly),
)

/** Whether this computer can run team hooks: POSIX shells only for now. */
internal fun teamHooksSupported() = servicePlatform().os != "windows"

class Hooks : SuspendingNoOpCliktCommand(name = "hooks") {
    override fun help(context: Context) = "Review, approve or refuse the team hooks delivered to you. teamcode run installs only the versions you approved."
    init { subcommands(HookList(), HookShow(), HookApprove(), HookRefuse()) }
}

private suspend fun delivered(client: TeamCodeClient, name: String): EffectiveHookView {
    if ("team.hooks.v1" !in client.capabilities().features) throw CliktError("This TeamCode server does not deliver team hooks.")
    return client.hookManifest().hooks.find { it.name == name } ?: throw CliktError("No team hook named $name is delivered to you.")
}

private fun describe(hook: EffectiveHookView) = "${hook.name} ${hook.version}: runs ${hook.definition.command} on " +
    hook.definition.events.sorted().joinToString(", ") + (hook.definition.matcher?.let { " (matcher $it)" } ?: "") +
    ", timeout ${hook.definition.timeoutSeconds} s"

private class HookList : SuspendingCliktCommand(name = "list") {
    override fun help(context: Context) = "List the team hooks delivered to you and your decision for each version."
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            if ("team.hooks.v1" !in client.capabilities().features) { echo("This TeamCode server does not deliver team hooks."); return }
            val hooks = client.hookManifest().hooks
            val decisions = HookApprovals(teamHooksRoot() / "approvals.json").load()
            if (hooks.isEmpty()) echo("No team hook is delivered to you.")
            for (hook in hooks) {
                val decision = decisions[hook.name]
                val state = when {
                    decision == null -> "awaiting your approval"
                    decision.digest != hook.digest -> "new version awaiting your approval"
                    decision.approved -> "approved"
                    else -> "refused"
                }
                echo("${describe(hook)} [$state]")
            }
            if (!teamHooksSupported()) echo("Team hooks are not installed on Windows yet.", err = true)
            if (hooks.any { decisions[it.name]?.digest != it.digest }) echo("Read one with teamcode hooks show NAME, then teamcode hooks approve NAME or teamcode hooks refuse NAME.", err = true)
        }
    }
}

private class HookShow : SuspendingCliktCommand(name = "show") {
    private val name by argument(help = "The hook to read.")
    override fun help(context: Context) = "Print a delivered hook's definition and files, verified against its digest, before you decide."
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            val hook = delivered(client, name)
            val artifact = client.hookArtifact(hook.name, hook.version)
            HookDigest.problem(hook, artifact)?.let { throw CliktError("The hook was refused ($it): its files do not match the approved digest.") }
            echo(describe(hook))
            echo("digest ${hook.digest}")
            for (file in artifact.files) {
                val bytes = checkNotNull(file.contentBase64.decodeBase64()).toByteArray()
                val text = bytes.decodeToString()
                echo("\n--- ${file.path} (${file.mode}, ${file.size} bytes) ---")
                echo(if (text.encodeToByteArray().contentEquals(bytes) && text.none { it.isISOControl() && it !in "\n\r\t" }) text else "(binary content not shown)")
            }
        }
    }
}

private class HookApprove : SuspendingCliktCommand(name = "approve") {
    private val name by argument(help = "The hook to approve.")
    override fun help(context: Context) = "Approve the delivered version of a team hook: it is verified, installed, and run by your next teamcode run."
    override suspend fun run() {
        if (!teamHooksSupported()) throw CliktError("Team hooks are not installed on Windows yet.")
        sdk(loadConfig()).use { client ->
            val hook = delivered(client, name)
            val sync = teamHookSync(client)
            sync.ensure(hook)
            HookApprovals(teamHooksRoot() / "approvals.json").record(hook.name, HookApproval(hook.digest, approved = true))
            echo("Approved ${describe(hook)}. It runs with your user's rights from your next teamcode run; a new version needs a new approval.")
        }
    }
}

private class HookRefuse : SuspendingCliktCommand(name = "refuse") {
    private val name by argument(help = "The hook to refuse.")
    override fun help(context: Context) = "Refuse the delivered version of a team hook: teamcode run does not install it and stops asking until a new version is delivered."
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            val hook = delivered(client, name)
            HookApprovals(teamHooksRoot() / "approvals.json").record(hook.name, HookApproval(hook.digest, approved = false))
            echo("Refused ${hook.name} ${hook.version}. Your agents do not run it; the team is not notified.")
        }
    }
}
