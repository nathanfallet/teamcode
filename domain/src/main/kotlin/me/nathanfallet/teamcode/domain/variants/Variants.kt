package me.nathanfallet.teamcode.domain.variants

import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure

/** What a variant configures: one environment variable or one MCP server. */
enum class VariantKind { ENV, MCP }

/**
 * Where the secret part of a variant lives. SHARED keeps one encrypted value on the variant (an environment value or a
 * team MCP credential). PER_MEMBER stores an encrypted value on each assignment (an individual value or a delegated MCP
 * credential), so the variant itself never holds one.
 */
enum class VariantBinding { SHARED, PER_MEMBER }

/**
 * How an assignment was made. Higher precedence wins when several sources select the same target for a member.
 * GROUP is reserved for group assignments (between DIRECT and BULK); no adapter produces it yet.
 */
enum class AssignmentSource(val precedence: Int) { DIRECT(300), GROUP(200), BULK(100) }

/** The slot a variant fills: an environment variable name or an MCP server name. A member has at most one per slot. */
data class VariantTarget(val kind: VariantKind, val name: String)

/** A secret that never prints itself. Callers read [reveal] only to hand it to an authorized execution. */
class SecretValue(private val value: String) {
    fun reveal() = value
    override fun toString() = "SecretValue([REDACTED])"
    override fun equals(other: Any?) = other is SecretValue && other.value == value
    override fun hashCode() = value.hashCode()
}

/**
 * How the gateway reaches an MCP variant's server. HTTP calls its HTTPS [Variant.endpoint]; STDIO runs [Variant.command]
 * on the server's MCP host. [wire] is the value of the API and of the `variants.transport` column. Environment variants
 * keep the default, HTTP, which means nothing for them.
 */
enum class McpTransport(val wire: String) {
    HTTP("http"), STDIO("stdio");
    companion object {
        fun parse(value: String) = entries.find { it.wire == value } ?: throw TeamFailure("invalid_variant", 400, "Use the http or stdio transport.")
    }
}

/** The package runner the MCP host starts a hosted command with: `npx` for an npm package, `uvx` for a PyPI one. */
enum class StdioLauncher(val wire: String) {
    NPM("npm"), PYPI("pypi");
    companion object {
        fun parse(value: String) = entries.find { it.wire == value } ?: throw TeamFailure("invalid_variant", 400, "Use the npm or pypi launcher.")
    }
}

/**
 * A hosted MCP server: the exact package version the MCP host runs ([pkg]: `name@1.2.3` on npm, `name==1.2.3` on PyPI)
 * and the arguments passed to it. An argument may hold `${NAME}`, which the host replaces with the secret variable NAME
 * at launch. No shell ever reads it. Immutable after creation, like an HTTP variant's endpoint.
 */
data class StdioCommand(val launcher: StdioLauncher, val pkg: String, val args: List<String> = emptyList())

/**
 * Variant metadata. Secret values are not part of this type; [hasSharedSecret] only says whether one is stored.
 * An MCP variant has either an [endpoint] ([McpTransport.HTTP]) or a [command] ([McpTransport.STDIO]); for a hosted
 * command, [config] holds its visible environment variables.
 */
data class Variant(
    val id: String, val kind: VariantKind, val target: String, val name: String, val binding: VariantBinding,
    val version: Int, val endpoint: String?, val config: Map<String, String>, val hasSharedSecret: Boolean,
    val createdAt: Long, val updatedAt: Long, val transport: McpTransport = McpTransport.HTTP, val command: StdioCommand? = null,
) {
    val slot get() = VariantTarget(kind, target)
}

/** For a hosted command, [sharedSecret] is a [StdioEnvironment] object of secret variables, not a bare credential. */
data class NewVariant(
    val kind: VariantKind, val target: String, val name: String, val binding: VariantBinding,
    val endpoint: String? = null, val config: Map<String, String> = emptyMap(), val sharedSecret: SecretValue? = null,
    val transport: McpTransport = McpTransport.HTTP, val command: StdioCommand? = null,
)

/**
 * The secret variables of a hosted command, kept in one existing secret envelope as a JSON object `{"NAME":"value"}`, so
 * shared values, member values, rotation and bulk assignment work as for any variant. Encoded with sorted names.
 */
object StdioEnvironment {
    fun encode(values: Map<String, String>): SecretValue =
        SecretValue(buildJsonObject { values.toSortedMap().forEach { (name, value) -> put(name, value) } }.toString())
    /** The variables, or a 400 when the secret is not a JSON object of strings. */
    fun decode(secret: SecretValue): Map<String, String> = decodeOrNull(secret)
        ?: throw TeamFailure("invalid_variant", 400, "A hosted command takes its secrets as variables: an object of NAME and value strings.")
    fun decodeOrNull(secret: SecretValue): Map<String, String>? {
        val parsed = try { Json.parseToJsonElement(secret.reveal()) } catch (_: IllegalArgumentException) { return null }
        if (parsed !is JsonObject) return null
        return parsed.mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null }
    }
}

