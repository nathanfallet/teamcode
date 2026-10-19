package me.nathanfallet.teamcode.domain.cloud

/** Result of one garbage-collection pass over a cluster; counts only, no object names leave the domain. */
data class GarbageReport(val deletedPods: Int, val deletedClaims: Int, val orphanedVolumes: Int)

/**
 * Deletes TeamCode-labelled pods and claims that no live session accounts for, and counts provider volumes left
 * behind (for example by a `Retain` reclaim policy) so an administrator is alerted. Cluster objects are listed
 * before the live set is read: an object is created only after its row commits, so a fresh object is never
 * mistaken for an orphan.
 */
class CollectCloudGarbageUseCase(private val store: CloudSessionStore, private val cluster: ClusterPort) {
    suspend operator fun invoke(cloudCluster: CloudCluster): GarbageReport {
        // Namespaces removed from the cluster's allowed list still hold objects until their sessions are cleaned up.
        val namespaces = cloudCluster.allowedNamespaces.toSet() + store.namespaces(cloudCluster.id)
        val observed = namespaces.associateWith { cluster.managed(cloudCluster.id, it) }
        val volumes = cluster.orphanedVolumes(cloudCluster.id)
        val live = store.liveObjects(cloudCluster.id)
        var pods = 0
        var claims = 0
        for ((namespace, objects) in observed) {
            for (pod in objects.pods) if (pod.name !in live.pods) { cluster.deletePod(cloudCluster.id, namespace, pod.name, force = true); pods++ }
            for (claim in objects.claims) if (claim !in live.claims) { cluster.deleteClaim(cloudCluster.id, namespace, claim); claims++ }
        }
        // A Delete-policy volume whose claim is gone is being removed by its provisioner; a Retain or failed one is not.
        // Only volumes whose claim lived in one of this cluster record's namespaces count as its orphans.
        val orphaned = volumes.count { it.claimNamespace in namespaces && it.claimName !in live.claims && (it.reclaimPolicy != "Delete" || it.phase == "Failed") }
        store.recordGarbageCollection(cloudCluster.id, orphaned)
        return GarbageReport(pods, claims, orphaned)
    }
}
