package me.nathanfallet.teamcode.infrastructure.cloud

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.Role
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.tables.CloudSessions
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/**
 * Opt-in smoke against a real cluster, prepared by `scripts/smoke-cloud-kind.py` (not run in pull-request CI).
 * It drives the real reconciler, stores and Kubernetes adapter: workspace claim and pod creation with the
 * production pod spec, a real TokenReview of a pod-bound token, pause with checkpoint, and proven deletion of
 * the claim and the PersistentVolume behind it. Two server replicas (separate connection pools, stores and cluster
 * clients over one MySQL) reconcile concurrently; resume after the pause must start exactly one pod in a new epoch,
 * with a new Kubernetes UID, on the same workspace. The runner is a stand-in that only sleeps; its calls are made
 * here through [RunnerChannelUseCase].
 */
class KindSmokeTest {
    @Test fun lifecycleOnARealCluster(): Unit = runBlocking {
        val dir = System.getenv("TEAMCODE_KIND_SMOKE_DIR")
        assumeTrue(dir != null, "Set TEAMCODE_KIND_SMOKE_DIR (see scripts/smoke-cloud-kind.py).")
        fun read(name: String) = File(dir, name).readText().trim()
        val namespace = read("namespace")
        val kubeconfig = File(dir, "kubeconfig").path
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").use { mysql ->
            mysql.start()
            Database(mysql.jdbcUrl, mysql.username, mysql.password).use { database ->
                database.migrate()
                val cipher = CredentialCipher(ByteArray(32) { 7 })
                val adminStore = SqlCloudAdminStore(database, cipher)
                val options = KubernetesPodOptions("https://teamcode.invalid", read("image"), workspaceAccessMode = "ReadWriteOnce")
                KubernetesClusterPort({ adminStore.connection(it) }, { options }).use { port ->
                    val store = SqlCloudSessionStore(database)
                    val admin = CloudAdministrationService(adminStore, port)
                    val sessions = CloudSessionService(store)
                    val reconciler = ReconcileCloudSessionsUseCase(store, port, onError = { id, error -> println("reconcile $id: $error") })
                    // The second replica: its own pool, store, cluster client and reconciler over the same database.
                    Database(mysql.jdbcUrl, mysql.username, mysql.password).use { secondDatabase ->
                        val secondAdminStore = SqlCloudAdminStore(secondDatabase, cipher)
                        KubernetesClusterPort({ secondAdminStore.connection(it) }, { options }).use { secondPort ->
                        val secondStore = SqlCloudSessionStore(secondDatabase)
                        val secondSessions = CloudSessionService(secondStore)
                        val secondReconciler = ReconcileCloudSessionsUseCase(secondStore, secondPort, onError = { id, error -> println("reconcile (replica 2) $id: $error") })
                        val runner = RunnerChannelUseCase(store)
                        val team = SqlTeamService(database)
                        val password = team.bootstrap()!!
                        team.changePassword(team.login("admin", password, "smoke").principal, password, "kind smoke administrator password")
                        val principal = team.login("admin", "kind smoke administrator password", "smoke").principal
                        assertEquals(Role.OWNER, principal.member.role, "The bootstrap member is the owner")

                        val cluster = admin.saveCluster(principal, null, null, CloudClusterInput("kind", read("api-url"), read("ca.pem") + "\n", read("token"), listOf(namespace), 2, "standard"))
                        // The profile form's choices, read from the real API server with the optional rights of the smoke roles.
                        val choices = admin.choices(principal, cluster.id, namespace)
                        assertEquals(listOf(NamespaceChoice(namespace, NamespaceStatus.FOUND)), choices.namespaces)
                        assertEquals(ClusterListing.Listed(listOf("smoke-registry")), choices.imagePullSecrets, "Registry secrets only, by name, listed as metadata")
                        assertTrue((choices.storageClasses as ClusterListing.Listed).items.any { it.name == "standard" && it.isDefault && !it.keepsVolumes })
                        val profile = admin.saveProfile(principal, null, 0, "smoke", CloudProfileSpec(cluster.id, namespace, read("image"),
                            resources = CloudResources(100, 128), workspaceSizeGibibytes = 1), emptySet())
                        val created = (sessions.create(principal, null, CloudSessionDraft("Smoke", profile.id, null, null, null, mapOf("smoke" to "kind"), null), null) as CloudCreateOutcome.Created).session
                        // Both replicas reconcile at the same moment; each keeps its own lease owner.
                        // A transient database error of one replica under the deliberate contention is retried at the next round.
                        val roundFailures = AtomicInteger()
                        suspend fun tolerant(name: String, round: suspend () -> Int) {
                            try { round() } catch (error: CancellationException) { throw error } catch (error: Exception) {
                                println("$name round failed: $error")
                                check(roundFailures.incrementAndGet() <= 5) { "Too many failed reconciliation rounds: the replicas did not really reconcile" }
                            }
                        }
                        suspend fun both() = coroutineScope { listOf(async { tolerant("replica 1") { reconciler("replica-1") } }, async { tolerant("replica 2") { secondReconciler("replica-2") } }).forEach { it.await() } }
                        suspend fun until(what: String, timeoutSeconds: Int = 180, done: suspend (CloudSession) -> Boolean): CloudSession {
                            repeat(timeoutSeconds) {
                                val s = store.find(principal.teamId, created.id)!!
                                if (done(s)) return s
                                database.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }
                                                                                                                            both()
                                delay(1_000)
                            }
                            fail("Timed out waiting for $what: ${store.find(principal.teamId, created.id)}")
                        }

                        val pod = CloudSessionService.podName(created.id, 1)
                        val starting = until("the pod to run") { it.phase == "starting_runner" && it.imageDigest != null }
                        assertNotNull(starting.workspace.volumeName, "The claim bound to a PersistentVolume")
                        assertEquals(PodPhase.RUNNING, port.pod(cluster.id, namespace, pod)!!.phase)

                        // A real TokenReview of a token bound to the pod, with the audience the runner uses.
                        val uid = kubectl(kubeconfig, "get", "pod", pod, "-n", namespace, "-o", "jsonpath={.metadata.uid}")
                        val token = kubectl(kubeconfig, "create", "token", "default", "-n", namespace, "--audience", AuthenticateRunnerUseCase.AUDIENCE,
                            "--bound-object-kind", "Pod", "--bound-object-name", pod, "--bound-object-uid", uid, "--duration", "10m")
                        val identity = AuthenticateRunnerUseCase(store, port)(token, principal.teamId, created.id, 1)
                        assertEquals(pod, identity.podName)
                        assertEquals(401, assertFailsWith<TeamFailure> {
                            AuthenticateRunnerUseCase(store, port)(kubectl(kubeconfig, "create", "token", "default", "-n", namespace, "--duration", "10m"), principal.teamId, created.id, 1)
                        }.status, "A token for another audience is refused")

                        runner.register(principal.teamId, created.id, 1, identity.podName)
                        runner.heartbeat(principal.teamId, created.id, 1, ready = true)
                        assertEquals(READY, store.find(principal.teamId, created.id)!!.state)

                        sessions.pause(principal, null, created.id, false)
                        until("pausing") { it.state == PAUSING }
                        runner.checkpoint(principal.teamId, created.id, 1, completed = true)
                        val paused = until("paused") { it.state == PAUSED }
                        assertEquals(WorkspaceResume.CONSISTENT, paused.resumeWorkspace)
                        assertNull(port.pod(cluster.id, namespace, pod), "A paused session keeps no pod")
                        assertNotNull(port.claim(cluster.id, namespace, paused.workspace.claimName), "A paused session keeps its workspace")
                        repeat(60) { if (sessionPods(kubeconfig, namespace, created.id).isNotEmpty()) delay(500) }
                        assertEquals(emptyList(), sessionPods(kubeconfig, namespace, created.id), "No pod of the session exists on the cluster while paused")

                        // Resume, requested twice at once (two API replicas), lands in a new pod of epoch 2.
                        val resumes = coroutineScope { listOf(
                            async { runCatching { sessions.resume(principal, null, created.id) } },
                            async { runCatching { secondSessions.resume(principal, null, created.id) } },
                        ).map { it.await() } }
                        // One of the two may lose the race with a conflict; at least one must be accepted, and none may fail otherwise.
                        assertTrue(resumes.any { it.isSuccess }, "At least one resume request is accepted: $resumes")
                        resumes.mapNotNull { it.exceptionOrNull() }.forEach { assertTrue(it is TeamFailure && it.status == 409, "A refused resume is a conflict: $it") }
                        val secondPod = CloudSessionService.podName(created.id, 2)
                        val resumed = until("the pod of epoch 2 to run") { it.currentEpoch == 2 && port.pod(cluster.id, namespace, secondPod)?.phase == PodPhase.RUNNING }
                        assertEquals(paused.workspace.claimName, resumed.workspace.claimName, "The same workspace claim")
                        assertEquals(paused.workspace.volumeName, resumed.workspace.volumeName, "The same PersistentVolume")
                        val newUid = kubectl(kubeconfig, "get", "pod", secondPod, "-n", namespace, "-o", "jsonpath={.metadata.uid}")
                        assertTrue(newUid.isNotEmpty() && newUid != uid, "A new pod: UID $newUid differs from $uid")
                        val secondToken = kubectl(kubeconfig, "create", "token", "default", "-n", namespace, "--audience", AuthenticateRunnerUseCase.AUDIENCE,
                            "--bound-object-kind", "Pod", "--bound-object-name", secondPod, "--bound-object-uid", newUid, "--duration", "10m")
                        // The cluster itself says which pod incarnation the token belongs to.
                        assertEquals(newUid, port.reviewToken(cluster.id, secondToken, AuthenticateRunnerUseCase.AUDIENCE)?.podUid)
                        val secondIdentity = AuthenticateRunnerUseCase(store, port)(secondToken, principal.teamId, created.id, 2)
                        assertEquals(secondPod, secondIdentity.podName)
                        runner.register(principal.teamId, created.id, 2, secondIdentity.podName)
                        runner.heartbeat(principal.teamId, created.id, 2, ready = true)
                        until("the resumed session to be ready") { it.state == READY }

                        // No double launch: many more concurrent reconciliation rounds neither add a pod nor an epoch.
                        repeat(15) {
                            runner.heartbeat(principal.teamId, created.id, 2, ready = true)
                            database.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }
                            both()
                            delay(500)
                        }
                        assertEquals(listOf(secondPod), sessionPods(kubeconfig, namespace, created.id), "Exactly one pod for the session")
                        assertEquals(newUid, kubectl(kubeconfig, "get", "pod", secondPod, "-n", namespace, "-o", "jsonpath={.metadata.uid}"), "The pod was never deleted and recreated while the replicas reconciled")
                        assertEquals(listOf(1, 2), store.executions(principal.teamId, created.id).map { it.epoch }.sorted(), "One execution per epoch, none duplicated")
                        assertEquals(2, store.find(principal.teamId, created.id)!!.currentEpoch)

                        sessions.delete(principal, null, created.id)
                        val deleted = until("proven deletion") { it.workspace.deletion == DeletionState.PROVEN }
                        assertEquals(TERMINATED, deleted.state)
                        assertNull(port.claim(cluster.id, namespace, deleted.workspace.claimName))
                        assertNull(port.volume(cluster.id, deleted.workspace.volumeName!!), "The PersistentVolume, and the volume behind it, is gone")
                        assertEquals(0, CollectCloudGarbageUseCase(store, port)(store.cluster(principal.teamId, cluster.id)!!).deletedPods)
                        }
                    }
                }
            }
        }
    }

    private fun sessionPods(kubeconfig: String, namespace: String, sessionId: String): List<String> =
        kubectl(kubeconfig, "get", "pods", "-n", namespace, "-l", "${ReconcileCloudSessionsUseCase.SESSION_LABEL}=$sessionId", "-o", "name")
            .lines().filter { it.isNotBlank() }.map { it.removePrefix("pod/") }

    private fun kubectl(kubeconfig: String, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("kubectl", "--kubeconfig", kubeconfig) + arguments).redirectErrorStream(false).start()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "kubectl ${arguments.first()} failed" }
        return process.inputStream.bufferedReader().readText().trim()
    }
}
