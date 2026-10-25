package me.nathanfallet.teamcode.mcp

import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Settings of the host, read from `TEAMCODE_MCP_*` variables (the Helm chart sets them). Defaults follow docs/mcp.md;
 * the durations below the second exist for tests.
 */
data class HostConfig(
    val token: String,
    val host: String = "0.0.0.0",
    val port: Int = 8090,
    /** One directory per key below it: HOME, working directory, temporary files and the npm and uv caches. */
    val dataDir: Path = Path("/var/mcp"),
    val launchers: Set<String> = setOf(Launchers.NPM, Launchers.PYPI),
    val npmRegistry: String? = null,
    val pypiIndex: String? = null,
    val maxProcesses: Int = 16,
    val maxInFlightPerProcess: Int = 16,
    val processMemoryBytes: Long = 512L * 1024 * 1024,
    val idleMillis: Long = 15 * 60_000L,
    val maxLifetimeMillis: Long = 24 * 3_600_000L,
    val startTimeoutMillis: Long = 45_000,
    /** A stdout line longer than this is dropped unparsed. */
    val maxLineBytes: Int = 4 * 1024 * 1024,
    val maxRequestBytes: Int = 2 * 1024 * 1024,
    val stderrBytes: Int = 64 * 1024,
    /** A write to stdin blocked this long means the process stopped reading: it is restarted. */
    val writeStallMillis: Long = 5_000,
    /** RLIMIT_NPROC counts every thread of the pod's user, so it bounds a fork bomb, not one process (docs/mcp.md). */
    val maxPids: Int = 1024,
    val maxOpenFiles: Int = 1024,
    /** The only PATH a process sees. */
    val path: String = "/usr/local/bin:/usr/bin:/bin",
    val npx: String = "npx",
    val uvx: String = "uvx",
    /** Null runs processes without prlimit (development machines without util-linux). */
    val prlimit: String? = "prlimit",
    /** On SIGTERM: answer what arrives for at least [drainMinMillis], and wait for running operations up to [drainMillis]. */
    val drainMillis: Long = 75_000,
    val drainMinMillis: Long = 5_000,
    val tickMillis: Long = 1_000,
    val memoryCheckMillis: Long = 5_000,
    val crashLimit: Int = 3,
    val crashWindowMillis: Long = 10 * 60_000L,
    val backoffMillis: Long = 10 * 60_000L,
    val nonJsonLimitPerMinute: Int = 100,
    val oversizeLimit: Int = 3,
    val oversizeWindowMillis: Long = 10 * 60_000L,
    val closeStdinWaitMillis: Long = 2_000,
    /** A retain sweep spares keys first seen this recently (an assignment made after the server read its list). */
    val retainGraceMillis: Long = 60_000,
    val terminateWaitMillis: Long = 5_000,
) {
    init {
        require(token.length >= MIN_TOKEN_LENGTH) { "TEAMCODE_MCP_TOKEN must hold at least $MIN_TOKEN_LENGTH characters." }
        require(port in 1..65_535) { "TEAMCODE_MCP_PORT must be a TCP port." }
        require(launchers.isNotEmpty() && launchers.all { it in Launchers.ALL }) { "TEAMCODE_MCP_LAUNCHERS takes npm and/or pypi." }
        require(maxProcesses in 1..256 && maxInFlightPerProcess in 1..256) { "Use 1 to 256 processes and in-flight requests per process." }
        require(processMemoryBytes >= 64L * 1024 * 1024) { "TEAMCODE_MCP_PROCESS_MEMORY_MIB must be at least 64." }
        require(maxLineBytes in 1_024..64 * 1024 * 1024 && maxRequestBytes in 1_024..64 * 1024 * 1024)
        require(npmRegistry == null || npmRegistry.startsWith("https://") || npmRegistry.startsWith("http://")) { "TEAMCODE_MCP_NPM_REGISTRY must be an HTTP(S) URL." }
        require(pypiIndex == null || pypiIndex.startsWith("https://") || pypiIndex.startsWith("http://")) { "TEAMCODE_MCP_PYPI_INDEX must be an HTTP(S) URL." }
    }

    override fun toString() = "HostConfig(port=$port, dataDir=$dataDir, launchers=$launchers, maxProcesses=$maxProcesses)"

    companion object {
        const val MIN_TOKEN_LENGTH = 32

        fun fromEnvironment(env: Map<String, String>): HostConfig {
            fun value(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun long(name: String) = value(name)?.let { it.toLongOrNull() ?: throw IllegalArgumentException("$name must be a number.") }
            val defaults = HostConfig(token = "x".repeat(MIN_TOKEN_LENGTH))
            return HostConfig(
                token = value("TEAMCODE_MCP_TOKEN") ?: throw IllegalArgumentException("TEAMCODE_MCP_TOKEN is required."),
                host = value("TEAMCODE_MCP_HOST") ?: defaults.host,
                port = long("TEAMCODE_MCP_PORT")?.toInt() ?: defaults.port,
                dataDir = value("TEAMCODE_MCP_DATA_DIR")?.let { Path(it) } ?: defaults.dataDir,
                launchers = value("TEAMCODE_MCP_LAUNCHERS")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: defaults.launchers,
                npmRegistry = value("TEAMCODE_MCP_NPM_REGISTRY"),
                pypiIndex = value("TEAMCODE_MCP_PYPI_INDEX"),
                maxProcesses = long("TEAMCODE_MCP_MAX_PROCESSES")?.toInt() ?: defaults.maxProcesses,
                maxInFlightPerProcess = long("TEAMCODE_MCP_MAX_IN_FLIGHT_PER_PROCESS")?.toInt() ?: defaults.maxInFlightPerProcess,
                processMemoryBytes = long("TEAMCODE_MCP_PROCESS_MEMORY_MIB")?.let { it * 1024 * 1024 } ?: defaults.processMemoryBytes,
                idleMillis = long("TEAMCODE_MCP_IDLE_MINUTES")?.let { it * 60_000 } ?: defaults.idleMillis,
                startTimeoutMillis = long("TEAMCODE_MCP_STDIO_START_TIMEOUT_MS") ?: defaults.startTimeoutMillis,
                path = value("TEAMCODE_MCP_PATH") ?: defaults.path,
                prlimit = when (val prlimit = value("TEAMCODE_MCP_PRLIMIT")) { null -> defaults.prlimit; "none" -> null; else -> prlimit },
                drainMillis = long("TEAMCODE_MCP_DRAIN_SECONDS")?.let { it * 1_000 } ?: defaults.drainMillis,
            )
        }
    }
}
