package me.nathanfallet.teamcode.cli.daemon

import me.nathanfallet.teamcode.cli.platform.environment
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Whether this process runs in a cloud session's pod, in the runner's container or in the agent's: one pod runs one
 * session, so it never becomes a host device (the server refuses a pod's credential anyway, `cloud_session_credential`).
 * The runner's container carries the session's coordinates; both containers carry the sockets the pod shares between
 * them, which also exist on disk for an agent whose environment was trimmed.
 */
internal fun insideCloudRunner(
    env: (String) -> String? = ::environment,
    exists: (String) -> Boolean = { FileSystem.SYSTEM.metadataOrNull(it.toPath()) != null },
): Boolean = CLOUD_RUNNER_VARIABLES.any { !env(it).isNullOrBlank() } || CLOUD_RUNNER_PATHS.any(exists)

private val CLOUD_RUNNER_VARIABLES = listOf("TEAMCODE_CLOUD_SESSION", "TEAMCODE_CLOUD_TOKEN_FILE", "TEAMCODE_AGENT_SOCKET", "TEAMCODE_CREDENTIAL_SOCKET")
private val CLOUD_RUNNER_PATHS = listOf("/run/teamcode/agent.sock", "/run/teamcode/credential.sock")

internal const val CLOUD_RUNNER_REFUSAL = "This is a cloud session's runner: one pod runs one session, so it never hosts sessions for a device. Nothing was installed or started."
