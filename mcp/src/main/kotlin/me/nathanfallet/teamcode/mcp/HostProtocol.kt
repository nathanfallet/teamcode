package me.nathanfallet.teamcode.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Host protocol v1: the internal HTTP contract between the TeamCode server (its gateway) and this host. Every request
 * carries `TeamCode-Host-Protocol: 1` and `Authorization: Bearer <TEAMCODE_MCP_TOKEN>`. It is internal and released
 * with the server at the same version; docs/mcp.md describes it.
 */
object HostProtocol {
    const val HEADER = "TeamCode-Host-Protocol"
    const val VERSION = "1"
    const val OPS = "/v1/ops"
    const val WARM = "/v1/warm"
    const val RETAIN = "/v1/retain"
    const val STOP = "/v1/stop"
    const val STATUS = "/v1/status"
    /** A process key: the server's SHA-256 (hex) of team, variant and version, plus member and assignment time for PER_MEMBER. */
    val KEY = Regex("[0-9a-f]{64}")
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
}

/** Error codes of an operation or a host refusal. */
object HostErrors {
    const val START_FAILED = "start_failed"
    const val CRASHED = "crashed"
    const val TIMEOUT = "timeout"
    const val TOO_LARGE = "too_large"
    const val BUSY = "busy"
    const val PROTOCOL = "protocol"
    const val BACKOFF = "backoff"
    const val CAPACITY = "capacity"
    /** The upstream answered the tools/call with a JSON-RPC error (an unknown tool, invalid arguments). */
    const val TOOL_ERROR = "tool_error"
    /** The definition sent by the server breaks a host rule (launcher, pinning, reserved names). */
    const val INVALID_SPEC = "invalid_spec"
    const val BAD_REQUEST = "bad_request"
    const val UNAUTHORIZED = "unauthorized"
    const val UNSUPPORTED_PROTOCOL = "unsupported_protocol"
    const val SHUTTING_DOWN = "shutting_down"
}

/**
 * What to run for one key. Launch-time only: the host never stores it on disk. [env] holds the variant's non-secret
 * variables, [secrets] its secret ones (both become the process environment, and `${NAME}` in [args] is replaced by the
 * secret NAME); every secret value is scrubbed from what the host returns or keeps. [declaredAt] (epoch milliseconds)
 * freezes dependency resolution: npm `before`, uv `--exclude-newer`.
 */
@Serializable
data class ProcessSpec(
    val launcher: String,
    @SerialName("package") val pkg: String,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val secrets: Map<String, String> = emptyMap(),
    val declaredAt: Long,
)

@Serializable
data class OpsRequest(
    val key: String,
    val spec: ProcessSpec,
    /** `list` or `call`. */
    val op: String,
    val tool: String? = null,
    val arguments: JsonObject? = null,
    val deadlineMs: Long,
    val maxResponseBytes: Int,
)

@Serializable
data class HostError(val code: String, val message: String)

/** [result] is `{"tools":[…]}` for `list` and the upstream `CallToolResult` for `call`; exactly one of it and [error] is set. */
@Serializable
data class OpsResponse(val result: JsonObject? = null, val error: HostError? = null, val cold: Boolean, val state: String)

@Serializable
data class WarmRequest(val key: String, val spec: ProcessSpec)

@Serializable
data class RetainRequest(val keys: List<String>)

@Serializable
data class StopRequest(val key: String)

@Serializable
data class KeyState(val key: String, val state: String)

@Serializable
data class RetainResponse(val stopped: Int)

@Serializable
data class StopResponse(val stopped: Boolean)

@Serializable
data class ProcessStatus(
    val key: String,
    val state: String,
    val launcher: String,
    @SerialName("package") val pkg: String,
    val pid: Long? = null,
    val startedAt: Long? = null,
    val lastUsedAt: Long? = null,
    val inFlight: Int = 0,
    val starts: Int = 0,
    val failures: Int = 0,
    val lastExitCode: Int? = null,
    val lastFailure: String? = null,
    val coldStartMillis: Long? = null,
    val rssBytes: Long? = null,
    val backoffUntil: Long? = null,
    /** The last 64 KiB of the process's stderr, secrets scrubbed. For administrators only. */
    val stderr: String = "",
)

@Serializable
data class HostStatus(
    val version: String,
    val protocol: String,
    val launchers: List<String>,
    val maxProcesses: Int,
    val draining: Boolean,
    val processes: List<ProcessStatus>,
)

/** States of a key, as the dashboard shows them. */
object ProcessStates {
    const val IDLE = "idle"
    const val STARTING = "starting"
    const val READY = "ready"
    const val FAILED = "failed"
    const val BACKOFF = "backoff"
}

/** A refused or failed operation, answered to the server as [HostError]. Messages never contain a secret. */
class HostFailure(val code: String, message: String) : Exception(message)
