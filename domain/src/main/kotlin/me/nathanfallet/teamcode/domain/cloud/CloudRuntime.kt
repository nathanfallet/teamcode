package me.nathanfallet.teamcode.domain.cloud

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.domain.team.*

/**
 * Whether cloud sessions run on this server, and how their pods are built. Administrators edit it on the dashboard or
 * through `PUT /api/v1/cloud/runtime`; the database is the source of truth. Deployment variables only seed it once.
 *
 * @param runnerImage the image holding the `teamcode` Linux binary at [runnerBinary], copied into each session pod.
 * @param serverUrl the HTTPS origin session pods call back; null uses the server's public URL.
 * @param workspaceAccessMode `ReadWriteOncePod`, or `ReadWriteOnce` for storage drivers without it.
 */
data class CloudRuntime(
    val enabled: Boolean = false,
    val runnerImage: String? = null,
    val runnerBinary: String = DEFAULT_RUNNER_BINARY,
    val serverUrl: String? = null,
    val workspaceAccessMode: String = DEFAULT_ACCESS_MODE,
    /** When an administrator (or the initial seed) last saved it; null while nothing is stored. */
    val updatedAt: Long? = null,
) {
    companion object {
        const val DEFAULT_RUNNER_BINARY = "/usr/local/bin/teamcode"
        const val DEFAULT_ACCESS_MODE = "ReadWriteOncePod"
        val ACCESS_MODES = setOf("ReadWriteOncePod", "ReadWriteOnce")
    }
}

interface CloudRuntimeStore {
    suspend fun runtime(actor: Principal): CloudRuntime
    /**
     * Stores [runtime] when the stored one still carries [expectedUpdatedAt] (null: nothing stored yet); otherwise
     * `409 cloud_runtime_conflict`, so two administrators never silently overwrite each other.
     */
    suspend fun saveRuntime(actor: Principal, runtime: CloudRuntime, expectedUpdatedAt: Long?): CloudRuntime
    /** The stored runtime of the server's team, for the background workers; the defaults (disabled) without a row. */
    suspend fun current(): CloudRuntime
    /** Stores [initial] only when nothing was ever stored, and returns what is stored. */
    suspend fun seed(initial: CloudRuntime): CloudRuntime
}

/**
 * The cloud runtime, cached for request paths and workers. [refresh] rereads the database, so a change saved on one
 * replica reaches the others within one refresh interval, without a restart.
 */
class CloudRuntimeService(private val store: CloudRuntimeStore, val publicOrigin: String) {
    @Volatile private var cached = CloudRuntime()

    /** The last runtime read or saved by this replica. */
    val current: CloudRuntime get() = cached
    val enabled: Boolean get() = cached.enabled

    /** The origin pods call back: the stored one, or the server's public URL. */
    fun serverUrl(runtime: CloudRuntime = cached) = runtime.serverUrl ?: publicOrigin

    /** Never replaces a newer runtime this replica saved while the read was in flight. */
    suspend fun refresh(): CloudRuntime {
        val read = store.current()
        // A missing row (deleted or restored without it) is taken as is: off.
        synchronized(this) { if (read.updatedAt == null || read.updatedAt >= (cached.updatedAt ?: 0)) cached = read }
        return cached
    }

    suspend fun get(actor: Principal): CloudRuntime { actor.requireAdmin(); return store.runtime(actor) }

    /** [expectedUpdatedAt] is the `updatedAt` of the runtime the administrator edited (null when none was stored). */
    suspend fun save(actor: Principal, runtime: CloudRuntime, expectedUpdatedAt: Long?): CloudRuntime {
        actor.requireAdmin()
        val normalized = normalize(runtime)
        validate(normalized, publicOrigin)
        return store.saveRuntime(actor, normalized, expectedUpdatedAt).also { saved -> synchronized(this) { cached = saved } }
    }

    /**
     * Seeds the runtime from the former deployment variables. An invalid seed is stored disabled, with a reason, so the
     * server still starts and the administrator fixes it on the dashboard.
     */
    suspend fun seed(initial: CloudRuntime?, warn: (String) -> Unit = {}): CloudRuntime {
        val candidate = initial?.let(::normalize)
        val stored = if (candidate == null) store.current() else {
            val usable = try { validate(candidate, publicOrigin); candidate }
                catch (error: CancellationException) { throw error }
                catch (error: TeamFailure) { warn(error.message ?: "Invalid cloud runtime."); disabledSeed(candidate) }
            store.seed(usable)
        }
        synchronized(this) { cached = stored }
        return stored
    }

