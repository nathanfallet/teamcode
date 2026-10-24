package me.nathanfallet.teamcode.cli.launch

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.nathanfallet.teamcode.cli.SystemExit
import me.nathanfallet.teamcode.cli.cloud.parseLabels
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.cli.platform.*
import me.nathanfallet.teamcode.cli.proxy.LOCAL_TOKEN_HEADER
import me.nathanfallet.teamcode.cli.proxy.proxyServer
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.service.loadService
import me.nathanfallet.teamcode.cli.service.serviceResponds
import me.nathanfallet.teamcode.api.models.Capabilities
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.cli.hooks.HookEvents
import me.nathanfallet.teamcode.cli.hooks.InstalledHook
import me.nathanfallet.teamcode.cli.hooks.teamHookSync
import me.nathanfallet.teamcode.cli.hooks.teamHooksSupported
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okio.Path.Companion.toPath

class Run : SuspendingNoOpCliktCommand(name = "run") {
    override fun help(context: Context) = "Run Claude Code or Codex through an automatically managed local proxy. Pass agent arguments after --."
    init { subcommands(LaunchAgent("claude"), LaunchAgent("codex")) }
}

private class LaunchAgent(private val agent: String) : SuspendingCliktCommand(name = agent) {
    private val noActivity by option("--no-activity", help = "Do not report to the server whether the agent works or waits for you.").flag()
    private val noGuidance by option("--no-guidance", help = "Disable automatic session guidance for this launch.").flag()
    private val noMcp by option("--no-mcp", help = "Do not connect the team MCP servers assigned to you for this launch.").flag()
    private val noTeamHooks by option("--no-team-hooks", help = "Do not install the team hooks you approved for this launch.").flag()
    private val noArtifacts by option("--no-artifacts", help = "Do not connect the artifact tools (teamcode mcp artifacts) for this launch.").flag()
    private val noVariables by option("--no-variables", help = "Do not give the agent the environment variables assigned to you for this launch.").flag()
    private val workload by option("--workload", help = "Scheduling class for this launch; quotas and permissions still apply.").choice("interactive", "background").default("interactive")
    private val labels by option("--label", help = "key=value; repeatable. Attached to this launch; filter with teamcode sessions list --label.").multiple()
    private val arguments by argument("AGENT_ARGS").multiple()
    override suspend fun run() {
        val code = coroutineScope {
            val service = loadService(configPath())
            val report: (String) -> Unit = { echo(it, err = true) }
            // Before the agent starts: a pending service restart and the wait for it are said at once.
            val lease = me.nathanfallet.teamcode.cli.update.agentInstallationLease(service?.executable, report)
            try {
                // While the agent runs it owns the terminal: a release notice waits until it has exited.
                withHeldReports(report, { held -> me.nathanfallet.teamcode.cli.update.watchReleaseUpdates(held) }) { runAgent() }
            } finally {
                try { lease?.close() } finally {
                    // After the agent exited: the last session to end restarts the service on the version selected while it ran.
                    lease?.let { withContext(NonCancellable) { me.nathanfallet.teamcode.cli.update.applyPendingServiceRestart(it.store, report = report) } }
                }
            }
        }
        if (code != 0) SystemExit.exit(code)
    }
    private suspend fun runAgent(): Int {
        val parsedLabels = parseLabels(labels)
        val config = loadConfig()
        return sdk(config).use { client ->
            val capabilities = client.capabilities()
            // An older server ignores unknown fields: never let it drop the labels silently.
            if (parsedLabels.isNotEmpty() && RUN_LABELS_CAPABILITY !in capabilities.features)
                throw CliktError("This server does not record run labels; run without --label.")
            // Fails before launching the agent when the saved session is invalid; names only, never an address or credential.
            val servers = assignedMcpServers(client, capabilities)
            val hooks = approvedTeamHooks(client, capabilities)
            val artifacts = artifactsAvailable(client, capabilities)
            // Read before the agent starts: it begins with the current values in its environment.
            val variables = assignedVariables(client, capabilities)
            // Whether the agent works or waits for a person: a state only, reported by the hooks that carry guidance.
            val activity = !noActivity && RUN_ACTIVITY_CAPABILITY in capabilities.features
            val claudeLogin = agent == "claude" && "--bare" !in arguments.takeWhile { it != "--" } && claudeLoggedIn()
            trackedRun(client, agent, workload, "team.runs.v1" in capabilities.features, report = { echo(it, err = true) }, labels = parsedLabels) { runId ->
                val service = loadService(configPath())
                if (service != null && serviceResponds(service, config.serverUrl, backgroundWorkload = workload == "background", runRouting = runId != null, mcpRelay = servers.isNotEmpty(), localTokenHeader = claudeLogin, artifactRelay = artifacts, activityRelay = activity && runId != null && claudeGuided())) {
                    echo("Running $agent through the TeamCode user service.", err = true)
                    launch(client, service.port, service.localToken, runId, servers, claudeLogin, hooks, artifacts, activity, variables)
                } else {
                    val token = secureRandomBytes(32).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                    // A dedicated listener also avoids stopping an older, incompatible shared service.
                    val server = proxyServer(client, token, 0) { echo(it, err = true) }
                    try {
                        server.start(wait = false)
                        val port = server.engine.resolvedConnectors().single().port
                        echo("Running $agent through TeamCode.", err = true)
                        launch(client, port, token, runId, servers, claudeLogin, hooks, artifacts, activity, variables)
                    } finally { server.stop(500, 5_000) }
                }
            }
        }
    }
    /**
     * Whether to connect `teamcode mcp artifacts`: the server publishes the policy and says this member may publish now.
     * Never blocks the launch; without a configured, tested and enabled storage the tools are simply left out.
     */
    private suspend fun artifactsAvailable(client: TeamCodeClient, capabilities: Capabilities): Boolean {
        if (noArtifacts || ARTIFACTS_POLICY_CAPABILITY !in capabilities.features) return false
        val policy = try { client.artifactPolicy() } catch (error: CancellationException) { throw error } catch (_: Exception) {
            echo("Artifact publication could not be checked; continuing without the artifact tools.", err = true)
            return false
        }
        when (policy.reason) {
            "artifact_storage_unverified" -> echo("Artifacts are paused: their storage has not passed its connection test (Dashboard → Settings → Artifacts). The artifact tools are not connected.", err = true)
            "artifact_publish_forbidden" -> echo("Only administrators publish artifacts in this team; the artifact tools are not connected.", err = true)
            "credential_scope" -> echo("This service key cannot publish artifacts (it needs artifacts:write); the artifact tools are not connected.", err = true)
        }
        return policy.canPublish
    }
    /**
     * The environment variables assigned to this member, with their values, or null when the server does not serve
     * them or they are turned off. Never blocks the launch: a failed read starts the agent without them (and a Claude
     * Code session still gets them once the next refresh succeeds). Only names are ever printed.
     */
    private suspend fun assignedVariables(client: TeamCodeClient, capabilities: Capabilities): Map<String, String>? {
        if (noVariables || ENVIRONMENT_CAPABILITY !in capabilities.features) return null
        val view = try { client.environmentVariables() } catch (error: CancellationException) { throw error } catch (error: Exception) {
            val code = (error as? me.nathanfallet.teamcode.client.TeamCodeException)?.code
            echo(if (code == "credential_scope") "Environment variables are given to interactive logins only; this service key starts the agent without them."
                else "Environment variables could not be read; starting the agent without them.", err = true)
            return if (code == "credential_scope") null else emptyMap()
        }
        if (view.unavailable.isNotEmpty()) echo("Environment variables unavailable right now: ${view.unavailable.sorted().joinToString(", ")}.", err = true)
        val refused = AgentEnvironment.refused(view.variables)
        if (refused.isNotEmpty()) echo("Environment variables not given to the agent (reserved names): ${refused.joinToString(", ")}.", err = true)
        val accepted = AgentEnvironment.accepted(view.variables)
        if (accepted.isNotEmpty()) echo("Environment variables: ${accepted.keys.joinToString(", ")}.", err = true)
        return accepted
    }
    /** The team MCP servers to connect for this launch: names only, never an upstream address or credential. */
    private suspend fun assignedMcpServers(client: TeamCodeClient, capabilities: Capabilities): List<String> {
        if (noMcp || "mcp.gateway.v1" !in capabilities.features) return emptyList()
        return try {
            client.mcpServers().map { it.name }.filter { TeamCodeClient.MCP_SERVER_NAME.matches(it) }.also { servers ->
                val skipped = servers.groupBy(::mcpLabel).filterValues { it.size > 1 }.values.flatten()
                if (skipped.isNotEmpty()) echo("MCP servers with conflicting names were not connected: ${skipped.joinToString(", ")}.", err = true)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            echo("Team MCP servers could not be listed; continuing without them.", err = true)
            emptyList()
        }
    }
    /**
     * The team hooks this member approved on this computer, verified and installed. Never blocks the launch: an
     * unavailable manifest or download leaves hooks out with a notice. Hooks awaiting approval are named, not run.
     */
    private suspend fun approvedTeamHooks(client: TeamCodeClient, capabilities: Capabilities): List<InstalledHook> {
        if (noTeamHooks || "team.hooks.v1" !in capabilities.features) return emptyList()
        val options = arguments.takeWhile { it != "--" }
        if (agent == "claude" && "--bare" in options) return emptyList()
        if (agent == "codex" && hasCodexHookOverrides(arguments)) {
            echo("Custom Codex CLI hook overrides detected; team hooks were not added for this launch.", err = true)
            return emptyList()
        }
        val plan = try { teamHookSync(client).plan() } catch (error: CancellationException) { throw error } catch (_: Exception) {
            echo("Team hooks could not be read; continuing without them.", err = true)
            return emptyList()
        }
        if (plan.awaiting.isNotEmpty()) echo("Team hooks awaiting your approval: ${plan.awaiting.joinToString(", ") { it.name }}. " +
            "They do not run until you approve them; review them with teamcode hooks list.", err = true)
        if (plan.failed.isNotEmpty()) echo("Team hooks not installed for this launch (download or verification failed): ${plan.failed.joinToString(", ")}.", err = true)
        if (!teamHooksSupported()) {
            if (plan.installed.isNotEmpty()) echo("Team hooks are not installed on Windows yet.", err = true)
            return emptyList()
        }
        val active = plan.installed.filter { HookEvents.forAgent(agent, it.definition).isNotEmpty() }
        val unsupported = plan.installed - active.toSet()
        if (unsupported.isNotEmpty()) echo("Team hooks with no $agent event: ${unsupported.joinToString(", ") { it.name }}.", err = true)
        if (active.isNotEmpty()) echo("Team hooks: ${active.joinToString(", ") { "${it.name} ${it.version}" }}.", err = true)
        return active
    }
    /** Whether the session plugin carries TeamCode's local HTTP hooks (guidance, and activity with them). */
    private fun claudeGuided() = agent == "claude" && !noGuidance && "--bare" !in arguments.takeWhile { it != "--" }
    private suspend fun launch(client: TeamCodeClient, port: Int, token: String, runId: String?, mcpServers: List<String>, claudeLogin: Boolean, teamHooks: List<InstalledHook>, artifacts: Boolean, activity: Boolean, variables: Map<String, String>?): Int {
        val options = arguments.takeWhile { it != "--" }
        val guided = claudeGuided()
        val plugin = if (agent == "claude" && (guided || teamHooks.isNotEmpty())) try {
            GuidancePlugin.create(configPath(), if (guided) port else null, teamHooks, runId.takeIf { activity })
        } catch (_: Exception) {
            echo("Automatic guidance and team hooks could not be prepared; continuing with server-side limits.", err = true)
            null
        } else null
        val customCodexHooks = agent == "codex" && hasCodexHookOverrides(arguments)
        if (customCodexHooks && !noGuidance) {
            echo("Custom Codex CLI hook overrides detected; add teamcode agents hook to those hooks for guidance.", err = true)
        }
        val codexGuided = agent == "codex" && !noGuidance && !customCodexHooks
        val guidance = if (agent == "codex" && (codexGuided || teamHooks.isNotEmpty())) try {
            codexGuidance(servicePlatform(), configPath(), teamHooks, guidance = codexGuided, activity = codexGuided && runId != null && activity).also {
                echo("TeamCode ${if (codexGuided) "guidance hooks are" else "team hooks are"} available. Review and trust them in Codex /hooks before first use.", err = true)
            }
        } catch (_: Exception) {
            echo("Automatic guidance and team hooks could not be prepared; continuing with server-side limits.", err = true)
            AgentLaunch(emptyList(), emptyMap())
        } else AgentLaunch(emptyList(), emptyMap())
        val artifactServer = if (artifacts) try {
            ArtifactsMcp(servicePlatform().executable, okio.FileSystem.SYSTEM.canonicalize(".".toPath()).toString(),
                configDir = me.nathanfallet.teamcode.cli.config.configPath().toPath().parent?.toString())
        } catch (error: CancellationException) { throw error } catch (_: Exception) {
            echo("The artifact tools could not be prepared; continuing without them.", err = true)
            null
        } else null
        val codexInstructions = agent != "codex" || artifactServer == null || when {
            hasCodexOverride(arguments, "developer_instructions") -> false
            codexConfigSetsDeveloperInstructions() -> false
            else -> true
        }
        if (!codexInstructions) echo("Your Codex developer_instructions are kept; publish pages with the teamcode_artifacts tools.", err = true)
        try {
            val plan = agentLaunch(agent, arguments, port, token, plugin?.directory?.toString(), guidance.arguments, workload, runId, mcpServers,
                claudeLogin, environment("ANTHROPIC_CUSTOM_HEADERS"), hookToken = plugin != null && guided, artifacts = artifactServer,
                codexInstructions = codexInstructions)
            // The variables go first: TeamCode's own settings always win (the server already refuses their names).
            val environment = variables.orEmpty() + plan.environment + mapOf("TEAMCODE_ACTIVITY" to null) + guidance.environment
            val variablesFile = if (agent == "claude" && variables != null) LocalEnvironmentFile.create(client, variables) { echo(it, err = true) } else null
            return try {
                coroutineScope {
                    val refresh = variablesFile?.let { file -> launch { file.refresh.run() } }
                    try { runInteractiveProcess(agent, plan.arguments, environment + (variablesFile?.let { mapOf(AgentEnvironment.CLAUDE_ENV_FILE to it.path) }.orEmpty())) }
                    finally { refresh?.cancel() }
                }
            } catch (error: me.nathanfallet.teamcode.cli.platform.ProcessStartException) { throw error.toUserError() }
            finally { variablesFile?.close() }
        } finally {
            try { plugin?.close() } catch (_: Exception) { echo("The temporary guidance plugin could not be removed.", err = true) }
        }
    }
}

private const val RUN_LABELS_CAPABILITY = "team.runs.labels.v1"
private const val RUN_ACTIVITY_CAPABILITY = "team.runs.activity.v1"
private const val ARTIFACTS_POLICY_CAPABILITY = "artifacts.policy.v1"
internal const val ENVIRONMENT_CAPABILITY = "team.environment.v1"

/**
 * The run's `CLAUDE_ENV_FILE` ([path]) and the values file it sources before each Bash command, both private
 * (`0600`, in a `0700` directory under the TeamCode configuration directory). [refresh] rewrites the values file when
 * the variables change; both are removed when the run ends. A script the user had in `CLAUDE_ENV_FILE` keeps running first.
 */
internal class LocalEnvironmentFile private constructor(val path: String, private val values: String, val refresh: EnvironmentRefresh) {
    fun close() {
        for (file in listOf(values, path)) try { okio.FileSystem.SYSTEM.delete(file.toPath(), mustExist = false) } catch (_: Exception) { }
    }

