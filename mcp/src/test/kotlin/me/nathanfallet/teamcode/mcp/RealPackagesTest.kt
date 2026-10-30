package me.nathanfallet.teamcode.mcp

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.isExecutable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opt-in (`TEAMCODE_MCP_REAL_PACKAGES=1`, network): the real npm and PyPI servers the plan names, through the real
 * `npx` and `uvx`, with the dependency cut-off set to the declaration date. Prints cold and warm timings and the
 * process tree's memory for the pull request. Ordinary CI does not run it; the image job runs the same packages.
 */
class RealPackagesTest {
    private fun real(name: String, spec: ProcessSpec, tool: String, arguments: kotlinx.serialization.json.JsonObject) {
        assumeTrue(System.getenv("TEAMCODE_MCP_REAL_PACKAGES") == "1", "set TEAMCODE_MCP_REAL_PACKAGES=1 to run")
        val launcher = if (spec.launcher == Launchers.NPM) "npx" else "uvx"
        val directory = System.getenv("PATH").orEmpty().split(':').firstOrNull { Path(it, launcher).isExecutable() } ?: error("$launcher is not on PATH")
        val nodeDirectory = Path(HostFixture.node).parent.toString()
        val root = Files.createTempDirectory("teamcode-mcp-real")
        val config = HostConfig(
            token = "r".repeat(48), host = "127.0.0.1", dataDir = root,
            path = listOf(directory, nodeDirectory, "/usr/bin", "/bin").distinct().joinToString(":"),
            prlimit = "/usr/bin/prlimit".takeIf { Path(it).isExecutable() },
            memoryCheckMillis = 500, tickMillis = 250,
        )
        val supervisor = Supervisor(config)
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { mcpHost(config, supervisor) }.start()
        try {
            runBlocking {
                val key = HostFixture.key(name)
                val list = supervisor.op(OpsRequest(key, spec, "list", deadlineMs = 120_000, maxResponseBytes = 4 * 1024 * 1024))
                assertEquals(null, list.error, "list: ${list.error}")
                val tools = list.result!!["tools"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
                assertTrue(tool in tools, "$tool in $tools")
                val warmStarted = System.nanoTime()
                val call = supervisor.op(OpsRequest(key, spec, "call", tool, arguments, deadlineMs = 60_000, maxResponseBytes = 4 * 1024 * 1024))
                val warmMillis = (System.nanoTime() - warmStarted) / 1_000_000
                assertEquals(null, call.error, "call: ${call.error}")
                Thread.sleep(1_500)
                val status = supervisor.status().processes.single()
                // A restart reuses the key's caches: the start without download.
                supervisor.stop(key)
                Thread.sleep(3_000)
                val again = supervisor.op(OpsRequest(key, spec, "list", deadlineMs = 120_000, maxResponseBytes = 4 * 1024 * 1024))
                assertTrue(again.cold && again.error == null)
                val cached = supervisor.status().processes.single().coldStartMillis
                println("REAL-PACKAGE $name ${spec.pkg}: cold start ${status.coldStartMillis} ms (download), ${cached} ms (cached), warm $tool ${warmMillis} ms, " +
                    "tools ${tools.size}, RSS ${status.rssBytes?.let { it / (1024 * 1024) }} MiB (process tree)")
            }
        } finally {
            runBlocking { supervisor.shutdown() }
            server.stop(0, 2_000)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun `server-everything from npm`() = real(
        "everything", ProcessSpec(Launchers.NPM, "@modelcontextprotocol/server-everything@2.0.0", declaredAt = System.currentTimeMillis()),
        "echo", buildJsonObject { put("message", "hello") },
    )

    @Test
    fun `mcp-server-fetch from PyPI`() = real(
        "fetch", ProcessSpec(Launchers.PYPI, "mcp-server-fetch==2026.10.24", declaredAt = System.currentTimeMillis()),
        "fetch", buildJsonObject { put("url", "https://example.com") },
    )

    @Test
    fun `a version published after the declaration date is refused by npm`() {
        assumeTrue(System.getenv("TEAMCODE_MCP_REAL_PACKAGES") == "1", "set TEAMCODE_MCP_REAL_PACKAGES=1 to run")
        val directory = System.getenv("PATH").orEmpty().split(':').first { Path(it, "npx").isExecutable() }
        val root = Files.createTempDirectory("teamcode-mcp-real")
        val config = HostConfig(token = "r".repeat(48), dataDir = root, path = "$directory:${Path(HostFixture.node).parent}:/usr/bin:/bin", prlimit = null)
        val supervisor = Supervisor(config)
        try {
            runBlocking {
                // 2.0.0 was published on 2026-10-19: a declaration dated 2026-10-01 cannot run it.
                val spec = ProcessSpec(Launchers.NPM, "@modelcontextprotocol/server-everything@2.0.0", declaredAt = 1_790_812_800_000)
                val list = supervisor.op(OpsRequest(HostFixture.key("before"), spec, "list", deadlineMs = 120_000, maxResponseBytes = 1024 * 1024))
                assertEquals(HostErrors.START_FAILED, list.error?.code)
                val stderr = supervisor.status().processes.single().stderr
                assertTrue("No matching version found" in stderr && "with a date before" in stderr, stderr)
                println("REAL-PACKAGE before: refused, npm said: ${stderr.lines().first { "No matching version" in it }.trim()}")
            }
        } finally {
            runBlocking { supervisor.shutdown() }
            root.toFile().deleteRecursively()
        }
    }
}
