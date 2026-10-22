package me.nathanfallet.teamcode.cli.launch

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.choice
import kotlinx.coroutines.cancelAndJoin
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

class Run : SuspendingNoOpCliktCommand(name = "run") {
    override fun help(context: Context) = "Run Claude Code or Codex through an automatically managed local proxy. Pass agent arguments after --."
    init { subcommands(LaunchAgent("claude"), LaunchAgent("codex")) }
}

private class LaunchAgent(private val agent: String) : SuspendingCliktCommand(name = agent) {
    private val noGuidance by option("--no-guidance", help = "Disable automatic session guidance for this launch.").flag()
    private val noMcp by option("--no-mcp", help = "Do not connect the team MCP servers assigned to you for this launch.").flag()
    private val noTeamHooks by option("--no-team-hooks", help = "Do not install the team hooks you approved for this launch.").flag()
    private val workload by option("--workload", help = "Scheduling class for this launch; quotas and permissions still apply.").choice("interactive", "background").default("interactive")
    private val labels by option("--label", help = "key=value; repeatable. Attached to this launch; filter with teamcode sessions list --label.").multiple()
    private val arguments by argument("AGENT_ARGS").multiple()
    override suspend fun run() {
        val code = coroutineScope {
            val service = loadService(configPath())
            val report: (String) -> Unit = { echo(it, err = true) }
            val lease = me.nathanfallet.teamcode.cli.update.agentInstallationLease(service?.executable, report)
            val updates = launch { me.nathanfallet.teamcode.cli.update.watchReleaseUpdates(report) }
            try { runAgent() } finally {
                try { withContext(NonCancellable) { updates.cancelAndJoin() } } finally {
                    try { lease?.close() } finally {
                        // The last session to end restarts the service on the version selected while it ran.
                        lease?.let { withContext(NonCancellable) { me.nathanfallet.teamcode.cli.update.applyPendingServiceRestart(it.store, report = report) } }
                    }
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
            val claudeLogin = agent == "claude" && "--bare" !in arguments.takeWhile { it != "--" } && claudeLoggedIn()
            trackedRun(client, agent, workload, "team.runs.v1" in capabilities.features, report = { echo(it, err = true) }, labels = parsedLabels) { runId ->
                val service = loadService(configPath())
                if (service != null && serviceResponds(service, config.serverUrl, backgroundWorkload = workload == "background", runRouting = runId != null, mcpRelay = servers.isNotEmpty(), localTokenHeader = claudeLogin)) {
                    echo("Running $agent through the TeamCode user service.", err = true)
                    launch(service.port, service.localToken, runId, servers, claudeLogin, hooks)
                } else {
                    val token = secureRandomBytes(32).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                    // A dedicated listener also avoids stopping an older, incompatible shared service.
                    val server = proxyServer(client, token, 0) { echo(it, err = true) }
                    try {
                        server.start(wait = false)
                        val port = server.engine.resolvedConnectors().single().port
                        echo("Running $agent through TeamCode.", err = true)
                        launch(port, token, runId, servers, claudeLogin, hooks)
                    } finally { server.stop(500, 5_000) }
                }
            }
        }
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
    private suspend fun launch(port: Int, token: String, runId: String?, mcpServers: List<String>, claudeLogin: Boolean, teamHooks: List<InstalledHook>): Int {
        val options = arguments.takeWhile { it != "--" }
        val guided = agent == "claude" && !noGuidance && "--bare" !in options
        val plugin = if (agent == "claude" && (guided || teamHooks.isNotEmpty())) try {
            GuidancePlugin.create(configPath(), if (guided) port else null, teamHooks)
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
            codexGuidance(servicePlatform(), configPath(), teamHooks, guidance = codexGuided).also {
                echo("TeamCode ${if (codexGuided) "guidance hooks are" else "team hooks are"} available. Review and trust them in Codex /hooks before first use.", err = true)
            }
        } catch (_: Exception) {
            echo("Automatic guidance and team hooks could not be prepared; continuing with server-side limits.", err = true)
            AgentLaunch(emptyList(), emptyMap())
        } else AgentLaunch(emptyList(), emptyMap())
        try {
            val plan = agentLaunch(agent, arguments, port, token, plugin?.directory?.toString(), guidance.arguments, workload, runId, mcpServers,
                claudeLogin, environment("ANTHROPIC_CUSTOM_HEADERS"), hookToken = plugin != null && guided)
            return try { runInteractiveProcess(agent, plan.arguments, plan.environment + guidance.environment) }
            catch (error: me.nathanfallet.teamcode.cli.platform.ProcessStartException) { throw error.toUserError() }
        } finally {
            try { plugin?.close() } catch (_: Exception) { echo("The temporary guidance plugin could not be removed.", err = true) }
        }
    }
}

private const val RUN_LABELS_CAPABILITY = "team.runs.labels.v1"

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
internal fun agentLaunch(agent: String, arguments: List<String>, port: Int, token: String, guidancePlugin: String? = null, codexGuidance: List<String> = emptyList(), workload: String = "interactive", runId: String? = null, mcpServers: List<String> = emptyList(), claudeLogin: Boolean = false, inheritedHeaders: String? = null, hookToken: Boolean = guidancePlugin != null): AgentLaunch {
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
    // Agent options go before a literal "--" delimiter, and after the caller's own options because
    // Claude Code's --mcp-config is variadic and would otherwise swallow a following prompt argument.
    val delimiter = arguments.indexOf("--").let { if (it < 0) arguments.size else it }
    return if (agent == "claude") {
        val mcpConfig = if (mcp.isEmpty()) emptyList() else listOf("--mcp-config", buildJsonObject {
            putJsonObject("mcpServers") {
                for ((label, server) in mcp) putJsonObject(label) {
                    put("type", "http"); put("url", mcpUrl(server))
                    // Expanded by Claude Code from its environment, so the token is not on the command line.
                    putJsonObject("headers") { put("Authorization", "Bearer \${TEAMCODE_MCP_TOKEN}") }
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
        AgentLaunch((guidancePlugin?.let { listOf("--plugin-dir", it) }.orEmpty()) + arguments.take(delimiter) + mcpConfig + arguments.drop(delimiter), claudeProviderOverrides + mapOf(
        "TEAMCODE_HOOK_TOKEN" to if (guidancePlugin != null && hookToken) token else null,
        "TEAMCODE_RUN_ID" to runId,
        "TEAMCODE_MCP_TOKEN" to if (mcp.isNotEmpty()) token else null,
        "ANTHROPIC_BASE_URL" to origin,
        "ANTHROPIC_AUTH_TOKEN" to if (keepLogin) null else token,
        "ANTHROPIC_CUSTOM_HEADERS" to headers,
    )) } else {
        val overrides = codexGuidance + listOf(
        "-c", "model_provider=\"teamcode\"",
        "-c", "model_providers.teamcode={name=\"TeamCode\",base_url=\"$origin/v1\",env_key=\"TEAMCODE_LOCAL_TOKEN\",wire_api=\"responses\",request_max_retries=0,stream_max_retries=0}",
        ) + mcp.flatMap { (label, server) -> listOf("-c", "mcp_servers.$label={url=\"${mcpUrl(server)}\",bearer_token_env_var=\"TEAMCODE_LOCAL_TOKEN\"}") }
        // Put overrides in the leaf command scope (exec/resume/review), before any literal delimiter.
        // Root overrides can be discarded by exec --ignore-user-config in some Codex versions.
        AgentLaunch(arguments.take(delimiter) + overrides + arguments.drop(delimiter),
            mapOf("TEAMCODE_LOCAL_TOKEN" to token, "TEAMCODE_HOOK_TOKEN" to null, "TEAMCODE_RUN_ID" to runId))
    }
}
