package me.nathanfallet.teamcode.cli.launch

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.models.RunIdentifiers
import me.nathanfallet.teamcode.cli.hooks.HookEvents
import me.nathanfallet.teamcode.cli.hooks.InstalledHook
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Session-only plugin carrying TeamCode's guidance hooks and the member's approved team hooks; no user/project
 * settings or provider credentials are written. [create] with a null port leaves guidance out.
 */
internal class GuidancePlugin private constructor(val directory: Path) : AutoCloseable {
    override fun close() { FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false) }
    companion object {
        fun create(configPath: String, port: Int?, teamHooks: List<InstalledHook> = emptyList(), runId: String? = null): GuidancePlugin {
            val id = secureRandomBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            val root = requireNotNull(configPath.toPath().parent) / "runs" / id
            val plugin = GuidancePlugin(root)
            try {
                // Create the run directory privately before placing nested plugin files.
                writePrivateFile((root / "owner").toString(), "TeamCode ephemeral guidance plugin\n")
                writePrivateFile((root / ".claude-plugin" / "plugin.json").toString(), """{"name":"teamcode-guidance","version":"1.0.0","description":"Session capacity guidance and team hooks from your TeamCode server"}""")
                writePrivateFile((root / "hooks" / "hooks.json").toString(), sessionHooks(port, teamHooks, runId).toString())
                return plugin
            } catch (error: Throwable) { plugin.close(); throw error }
        }
    }
}
internal fun guidanceHooks(port: Int): JsonObject = sessionHooks(port, emptyList(), null)

/** Events whose HTTP hook only reports whether the agent works or waits for a person (see `HookActivity`). */
internal val CLAUDE_ACTIVITY_EVENTS = listOf("Stop", "Notification", "PostToolUse")

/** POSIX single quoting: the agents run hook commands through a shell. */
internal fun shellQuote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

/**
 * Claude Code hook configuration: TeamCode's HTTP guidance hooks when [port] is set, then each approved team hook as
 * a command hook on its events, running its installed executable by quoted absolute path, with no argument.
 * With a [runId] (a registered launch on a server that records activity), the same local hook also receives `Stop`,
 * `Notification` and `PostToolUse`, and names the launch in `X-TeamCode-Run`.
 */
internal fun sessionHooks(port: Int?, teamHooks: List<InstalledHook>, runId: String? = null): JsonObject {
    require(port == null || port in 1..65535)
    require(runId == null || RunIdentifiers.isValid(runId))
    val guidance = if (port == null) emptyList() else listOf("PreToolUse", "UserPromptSubmit") + (if (runId == null) emptyList() else CLAUDE_ACTIVITY_EVENTS)
    val events = (guidance + teamHooks.flatMap { HookEvents.forAgent("claude", it.definition) }).distinct()
    return buildJsonObject { putJsonObject("hooks") {
        for (event in events) putJsonArray(event) {
            if (event in guidance) add(buildJsonObject {
                if (event == "PreToolUse") put("matcher", "*")
                putJsonArray("hooks") { add(buildJsonObject {
                    put("type", "http"); put("url", "http://127.0.0.1:$port/_teamcode/hooks/claude"); put("timeout", 4)
                    putJsonObject("headers") {
                        put("Authorization", "Bearer ${'$'}TEAMCODE_HOOK_TOKEN")
                        if (runId != null) put("X-TeamCode-Run", runId)
                    }
                    putJsonArray("allowedEnvVars") { add(JsonPrimitive("TEAMCODE_HOOK_TOKEN")) }
                }) }
            })
            for (hook in teamHooks) if (event in hook.definition.events) add(buildJsonObject {
                hook.definition.matcher?.let { put("matcher", it) }
                putJsonArray("hooks") { add(buildJsonObject {
                    put("type", "command"); put("command", shellQuote(hook.command)); put("timeout", hook.definition.timeoutSeconds)
                }) }
            })
        }
    } }
}
