package me.nathanfallet.teamcode.domain.cloud

/** The cluster refused a read (HTTP 401 or 403): the service account lacks an optional right. */
class ClusterAccessDeniedException : Exception("The cluster's service account may not read this.")

/**
 * Optional reads that offer choices in the profile form. They only help an administrator fill the form: publishing
 * a profile keeps its own checks. No implementation reads or returns the contents of a secret.
 * Each call throws [ClusterAccessDeniedException] when the service account lacks the right, and any other exception
 * when the cluster cannot be reached.
 */
interface ClusterDiscoveryPort {
    suspend fun storageClasses(clusterId: String): List<StorageClassChoice>
    /** Whether [namespace] exists; needs `get` on that namespace. */
    suspend fun namespaceExists(clusterId: String, namespace: String): Boolean
    /** Names of the `kubernetes.io/dockerconfigjson` secrets in [namespace], read as metadata only. Needs `list` on `secrets`. */
    suspend fun imagePullSecrets(clusterId: String, namespace: String): List<String>
}

data class StorageClassChoice(val name: String, val reclaimPolicy: String, val isDefault: Boolean, val provisioner: String? = null) {
    /** A workspace on this class leaves its provider volume behind when TeamCode deletes the claim: publishing refuses it. */
    val keepsVolumes get() = reclaimPolicy != "Delete"
}

/** What a list read from the cluster gave: the items, or why there are none to choose from. */
sealed interface ClusterListing<out T> {
    data class Listed<T>(val items: List<T>) : ClusterListing<T>
    /** The service account lacks the (optional) right: the form asks for the name instead, without an error. */
    data object Forbidden : ClusterListing<Nothing>
    /** The cluster could not be reached or answered with an error. */
    data object Unavailable : ClusterListing<Nothing>
}

enum class NamespaceStatus(val wire: String) { FOUND("found"), MISSING("missing"), UNKNOWN("unknown") }
data class NamespaceChoice(val name: String, val status: NamespaceStatus)

/**
 * The choices for a profile on [cluster]: its allowed namespaces (with whether each exists, when readable), and in
 * [namespace] the storage classes and image pull secrets the cluster returns.
 */
data class ClusterChoices(
    val cluster: CloudCluster,
    val namespace: String,
    val namespaces: List<NamespaceChoice>,
    val storageClasses: ClusterListing<StorageClassChoice>,
    val imagePullSecrets: ClusterListing<String>,
)