    /** Off, keeping only the fields that are valid on their own, so an invalid variable never prevents the start. */
    private fun disabledSeed(candidate: CloudRuntime): CloudRuntime {
        fun valid(runtime: CloudRuntime) = try { validate(runtime, publicOrigin); true } catch (_: TeamFailure) { false }
        val base = CloudRuntime()
        return base.copy(
            runnerImage = candidate.runnerImage.takeIf { valid(base.copy(runnerImage = it)) },
            runnerBinary = candidate.runnerBinary.takeIf { valid(base.copy(runnerBinary = it)) } ?: base.runnerBinary,
            serverUrl = candidate.serverUrl.takeIf { valid(base.copy(serverUrl = it)) },
            workspaceAccessMode = candidate.workspaceAccessMode.takeIf { valid(base.copy(workspaceAccessMode = it)) } ?: base.workspaceAccessMode,
        )
    }

    companion object {
        private val image = Regex("[A-Za-z0-9][A-Za-z0-9._/:@+-]{0,511}")
        private val binary = Regex("/[A-Za-z0-9._/+-]{1,255}")
        private val httpsOrigin = Regex("https://[^/?#@\\s]+/?")
        private val dottedQuad = Regex("(?:(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])")
        private val ipv6Literal = Regex("[0-9a-f:.]{2,45}")

        fun normalize(runtime: CloudRuntime) = runtime.copy(
            runnerImage = runtime.runnerImage?.trim()?.takeIf { it.isNotEmpty() },
            runnerBinary = runtime.runnerBinary.trim().ifEmpty { CloudRuntime.DEFAULT_RUNNER_BINARY },
            serverUrl = runtime.serverUrl?.trim()?.takeIf { it.isNotEmpty() },
            workspaceAccessMode = runtime.workspaceAccessMode.trim().ifEmpty { CloudRuntime.DEFAULT_ACCESS_MODE },
        )

        /** A disabled runtime may stay incomplete; enabling it needs a runner image and an HTTPS origin pods can reach. */
        fun validate(runtime: CloudRuntime, publicOrigin: String) {
            fun invalid(message: String): Nothing = throw TeamFailure("invalid_cloud_runtime", 400, message)
            runtime.runnerImage?.let { if (!image.matches(it) || ".." in it) invalid("Use an image reference such as registry/teamcode-runner@sha256:..., without spaces.") }
            if (!binary.matches(runtime.runnerBinary) || ".." in runtime.runnerBinary) invalid("Use an absolute path for the runner binary, without spaces or dot segments.")
            if (runtime.workspaceAccessMode !in CloudRuntime.ACCESS_MODES) invalid("Use ReadWriteOncePod or ReadWriteOnce.")
            runtime.serverUrl?.let { if (!usableOrigin(it)) invalid("Use the HTTPS origin session pods reach, not a loopback address.") }
            if (runtime.enabled && runtime.runnerImage == null) invalid("Set the runner image before enabling cloud sessions.")
            // Off, the runtime may stay incomplete, so an administrator can always save it; pods are built only while on.
            if (runtime.enabled && !usableOrigin(runtime.serverUrl ?: publicOrigin))
                invalid("Session pods need an HTTPS callback origin: set the server URL for pods, since the public URL is not HTTPS or is loopback.")
        }

        /** The runner refuses plain HTTP except on loopback, and a pod's loopback (or an unspecified address) is not the server. */
        fun usableOrigin(url: String): Boolean {
            if (url.length > 512 || !httpsOrigin.matches(url)) return false
            val authority = url.removePrefix("https://").trimEnd('/').substringAfterLast('@')
            val host = (if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')).lowercase().trimEnd('.')
            if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost")) return false
            // A name never ends with a label starting with an ASCII digit; such a host is an address spelling. Only a plain
            // ASCII dotted quad or IPv6 literal is accepted, and only those reach the parser, so no name is ever resolved
            // here; short, hexadecimal, octal (leading zero) or non-ASCII-digit spellings are refused.
            val ipv6 = ':' in host
            if (!ipv6 && host.substringAfterLast('.').firstOrNull()?.let { it in '0'..'9' } != true) return true
            if (ipv6 && !ipv6Literal.matches(host)) return false
            if (!ipv6 && !dottedQuad.matches(host)) return false
            val address = try { java.net.InetAddress.getByName(host) } catch (_: Exception) { return false }
            return !address.isLoopbackAddress && !address.isAnyLocalAddress
        }
    }
}
