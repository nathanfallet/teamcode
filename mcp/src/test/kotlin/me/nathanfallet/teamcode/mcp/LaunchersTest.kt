package me.nathanfallet.teamcode.mcp

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchersTest {
    private val token = "t".repeat(48)
    private val dirs = ProcessDirs(Files.createTempDirectory("mcp-launchers").resolve("a".repeat(64)))
    private val declaredAt = 1_761_350_400_000L // 2025-10-25T00:00:00Z
    private val secret = "s3cr3t-value-of-the-variant"

    private fun npm(pkg: String = "@modelcontextprotocol/server-everything@2.0.0", args: List<String> = emptyList(), env: Map<String, String> = emptyMap(), secrets: Map<String, String> = emptyMap()) =
        ProcessSpec(Launchers.NPM, pkg, args, env, secrets, declaredAt)

    private fun pypi(pkg: String = "mcp-server-fetch==2026.10.24", args: List<String> = emptyList()) =
        ProcessSpec(Launchers.PYPI, pkg, args, emptyMap(), emptyMap(), declaredAt)

    private fun invalid(spec: ProcessSpec, config: HostConfig = HostConfig(token)) {
        val failure = assertFailsWith<HostFailure> { Launchers(config).plan(spec, dirs) }
        assertEquals(HostErrors.INVALID_SPEC, failure.code)
        assertFalse(secret in failure.message.orEmpty(), "a refusal never quotes a secret")
    }

    @Test
    fun `npm runs npx without a shell, pinned, frozen at the declaration date and without install scripts`() {
        val plan = Launchers(HostConfig(token)).plan(
            npm(args = listOf("stdio", "--token", "\${API_TOKEN}"), env = mapOf("API_URL" to "https://api.example.org"), secrets = mapOf("API_TOKEN" to secret)), dirs,
        )
        assertEquals(
            listOf("prlimit", "--nproc=1024", "--nofile=1024", "--", "npx", "--yes", "--", "@modelcontextprotocol/server-everything@2.0.0", "stdio", "--token", secret),
            plan.command,
        )
        val env = plan.environment
        assertEquals("/usr/local/bin:/usr/bin:/bin", env["PATH"])
        assertEquals(dirs.home.toString(), env["HOME"])
        assertEquals(dirs.tmp.toString(), env["TMPDIR"])
        assertEquals(dirs.npmCache.toString(), env["npm_config_cache"])
        assertEquals("2025-10-25T00:00:00Z", env["npm_config_before"])
        assertEquals("true", env["npm_config_ignore_scripts"])
        assertEquals("https://api.example.org", env["API_URL"])
        assertEquals(secret, env["API_TOKEN"])
        assertFalse("npm_config_registry" in env, "the default registry stays npm's own")
        assertEquals(dirs.work, plan.workDir)
        assertEquals(listOf(secret), plan.secrets.toList())
        // Nothing from the host's own environment.
        assertTrue(env.keys.none { it.startsWith("JAVA") || it.startsWith("TEAMCODE") || it == "USER" })
    }

    @Test
    fun `pypi runs uvx pinned and frozen with its own caches and Python`() {
        val config = HostConfig(token, prlimit = null, pypiIndex = "https://pypi.internal.example/simple")
        val plan = Launchers(config).plan(pypi(args = listOf("--ignore-robots-txt")), dirs)
        assertEquals(
            listOf("uvx", "--exclude-newer", "2025-10-25T00:00:00Z", "--default-index", "https://pypi.internal.example/simple", "mcp-server-fetch==2026.10.24", "--ignore-robots-txt"),
            plan.command,
        )
        assertEquals(dirs.uvCache.toString(), plan.environment["UV_CACHE_DIR"])
        assertEquals(dirs.python.toString(), plan.environment["UV_PYTHON_INSTALL_DIR"])
        assertEquals(dirs.uvTools.toString(), plan.environment["UV_TOOL_DIR"])
        assertEquals("1", plan.environment["UV_NO_CONFIG"])
    }

    @Test
    fun `a configured npm registry is used`() {
        val plan = Launchers(HostConfig(token, npmRegistry = "https://npm.internal.example/")).plan(npm(), dirs)
        assertEquals("https://npm.internal.example/", plan.environment["npm_config_registry"])
    }

    @Test
    fun `only exact versions of the configured launchers run`() {
        invalid(npm(), HostConfig(token, launchers = setOf(Launchers.PYPI)))
        invalid(ProcessSpec("image", "x@1.0.0", declaredAt = declaredAt))
        for (pkg in listOf("left-pad", "left-pad@^1.3.0", "left-pad@~1.3.0", "left-pad@latest", "left-pad@1.3", "left-pad@1.3.0 ", "left-pad@*",
            "../left-pad@1.3.0", "left-pad@file:../x", "https://example.org/x.tgz", "git+https://example.org/x.git", "@scope@1.0.0", "-e@1.0.0"))
            invalid(npm(pkg))
        for (pkg in listOf("mcp-server-fetch", "mcp-server-fetch>=1.0", "mcp-server-fetch==1.*", "mcp-server-fetch===1.0", "mcp-server-fetch==1.0;python_version<'4'",
            "-mcp==1.0", "git+https://example.org/x.git"))
            invalid(pypi(pkg))
        // Accepted forms.
        Launchers(HostConfig(token)).plan(npm("@scope/name.js@1.2.3-beta.1"), dirs)
        Launchers(HostConfig(token)).plan(npm("left-pad@1.3.0"), dirs)
        Launchers(HostConfig(token)).plan(pypi("mcp-server-git[cli]==2025.1.14"), dirs)
        invalid(npm().copy(declaredAt = 0))
    }

    @Test
    fun `arguments are bounded and never carry control characters or unknown references`() {
        invalid(npm(args = List(Launchers.MAX_ARGS + 1) { "a" }))
        invalid(npm(args = listOf("a".repeat(Launchers.MAX_ARG_LENGTH + 1))))
        for (arg in listOf("a\nb", "a\u0000b", "a\rb", "a\u001bb", "a\u0085b")) invalid(npm(args = listOf(arg)))
        invalid(npm(args = listOf("\${MISSING}"), secrets = mapOf("API_TOKEN" to secret)))
        Launchers(HostConfig(token)).plan(npm(args = List(Launchers.MAX_ARGS) { "a".repeat(Launchers.MAX_ARG_LENGTH) }), dirs)
    }

    @Test
    fun `variables cannot take over the runtime, the registry or the dependency cut-off`() {
        for (name in listOf("PATH", "HOME", "NODE_OPTIONS", "LD_PRELOAD", "PYTHONPATH", "NPM_CONFIG_REGISTRY", "NPM_CONFIG_BEFORE", "UV_EXCLUDE_NEWER",
            "UV_INDEX_URL", "PIP_INDEX_URL", "TEAMCODE_MCP_TOKEN", "npm_config_registry", "lower", "1ABC", "A-B")) {
            invalid(npm(env = mapOf(name to "value")))
            invalid(npm(secrets = mapOf(name to secret)))
        }
        invalid(npm(env = mapOf("API_URL" to "a\u0000b")))
        invalid(npm(env = mapOf("API_TOKEN" to "x"), secrets = mapOf("API_TOKEN" to secret)))
    }

    @Test
    fun `secret values are bounded and long enough to be scrubbed`() {
        invalid(npm(secrets = mapOf("API_TOKEN" to "short")))
        invalid(npm(secrets = (1..Launchers.MAX_SECRETS + 1).associate { "S$it" to secret }))
        invalid(npm(secrets = mapOf("A" to "a".repeat(9_000), "B" to "b".repeat(9_000))))
    }
}
