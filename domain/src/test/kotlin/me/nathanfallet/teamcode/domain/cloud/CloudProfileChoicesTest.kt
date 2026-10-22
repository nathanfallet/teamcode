package me.nathanfallet.teamcode.domain.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import me.nathanfallet.teamcode.domain.team.*

/** The profile form's choices read from a cluster, and the dry-run check of a draft. */
class CloudProfileChoicesTest {
    private val admin = Principal(Member("admin-id", "admin", Role.ADMIN, false, false), "session-1", "team")
    private val member = Principal(Member("member-id", "member", Role.MEMBER, false, false), "session-2", "team")
    private val lab = CloudCluster("cluster-id", "team", "lab", "https://k8s.example.test", listOf("teamcode", "sandbox", "gone"), 10, null, 1, 0, null, 1, 1)

    private class Store(val clusters: List<CloudCluster>) : CloudAdminStore {
        val profiles = mutableListOf<CloudProfile>()
        var saves = 0
        override suspend fun clusters(actor: Principal) = clusters
        override suspend fun saveCluster(actor: Principal, id: String?, expectedVersion: Int?, input: CloudClusterInput) = error("unused")
        override suspend fun profiles(actor: Principal) = profiles.toList()
        override suspend fun saveProfile(actor: Principal, id: String?, expectedVersion: Int, name: String, spec: CloudProfileSpec, assignedMemberIds: Set<String>): CloudProfile {
            saves++
            return CloudProfile(id ?: "profile-$saves", actor.teamId, name, expectedVersion + 1, spec, assignedMemberIds, 1, 1).also { profiles.removeAll { p -> p.id == it.id }; profiles += it }
        }
        override suspend fun settings(actor: Principal) = CloudSettings()
        override suspend fun saveSettings(actor: Principal, settings: CloudSettings) = settings
    }

    /** A cluster whose reads succeed, are refused (403) or fail, one by one. */
    private class Cluster : ClusterPort by NoCluster, ClusterDiscoveryPort {
        val classes = mutableListOf(StorageClassChoice("slow", "Retain", false, "csi.example"), StorageClassChoice("fast", "Delete", true, "csi.example"),
            StorageClassChoice("archive", "Delete", false))
        var classesRead: () -> Unit = {}
        var secretsRead: () -> Unit = {}
        var namespaceRead: (String) -> Unit = {}
        val secretNamespaces = mutableListOf<String>()
        override suspend fun storageClasses(clusterId: String) = classesRead().let { classes.toList() }
        override suspend fun namespaceExists(clusterId: String, namespace: String) = namespaceRead(namespace).let { namespace != "gone" }
        override suspend fun imagePullSecrets(clusterId: String, namespace: String): List<String> {
            secretsRead(); secretNamespaces += namespace
            return listOf("registry-b", "registry-a", "registry-b")
        }
        override suspend fun storageClass(clusterId: String, name: String?) = classes.firstOrNull { it.name == (name ?: "fast") }?.let { StorageClassObservation(it.name, it.reclaimPolicy) }
    }
    private object NoCluster : ClusterPort {
        override suspend fun storageClass(clusterId: String, name: String?): StorageClassObservation? = error("unused")
        override suspend fun ensureClaim(clusterId: String, request: WorkspaceRequest) = error("unused")
        override suspend fun claim(clusterId: String, namespace: String, name: String): ClaimObservation? = error("unused")
        override suspend fun deleteClaim(clusterId: String, namespace: String, name: String) = error("unused")
        override suspend fun volume(clusterId: String, name: String): VolumeObservation? = error("unused")
        override suspend fun orphanedVolumes(clusterId: String): List<VolumeObservation> = error("unused")
        override suspend fun createPod(clusterId: String, request: PodRequest) = error("unused")
        override suspend fun pod(clusterId: String, namespace: String, name: String): PodObservation? = error("unused")
        override suspend fun deletePod(clusterId: String, namespace: String, name: String, force: Boolean) = error("unused")
        override suspend fun managed(clusterId: String, namespace: String): ManagedObjects = error("unused")
        override suspend fun reviewToken(clusterId: String, token: String, audience: String): TokenIdentity? = error("unused")
    }

    @Test fun choicesComeFromTheClusterWithTheDefaultClassFirst(): Unit = runBlocking {
        val cluster = Cluster()
        val service = CloudAdministrationService(Store(listOf(lab)), cluster)
        val choices = service.choices(admin, lab.id, "sandbox")
        assertEquals("sandbox", choices.namespace)
        assertEquals(listOf(NamespaceChoice("teamcode", NamespaceStatus.FOUND), NamespaceChoice("sandbox", NamespaceStatus.FOUND), NamespaceChoice("gone", NamespaceStatus.MISSING)), choices.namespaces)
        assertEquals(listOf("fast", "archive", "slow"), (choices.storageClasses as ClusterListing.Listed).items.map { it.name }, "The default class comes first")
        assertTrue((choices.storageClasses as ClusterListing.Listed).items.single { it.name == "slow" }.keepsVolumes)
        assertEquals(ClusterListing.Listed(listOf("registry-a", "registry-b")), choices.imagePullSecrets)
        assertEquals(listOf("sandbox"), cluster.secretNamespaces, "Secrets are read in the chosen namespace only")
    }

