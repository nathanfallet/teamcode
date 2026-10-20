package me.nathanfallet.teamcode.domain.cloud

import kotlinx.coroutines.CancellationException
import me.nathanfallet.teamcode.domain.team.*

/** Validation for clusters, profiles and settings. Persistence revalidates the administrator in its transaction. */
class CloudAdministrationService(private val store: CloudAdminStore, private val cluster: ClusterPort) {
    suspend fun clusters(actor: Principal) = admin(actor).let { store.clusters(actor) }
    suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput): CloudCluster {
        admin(actor)
        validate(input, creating = id == null)
        return store.saveCluster(actor, id, expectedVersion, input)
    }
    suspend fun profiles(actor: Principal): List<CloudProfile> {
        actor.requireReady(); actor.requireInteractive()
        return store.profiles(actor)
    }
    /** Refuses a storage class that would keep provider volumes after a workspace is deleted. */
    suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>): CloudProfile {
        admin(actor)
        validate(name, spec, assignedMemberIds)
        val target = store.clusters(actor).singleOrNull { it.id == spec.clusterId } ?: throw TeamFailure("cluster_not_found", 404, "Cluster not found.")
        if (spec.namespace !in target.allowedNamespaces) throw TeamFailure("namespace_not_allowed", 422, "The namespace is not allowed on this cluster.")
        val storageClass = try { cluster.storageClass(target.id, spec.storageClass ?: target.defaultStorageClass) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { throw TeamFailure("cluster_unreachable", 502, "The cluster could not be reached to verify the storage class.") }
            ?: throw TeamFailure("storage_class_missing", 422, "The workspace storage class does not exist on the cluster.")
        if (storageClass.reclaimPolicy != "Delete")
            throw TeamFailure("storage_class_retains_volumes", 422, "Storage class ${storageClass.name} has reclaimPolicy ${storageClass.reclaimPolicy}; workspaces require reclaimPolicy Delete so deleted sessions leave no provider volume.")
        return store.saveProfile(actor, id, expectedVersion, name, spec, assignedMemberIds)
    }
    suspend fun settings(actor: Principal) = admin(actor).let { store.settings(actor) }
    suspend fun saveSettings(actor: Principal, settings: CloudSettings): CloudSettings {
        admin(actor)
        validate(settings)
        return store.saveSettings(actor, settings)
    }

    private fun admin(actor: Principal) = actor.requireAdmin()

    companion object {
        private val dnsLabel = Regex("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")
        private val dnsSubdomain = Regex("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?")
        fun validate(settings: CloudSettings) {
            if ((settings.pausedRetentionDays != null && settings.pausedRetentionDays !in 1..3650) || settings.failedWorkspaceRetentionDays !in 0..365 ||
                settings.maxRunningSessionsPerMember !in 1..100 || settings.maxRetainedSessionsPerMember !in 1..1000 ||
                settings.maxRetainedSessionsPerMember < settings.maxRunningSessionsPerMember)
                throw TeamFailure("invalid_cloud_settings", 400, "Use a paused retention of 1–3650 days (or indefinite), a failed-workspace retention of 0–365 days and running/retained caps of 1–100/1–1000 with retained ≥ running.")
        }
        fun validate(input: CloudClusterInput, creating: Boolean) {
            val url = input.apiUrl
            if (input.name.isBlank() || input.name.length > 120 || input.name.any(Char::isISOControl) ||
                !url.startsWith("https://") || url.length > 512 || url.any { it.isWhitespace() || it.isISOControl() } ||
                input.allowedNamespaces.isEmpty() || input.allowedNamespaces.size > 32 || input.allowedNamespaces.any { !dnsLabel.matches(it) } ||
                input.maxRunningPods !in 1..1000 || input.defaultStorageClass?.let { !dnsSubdomain.matches(it) } == true ||
                (creating && (input.serviceAccountToken.isNullOrBlank() || input.caCertificate.isNullOrBlank())) ||
                (input.serviceAccountToken != null && (input.serviceAccountToken.length !in 16..16_384 || input.serviceAccountToken.any { it.isWhitespace() })) ||
                (input.caCertificate != null && (input.caCertificate.length > 65_536 || !input.caCertificate.contains("-----BEGIN CERTIFICATE-----"))))
                throw TeamFailure("invalid_cluster", 400, "Provide a name, an https API URL, a PEM CA bundle, a service-account token, 1–32 namespaces and a pod cap of 1–1000.")
            if (!allowedApiHost(url)) throw TeamFailure("cluster_host_not_allowed", 400, "The API URL must not point at a link-local or cloud metadata address.")
        }

        /**
         * The API URL is trusted administrator input: loopback (a local `kind` cluster) and private addresses (a
         * private control plane) are allowed. Link-local and unspecified addresses and the cloud metadata host
         * names are refused, so the server's own instance credentials are never a cluster target.
         */
        fun allowedApiHost(url: String): Boolean {
            val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            val host = (if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')).lowercase().trimEnd('.')
            if (host.isEmpty() || host in setOf("metadata", "metadata.google.internal", "metadata.goog", "instance-data")) return false
            // Any numeric spelling of an address is decoded before it is judged, so decimal, hex, octal and short forms
            // of a link-local or unspecified IPv4 address are refused like the dotted form.
            literalIpv4(host)?.let { return allowedIpv4(it) }
            if (':' in host) return literalIpv6(host)?.let(::allowedIpv6) ?: false
            return true
        }

        /** The address a cluster API host resolved to: the same rules as [allowedApiHost] applied to the connection itself. */
        fun allowedResolvedAddress(address: ByteArray): Boolean = when (address.size) {
            4 -> allowedIpv4(IntArray(4) { address[it].toInt() and 0xff })
            16 -> allowedIpv6(IntArray(16) { address[it].toInt() and 0xff })
            else -> false
        }

        /** The names of a DNS host are checked again against their resolved addresses by the cluster adapter when it connects. */
        private fun allowedIpv4(octets: IntArray) = !(octets[0] == 0 || (octets[0] == 169 && octets[1] == 254))

        private fun allowedIpv6(bytes: IntArray): Boolean {
            if (bytes.all { it == 0 }) return false
            val mapped = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
            val compatible = bytes.take(12).all { it == 0 }
            if (mapped || compatible) return allowedIpv4(intArrayOf(bytes[12], bytes[13], bytes[14], bytes[15]))
            if (bytes[0] == 0xfe && (bytes[1] and 0xc0) == 0x80) return false // fe80::/10 link-local
            if (bytes[0] == 0xfd && bytes[1] == 0x00 && bytes[2] == 0x0e && bytes[3] == 0xc2) return false // fd00:ec2::/32 cloud metadata
            return true
        }

        /** inet_aton rules: one to four parts, decimal, `0x` hexadecimal or leading-zero octal; the last part fills the remaining bytes. */
        private fun literalIpv4(host: String): IntArray? {
            val parts = host.split('.')
            if (parts.size !in 1..4 || parts.any { it.isEmpty() }) return null
            val values = parts.map { part ->
                val parsed = when {
                    part.startsWith("0x") -> part.drop(2).takeIf { it.isNotEmpty() && it.all { c -> c in "0123456789abcdef" } }?.toLongOrNull(16)
                    part.length > 1 && part.startsWith("0") -> part.drop(1).takeIf { it.all { c -> c in '0'..'7' } }?.toLongOrNull(8)
                    else -> part.takeIf { it.all(Char::isDigit) }?.toLongOrNull()
                }
                parsed ?: return null
            }
            val last = values.last()
            val lastBytes = 5 - parts.size
            if (values.dropLast(1).any { it !in 0..255 } || last !in 0 until (1L shl (8 * lastBytes))) return null
            var address = 0L
            values.dropLast(1).forEach { address = (address shl 8) or it }
            address = (address shl (8 * lastBytes)) or last
            return IntArray(4) { ((address shr (8 * (3 - it))) and 0xff).toInt() }
        }

        private fun literalIpv6(host: String): IntArray? {
            val tail = host.substringAfterLast(':')
            val text = if ('.' in tail) {
                val v4 = literalIpv4(tail) ?: return null
                host.substringBeforeLast(':') + ":" + "%x:%x".format(v4[0] * 256 + v4[1], v4[2] * 256 + v4[3])
            } else host
            if (text.count { it == ':' } > 7 || ":::" in text || text.split("::").size > 2) return null
            fun groups(s: String): List<Int>? = if (s.isEmpty()) emptyList() else s.split(':').map { g -> g.takeIf { it.length in 1..4 }?.toIntOrNull(16) ?: return null }
            val halves = text.split("::")
            val head = groups(halves[0]) ?: return null
            val rest = if (halves.size == 2) groups(halves[1]) ?: return null else emptyList()
            val all = if (halves.size == 2) {
                if (head.size + rest.size > 7) return null
                head + List(8 - head.size - rest.size) { 0 } + rest
            } else head
            if (all.size != 8) return null
            return IntArray(16) { (all[it / 2] shr (if (it % 2 == 0) 8 else 0)) and 0xff }
        }
        fun validate(name: String, spec: CloudProfileSpec, assigned: Set<String>) {
            val image = Regex("[^\\s]{1,512}")
            if (name.isBlank() || name.length > 120 || name.any(Char::isISOControl) || !dnsLabel.matches(spec.namespace) || !image.matches(spec.image) ||
                spec.allowedImageOverrides.size > 32 || spec.allowedImageOverrides.any { !image.matches(it) } ||
                spec.imagePullSecrets.size > 8 || spec.imagePullSecrets.any { !dnsSubdomain.matches(it) } ||
                spec.resources.cpuMillis !in 100..64_000 || spec.resources.memoryMebibytes !in 128..262_144 ||
                spec.workspaceSizeGibibytes !in 1..2048 || spec.storageClass?.let { !dnsSubdomain.matches(it) } == true ||
                spec.agentKind !in setOf("claude", "codex") || spec.agentVersion?.let { !Regex("[0-9A-Za-z.+-]{1,64}").matches(it) } == true ||
                spec.minIdleTimeoutMinutes !in 5..1440 || spec.maxIdleTimeoutMinutes !in spec.minIdleTimeoutMinutes..1440 ||
                spec.idleTimeoutMinutes !in spec.minIdleTimeoutMinutes..spec.maxIdleTimeoutMinutes || assigned.size > 1000)
                throw TeamFailure("invalid_profile", 400, "Check the profile namespace, image, resources (100–64000 mCPU, 128–262144 MiB), workspace size (1–2048 GiB), agent and idle bounds (5–1440 minutes).")
        }
    }
}