/**
 * Replaces the mutable fields. A null [sharedSecret] keeps the stored one unless [clearSharedSecret] removes it.
 * Kind, target, binding, transport, the MCP endpoint and a hosted command (with its visible variables in [config]) are
 * immutable: stored credentials must never follow a new host or reach other code.
 */
data class VariantUpdate(
    val expectedVersion: Int, val name: String, val config: Map<String, String> = emptyMap(),
    val sharedSecret: SecretValue? = null, val clearSharedSecret: Boolean = false,
)

data class VariantAssignment(
    val memberId: String, val target: VariantTarget, val variantId: String, val variantName: String,
    val version: Int, val source: AssignmentSource, val hasMemberSecret: Boolean, val assignedAt: Long,
)

/** Metadata view of one member's effective assignments, for administrators. No secret values. */
data class MemberVariantAssignments(
    val memberId: String, val username: String, val disabled: Boolean,
    val assignments: List<VariantAssignment>, val exclusions: List<VariantTarget>,
)

data class BulkAssignment(val assigned: Int, val keptDirect: Int, val excluded: Int)

/** Administrator management of variants, assignments and exclusions. Adapters revalidate the actor in the write transaction. */
interface VariantService {
    suspend fun list(actor: Principal): List<Variant>
    suspend fun create(actor: Principal, variant: NewVariant): Variant
    suspend fun update(actor: Principal, id: String, update: VariantUpdate): Variant
    suspend fun delete(actor: Principal, id: String)
    /** Selects [variantId] for the member's slot, replacing any previous selection. Refused while the member is excluded. */
    suspend fun assign(actor: Principal, memberId: String, variantId: String, memberSecret: SecretValue? = null): VariantAssignment
    suspend fun unassign(actor: Principal, memberId: String, target: VariantTarget)
    /** Assigns a SHARED variant to every enabled member, except excluded members and members with a DIRECT selection. */
    suspend fun bulkAssign(actor: Principal, variantId: String): BulkAssignment
    /** Removes the member's selection for this slot and blocks later direct or bulk assignment until [include]. */
    suspend fun exclude(actor: Principal, memberId: String, target: VariantTarget)
    suspend fun include(actor: Principal, memberId: String, target: VariantTarget)
    /** Every member of the team, or only [memberId], with their effective assignments. */
    suspend fun assignments(actor: Principal, memberId: String? = null): List<MemberVariantAssignments>

