package me.nathanfallet.teamcode.mcp

import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class HostConfigTest {
    private val token = "t".repeat(HostConfig.MIN_TOKEN_LENGTH)

    @Test
    fun `the token is required and long enough, and never printed`() {
        assertFailsWith<IllegalArgumentException> { HostConfig.fromEnvironment(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { HostConfig.fromEnvironment(mapOf("TEAMCODE_MCP_TOKEN" to "short")) }
        assertFalse(token in HostConfig.fromEnvironment(mapOf("TEAMCODE_MCP_TOKEN" to token)).toString())
    }

    @Test
    fun `chart values map to the host settings`() {
        val config = HostConfig.fromEnvironment(mapOf(
            "TEAMCODE_MCP_TOKEN" to token, "TEAMCODE_MCP_PORT" to "9000", "TEAMCODE_MCP_DATA_DIR" to "/data",
            "TEAMCODE_MCP_LAUNCHERS" to "npm", "TEAMCODE_MCP_NPM_REGISTRY" to "https://npm.example.org/",
            "TEAMCODE_MCP_MAX_PROCESSES" to "4", "TEAMCODE_MCP_MAX_IN_FLIGHT_PER_PROCESS" to "8", "TEAMCODE_MCP_PROCESS_MEMORY_MIB" to "256",
            "TEAMCODE_MCP_IDLE_MINUTES" to "5", "TEAMCODE_MCP_STDIO_START_TIMEOUT_MS" to "30000", "TEAMCODE_MCP_PRLIMIT" to "none",
            "TEAMCODE_MCP_DRAIN_SECONDS" to "90", "TEAMCODE_MCP_RETAIN_GRACE_SECONDS" to "0",
        ))
        assertEquals(9000, config.port)
        assertEquals(Path("/data"), config.dataDir)
        assertEquals(setOf(Launchers.NPM), config.launchers)
        assertEquals("https://npm.example.org/", config.npmRegistry)
        assertEquals(4, config.maxProcesses)
        assertEquals(8, config.maxInFlightPerProcess)
        assertEquals(256L * 1024 * 1024, config.processMemoryBytes)
        assertEquals(5 * 60_000L, config.idleMillis)
        assertEquals(30_000L, config.startTimeoutMillis)
        assertNull(config.prlimit)
        assertEquals(90_000L, config.drainMillis)
        assertEquals(0L, config.retainGraceMillis)
        assertEquals(60_000L, HostConfig.fromEnvironment(mapOf("TEAMCODE_MCP_TOKEN" to token)).retainGraceMillis)
        assertEquals("prlimit", HostConfig.fromEnvironment(mapOf("TEAMCODE_MCP_TOKEN" to token)).prlimit)
        assertFailsWith<IllegalArgumentException> { HostConfig.fromEnvironment(mapOf("TEAMCODE_MCP_TOKEN" to token, "TEAMCODE_MCP_LAUNCHERS" to "npm,cargo")) }
        assertFailsWith<IllegalArgumentException> { HostConfig.fromEnvironment(mapOf("TEAMCODE_MCP_TOKEN" to token, "TEAMCODE_MCP_MAX_PROCESSES" to "many")) }
    }
}
