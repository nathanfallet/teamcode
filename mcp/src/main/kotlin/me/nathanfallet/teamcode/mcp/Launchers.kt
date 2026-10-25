package me.nathanfallet.teamcode.mcp

import java.nio.file.Path
import java.time.Instant

/** The directories of one key, below [HostConfig.dataDir]. Each process has its own HOME and caches. */
class ProcessDirs(val root: Path) {
    val home: Path = root.resolve("home")
    val work: Path = root.resolve("work")
    val tmp: Path = root.resolve("tmp")
    val npmCache: Path = root.resolve("npm-cache")
    val uvCache: Path = root.resolve("uv-cache")
    val uvTools: Path = root.resolve("uv-tools")
    val uvBin: Path = root.resolve("uv-bin")
    val python: Path = root.resolve("python")
    val all get() = listOf(home, work, tmp, npmCache, uvCache, uvTools, uvBin, python)
}

/** The exact argv, environment and working directory of a process. No shell is involved anywhere. */
class LaunchPlan(val command: List<String>, val environment: Map<String, String>, val workDir: Path, val secrets: Collection<String>)

/**
 * Turns a [ProcessSpec] into a [LaunchPlan] after checking every rule the server also checks when an administrator
 * declares the variant: a launcher of the configured list, an exactly pinned package, bounded arguments without control
 * characters, environment names that cannot take over the runtime, the registry or the dependency cut-off.
 */
class Launchers(private val config: HostConfig) {
    fun plan(spec: ProcessSpec, dirs: ProcessDirs): LaunchPlan {
        if (spec.launcher !in config.launchers) throw invalid("This host runs only ${config.launchers.sorted().joinToString(" and ")} servers.")
        val pinned = if (spec.launcher == NPM) npmPackage else pypiPackage
        if (!pinned.matches(spec.pkg)) throw invalid(
            if (spec.launcher == NPM) "Name an npm package with its exact version, such as @scope/name@1.2.3 (no range, tag or URL)."
            else "Name a PyPI package with its exact version, such as name==1.2.3 (no range, wildcard or URL)."
        )
        if (spec.declaredAt <= 0) throw invalid("The declaration date is missing.")
        validateVariables(spec)
        val args = arguments(spec)
        val before = Instant.ofEpochMilli(spec.declaredAt).toString()
        val environment = linkedMapOf(
            "PATH" to config.path,
            "HOME" to dirs.home.toString(),
            "TMPDIR" to dirs.tmp.toString(),
            "LANG" to "C.UTF-8",
        )
        val command = mutableListOf<String>()
        config.prlimit?.let { command += listOf(it, "--nproc=${config.maxPids}", "--nofile=${config.maxOpenFiles}", "--") }
        if (spec.launcher == NPM) {
            command += listOf(config.npx, "--yes", "--", spec.pkg)
            environment += mapOf(
                "npm_config_cache" to dirs.npmCache.toString(),
                // Transitive dependencies resolve as they did on the declaration day, so a version published later is never taken.
                "npm_config_before" to before,
                "npm_config_ignore_scripts" to "true",
                "npm_config_yes" to "true",
                "npm_config_update_notifier" to "false",
                "npm_config_fund" to "false",
                "npm_config_audit" to "false",
            )
            config.npmRegistry?.let { environment["npm_config_registry"] = it }
        } else {
            command += listOf(config.uvx, "--exclude-newer", before)
            config.pypiIndex?.let { command += listOf("--default-index", it) }
            command += spec.pkg
            environment += mapOf(
                "UV_CACHE_DIR" to dirs.uvCache.toString(),
                "UV_TOOL_DIR" to dirs.uvTools.toString(),
                "UV_TOOL_BIN_DIR" to dirs.uvBin.toString(),
                "UV_PYTHON_INSTALL_DIR" to dirs.python.toString(),
                "UV_NO_CONFIG" to "1",
                "UV_NO_PROGRESS" to "1",
            )
        }
        command += args
        environment += spec.env
        environment += spec.secrets
        return LaunchPlan(command, environment, dirs.work, spec.secrets.values)
    }