    companion object {
        const val VARIANT_LIMIT = 500L
        const val SECRET_LIMIT = 16_384
        const val STDIO_ARGS_LIMIT = 32
        const val STDIO_ARG_LENGTH = 512
        const val STDIO_SECRET_LIMIT = 16
        /** Shorter secret values are refused: masking them in a hosted command's output would also hide ordinary text. */
        const val STDIO_SECRET_MIN_LENGTH = 8
        private const val PACKAGE_LENGTH = 256
        private val envName = Regex("[A-Z_][A-Z0-9_]{0,127}")
        private val mcpName = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val configKey = Regex("[A-Za-z0-9_.-]{1,64}")
        /** Names that would let a variant take over the process, the dynamic loader or the agent's own connection. */
        private val reservedEnvNames = setOf(
            "PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "TMPDIR", "TMP", "TEMP", "IFS", "ENV", "BASH_ENV", "PROMPT_COMMAND",
            "NODE_OPTIONS", "NODE_PATH", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "PYTHONPATH", "PYTHONSTARTUP",
            "PYTHONHOME", "PERL5OPT", "PERL5LIB", "RUBYOPT", "RUBYLIB", "GIT_SSH", "GIT_SSH_COMMAND", "GIT_EXEC_PATH", "GIT_CONFIG_GLOBAL",
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR", "NODE_EXTRA_CA_CERTS", "REQUESTS_CA_BUNDLE",
        )
        private val reservedEnvPrefixes = listOf("LD_", "DYLD_", "TEAMCODE_", "ANTHROPIC_", "CLAUDE_", "OPENAI_", "CODEX_", "XDG_", "GIT_CONFIG_")
        /** A hosted command's variables cannot change how its launcher resolves or installs the package. */
        private val reservedLauncherPrefixes = listOf("NPM_CONFIG_", "UV_", "PIP_")
        private const val SEMVER = """(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?(\+[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*)?"""
        /** `name@1.2.3` or `@scope/name@1.2.3`: npm's lowercase names and one exact semantic version, no range or tag. */
        private val npmPackage = Regex("""(@[a-z0-9~][a-z0-9._~-]*/)?[a-z0-9~][a-z0-9._~-]*@$SEMVER""")
        /** `name==1.2.3`: a PyPI project name and one exact public version (PEP 440), no range, wildcard, extra or marker. */
        private val pypiPackage = Regex("""[A-Za-z0-9]([A-Za-z0-9._-]*[A-Za-z0-9])?==[0-9]+(\.[0-9]+)*((a|b|rc)[0-9]+)?(\.post[0-9]+)?(\.dev[0-9]+)?""")
        private val secretReference = Regex("""\$\{([^}]*)}""")

        fun validateTarget(target: VariantTarget) {
            val pattern = if (target.kind == VariantKind.ENV) envName else mcpName
            if (!pattern.matches(target.name)) throw invalid(
                if (target.kind == VariantKind.ENV) "Use an environment variable name of uppercase letters, digits and underscores."
                else "Use an MCP server name of lowercase letters, digits, dots, dashes and underscores (at most 64)."
            )
            if (target.kind == VariantKind.ENV && (target.name in reservedEnvNames || reservedEnvPrefixes.any { target.name.startsWith(it) }))
                throw invalid("${target.name} is reserved for the runtime, its loader or TeamCode's own agent connection.")
        }
        /**
         * [stdioLaunchers] are the launchers the server's MCP host offers. Empty means no host is configured, and a hosted
         * command is refused with `409 mcp_disabled` before anything else: nothing could ever run it.
         */
        fun validate(variant: NewVariant, stdioLaunchers: Set<StdioLauncher> = emptySet()) {
            val hosted = variant.kind == VariantKind.MCP && variant.transport == McpTransport.STDIO
            if (hosted && stdioLaunchers.isEmpty())
                throw TeamFailure("mcp_disabled", 409, "No MCP host is configured on this server (Helm: mcp.enabled), so it cannot run a hosted command.")
            validateTarget(VariantTarget(variant.kind, variant.target))
            if (variant.kind == VariantKind.ENV && (variant.transport != McpTransport.HTTP || variant.command != null))
                throw invalid("Environment variants have a value only, no transport or command.")
            validateFields(variant.kind, variant.name, variant.transport, variant.endpoint, variant.command, variant.config, variant.sharedSecret)
            when {
                variant.binding == VariantBinding.PER_MEMBER && variant.sharedSecret != null ->
                    throw invalid("A per-member variant stores its values on each assignment, not on the variant.")
                variant.kind == VariantKind.ENV && variant.binding == VariantBinding.SHARED && variant.sharedSecret == null ->
                    throw invalid("A shared environment variant needs a value.")
            }
            if (hosted) {
                val command = variant.command!!
                if (command.launcher !in stdioLaunchers) throw invalid("This server's MCP host does not offer the ${command.launcher.wire} launcher.")
                if (variant.binding == VariantBinding.SHARED) requireReferences(command, variant.sharedSecret?.let(StdioEnvironment::decode).orEmpty())
            }
        }
        fun validate(current: Variant, update: VariantUpdate) {
            validateFields(current.kind, update.name, current.transport, current.endpoint, current.command, update.config, update.sharedSecret)
            if (current.binding == VariantBinding.PER_MEMBER && (update.sharedSecret != null || update.clearSharedSecret))
                throw invalid("A per-member variant stores its values on each assignment, not on the variant.")
            if (update.clearSharedSecret && update.sharedSecret != null) throw invalid("Either replace or clear the shared secret, not both.")
            if (current.kind == VariantKind.ENV && update.clearSharedSecret) throw invalid("A shared environment variant needs a value.")
            val command = current.command
            if (current.kind == VariantKind.MCP && current.transport == McpTransport.STDIO && command != null) {
                if (update.config != current.config)
                    throw invalid("A hosted command's variables are fixed at creation, since they decide where its secrets go. Send them unchanged, or create another variant.")
                if (update.clearSharedSecret) requireReferences(command, emptyMap())
                update.sharedSecret?.let { requireReferences(command, StdioEnvironment.decode(it)) }
            }
        }
        fun validateMemberSecret(binding: VariantBinding, secret: SecretValue?) {
            if (binding == VariantBinding.PER_MEMBER && secret == null) throw invalid("A per-member variant needs this member's value or credential.")
            if (binding == VariantBinding.SHARED && secret != null) throw invalid("A shared variant does not accept a member value.")
            secret?.let(::validateSecret)
        }
        /** As above, and for a hosted command the member's value is an object of secret variables holding every `${NAME}` its arguments use. */
        fun validateMemberSecret(variant: Variant, secret: SecretValue?) {
            validateMemberSecret(variant.binding, secret)
            val command = variant.command
            if (secret == null || variant.kind != VariantKind.MCP || variant.transport != McpTransport.STDIO || command == null) return
            requireReferences(command, validateStdioSecrets(secret, variant.config))
        }
        private fun validateFields(
            kind: VariantKind, name: String, transport: McpTransport, endpoint: String?, command: StdioCommand?,
            config: Map<String, String>, secret: SecretValue?,
        ) {
            if (name.isBlank() || name.length > 120 || name.any(Char::isISOControl)) throw invalid("Use a variant name of 1–120 characters without control characters.")
            secret?.let(::validateSecret)
            if (kind == VariantKind.ENV) {
                if (endpoint != null || config.isNotEmpty()) throw invalid("Environment variants have a value only, no endpoint or configuration.")
                return
            }
            if (transport == McpTransport.STDIO) {
                if (endpoint != null) throw invalid("A hosted command has no endpoint: the gateway reaches it through the MCP host.")
                validateCommand(command ?: throw invalid("A hosted command needs a launcher and a package."))
                validateStdioVariables(config)
                secret?.let { validateStdioSecrets(it, config) }
                return
            }
            if (command != null) throw invalid("An HTTPS MCP variant has no command.")
            validateEndpoint(endpoint ?: throw invalid("An MCP variant needs an HTTPS endpoint."))
            if (config.size > 32 || config.any { (key, value) -> !configKey.matches(key) || value.length > 1024 || value.any(Char::isISOControl) })
                throw invalid("Use at most 32 configuration entries with simple keys and values of at most 1024 characters.")
        }
        private fun validateCommand(command: StdioCommand) {
            val pattern = if (command.launcher == StdioLauncher.NPM) npmPackage else pypiPackage
            if (command.pkg.length > PACKAGE_LENGTH || !pattern.matches(command.pkg)) throw invalid(
                if (command.launcher == StdioLauncher.NPM) "Use an npm package with one exact version, such as @scope/name@1.2.3: no range, tag or URL."
                else "Use a PyPI package with one exact version, such as name==1.2.3: no range, wildcard, extra or URL."
            )
            if (command.args.size > STDIO_ARGS_LIMIT || command.args.any { it.isEmpty() || it.length > STDIO_ARG_LENGTH || it.any(Char::isISOControl) })
                throw invalid("Use at most $STDIO_ARGS_LIMIT arguments of 1–$STDIO_ARG_LENGTH characters without control characters.")
            if (references(command).any { !envName.matches(it) })
                throw invalid("Write a secret reference as \${NAME}, with an environment variable name of uppercase letters, digits and underscores.")
        }
        private fun validateStdioVariables(variables: Map<String, String>) {
            if (variables.size > 32 || variables.any { (key, value) -> !stdioName(key) || value.length > 1024 || value.any(Char::isISOControl) })
                throw invalid("Use at most 32 visible variables with unreserved environment names and values of at most 1024 characters.")
        }
        /** Secret variables: at most 16, unreserved names that no visible variable uses, values of at least 8 characters. */
        private fun validateStdioSecrets(secret: SecretValue, variables: Map<String, String>): Map<String, String> {
            val values = StdioEnvironment.decode(secret)
            if (values.isEmpty() || values.size > STDIO_SECRET_LIMIT) throw invalid("Use 1–$STDIO_SECRET_LIMIT secret variables.")
            if (values.keys.any { !stdioName(it) || it in variables }) throw invalid("Use unreserved environment names for secret variables, different from the visible ones.")
            if (values.values.any { it.length < STDIO_SECRET_MIN_LENGTH || '\u0000' in it })
                throw invalid("Secret variable values must contain at least $STDIO_SECRET_MIN_LENGTH characters, so that masking them never hides ordinary output, and no NUL character.")
            return values
        }
        private fun requireReferences(command: StdioCommand, secrets: Map<String, String>) {
            val missing = references(command).filter { it !in secrets }
            if (missing.isNotEmpty()) throw invalid("The arguments use ${missing.joinToString { "\${$it}" }}, which the secret variables do not define.")
        }
        private fun references(command: StdioCommand) = command.args.flatMap { arg -> secretReference.findAll(arg).map { it.groupValues[1] } }.toSet()
        private fun stdioName(name: String) = envName.matches(name) && name !in reservedEnvNames &&
            (reservedEnvPrefixes + reservedLauncherPrefixes).none { name.startsWith(it) }
        private fun validateSecret(secret: SecretValue) {
            val value = secret.reveal()
            if (value.isEmpty() || value.length > SECRET_LIMIT) throw invalid("Secret values must contain 1–$SECRET_LIMIT characters.")
        }
        private fun validateEndpoint(endpoint: String) {
            val uri = try { URI(endpoint) } catch (_: URISyntaxException) { throw invalid("Use a valid HTTPS MCP endpoint.") }
            if (endpoint.length > 2048 || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null)
                throw invalid("Use an HTTPS MCP endpoint without credentials or a fragment; put credentials in the encrypted secret.")
        }
        private fun invalid(message: String) = TeamFailure("invalid_variant", 400, message)
    }
}
