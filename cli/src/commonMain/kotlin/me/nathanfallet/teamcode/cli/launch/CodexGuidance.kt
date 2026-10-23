package me.nathanfallet.teamcode.cli.launch

import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.cli.hooks.HookEvents
import me.nathanfallet.teamcode.cli.hooks.InstalledHook
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import me.nathanfallet.teamcode.cli.platform.environment
import okio.Path.Companion.toPath
import kotlin.io.encoding.Base64

/** Codex merges files/layers, but repeated CLI assignments replace arrays within that layer. */
internal fun hasCodexHookOverrides(arguments: List<String>): Boolean {
    val options = arguments.takeWhile { it != "--" }
    return options.indices.any { index ->
        val argument = options[index]
        val value = when {
            argument == "-c" || argument == "--config" -> options.getOrNull(index + 1)
            argument.startsWith("--config=") -> argument.removePrefix("--config=")
            argument.startsWith("-c") -> argument.removePrefix("-c").removePrefix("=")
            else -> null
        }
        val key = value?.substringBefore('=')?.filterNot { it.isWhitespace() || it == '"' || it == '\'' }
        key == "hooks" || key?.startsWith("hooks.") == true
    }
}

/**
 * Codex hook overrides: TeamCode's guidance command on `UserPromptSubmit` and `PreToolUse` when [guidance] is set
 * (and on `Stop` when [activity] is too: the same command also reports whether the agent waits for a person),
 * and each approved team hook on the events Codex supports. One `-c hooks.<event>=[...]` per event, because Codex
 * replaces arrays within one CLI layer. A stable definition avoids re-review on every proxy port or token rotation;
 * a new team hook version has a new path, which Codex asks the member to trust again.
 */
internal fun codexGuidance(platform: ServicePlatform, config: String, teamHooks: List<InstalledHook> = emptyList(), guidance: Boolean = true, activity: Boolean = false): AgentLaunch {
    val team = teamHooks.flatMap { hook -> HookEvents.forAgent("codex", hook.definition).map { it to hook } }
    if (!guidance) return AgentLaunch(codexHookOverrides(null, team, false), emptyMap())
    require(platform.executable.toPath().isAbsolute && config.toPath().isAbsolute)
    val arguments = listOf(platform.executable, "agents", "hook", "--config", config)
    require(arguments.none { '\u0000' in it })
    val command = if (platform.os == "windows") {
        // Explicit PowerShell with an encoded script avoids cmd.exe expansion and preserves Unicode paths.
        val script = "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(\$false); & " +
            arguments.joinToString(" ") { "'${it.replace("'", "''")}'" }
        val utf16 = ByteArray(script.length * 2)
        script.forEachIndexed { index, character ->
            utf16[index * 2] = character.code.toByte()
            utf16[index * 2 + 1] = (character.code ushr 8).toByte()
        }
        "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand ${Base64.encode(utf16)}"
    } else arguments.joinToString(" ") { "'${it.replace("'", "'\"'\"'")}'" }
    val handler = "{hooks=[{type=\"command\",command=${JsonPrimitive(command)},timeout=5,additionalContextLimit=1000}]}"
    val overrides = if (platform.os == "windows") {
        val systemRoot = requireNotNull(environment("SystemRoot")).toPath()
        require(systemRoot.isAbsolute)
        val powershell = systemRoot / "System32" / "WindowsPowerShell" / "v1.0"
        // Native/npm launchers can receive a deliberately minimal PATH. PowerShell ships with Windows
        // but lives below System32; make it discoverable without changing any persistent user setting.
        mapOf("PATH" to listOfNotNull(environment("PATH")?.takeIf { it.isNotBlank() }, powershell.toString()).joinToString(";"))
    } else emptyMap()
    return AgentLaunch(codexHookOverrides(handler, team, activity), overrides)
}

/** Codex has no `Notification` event: for it the state comes from `Stop` (waiting) and `UserPromptSubmit` (working). */
private fun codexHookOverrides(guidance: String?, team: List<Pair<String, InstalledHook>>, activity: Boolean): List<String> {
    val guided = if (guidance == null) emptyList() else listOf("UserPromptSubmit", "PreToolUse") + (if (activity) listOf("Stop") else emptyList())
    return (guided + team.map { it.first }.sorted()).distinct().flatMap { event ->
        val groups = listOfNotNull(guidance?.takeIf { event in guided }) + team.filter { it.first == event }.map { (_, hook) ->
            val matcher = hook.definition.matcher?.let { "matcher=${JsonPrimitive(it)}," }.orEmpty()
            "{${matcher}hooks=[{type=\"command\",command=${JsonPrimitive(shellQuote(hook.command))},timeout=${hook.definition.timeoutSeconds}}]}"
        }
        listOf("-c", "hooks.$event=[${groups.joinToString(",")}]")
    }
}