    private fun validateVariables(spec: ProcessSpec) {
        for ((name, value) in spec.env + spec.secrets) {
            if (!envName.matches(name)) throw invalid("Use environment variable names of uppercase letters, digits and underscores.")
            if (name in reservedNames || reservedPrefixes.any { name.startsWith(it) })
                throw invalid("$name is reserved for the runtime, the package manager or TeamCode.")
            if ('\u0000' in value) throw invalid("The value of $name contains a NUL character.")
        }
        spec.env.keys.firstOrNull { it in spec.secrets }?.let { throw invalid("$it is both a plain and a secret variable.") }
        if (spec.secrets.size > MAX_SECRETS) throw invalid("Use at most $MAX_SECRETS secret variables.")
        if (spec.secrets.entries.sumOf { it.key.length + it.value.toByteArray().size } > SECRET_BYTES) throw invalid("The secret variables exceed $SECRET_BYTES bytes.")
        spec.secrets.entries.firstOrNull { it.value.length < MIN_SECRET_LENGTH }
            ?.let { throw invalid("The secret ${it.key} must hold at least $MIN_SECRET_LENGTH characters, so it can be scrubbed from outputs.") }
    }

    private fun arguments(spec: ProcessSpec): List<String> {
        if (spec.args.size > MAX_ARGS) throw invalid("Use at most $MAX_ARGS arguments.")
        return spec.args.map { arg ->
            if (arg.length > MAX_ARG_LENGTH) throw invalid("An argument exceeds $MAX_ARG_LENGTH characters.")
            if (arg.any { it.isISOControl() }) throw invalid("An argument contains a control character.")
            reference.replace(arg) { match ->
                spec.secrets[match.groupValues[1]] ?: throw invalid("\${${match.groupValues[1]}} does not name a secret variable of this server.")
            }
        }
    }

    private fun invalid(message: String) = HostFailure(HostErrors.INVALID_SPEC, message)

    companion object {
        const val NPM = "npm"
        const val PYPI = "pypi"
        val ALL = setOf(NPM, PYPI)
        const val MAX_ARGS = 32
        const val MAX_ARG_LENGTH = 512
        const val MAX_SECRETS = 16
        const val SECRET_BYTES = 16_384
        const val MIN_SECRET_LENGTH = 8
        private const val VERSION = """(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?"""
        /** `name@1.2.3` or `@scope/name@1.2.3`: an exact semantic version, never a range, a tag, a path or a URL. */
        private val npmPackage = Regex("""(@[a-z0-9][a-z0-9._~-]*/)?[A-Za-z0-9][A-Za-z0-9._~-]*@$VERSION""")
        /** `name==1.2.3`, optionally with extras: an exact PEP 440 version, never a range, a wildcard or a URL. */
        private val pypiPackage = Regex("""[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?(\[[A-Za-z0-9._-]+(,[A-Za-z0-9._-]+)*])?==[0-9][0-9A-Za-z.!+-]*""")
        private val reference = Regex("""\${'$'}\{([A-Z_][A-Z0-9_]{0,127})}""")
        private val envName = Regex("[A-Z_][A-Z0-9_]{0,127}")
        /** The server's reserved list for environment variants, plus what would redirect npm, uv or pip. */
        private val reservedNames = setOf(
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "TMPDIR", "TMP", "TEMP", "IFS", "ENV", "BASH_ENV", "PROMPT_COMMAND",
            "NODE_OPTIONS", "NODE_PATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "PYTHONPATH", "PYTHONSTARTUP",
            "PYTHONHOME", "PERL5OPT", "PERL5LIB", "RUBYOPT", "RUBYLIB", "GIT_SSH", "GIT_SSH_COMMAND", "GIT_EXEC_PATH", "GIT_CONFIG_GLOBAL",
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE",
        )
        private val reservedPrefixes = listOf(
            "LD_", "DYLD_", "TEAMCODE_", "ANTHROPIC_", "CLAUDE_", "OPENAI_", "CODEX_", "XDG_", "GIT_CONFIG_", "NPM_CONFIG_", "UV_", "PIP_",
        )
    }
}
