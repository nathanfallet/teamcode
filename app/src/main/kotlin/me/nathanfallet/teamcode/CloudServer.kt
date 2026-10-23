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
import me.nathanfallet.teamcode.infrastructure.database.SqlCloudRuntimeStore
import me.nathanfallet.teamcode.infrastructure.database.SqlCloudTransferStore
import me.nathanfallet.teamcode.infrastructure.database.SqlGitHubStore
import me.nathanfallet.teamcode.infrastructure.database.SqlMemberAllowance
import me.nathanfallet.teamcode.infrastructure.database.SqlVariantService
import me.nathanfallet.teamcode.infrastructure.database.SqlModelPolicyStore
import me.nathanfallet.teamcode.infrastructure.github.GitHubAppClient
import me.nathanfallet.teamcode.infrastructure.database.SqlScheduledWork
import me.nathanfallet.teamcode.infrastructure.secrets.CipherSecretSealer
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.presentation.CloudConversation
import me.nathanfallet.teamcode.presentation.CloudRunnerChannel

/**
 * Cloud sessions. Built whenever the server has a database, so administrators can configure them from the dashboard;
 * [runtime] (stored in the database) says whether members can use them: while it is off the member-facing routes answer
 * `404` and `cloud.*` capabilities are not advertised. The runner channel and the workers keep serving the sessions that
 * already exist, so turning cloud sessions off never kills a running agent; they stay idle until a runtime is stored.
 */
class CloudServer(database: Database, cipher: CredentialCipher, publicOrigin: String) : AutoCloseable {
    val runtime = CloudRuntimeService(SqlCloudRuntimeStore(database), publicOrigin)
    private val store = SqlCloudSessionStore(database)
    private val adminStore = SqlCloudAdminStore(database, cipher)
    private val cluster = KubernetesClusterPort({ adminStore.connection(it) }, {
        val current = runtime.current
        // Off: no new pod starts. A session already queued waits (and can be deleted) until cloud sessions are on.
        if (!current.enabled) throw CloudConfigurationException("cloud sessions are turned off; queued sessions start once they are on again")
        val image = current.runnerImage ?: throw CloudConfigurationException("no runner image is set; set it on Dashboard → Cloud sessions")
        if (!CloudRuntimeService.usableOrigin(runtime.serverUrl(current))) throw CloudConfigurationException("the callback origin for pods is not HTTPS or is loopback; set the server URL for pods on Dashboard → Cloud sessions")
        KubernetesPodOptions(serverUrl = runtime.serverUrl(current), runnerImage = image, runnerBinary = current.runnerBinary, workspaceAccessMode = current.workspaceAccessMode)
    }, workspaceAccessMode = { runtime.current.workspaceAccessMode })
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

    private val reconciler = ReconcileCloudSessionsUseCase(store, cluster, startAllowed = { runtime.enabled }, onError = { session, error ->
        // Class and HTTP status only: messages from the cluster or the driver may echo request content.
        System.err.println("TeamCode cloud reconciliation failed for session $session (${describe(error)}); retrying.")
    }, transfers = CompleteCloudTransferUseCase(store, transferStore, cluster, recipients), activity = conversationStore)
    private val garbage = CollectCloudGarbageUseCase(store, cluster)
    val admin = CloudAdministrationService(adminStore, cluster, KubeconfigImportService(CipherSecretSealer(cipher)))
    private val inferenceCredentials = SqlCloudInferenceCredentialStore(database)
    /** Members' GitHub App links; their tokens are used only by the members' own cloud sessions. */
    val github = GitHubLinkService(githubStore, githubStore, githubClient)
    /** The owner's environment variables come from the variants, resolved at every preparation. */
    private val variables = ResolveCloudVariablesUseCase(ResolveEffectiveVariantsUseCase(SqlVariantService(database, cipher)))
    private val githubAccess = ResolveGitHubAccessUseCase(githubStore, githubStore, githubClient)
    val runner = CloudRunnerChannel(RunnerChannelUseCase(store, variables = variables, github = githubAccess),
        AuthenticateRunnerUseCase(store, cluster), RunnerInferenceUseCase(store, inferenceCredentials), RunnerGitHubUseCase(store, githubAccess),
        RunnerVariablesUseCase(store, variables))

