package me.nathanfallet.teamcode.domain.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import me.nathanfallet.teamcode.domain.team.*

/** Validation for clusters, profiles and settings. Persistence revalidates the administrator in its transaction. */
class CloudAdministrationService(
    private val store: CloudAdminStore,
    private val cluster: ClusterPort,
    val kubeconfigImports: KubeconfigImportService? = null,
    /** The reads that fill the profile form's lists; without it the form asks for every name. */
    private val discovery: ClusterDiscoveryPort? = cluster as? ClusterDiscoveryPort,
) {
    suspend fun clusters(actor: Principal) = admin(actor).let { store.clusters(actor) }
    suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput): CloudCluster {
        admin(actor)
        validate(input, creating = id == null)
        return store.saveCluster(actor, id, expectedVersion, input)
    }
    /**
     * The profiles the caller can use (every profile for an administrator); also readable with a `cloud:read` key, as
     * its owner. [memberId] names another member, for a cloud administrator only (as creating on their behalf): the
     * profiles that member can create sessions from.
     */
    suspend fun profiles(actor: Principal, memberId: String? = null): List<CloudProfile> {
        actor.requireCloudRead()
        if (memberId == null || memberId == actor.member.id) return store.profiles(actor)
        actor.requireCloudAdministrator()
        val target = store.member(actor, memberId)
        if (target == null || target.disabled || target.passwordChangeRequired)
            throw TeamFailure("invalid_effective_member", 422, "The member must be an enabled team member who completed password setup.")
        return store.profiles(actor).filter { target.role.isAdministrator || target.id in it.assignedMemberIds }
    }

    /** The names of the clusters [profiles] run on, by id, shown next to them; no other cluster, no other field. */
    suspend fun clusterNames(actor: Principal, profiles: List<CloudProfile>): Map<String, String> {
        actor.requireCloudRead()
        val ids = profiles.map { it.spec.clusterId }.toSet()
        if (ids.isEmpty()) return emptyMap()
        return store.clusters(actor).filter { it.id in ids }.associate { it.id to it.name }
    }
    /** Refuses a storage class that would keep provider volumes after a workspace is deleted. */
    suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>): CloudProfile {
        verify(actor, name, spec, assignedMemberIds)
        return store.saveProfile(actor, id, expectedVersion, name, spec, assignedMemberIds)
    }

    /**
     * Runs the validation [saveProfile] runs before writing, without publishing (the store's own checks, such as a name
     * already used, are only met at publication): a draft on the dashboard is checked before it becomes a
     * version. A draft of an existing profile must still be based on its current version.
     */
    suspend fun checkProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) {
        verify(actor, name, spec, assignedMemberIds)
        if (id == null) return
        val current = store.profiles(actor).singleOrNull { it.id == id } ?: throw TeamFailure("not_found", 404, "Profile not found.")
        if (current.version != expectedVersion)
            throw TeamFailure("profile_conflict", 409, "Version ${current.version} was published since this draft started from version $expectedVersion. Reload the profile.")
    }

    /**
     * What the profile form offers on [clusterId]: the allowed namespaces, then the storage classes and the image pull
     * secrets of [namespace] (otherwise the first allowed one that is not known to be missing). A read the service account may not make,
     * or a cluster that cannot be reached, gives no list rather than an error: the form then asks for the name.
     */
    suspend fun choices(actor: Principal, clusterId: String, namespace: String?): ClusterChoices {
        admin(actor)
        val target = store.clusters(actor).singleOrNull { it.id == clusterId } ?: throw TeamFailure("cluster_not_found", 404, "Cluster not found.")
        val reads = discovery ?: return ClusterChoices(target, namespace?.takeIf { it in target.allowedNamespaces } ?: target.allowedNamespaces.first(),
            target.allowedNamespaces.map { NamespaceChoice(it, NamespaceStatus.UNKNOWN) }, ClusterListing.Unavailable, ClusterListing.Unavailable)
        return coroutineScope {
            // Up to 32 namespaces: a few reads at a time, so one page load never fans out to the whole list at once.
            val permits = Semaphore(PARALLEL_READS)
            val classes = async { listing { reads.storageClasses(target.id).sortedWith(compareBy({ !it.isDefault }, { it.name })) } }
            val namespaces = target.allowedNamespaces.map { name ->
                async { permits.withPermit { NamespaceChoice(name, when (val found = listing { listOf(reads.namespaceExists(target.id, name)) }) {
                    is ClusterListing.Listed -> if (found.items.single()) NamespaceStatus.FOUND else NamespaceStatus.MISSING
                    else -> NamespaceStatus.UNKNOWN
                }) } }
            }.awaitAll()
            val chosen = namespace?.takeIf { it in target.allowedNamespaces }
                ?: namespaces.firstOrNull { it.status != NamespaceStatus.MISSING }?.name ?: target.allowedNamespaces.first()
            val secrets = listing { reads.imagePullSecrets(target.id, chosen).distinct().sorted().take(MAX_LISTED) }
            ClusterChoices(target, chosen, namespaces, classes.await(), secrets)
        }
    }

    private suspend fun <T> listing(read: suspend () -> List<T>): ClusterListing<T> = try { ClusterListing.Listed(read()) }
        catch (error: CancellationException) { throw error }
        catch (_: ClusterAccessDeniedException) { ClusterListing.Forbidden }
        catch (_: Exception) { ClusterListing.Unavailable }

    private suspend fun verify(actor: Principal, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>) {
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
    }
    suspend fun settings(actor: Principal) = admin(actor).let { store.settings(actor) }
    suspend fun saveSettings(actor: Principal, settings: CloudSettings): CloudSettings {
        admin(actor)
        validate(settings)
        return store.saveSettings(actor, settings)
    }

    private fun admin(actor: Principal) = actor.requireAdmin()

    companion object {
        /** Image pull secrets offered at most, the first by name; another one can still be typed in. */
        const val MAX_LISTED = 200
        private const val PARALLEL_READS = 4
        private val dnsLabel = Regex("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")
        private val dnsSubdomain = Regex("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?")
        fun validate(settings: CloudSettings) {
            if ((settings.pausedRetentionDays != null && settings.pausedRetentionDays !in 1..3650) || settings.failedWorkspaceRetentionDays !in 0..365 ||
                settings.maxRunningSessionsPerMember !in 1..100 || settings.maxRetainedSessionsPerMember !in 1..1000 ||
                settings.maxRetainedSessionsPerMember < settings.maxRunningSessionsPerMember ||
                (settings.transcriptRetentionDays != null && settings.transcriptRetentionDays !in 1..3650) || settings.transferOfferDays !in 1..30 ||
                settings.permissionDeadlineMinutes !in 0..CloudConversationRules.MAX_PERMISSION_DEADLINE_MINUTES ||
                settings.remoteIdleTimeoutMinutes !in 0..CloudIdleRules.MAX_TIMEOUT_MINUTES ||
                settings.fileMaxMebibytes !in 1..CloudFileRules.MAX_MEBIBYTES || settings.messageFileMebibytes !in 1..CloudFileRules.MAX_MEBIBYTES)
                throw TeamFailure("invalid_cloud_settings", 400, "Use a paused retention of 1–3650 days (or indefinite), a transcript retention of 1–3650 days (or indefinite), a failed-workspace retention of 0–365 days, running/retained caps of 1–100/1–1000 with retained ≥ running, a transfer offer validity of 1–30 days, a permission request deadline of 1–${CloudConversationRules.MAX_PERMISSION_DEADLINE_MINUTES} minutes (or 0, no deadline), a remote session idle pause of 1–${CloudIdleRules.MAX_TIMEOUT_MINUTES} minutes (or 0, never), and file limits of 1–${CloudFileRules.MAX_MEBIBYTES} MiB per file and per message.")
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
            // Deprecated IPv4-compatible form (::a.b.c.d); the loopback ::1 is not one.
            val compatible = bytes.take(12).all { it == 0 } && !(bytes[12] == 0 && bytes[13] == 0 && bytes[14] == 0 && bytes[15] == 1)
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
                spec.minIdleTimeoutMinutes !in 5..CloudIdleRules.MAX_TIMEOUT_MINUTES || spec.maxIdleTimeoutMinutes !in spec.minIdleTimeoutMinutes..CloudIdleRules.MAX_TIMEOUT_MINUTES ||
                spec.idleTimeoutMinutes !in spec.minIdleTimeoutMinutes..spec.maxIdleTimeoutMinutes || assigned.size > 1000)
                throw TeamFailure("invalid_profile", 400, "Check the profile namespace, image, resources (100–64000 mCPU, 128–262144 MiB), workspace size (1–2048 GiB), agent and idle bounds (5–1440 minutes).")
            CloudEnvironmentRules.validate(spec.environment)
        }
    }
}
