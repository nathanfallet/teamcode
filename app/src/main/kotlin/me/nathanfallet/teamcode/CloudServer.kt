package me.nathanfallet.teamcode

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.github.GitHubLinkService
import me.nathanfallet.teamcode.domain.github.ResolveGitHubAccessUseCase
import me.nathanfallet.teamcode.domain.variants.ResolveEffectiveVariantsUseCase
import me.nathanfallet.teamcode.infrastructure.cloud.ClusterApiException
import me.nathanfallet.teamcode.infrastructure.cloud.KubernetesClusterPort
import me.nathanfallet.teamcode.infrastructure.cloud.KubernetesPodOptions
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.SqlCloudAdminStore
import me.nathanfallet.teamcode.infrastructure.database.SqlCloudConversationStore
import me.nathanfallet.teamcode.infrastructure.database.SqlCloudInferenceCredentialStore
import me.nathanfallet.teamcode.infrastructure.database.SqlCloudSessionStore
import me.nathanfallet.teamcode.infrastructure.database.SqlCloudTransferStore
import me.nathanfallet.teamcode.infrastructure.database.SqlGitHubStore
import me.nathanfallet.teamcode.infrastructure.database.SqlMemberAllowance
import me.nathanfallet.teamcode.infrastructure.database.SqlVariantService
import me.nathanfallet.teamcode.infrastructure.database.SqlModelPolicyStore
import me.nathanfallet.teamcode.infrastructure.github.GitHubAppClient
import me.nathanfallet.teamcode.infrastructure.database.SqlScheduledWork
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.presentation.CloudConversation
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

    private val githubStore = SqlGitHubStore(database, cipher)
    private val githubClient = GitHubAppClient()

    private val conversationStore = SqlCloudConversationStore(database)
    // Groups do not exist in TeamCode yet; a group directory plugs in here when they land.
    private val access = CloudAccessService(conversationStore, store, CloudGroupDirectory.NONE)
    private val conversationService = CloudConversationService(conversationStore, store, access)
    val sessions = CloudSessionService(store, access = access, initialMessages = conversationService)

    // Transfers recheck the recipient with the same rules as creation, plus model policy and quota. Environment
    // variables, MCP servers and skills reach cloud sessions in other branches; their assignment checks join this list.
    private val transferStore = SqlCloudTransferStore(database)
    private val recipients = CloudRecipientPolicy(store, access, listOf(ModelPolicyRecipientCheck(SqlModelPolicyStore(database)), QuotaRecipientCheck(SqlMemberAllowance(database))))
    val transfers = CloudTransferService(store, transferStore, sessions, recipients)

    private val reconciler = ReconcileCloudSessionsUseCase(store, cluster, onError = { session, error ->
        // Class and HTTP status only: messages from the cluster or the driver may echo request content.
        System.err.println("TeamCode cloud reconciliation failed for session $session (${describe(error)}); retrying.")
    }, transfers = CompleteCloudTransferUseCase(store, transferStore, cluster, recipients), activity = conversationStore)
    private val garbage = CollectCloudGarbageUseCase(store, cluster)
    val admin = CloudAdministrationService(adminStore, cluster)
    private val inferenceCredentials = SqlCloudInferenceCredentialStore(database)
    /** Members' GitHub App links; their tokens are used only by the members' own cloud sessions. */
    val github = GitHubLinkService(githubStore, githubStore, githubClient)
    /** The owner's environment variables come from the variants, resolved at every preparation. */
    private val variables = ResolveCloudVariablesUseCase(ResolveEffectiveVariantsUseCase(SqlVariantService(database, cipher)))
    val runner = CloudRunnerChannel(RunnerChannelUseCase(store, variables = variables, github = ResolveGitHubAccessUseCase(githubStore, githubStore, githubClient)),
        AuthenticateRunnerUseCase(store, cluster), RunnerInferenceUseCase(store, inferenceCredentials))

    val conversation = CloudConversation(conversationService, access, RunnerConversationUseCase(store, conversationStore))

    fun launch(scope: CoroutineScope) {
        scope.launch {
            // Messages sent while a session was pausing wake it once paused; inputs of ended sessions are cancelled.
            while (isActive) {
                try { conversationService.wakePending(); conversationStore.cancelEnded(50) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { System.err.println("TeamCode cloud message delivery is temporarily unavailable (${describe(error)}).") }
                delay(5_000)
            }
        }
        scope.launch {
            while (isActive) {
                try { work.runDue("cloud-transcript-retention", intervalMillis = 10 * 60_000, timeoutMillis = 120_000) { conversationStore.purgeTranscripts(1_000) } }
                catch (_: TimeoutCancellationException) { System.err.println("TeamCode cloud transcript retention deadline exceeded.") }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { System.err.println("TeamCode cloud transcript retention is temporarily unavailable (${describe(error)}).") }
                delay(60_000)
            }
        }
        scope.launch {
            while (isActive) {
                val claimed = try { reconciler(replica) }
                    catch (error: TimeoutCancellationException) { System.err.println("TeamCode cloud reconciler pass timed out."); 0 }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { System.err.println("TeamCode cloud reconciler is temporarily unavailable (${describe(error)})."); 0 }
                if (claimed == 0) delay(1_000)
            }
        }
        scope.launch {
            while (isActive) {
                try {
                    work.runDue("cloud-maintenance", intervalMillis = 60_000) { store.maintain(); transferStore.maintain(); transfers.expireOffers(); inferenceCredentials.purge(500) }
                    work.runDue("cloud-gc", intervalMillis = 10 * 60_000, timeoutMillis = 240_000) { collectGarbage() }
                }
                // runDue's deadline is a cancellation of the block only; the loop must survive it.
                catch (_: TimeoutCancellationException) { System.err.println("TeamCode cloud maintenance deadline exceeded.") }
                catch (error: CancellationException) { throw error }
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

    override fun close() { cluster.close(); githubClient.close() }

    private companion object {
        fun describe(error: Exception) = if (error is ClusterApiException) "Kubernetes HTTP ${error.status}" else error::class.simpleName ?: "error"
    }
}
