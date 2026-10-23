package me.nathanfallet.teamcode.cli.launch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.nathanfallet.teamcode.api.models.EnvironmentVariablesView

/**
 * The member's environment variables (`ENV` variants) as the agent receives them.
 *
 * - **At start**, every agent gets them in its process environment, so its tools, hooks and MCP servers see them.
 * - **During the session**, Claude Code also gets `CLAUDE_ENV_FILE`. Claude Code reads that script once, when the
 *   session starts, and runs what it read in the shell of every Bash command, just before the command (measured with
 *   Claude Code 2.1.309: rewriting the file later changes nothing). So TeamCode's script holds no value: it fetches
 *   them on each command, and a value rotated, added or removed while the agent runs reaches its next Bash command
 *   without a restart. Locally it sources a values file that TeamCode rewrites ([localScript]; `0600`, in a `0700`
 *   directory, removed when the run ends); in a cloud session it asks the runner over a socket ([cloudScript]).
 *
 * Values are never logged and never placed on a command line; only names are reported.
 */
internal object AgentEnvironment {
    /** What the server accepts as a variant name (`VariantService`), checked again here since values reach a shell. */
    private val name = Regex("[A-Z_][A-Z0-9_]{0,127}")
    /**
     * Names the server refuses for variants, plus the ones TeamCode itself sets for the agent: a value from an older
     * server or a tampered answer must never steer the shell, the loader, Git, `gh` or the agent's own connection.
     */
    private val reserved = setOf(
        "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "OLDPWD", "TMPDIR", "TMP", "TEMP", "IFS", "ENV", "BASH_ENV", "PROMPT_COMMAND",
        "NODE_OPTIONS", "NODE_PATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "PYTHONPATH", "PYTHONSTARTUP",
        "PYTHONHOME", "PERL5OPT", "PERL5LIB", "RUBYOPT", "RUBYLIB", "GIT_SSH", "GIT_SSH_COMMAND", "GIT_EXEC_PATH", "GIT_CONFIG_GLOBAL",
        "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE",
        "GIT_TERMINAL_PROMPT", "GH_CONFIG_DIR", "SHELLOPTS", "BASHOPTS", "CDPATH", "GLOBIGNORE", "PS4",
    )
    private val reservedPrefixes = listOf("LD_", "DYLD_", "TEAMCODE_", "ANTHROPIC_", "CLAUDE_", "OPENAI_", "CODEX_", "XDG_", "GIT_CONFIG_", "BASH_FUNC_")

    /** The variable Claude Code reads its per-command environment script from. */
    const val CLAUDE_ENV_FILE = "CLAUDE_ENV_FILE"

    fun accepted(name: String) = this.name.matches(name) && name !in reserved && reservedPrefixes.none(name::startsWith)

    /** The variables that may reach the agent: valid, unreserved names and values without a NUL. */
    fun accepted(variables: Map<String, String>): Map<String, String> =
        variables.filter { (key, value) -> accepted(key) && '\u0000' !in value }.entries.sortedBy { it.key }.associate { it.key to it.value }

    /** Names withheld by [accepted], reported by name so an administrator can rename them. */
    fun refused(variables: Map<String, String>): List<String> = (variables.keys - accepted(variables).keys).sorted()

    /** Shell code that sets [variables] and unsets [withdrawn] (names the agent was given earlier and no longer has). */
    fun script(variables: Map<String, String>, withdrawn: Set<String> = emptySet()): String = buildString {
        appendLine("# Written by TeamCode for this session: your assigned environment variables. Do not edit.")
        for (variable in withdrawn.filter(::accepted).filter { it !in variables }.sorted()) appendLine("unset $variable")
        for ((key, value) in accepted(variables)) appendLine("export $key=${quote(value)}")
    }

    /**
     * A shell word for [value]: single quotes when it has none, otherwise double quotes with `\`, `$`, `` ` `` and
     * `"` escaped. Both read back as exactly [value] in Bash, with nothing expanded.
     */
    fun quote(value: String): String =
        if ('\'' !in value) "'$value'"
        else "\"" + value.replace("\\", "\\\\").replace("$", "\\$").replace("`", "\\`").replace("\"", "\\\"") + "\""

    /**
     * The local `CLAUDE_ENV_FILE`: sources [values] (rewritten by [EnvironmentRefresh]) on every command. [chained] is
     * a script the user had in `CLAUDE_ENV_FILE` before TeamCode's: it still runs, first, so team values win.
     */
    fun localScript(values: String, chained: String? = null) = buildString {
        appendLine("# Written by TeamCode: reads your current assigned environment variables before each command.")
        chained?.let { appendLine("[ -f ${quote(it)} ] && . ${quote(it)}") }
        appendLine("[ -f ${quote(values)} ] && . ${quote(values)}")
    }

    /**
     * The cloud script: no value, only a call to the runner, which answers with [script] for the current owner. A
     * failure prints nothing, so a Bash command never fails because of it.
     */
    fun cloudScript(binary: String, socket: String) = buildString {
        appendLine("# Written by TeamCode: asks the session runner for the owner's current environment variables before each command.")
        appendLine("eval \"\$(${quote(binary)} cloud environment --socket ${quote(socket)} 2>/dev/null)\"")
    }
}

/**
 * Keeps a local run's values file current: asks the server every [intervalMillis] and rewrites the file when
 * the variables changed. Names given earlier and no longer assigned are unset. A failed read keeps the last values
 * (the server may be briefly unreachable); refusals are reported once, by name only.
 */
internal class EnvironmentRefresh(
    private val fetch: suspend () -> EnvironmentVariablesView,
    private val write: (String) -> Unit,
    initial: Map<String, String>,
    /** Names the agent's process environment already holds, so a removal can unset them. */
    given: Set<String> = initial.keys,
    private val intervalMillis: Long = INTERVAL_MILLIS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val log: (String) -> Unit = {},
) {
    private var current = AgentEnvironment.accepted(initial)
    private val known = given.toMutableSet()
    private var failing = false

    /** Writes the file once, with what the agent starts with. */
    fun start() = write(AgentEnvironment.script(current))

    /** One read: rewrites the file if anything changed; returns whether it did. */
    suspend fun refresh(): Boolean {
        val view = try { fetch().also { if (failing) log("Environment variables are refreshed again.") ; failing = false } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (!failing) log("Environment variables could not be refreshed; the agent keeps the last values.")
                failing = true
                return false
            }
        val next = AgentEnvironment.accepted(view.variables)
        if (next == current) return false
        known += current.keys
        current = next
        write(AgentEnvironment.script(next, known - next.keys))
        log("Environment variables updated for the agent's next commands: ${next.keys.joinToString(", ").ifEmpty { "none" }}.")
        return true
    }

    suspend fun run() { while (true) { sleep(intervalMillis); refresh() } }

    companion object { const val INTERVAL_MILLIS = 60_000L }
}
