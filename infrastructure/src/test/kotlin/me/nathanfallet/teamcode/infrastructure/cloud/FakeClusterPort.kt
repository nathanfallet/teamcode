package me.nathanfallet.teamcode.infrastructure.cloud

import java.util.UUID
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.ReconcileCloudSessionsUseCase.Companion.CLUSTER_LABEL

/**
 * In-memory cluster with the behaviors the lifecycle depends on: claims bind to a volume whose reclaim policy
 * comes from the storage class, a deleted claim releases its volume and the provisioner removes a `Delete`
 * volume on a later observation, and a graceful pod deletion completes on the next observation.
 */
class FakeClusterPort : ClusterPort {
    data class Claim(val namespace: String, val name: String, val storageClass: String, val volume: String, val labels: Map<String, String>)
    data class Pod(val namespace: String, val request: PodRequest, val phase: PodPhase, val terminating: Boolean, val reason: String?, val nodeName: String?)
    data class Volume(val observation: VolumeObservation, val releasedObservations: Int)

    val storageClasses = mutableMapOf("standard" to StorageClassObservation("standard", "Delete", "WaitForFirstConsumer"), "keep" to StorageClassObservation("keep", "Retain", null))
    var defaultStorageClass = "standard"
    val claims = mutableMapOf<String, Claim>()
    val pods = mutableMapOf<String, Pod>()
    val volumes = mutableMapOf<String, Volume>()
    var unreachable = false
    val calls = mutableListOf<String>()

    @Synchronized private fun <T> op(name: String, block: () -> T): T {
        if (unreachable) throw IllegalStateException("cluster unreachable")
        calls += name
        return block()
    }
    private fun key(namespace: String, name: String) = "$namespace/$name"

    override suspend fun storageClass(clusterId: String, name: String?) = op("storageClass") { storageClasses[name ?: defaultStorageClass] }
    override suspend fun ensureClaim(clusterId: String, request: WorkspaceRequest) = op("ensureClaim") {
        val key = key(request.namespace, request.claimName)
        if (key !in claims) {
            val sc = storageClasses.getValue(request.storageClass ?: defaultStorageClass)
            val volume = "pv-" + UUID.randomUUID().toString()
            volumes[volume] = Volume(VolumeObservation(volume, "Bound", sc.reclaimPolicy, "cinder-" + UUID.randomUUID(), request.namespace, request.claimName, true), 0)
            claims[key] = Claim(request.namespace, request.claimName, sc.name, volume, request.labels)
        }
    }
    override suspend fun claim(clusterId: String, namespace: String, name: String) = op("claim") {
        claims[key(namespace, name)]?.let { ClaimObservation(ClaimPhase.BOUND, it.volume, it.storageClass) }
    }
    override suspend fun deleteClaim(clusterId: String, namespace: String, name: String) = op("deleteClaim") {
        claims.remove(key(namespace, name))?.let { claim ->
            volumes[claim.volume]?.let { volumes[claim.volume] = it.copy(observation = it.observation.copy(phase = "Released")) }
        }
        Unit
    }
    /** The provisioner deletes a released `Delete` volume (and its provider volume) one observation later. */
    override suspend fun volume(clusterId: String, name: String) = op("volume") {
        val volume = volumes[name] ?: return@op null
        if (volume.observation.phase == "Released" && volume.observation.reclaimPolicy == "Delete") {
            if (volume.releasedObservations >= 1) { volumes.remove(name); return@op null }
            volumes[name] = volume.copy(releasedObservations = volume.releasedObservations + 1)
        }
        volume.observation
    }
    override suspend fun orphanedVolumes(clusterId: String) = op("orphanedVolumes") {
        volumes.values.map { it.observation }.filter { it.managed && (it.phase == "Released" || it.phase == "Failed") }
    }
    override suspend fun deleteVolume(clusterId: String, name: String) = op("deleteVolume") { volumes.remove(name); Unit }
    override suspend fun createPod(clusterId: String, request: PodRequest) = op("createPod") {
        pods.getOrPut(key(request.namespace, request.name)) { Pod(request.namespace, request, PodPhase.PENDING, false, null, null) }
        Unit
    }
    override suspend fun pod(clusterId: String, namespace: String, name: String) = op("pod") {
        val pod = pods[key(namespace, name)] ?: return@op null
        if (pod.terminating) { pods.remove(key(namespace, name)); return@op null }
        observation(pod)
    }
    override suspend fun deletePod(clusterId: String, namespace: String, name: String, force: Boolean) = op("deletePod") {
        if (force) pods.remove(key(namespace, name)) else pods[key(namespace, name)]?.let { pods[key(namespace, name)] = it.copy(terminating = true) }
        Unit
    }
    override suspend fun managed(clusterId: String, namespace: String) = op("managed") {
        ManagedObjects(
            pods.values.filter { it.namespace == namespace && it.request.labels[CLUSTER_LABEL] == clusterId }.map(::observation),
            claims.values.filter { it.namespace == namespace && it.labels[CLUSTER_LABEL] == clusterId }.map { it.name },
        )
    }
    override suspend fun reviewToken(clusterId: String, token: String, audience: String): TokenIdentity? = null

    private fun observation(pod: Pod) = PodObservation(pod.request.name, pod.phase, pod.request.labels[ReconcileCloudSessionsUseCase.EPOCH_LABEL]?.toIntOrNull(),
        pod.nodeName, if (pod.phase == PodPhase.RUNNING) "registry.example/toolchain@sha256:" + "a".repeat(64) else null, pod.terminating, pod.reason)

    @Synchronized fun start(name: String) {
        val entry = pods.entries.single { it.value.request.name == name }
        entry.setValue(entry.value.copy(phase = PodPhase.RUNNING, nodeName = "node-1"))
    }
    @Synchronized fun fail(name: String, reason: String) {
        val entry = pods.entries.single { it.value.request.name == name }
        entry.setValue(entry.value.copy(reason = reason))
    }
    @Synchronized fun podNames() = pods.values.map { it.request.name }.toSet()
    /** Objects no reconciler accounts for, as a crashed replica could leave behind. */
    @Synchronized fun plantOrphans(clusterId: String, namespace: String) {
        val labels = mapOf(ReconcileCloudSessionsUseCase.MANAGED_LABEL to "teamcode", CLUSTER_LABEL to clusterId)
        pods[key(namespace, "tc-orphan-e1")] = Pod(namespace, PodRequest(namespace, "tc-orphan-e1", "image", "tc-ws-orphan", labels, CloudResources(100, 128), emptyList()), PodPhase.RUNNING, false, null, "node-1")
        claims[key(namespace, "tc-ws-orphan")] = Claim(namespace, "tc-ws-orphan", "standard", "pv-orphan", labels)
        volumes["pv-orphan"] = Volume(VolumeObservation("pv-orphan", "Bound", "Delete", "cinder-orphan", namespace, "tc-ws-orphan", true), 0)
        volumes["pv-retained"] = Volume(VolumeObservation("pv-retained", "Released", "Retain", "cinder-retained", namespace, "tc-ws-gone", true), 0)
    }
}