    companion object {
        fun create(client: TeamCodeClient, variables: Map<String, String>, log: (String) -> Unit): LocalEnvironmentFile? = try {
            val directory = configPath().toPath().parent?.div("environment") ?: error("No configuration directory.")
            val name = secureRandomBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            val path = (directory / "$name.sh").toString()
            val values = (directory / "$name.values.sh").toString()
            val chained = environment(AgentEnvironment.CLAUDE_ENV_FILE)?.takeIf { it.isNotBlank() }
            val refresh = EnvironmentRefresh({ client.environmentVariables() }, { writePrivateFile(values, it) }, variables, log = log)
            refresh.start()
            writePrivateFile(path, AgentEnvironment.localScript(values, chained))
            LocalEnvironmentFile(path, values, refresh)
        } catch (error: CancellationException) { throw error } catch (_: Exception) {
            log("The environment file could not be written; the agent keeps the variables it starts with.")
            null
        }
    }
}

/**
 * `teamcode mcp artifacts`, started by the agent itself as a stdio server: [command] is this TeamCode executable and
 * [root] the directory files are published from. [root] may be a local working directory or `/workspace` in a cloud
 * session; [scratch] is the private directory read and merged files go to (a default under the configuration
 * directory when null); [refused] are paths below the roots that are never published.
 */
internal class ArtifactsMcp(val command: String, val root: String, val scratch: String? = null, val refused: List<String> = emptyList(), val configDir: String? = null)

/** The agent-side name of the artifact tools; never a team server's label, which always starts with `teamcode-`. */
internal const val ARTIFACTS_MCP_LABEL = "teamcode_artifacts"

/** Claude Code's built-in artifact tools, which publish to claude.ai; denied while the TeamCode artifact tools are connected. */
internal val CLAUDE_NATIVE_ARTIFACT_TOOLS = listOf("Artifact", "ArtifactComments", "ArtifactData", "ArtifactCheck")

/** Appended to the agent's instructions while the TeamCode artifact tools are connected. */
internal const val ARTIFACTS_INSTRUCTIONS = "TeamCode artifacts are connected to this session through the `$ARTIFACTS_MCP_LABEL` MCP server. " +
    "Whenever you make a page for people to read (an HTML page, a document, a report, a plan, a dashboard), publish it with the " +
    "`publish` tool of `$ARTIFACTS_MCP_LABEL`, and share or list it with that server's other tools; never use another artifact " +
    "system. Give the person the link the tool returns, `<server>/artifact/<id>`."

/**
 * The caller's options without `--append-system-prompt`, and the text Claude Code would have used (the last one), so it
 * can be merged into TeamCode's. `--append-system-prompt-file` is left alone: Claude Code combines it with the text.
 */
internal fun withoutAppendedPrompt(options: List<String>): Pair<List<String>, String?> {
    val kept = mutableListOf<String>()
    var appended: String? = null
    var index = 0
    while (index < options.size) {
        val option = options[index]
        when {
            option == "--append-system-prompt" && index + 1 < options.size -> { appended = options[index + 1]; index += 2; continue }
            option.startsWith("--append-system-prompt=") -> appended = option.removePrefix("--append-system-prompt=")
            else -> kept += option
        }
        index++
    }
    return kept to appended
}

/**
 * Whether the user's Codex configuration (`$CODEX_HOME/config.toml`, by default `~/.codex/config.toml`) sets
 * `developer_instructions`, at the top level or in a profile: a `-c` override would replace them, so TeamCode adds none.
 * An unreadable file counts as not setting them.
 */
private fun codexConfigSetsDeveloperInstructions(): Boolean = try {
    val home = environment("CODEX_HOME")?.takeIf { it.isNotBlank() }
        ?: (environment("HOME") ?: environment("USERPROFILE"))?.let { "$it/.codex" }
    home != null && okio.FileSystem.SYSTEM.read("$home/config.toml".toPath()) { readUtf8() }
        .lineSequence().any { line -> line.trimStart().removePrefix("\"").startsWith("developer_instructions") && '=' in line }
} catch (error: CancellationException) { throw error } catch (_: Exception) { false }

/** A TOML basic string, for Codex `-c` overrides. */
private fun toml(value: String) = buildString {
    append('"')
    for (char in value) when {
        char == '\\' -> append("\\\\")
        char == '"' -> append("\\\"")
        char.code < 0x20 || char.code == 0x7f -> append("\\u").append(char.code.toString(16).padStart(4, '0'))
        else -> append(char)
    }
    append('"')
}

/** Provider settings that would take precedence over the local proxy; removed for every Claude Code launch. */
private val claudeProviderOverrides: Map<String, String?> = listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN",
    "CLAUDE_CODE_USE_BEDROCK", "CLAUDE_CODE_USE_VERTEX", "CLAUDE_CODE_USE_FOUNDRY").associateWith { null }

