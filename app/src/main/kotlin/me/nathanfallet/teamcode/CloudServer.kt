package me.nathanfallet.teamcode

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.infrastructure.cloud.ClusterApiException
import me.nathanfallet.teamcode.infrastructure.cloud.KubernetesClusterPort
import me.nathanfallet.teamcode.infrastructure.cloud.KubernetesPodOptions
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.SqlCloudAdminStore
import me.nathanfallet.teamcode.infrastructure.database.SqlCloudSessionStore
import me.nathanfallet.teamcode.infrastructure.database.SqlScheduledWork
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.presentation.CloudRunnerChannel

/**
 * Cloud sessions, enabled when `TEAMCODE_CLOUD_RUNNER_IMAGE` is set: without a runner image no session pod could
 * start, so the routes stay unmounted and `cloud.sessions.v1` is not advertised.
 */
class CloudServer(database: Database, cipher: CredentialCipher, publicOrigin: String, runnerImage: String) : AutoCloseable {
    private val store = SqlCloudSessionStore(database)
    private val adminStore = SqlCloudAdminStore(database, cipher)
    private val cluster = KubernetesClusterPort({ adminStore.connection(it) }, KubernetesPodOptions(
        serverUrl = System.getenv("TEAMCODE_CLOUD_SERVER_URL") ?: publicOrigin,
        runnerImage = runnerImage,
        runnerBinary = System.getenv("TEAMCODE_CLOUD_RUNNER_BINARY") ?: "/usr/local/bin/teamcode",
        workspaceAccessMode = System.getenv("TEAMCODE_CLOUD_WORKSPACE_ACCESS_MODE") ?: "ReadWriteOncePod",
    ))
    private val work = SqlScheduledWork(database)
    private val replica = UUID.randomUUID().toString()
    private val reconciler = ReconcileCloudSessionsUseCase(store, cluster, onError = { session, error ->
        // Class and HTTP status only: messages from the cluster or the driver may echo request content.
        System.err.println("TeamCode cloud reconciliation failed for session $session (${describe(error)}); retrying.")
    })
    private val garbage = CollectCloudGarbageUseCase(store, cluster)

    val sessions = CloudSessionService(store)
    val admin = CloudAdministrationService(adminStore, cluster)
    val runner = CloudRunnerChannel(RunnerChannelUseCase(store), AuthenticateRunnerUseCase(store, cluster))

    fun launch(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                val claimed = try { reconciler(replica) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { System.err.println("TeamCode cloud reconciler is temporarily unavailable (${describe(error)})."); 0 }
                if (claimed == 0) delay(1_000)
            }
        }
        scope.launch {
            while (isActive) {
                try {
                    work.runDue("cloud-maintenance", intervalMillis = 60_000) { store.maintain() }
                    work.runDue("cloud-gc", intervalMillis = 10 * 60_000, timeoutMillis = 240_000) { collectGarbage() }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { System.err.println("TeamCode cloud maintenance is temporarily unavailable (${describe(error)}).") }
                delay(15_000)
            }
        }
    }

    private suspend fun collectGarbage() {
        var after: String? = null
        while (true) {
            val page = store.clusters(after)
            for (target in page) {
                try { garbage(target) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { System.err.println("TeamCode cloud garbage collection failed for cluster ${target.id} (${describe(error)}).") }
            }
            after = page.lastOrNull()?.id ?: return
        }
    }

    override fun close() = cluster.close()

    private companion object {
        fun describe(error: Exception) = if (error is ClusterApiException) "Kubernetes HTTP ${error.status}" else error::class.simpleName ?: "error"
    }
}