    @Test fun aNamespaceThatIsNotAllowedFallsBackToTheFirstAllowedOne(): Unit = runBlocking {
        val cluster = Cluster()
        val service = CloudAdministrationService(Store(listOf(lab)), cluster)
        assertEquals("teamcode", service.choices(admin, lab.id, "kube-system").namespace)
        assertEquals("teamcode", service.choices(admin, lab.id, null).namespace)
        val first = lab.copy(id = "first-id", allowedNamespaces = listOf("gone", "sandbox"))
        assertEquals("sandbox", CloudAdministrationService(Store(listOf(first)), cluster).choices(admin, first.id, null).namespace,
            "Without a choice, the first allowed namespace that exists")
        cluster.secretNamespaces.removeLast()
        assertEquals(listOf("teamcode", "teamcode"), cluster.secretNamespaces, "Never a namespace outside the cluster's allowed list")
    }

    @Test fun aMissingRightGivesNoListRatherThanAnError(): Unit = runBlocking {
        val cluster = Cluster().apply {
            secretsRead = { throw ClusterAccessDeniedException() }
            namespaceRead = { throw ClusterAccessDeniedException() }
            classesRead = { throw IllegalStateException("connection refused") }
        }
        val choices = CloudAdministrationService(Store(listOf(lab)), cluster).choices(admin, lab.id, "teamcode")
        assertEquals(ClusterListing.Forbidden, choices.imagePullSecrets)
        assertEquals(ClusterListing.Unavailable, choices.storageClasses)
        assertTrue(choices.namespaces.all { it.status == NamespaceStatus.UNKNOWN })
        assertEquals(lab.allowedNamespaces, choices.namespaces.map { it.name }, "The allowed namespaces are still offered")
    }

    @Test fun withoutDiscoveryEveryListIsUnavailable(): Unit = runBlocking {
        val choices = CloudAdministrationService(Store(listOf(lab)), NoCluster).choices(admin, lab.id, null)
        assertEquals(ClusterListing.Unavailable, choices.storageClasses)
        assertEquals(ClusterListing.Unavailable, choices.imagePullSecrets)
    }

    @Test fun choicesAreForAdministratorsOnAKnownCluster(): Unit = runBlocking {
        val service = CloudAdministrationService(Store(listOf(lab)), Cluster())
        assertEquals(403, assertFailsWith<TeamFailure> { service.choices(member, lab.id, null) }.status)
        assertEquals("cluster_not_found", assertFailsWith<TeamFailure> { service.choices(admin, "other", null) }.code)
    }

    @Test fun cancellationIsNotAnUnavailableList(): Unit = runBlocking {
        val cluster = Cluster().apply { secretsRead = { throw CancellationException("stopped") } }
        assertFailsWith<CancellationException> { CloudAdministrationService(Store(listOf(lab)), cluster).choices(admin, lab.id, null) }
        Unit
    }

    @Test fun checkingADraftRunsThePublicationChecksWithoutPublishing(): Unit = runBlocking {
        val store = Store(listOf(lab))
        val service = CloudAdministrationService(store, Cluster())
        val spec = CloudProfileSpec(lab.id, "teamcode", "registry.example/toolchain:1")
        service.checkProfile(admin, null, 0, "toolchain", spec, emptySet())
        assertEquals(0, store.saves)
        assertEquals("storage_class_retains_volumes", assertFailsWith<TeamFailure> { service.checkProfile(admin, null, 0, "toolchain", spec.copy(storageClass = "slow"), emptySet()) }.code)
        assertEquals("storage_class_missing", assertFailsWith<TeamFailure> { service.checkProfile(admin, null, 0, "toolchain", spec.copy(storageClass = "nowhere"), emptySet()) }.code)
        assertEquals("namespace_not_allowed", assertFailsWith<TeamFailure> { service.checkProfile(admin, null, 0, "toolchain", spec.copy(namespace = "kube-system"), emptySet()) }.code)
        assertEquals("invalid_profile", assertFailsWith<TeamFailure> { service.checkProfile(admin, null, 0, "toolchain", spec.copy(workspaceSizeGibibytes = -1), emptySet()) }.code)
        assertEquals(403, assertFailsWith<TeamFailure> { service.checkProfile(member, null, 0, "toolchain", spec, emptySet()) }.status)
        assertEquals(0, store.saves)

        val published = service.saveProfile(admin, null, 0, "toolchain", spec, emptySet())
        service.checkProfile(admin, published.id, 1, "toolchain", spec.copy(workspaceSizeGibibytes = 20), emptySet())
        service.saveProfile(admin, published.id, 1, "toolchain", spec.copy(workspaceSizeGibibytes = 20), emptySet())
        assertEquals("profile_conflict", assertFailsWith<TeamFailure> { service.checkProfile(admin, published.id, 1, "toolchain", spec, emptySet()) }.code,
            "A draft started from a version that is no longer current is refused before publishing")
        assertEquals(404, assertFailsWith<TeamFailure> { service.checkProfile(admin, "unknown", 1, "toolchain", spec, emptySet()) }.status)
    }
}
