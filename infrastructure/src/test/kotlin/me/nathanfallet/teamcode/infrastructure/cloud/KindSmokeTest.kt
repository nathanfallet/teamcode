package me.nathanfallet.teamcode.infrastructure.cloud

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.cloud.CloudSessionState.*
import me.nathanfallet.teamcode.domain.team.Role
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
 * the claim and the PersistentVolume behind it. The runner is a stand-in that only sleeps; its calls are made
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
                val port = KubernetesClusterPort({ adminStore.connection(it) },
                    KubernetesPodOptions("https://teamcode.invalid", read("image"), workspaceAccessMode = "ReadWriteOnce"))
                val store = SqlCloudSessionStore(database)
                val admin = CloudAdministrationService(adminStore, port)
                val sessions = CloudSessionService(store)
                val reconciler = ReconcileCloudSessionsUseCase(store, port, onError = { id, error -> println("reconcile $id: $error") })
                val runner = RunnerChannelUseCase(store)
                val team = SqlTeamService(database)
                val password = team.bootstrap()!!
                team.changePassword(team.login("admin", password, "smoke").principal, password, "kind smoke administrator password")
                val principal = team.login("admin", "kind smoke administrator password", "smoke").principal
                assertEquals(Role.ADMIN, principal.member.role)

                val cluster = admin.saveCluster(principal, null, null, CloudClusterInput("kind", read("api-url"), read("ca.pem") + "\n", read("token"), listOf(namespace), 2, "standard"))
                val profile = admin.saveProfile(principal, null, 0, "smoke", CloudProfileSpec(cluster.id, namespace, read("image"),
                    resources = CloudResources(100, 128), workspaceSizeGibibytes = 1), emptySet())
                val created = (sessions.create(principal, null, CloudSessionDraft("Smoke", profile.id, null, null, null, mapOf("smoke" to "kind"), null), null) as CloudCreateOutcome.Created).session
                suspend fun until(what: String, timeoutSeconds: Int = 180, done: suspend (CloudSession) -> Boolean): CloudSession {
                    repeat(timeoutSeconds) {
                        val s = store.find(principal.teamId, created.id)!!
                        if (done(s)) return s
                        database.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }
                        reconciler()
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
                assertEquals(401, assertFailsWith<me.nathanfallet.teamcode.domain.team.TeamFailure> {
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

                sessions.delete(principal, null, created.id)
                val deleted = until("proven deletion") { it.workspace.deletion == DeletionState.PROVEN }
                assertEquals(TERMINATED, deleted.state)
                assertNull(port.claim(cluster.id, namespace, deleted.workspace.claimName))
                assertNull(port.volume(cluster.id, deleted.workspace.volumeName!!), "The PersistentVolume, and the volume behind it, is gone")
                assertEquals(0, CollectCloudGarbageUseCase(store, port)(store.cluster(principal.teamId, cluster.id)!!).deletedPods)
                port.close()
            }
        }
    }

    private fun kubectl(kubeconfig: String, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("kubectl", "--kubeconfig", kubeconfig) + arguments).redirectErrorStream(false).start()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "kubectl ${arguments.first()} failed" }
        return process.inputStream.bufferedReader().readText().trim()
    }
}