/**
 * Whether Claude Code has its own login (claude.ai, as TeamClaude requires), checked with the variables the launch
 * removes. `claude auth status` exits 0 only when logged in; any failure or a slow answer counts as no login. A
 * stored Console API key also counts: Claude Code then sends that key, which the proxy ignores like an OAuth login.
 */
internal suspend fun claudeLoggedIn(): Boolean = try {
    kotlinx.coroutines.withTimeoutOrNull(5_000) { runInteractiveProcess("claude", listOf("auth", "status"), claudeProviderOverrides, quiet = true) } == 0
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    false
}

/** The agent-side name of a team MCP server; two servers with the same label are both left out. */
internal fun mcpLabel(server: String) = "teamcode-" + server.replace('.', '_')

/** Null values remove inherited provider credentials without changing the parent environment or config files. */
internal class AgentLaunch(val arguments: List<String>, val environment: Map<String, String?>)
internal fun agentLaunch(agent: String, arguments: List<String>, port: Int, token: String, guidancePlugin: String? = null, codexGuidance: List<String> = emptyList(), workload: String = "interactive", runId: String? = null, mcpServers: List<String> = emptyList(), claudeLogin: Boolean = false, inheritedHeaders: String? = null, hookToken: Boolean = guidancePlugin != null, artifacts: ArtifactsMcp? = null, codexInstructions: Boolean = true): AgentLaunch {
    require(agent in setOf("claude", "codex") && port in 1..65535 && token.length >= 32 && workload in setOf("interactive", "background"))
    require(runId == null || me.nathanfallet.teamcode.api.models.RunIdentifiers.isValid(runId))
    require(mcpServers.all { TeamCodeClient.MCP_SERVER_NAME.matches(it) })
    val origin = "http://127.0.0.1:$port" + when {
        runId != null -> "/_teamcode/runs/$runId/$workload"
        workload == "background" -> "/_teamcode/workloads/background"
        else -> ""
    }
    // One agent-side MCP server per team server, each on its own local relay endpoint; never one merged server.
    // The `teamcode-` prefix keeps them apart from personal servers, and '.' is not allowed in Codex config keys.
    val mcp = mcpServers.groupBy(::mcpLabel).filterValues { it.size == 1 }.mapValues { it.value.single() }
    fun mcpUrl(server: String) = "http://127.0.0.1:$port/mcp/$server"
    // The artifact tools reach the same proxy's artifact relay; the token never goes on a command line.
    val artifactArguments = artifacts?.let { listOf("mcp", "artifacts", "--root", it.root) + (it.scratch?.let { dir -> listOf("--scratch", dir) } ?: emptyList()) +
        (it.configDir?.let { dir -> listOf("--config-dir", dir) } ?: emptyList()) +
        (runId?.let { id -> listOf("--run", id) } ?: emptyList()) + it.refused.flatMap { path -> listOf("--refuse", path) } }
    val relay = "http://127.0.0.1:$port"
    // Agent options go before a literal "--" delimiter, and after the caller's own options because
    // Claude Code's --mcp-config is variadic and would otherwise swallow a following prompt argument.
    val delimiter = arguments.indexOf("--").let { if (it < 0) arguments.size else it }
    return if (agent == "claude") {
        val mcpConfig = if (mcp.isEmpty() && artifacts == null) emptyList() else listOf("--mcp-config", buildJsonObject {
            putJsonObject("mcpServers") {
                for ((label, server) in mcp) putJsonObject(label) {
                    put("type", "http"); put("url", mcpUrl(server))
                    // Expanded by Claude Code from its environment, so the token is not on the command line.
                    putJsonObject("headers") { put("Authorization", "Bearer \${TEAMCODE_MCP_TOKEN}") }
                }
                if (artifacts != null) putJsonObject(ARTIFACTS_MCP_LABEL) {
                    put("type", "stdio"); put("command", artifacts.command)
                    putJsonArray("args") { artifactArguments!!.forEach { add(it) } }
                    putJsonObject("env") { put("TEAMCODE_ARTIFACTS_URL", relay); put("TEAMCODE_ARTIFACTS_TOKEN", "\${TEAMCODE_MCP_TOKEN}"); put("TEAMCODE_ARTIFACTS_TOKEN_VAR", "TEAMCODE_ARTIFACTS_TOKEN") }
                }
            }
        }.toString())
        // Logged in with claude.ai, Claude Code keeps that login, as under TeamClaude: an environment credential
        // would put it in API mode, which disables the organization's claude.ai connectors. The local capability
        // then rides in a custom header (kept out of the base URL, which Claude Code displays), beside the caller's
        // own custom headers; the proxy ignores the OAuth bearer. Without its own login, the capability is a bearer
        // (ANTHROPIC_AUTH_TOKEN), never ANTHROPIC_API_KEY, which interactive Claude Code asks about before use.
        val keepLogin = claudeLogin && token.none { it.isWhitespace() || it.isISOControl() }
        val headers = if (keepLogin) (inheritedHeaders.orEmpty().lines().filter {
            it.isNotBlank() && !it.substringBefore(':').trim().equals(LOCAL_TOKEN_HEADER, ignoreCase = true)
        } + "$LOCAL_TOKEN_HEADER: $token").joinToString("\n") else inheritedHeaders
        // With the artifact tools connected, Claude Code's own artifact tools (which publish to claude.ai) are denied and
        // the agent is told to publish through teamcode_artifacts. The `=` form is not variadic, and repeated
        // --disallowedTools add up, so the caller's own list is kept. Claude Code keeps only the last
        // --append-system-prompt, so the caller's text is moved into ours rather than replaced.
        val (options, appended) = if (artifacts == null) arguments.take(delimiter) to null else withoutAppendedPrompt(arguments.take(delimiter))
        val artifactOptions = if (artifacts == null) emptyList() else listOf(
            "--append-system-prompt", appended?.let { "$it\n\n$ARTIFACTS_INSTRUCTIONS" } ?: ARTIFACTS_INSTRUCTIONS,
            "--disallowedTools=" + CLAUDE_NATIVE_ARTIFACT_TOOLS.joinToString(","),
        )
        AgentLaunch((guidancePlugin?.let { listOf("--plugin-dir", it) }.orEmpty()) + options + artifactOptions + mcpConfig + arguments.drop(delimiter), claudeProviderOverrides + mapOf(
        "TEAMCODE_HOOK_TOKEN" to if (guidancePlugin != null && hookToken) token else null,
        "TEAMCODE_RUN_ID" to runId,
        "TEAMCODE_MCP_TOKEN" to if (mcp.isNotEmpty() || artifacts != null) token else null,
        "ANTHROPIC_BASE_URL" to origin,
        "ANTHROPIC_AUTH_TOKEN" to if (keepLogin) null else token,
        "ANTHROPIC_CUSTOM_HEADERS" to headers,
    )) } else {
        val overrides = codexGuidance + listOf(
        "-c", "model_provider=\"teamcode\"",
        "-c", "model_providers.teamcode={name=\"TeamCode\",base_url=\"$origin/v1\",env_key=\"TEAMCODE_LOCAL_TOKEN\",wire_api=\"responses\",request_max_retries=0,stream_max_retries=0}",
        ) + mcp.flatMap { (label, server) -> listOf("-c", "mcp_servers.$label={url=\"${mcpUrl(server)}\",bearer_token_env_var=\"TEAMCODE_LOCAL_TOKEN\"}") } +
            // Codex passes a stdio server only the variables it lists: the local token by name, never its value.
            (if (artifacts == null) emptyList() else listOf("-c", "mcp_servers.$ARTIFACTS_MCP_LABEL={command=${toml(artifacts.command)}," +
                "args=[${artifactArguments!!.joinToString(",") { toml(it) }}],env={TEAMCODE_ARTIFACTS_URL=${toml(relay)},TEAMCODE_ARTIFACTS_TOKEN_VAR=\"TEAMCODE_LOCAL_TOKEN\"},env_vars=[\"TEAMCODE_LOCAL_TOKEN\"]}")) +
            // Codex has no artifact tool of its own; it only gets the instruction, unless the caller set its own
            // developer instructions, which an override would replace.
            (if (artifacts == null || !codexInstructions || hasCodexOverride(arguments, "developer_instructions")) emptyList()
            else listOf("-c", "developer_instructions=${toml(ARTIFACTS_INSTRUCTIONS)}"))
        // Put overrides in the leaf command scope (exec/resume/review), before any literal delimiter.
        // Root overrides can be discarded by exec --ignore-user-config in some Codex versions.
        AgentLaunch(arguments.take(delimiter) + overrides + arguments.drop(delimiter),
            mapOf("TEAMCODE_LOCAL_TOKEN" to token, "TEAMCODE_HOOK_TOKEN" to null, "TEAMCODE_RUN_ID" to runId))
    }
}
