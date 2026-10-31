package me.nathanfallet.teamcode.infrastructure.cloud

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.automations.*
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.workflows.WorkflowRunService
import me.nathanfallet.teamcode.domain.workflows.WorkflowService
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.tables.CloudSessions
import me.nathanfallet.teamcode.infrastructure.secrets.CipherSecretSealer
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.update
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** One server replica of the automation engine, over its own connection pool. */
class AutomationReplica(val database: Database, val cluster: FakeClusterPort, cipher: CredentialCipher) {
    val sessionStore = SqlCloudSessionStore(database)
    val conversations = SqlCloudConversationStore(database)
    val access = CloudAccessService(conversations, sessionStore)
    val admin = CloudAdministrationService(SqlCloudAdminStore(database, cipher), cluster)
    val conversation = CloudConversationService(conversations, sessionStore, access, { _, _ -> false }, sleep = { delay(1) })
    val sessions = CloudSessionService(sessionStore, access = access, initialMessages = conversation)
    val reconciler = ReconcileCloudSessionsUseCase(sessionStore, cluster, activity = conversations)
    val runner = RunnerChannelUseCase(sessionStore)
    val agent = RunnerConversationUseCase(sessionStore, conversations, { _, _ -> false }, sleep = { delay(1) })
    val store = SqlAutomationStore(database)
    val workflowStore = SqlWorkflowStore(database)
    val workflowRunStore = SqlWorkflowRunStore(database)
    val workflowDefinitions = WorkflowService(workflowStore)
    val workflowRuns = WorkflowRunService(workflowRunStore)
    val directory = AutomationDirectory.Cloud(sessionStore, workflowStore)
    val automations = AutomationService(store, directory, CipherSecretSealer(cipher))
    val scheduler = ScheduleAutomationsUseCase(store)
    val dispatcher = DispatchAutomationRunsUseCase(store, CloudAutomationSessions(sessions), directory, WorkflowRunAutomations(workflowRuns, workflowRunStore))
}

/** Two replicas over one MySQL 8.4, an administrator, a member and a profile assigned to the member. */
class AutomationWorld(val a: AutomationReplica, val b: AutomationReplica, val admin: Principal, val member: Principal, val profile: CloudProfile, val team: SqlTeamService) {
    val teamId get() = admin.teamId

    fun cron(expression: String = "0 21 * * *") = AutomationDefinition("Nightly", AutomationTrigger(TriggerKind.CRON, expression, "Europe/Paris"),
        AutomationAction(ActionKind.CLOUD, profile.id), "Summarize {{run.date}}", overlap = OverlapPolicy.ALLOW)
    fun webhook(overlap: OverlapPolicy = OverlapPolicy.ALLOW, maxRunsPerDay: Int = 24) = AutomationDefinition("Ticket", AutomationTrigger(TriggerKind.WEBHOOK),
        AutomationAction(ActionKind.CLOUD, profile.id), "Fix ticket {{ticket}}", listOf(AutomationVariable("ticket", "$.ticket", VariableType.INT)),
        overlap = overlap, maxRunsPerDay = maxRunsPerDay)

    /** A request signed with [secret], as a sender would send it. */
    suspend fun signed(id: String, secret: String, body: String, key: String? = null): TriggerRequest {
        val seconds = (a.store.now() / 1000).toString()
        return TriggerRequest(id, null, null, seconds, AutomationRules.signature(secret, seconds, body.encodeToByteArray()), key, body.encodeToByteArray())
    }

    /** One reconciler pass with every scheduled session made due. */
    suspend fun pass(r: AutomationReplica = a) {
        r.database.query { CloudSessions.update({ CloudSessions.reconcileAfter.isNotNull() }) { it[reconcileAfter] = 0 } }
        r.reconciler()
    }

    companion object {
        suspend fun run(seed: Byte, test: suspend (AutomationWorld) -> Unit) {
            MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
                container.start()
                Database(container.jdbcUrl, container.username, container.password).use { da ->
                Database(container.jdbcUrl, container.username, container.password).use { db ->
                    coroutineScope { listOf(da, db).map { async(Dispatchers.IO) { it.migrate() } }.awaitAll() }
                    val cluster = FakeClusterPort()
                    val cipher = CredentialCipher(ByteArray(32) { seed })
                    val a = AutomationReplica(da, cluster, cipher); val b = AutomationReplica(db, cluster, cipher)
                    val team = SqlTeamService(da)
                    val password = team.bootstrap()!!
                    team.changePassword(team.login("admin", password, "test").principal, password, "automation admin replacement password")
                    val admin = team.login("admin", "automation admin replacement password", "test").principal
                    val created = team.createMember(admin, "automation-owner", Role.MEMBER)
                    team.changePassword(team.login("automation-owner", created.temporaryPassword, "test").principal, created.temporaryPassword, "automation owner replacement password")
                    val member = team.login("automation-owner", "automation owner replacement password", "test").principal
                    val connected = a.admin.saveCluster(admin, null, null, CloudClusterInput("lab", "https://k8s.example.test:6443",
                        "-----BEGIN CERTIFICATE-----\nMIIBsynthetic\n-----END CERTIFICATE-----\n", "synthetic-service-account-token-0123456789", listOf("teamcode"), 10, "standard"))
                    val profile = a.admin.saveProfile(admin, null, 0, "toolchain", CloudProfileSpec(connected.id, "teamcode", "registry.example/toolchain:2026-10"), setOf(member.member.id))
                    test(AutomationWorld(a, b, admin, member, profile, team))
                } }
            }
        }
    }
}

suspend fun refusedWith(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