    val conversation = CloudConversation(conversationService, access, RunnerConversationUseCase(store, conversationStore), me.nathanfallet.teamcode.domain.team.TeamEventService(me.nathanfallet.teamcode.infrastructure.database.SqlTeamEventStore(database)))

    /**
     * Seeds the runtime from the former deployment variables, only if nothing is stored yet; the database stays the
     * source of truth afterwards.
     */
    /** The former variables, kept until a runtime is stored so a seed that failed at startup is retried. */
    @Volatile private var seedCandidate: CloudRuntime? = null

    suspend fun start(initial: CloudRuntime?): CloudRuntime { seedCandidate = initial; return seed() }

    private suspend fun seed() = runtime.seed(seedCandidate) { reason -> System.err.println("TeamCode cloud runtime from the environment was stored disabled: $reason") }

    /** Workers run once a runtime is stored, on or off: sessions created while it was on keep being reconciled. */
    private fun managing() = runtime.current.updatedAt != null

    fun launch(scope: CoroutineScope) {
        scope.launch {
            // Another replica's dashboard save reaches this one within one interval.
            while (isActive) {
                delay(RUNTIME_REFRESH_MILLIS)
                try { if (runtime.current.updatedAt == null && seedCandidate != null) seed() else runtime.refresh() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { System.err.println("TeamCode cloud runtime could not be reread (${describe(error)}); keeping the last one.") }
            }
        }
        scope.launch {
            // Messages sent while a session was pausing wake it once paused; inputs of ended sessions are cancelled.
            while (isActive) {
                if (!managing()) { delay(RUNTIME_REFRESH_MILLIS); continue }
                // Waking a paused session for a queued message is a resume: not while cloud sessions are off.
                try { if (runtime.enabled) conversationService.wakePending(); conversationStore.cancelEnded(50) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { System.err.println("TeamCode cloud message delivery is temporarily unavailable (${describe(error)}).") }
                delay(5_000)
            }
        }
        scope.launch {
            while (isActive) {
                if (!managing()) { delay(RUNTIME_REFRESH_MILLIS); continue }
                try { work.runDue("cloud-transcript-retention", intervalMillis = 10 * 60_000, timeoutMillis = 120_000) { conversationStore.purgeTranscripts(1_000) } }
                catch (_: TimeoutCancellationException) { System.err.println("TeamCode cloud transcript retention deadline exceeded.") }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { System.err.println("TeamCode cloud transcript retention is temporarily unavailable (${describe(error)}).") }
                delay(60_000)
            }
        }
        scope.launch {
            while (isActive) {
                if (!managing()) { delay(RUNTIME_REFRESH_MILLIS); continue }
                val claimed = try { reconciler(replica) }
                    catch (error: TimeoutCancellationException) { System.err.println("TeamCode cloud reconciler pass timed out."); 0 }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { System.err.println("TeamCode cloud reconciler is temporarily unavailable (${describe(error)})."); 0 }
                if (claimed == 0) delay(1_000)
            }
        }
        scope.launch {
            while (isActive) {
                if (!managing()) { delay(RUNTIME_REFRESH_MILLIS); continue }
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
        const val RUNTIME_REFRESH_MILLIS = 5_000L
        /** Our own configuration refusals (missing runner image, unusable origin) carry their fix; other messages are never logged. */
        fun describe(error: Exception) = when (error) {
            is ClusterApiException -> "Kubernetes HTTP ${error.status}"
            is CloudConfigurationException -> error.message ?: "cloud runtime incomplete"
            else -> error::class.simpleName ?: "error"
        }
    }
}

/** The stored runtime cannot build a pod yet; the message says what an administrator must set. */
private class CloudConfigurationException(message: String) : Exception(message)
