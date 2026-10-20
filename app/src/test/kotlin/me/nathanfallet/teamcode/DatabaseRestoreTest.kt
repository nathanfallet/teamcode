package me.nathanfallet.teamcode

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.policy.ModelPolicy
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.tables.Accounts
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialDecryptionException
import me.nathanfallet.teamcode.presentation.configureGateway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.*
import org.junit.jupiter.api.Timeout
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Exercise an actual logical backup and a new MySQL instance, never an operator-supplied database. */
class DatabaseRestoreTest {
    @Test @Timeout(300)
    fun quiescedBackupRestoresEncryptedAccountsAuthorizationAndQuotaWithoutProcessState() = runBlocking {
        val root = Files.createTempDirectory("teamcode-restore-")
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        val backup = privateFile(root.resolve("backup.sql"))
        val diagnostics = privateFile(root.resolve("database-tool.log"))
        val key = SecureRandom().generateSeed(32)
        try {
            lateinit var fixture: Fixture
            lateinit var before: Map<String, Any?>
            lateinit var sourceId: String
            var backupMillis = 0L
            databaseContainer().use { source ->
                source.start(); sourceId = source.containerId
                Database(source.jdbcUrl, source.username, source.password).use { database ->
                    database.migrate()
                    // Daily reset is expected behavior; keep the round trip away from that boundary.
                    while (DAY - System.currentTimeMillis() % DAY < 120_000) delay(1_000)
                    fixture = seed(database, key)
                    before = snapshot(database, key, fixture)
                    val started = System.nanoTime()
                    transfer(source, backup, diagnostics, restore = false)
                    backupMillis = (System.nanoTime() - started) / 1_000_000
                    assertTrue(Files.size(backup) in 1..16_777_216, "Unexpected fixture backup size")
                    val bytes = Files.readString(backup)
                    assertTrue(fixture.secrets.none(bytes::contains), "Plaintext fixture credentials entered the backup")
                }
            } // Stop the original owned instance before activating its restored copy.
            databaseContainer().use { target ->
                target.start()
                assertNotEquals(sourceId, target.containerId)
                val started = System.nanoTime()
                transfer(target, backup, diagnostics, restore = true)
                val restoreMillis = (System.nanoTime() - started) / 1_000_000
                val restoredDump = privateFile(root.resolve("restored.sql"))
                transfer(target, restoredDump, diagnostics, restore = false)
                assertEquals(-1L, Files.mismatch(backup, restoredDump), "Restored schema/rows differ from the canonical full-database dump")
                Database(target.jdbcUrl, target.username, target.password).use { restored ->
                    restored.migrate() // Includes validating the restored Flyway history.
                    val team = SqlTeamService(restored)
                    assertNull(team.bootstrap(), "Restoration must not generate another administrator password")
                    assertTrue(before == snapshot(restored, key, fixture), "Restored durable state differs from the quiesced snapshot")
                    val admin = assertNotNull(team.authenticate(fixture.admin.token))
                    assertEquals(Role.OWNER, admin.member.role, "Restoration must preserve ownership without another bootstrap")
                    assertNull(team.authenticate(fixture.revokedToken), "A pre-backup revocation was lost")
                    assertNull(team.authenticate(fixture.revokedKey), "A revoked API key was resurrected")
                    val decrypted = SqlAccountStore(restored, CredentialCipher(key)).credentials("default", fixture.accountId)
                    assertTrue(decrypted.accessToken == fixture.access && decrypted.refreshToken == fixture.refresh,
                        "Restored account credentials did not decrypt with the original key")
                    assertFailsWith<CredentialDecryptionException> {
                        val wrongKey = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
                        SqlAccountStore(restored, CredentialCipher(wrongKey)).credentials("default", fixture.accountId)
                    }
                    verifyHttp(restored, key, fixture)
                    val admission = SqlAdmissionStore(restored)
                    assertEquals(200, admission.capacity(admin).chargedAndReservedUnits)
                    assertEquals("member_allowance", assertFailsWith<TeamFailure> {
                        admission.reserve(admin, 401, "claude-test")
                    }.code, "A restored active/uncertain reservation must not reset remaining allowance")
                    // Verify persisted refresh uncertainty without constructing any provider transport.
                    restored.query { Accounts.update({ Accounts.id eq fixture.accountId }) { it[disabled] = false } }
                    val account = SqlAccountStore(restored, CredentialCipher(key)).list("default").single { it.id == fixture.accountId }
                    val credentials = SqlCredentialSource(restored, CredentialCipher(key)) { _, _ -> fail("A restored pending refresh must never be replayed") }
                    assertEquals("provider_reconnect", assertFailsWith<TeamFailure> { credentials.fresh(account) }.code)
                    SqlServiceKeyService(restored).revoke(admin, fixture.activeKey.metadata.id)
                    assertNull(team.authenticate(fixture.activeKey.accessToken))
                    assertEquals(200, admission.capacity(admin).chargedAndReservedUnits)
                    team.logout(admin)
                    assertNull(team.authenticate(fixture.admin.token))
                    val member = assertNotNull(team.authenticate(fixture.memberToken))
                    assertEquals(0, admission.capacity(member).chargedAndReservedUnits)
                    assertEquals("forbidden", assertFailsWith<TeamFailure> { admission.capacity(member, admin.member.id) }.code)
                }
                println("Restore fixture passed: bytes=${Files.size(backup)}, backupMs=$backupMillis, restoreMs=$restoreMillis; no provider calls.")
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private suspend fun seed(database: Database, key: ByteArray): Fixture {
        val team = SqlTeamService(database)
        val temporary = assertNotNull(team.bootstrap())
        val password = secret()
        val initial = team.login("admin", temporary, "restore")
        team.changePassword(initial.principal, temporary, password)
        val admin = team.login("admin", password, "Restore workstation", UUID.randomUUID().toString())
        val other = team.login("admin", password, "Revoked before backup")
        team.logout(other.principal)
        val memberTokens = mutableListOf<String>()
        val secrets = mutableListOf(temporary, password, admin.token, other.token)
        val members = listOf("developer", "support").map { name ->
            val created = team.createMember(admin.principal, name, Role.MEMBER)
            val login = team.login(name, created.temporaryPassword, "restore")
            val replacement = secret()
            team.changePassword(login.principal, created.temporaryPassword, replacement)
            val ready = team.login(name, replacement, "restore")
            memberTokens += ready.token
            secrets += listOf(created.temporaryPassword, replacement, ready.token)
            ready.principal.member
        }
        val devices = SqlDeviceService(database)
        devices.rename(admin.principal, devices.devices(admin.principal).single().id, "Restored workstation")
        val keys = SqlServiceKeyService(database)
        val activeKey = keys.create(admin.principal, admin.principal.member.id, "Restore quota reader", setOf(ServiceScope.USAGE_READ), 1)
        val revokedKey = keys.create(admin.principal, admin.principal.member.id, "Revoked before backup", setOf(ServiceScope.INFERENCE), 1)
        keys.revoke(admin.principal, revokedKey.metadata.id)
        secrets += listOf(activeKey.accessToken, revokedKey.accessToken)
        val accounts = SqlAccountStore(database, CredentialCipher(key))
        val access = secret(); val refresh = secret()
        secrets += listOf(access, refresh)
        val verified = VerifiedProviderAccount(AccountIdentity("default", "anthropic", "restore-account", "restore-org"), "Restored Max account")
        val enrolled = accounts.upsert(AccountMutationActor.System, verified, ProviderCredentials(access, refresh, Long.MAX_VALUE), null, "restore-enrollment-001") as EnrollmentResult.Created
        accounts.handover(AccountMutationActor.System, "default", enrolled.account.id)
        val admission = SqlAdmissionStore(database)
        admission.configurePool(admin.principal, PoolBudget("", 1100))
        admission.configurePool(admin.principal, PoolBudget("protected", 700))
        admission.configure(admin.principal, MemberPolicy(admin.principal.member.id, maxConcurrent = 2, weight = 5, allowSharedOverflow = true))
        admission.configure(admin.principal, MemberPolicy(members[0].id, maxConcurrent = 2, weight = 5))
        admission.configure(admin.principal, MemberPolicy(members[1].id, maxConcurrent = 1, weight = 1))
        val groups = GroupServiceImpl(database)
        val group = groups.create(admin.principal, GroupPolicy("Restored group", 1000, 3)).summary.group.id
        groups.assign(admin.principal, group, admin.principal.member.id, GroupRole.MEMBER)
        groups.assign(admin.principal, group, members[0].id, GroupRole.MANAGER)
        groups.assign(admin.principal, group, members[1].id, GroupRole.MEMBER, paused = true)
        val runs = AgentRunServiceImpl(database)
        val runId = UUID.randomUUID().toString()
        runs.start(admin.principal, runId, "claude", "interactive")
        admission.settle(admission.reserve(admin.principal, AdmissionInput(100, "claude-test", "anthropic", runId = runId)), 20, true)
        admission.configurePool(admin.principal, PoolBudget("restored-overflow", 1000, true))
        accounts.configure(AccountMutationActor.System, "default", enrolled.account.id, "Restore borrowed account", false, 0, "restored-overflow")
        val review = admission.reserve(admin.principal, AdmissionInput(70, "claude-test", "anthropic", runId = runId))
        admission.recordProviderRequest(review, "req_restore_reconciled")
        admission.settle(review, null)
        UsageReconciliationServiceImpl(database).reconcile(admin.principal, review.id,
            UsageReconciliationChange(70, 70, UsageEvidence.PROVIDER_RECORD, "Synthetic restored provider record", "Verified restored usage", "req_restore_reconciled"))
        admission.reserve(admin.principal, AdmissionInput(100, "claude-test", "anthropic", runId = runId))
        val stillUnknown = admission.reserve(admin.principal, AdmissionInput(10, "claude-test", "anthropic", runId = runId))
        admission.recordProviderRequest(stillUnknown, "req_restore_pending")
        admission.settle(stillUnknown, null)
        runs.configure(admin.principal, runId, RunPolicy(500, 2, true))
        runs.end(admin.principal, runId, 0)
        val overrides = BudgetOverrideServiceImpl(database)
        val previousOverride = overrides.grant(admin.principal, admin.principal.member.id, BudgetOverrideChange(650, 1440, "Restore history fixture"))
        overrides.revoke(admin.principal, admin.principal.member.id, previousOverride.id, "Earlier exception completed")
        overrides.grant(admin.principal, admin.principal.member.id, BudgetOverrideChange(600, 1440, "Restore active exception"))
        val now = System.currentTimeMillis()
        admission.observe(enrolled.account.id, listOf(AccountQuota(enrolled.account.id, "seven_day", .4, now, now + DAY)))
        accounts.configure(AccountMutationActor.System, "default", enrolled.account.id, "Restored Max account", true, 7)
        database.query { Accounts.update({ Accounts.id eq enrolled.account.id }) { it[refreshPending] = true; it[refreshStartedAt] = 0 } }
        SqlModelPolicyStore(database).save(admin.principal, ModelPolicy(allowedEfforts = listOf("provider-default", "high"), maximumThinkingTokens = 1024))
        SqlPacingSettingsStore(database).save(admin.principal, PacingSettings(observationOnly = true))
        SqlRoutingStore(database).save(admin.principal, RouteRule("restore-opus", "claude-opus-*", listOf(RouteTarget("anthropic"), RouteTarget("codex", "gpt-6-astra")), priority = 7))
        return Fixture(admin, memberTokens[0], other.token, activeKey, revokedKey.accessToken, enrolled.account.id, access, refresh, secrets)
    }

    private suspend fun snapshot(database: Database, key: ByteArray, fixture: Fixture): Map<String, Any?> {
        val team = SqlTeamService(database)
        val actor = assertNotNull(team.authenticate(fixture.admin.token))
        val admission = SqlAdmissionStore(database)
        val members = team.members(actor)
        return mapOf(
            "members" to members,
            "capacity" to members.associate { it.id to admission.capacity(actor, it.id) },
            "pools" to admission.poolBudgets(actor), "quotas" to admission.quotas(actor),
            "statistics" to admission.statistics(), "accounts" to SqlAccountStore(database, CredentialCipher(key)).list("default"),
            "modelPolicy" to SqlModelPolicyStore(database).get("default"), "routes" to SqlRoutingStore(database).list("default"),
            "pacingSettings" to SqlPacingSettingsStore(database).get(actor),
            "groups" to GroupServiceImpl(database).list(actor).map { GroupServiceImpl(database).get(actor, it.group.id) },
            "usagePending" to UsageReconciliationServiceImpl(database).pending(actor),
            "usageHistory" to UsageReconciliationServiceImpl(database).history(actor),
            "budgetOverrides" to members.associate { it.id to BudgetOverrideServiceImpl(database).history(actor, it.id) },
            "runs" to AgentRunServiceImpl(database).list(actor, all = true).runs.map { AgentRunServiceImpl(database).get(actor, it.id) },
            "devices" to SqlDeviceService(database).devices(actor), "keys" to SqlServiceKeyService(database).list(actor),
            "audit" to SqlAuditService(database).list(actor),
            "envelopes" to database.query { Accounts.select(Accounts.id, Accounts.credentialEnvelope, Accounts.refreshPending, Accounts.refreshStartedAt).associate {
                it[Accounts.id] to Triple(it[Accounts.credentialEnvelope], it[Accounts.refreshPending], it[Accounts.refreshStartedAt])
            } },
        )
    }

    private suspend fun verifyHttp(database: Database, key: ByteArray, fixture: Fixture) {
        val team = SqlTeamService(database)
        val admission = SqlAdmissionStore(database)
        val accounts = AccountManagement(SqlAccountStore(database, CredentialCipher(key)), object : AccountVerifier {
            override suspend fun verify(teamId: String, credentials: ProviderCredentials): VerifiedProviderAccount = fail("Provider enrollment is forbidden in restore acceptance")
        })
        val gateway = object : GatewayService {
            override suspend fun status() = GatewaySnapshot("restore-acceptance", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit): Unit = fail("Inference is forbidden in restore acceptance")
        }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) {
            configureGateway(gateway, team = team, accounts = accounts, admission = admission,
                modelPolicies = SqlModelPolicyStore(database), routes = SqlRoutingStore(database),
                devices = SqlDeviceService(database), serviceKeys = SqlServiceKeyService(database), audit = SqlAuditService(database), readiness = { database.ready() },
                pacingSettings = SqlPacingSettingsStore(database), forecasts = ForecastServiceImpl(database), groups = GroupServiceImpl(database), runs = AgentRunServiceImpl(database), budgetOverrides = BudgetOverrideServiceImpl(database), reconciliation = UsageReconciliationServiceImpl(database))
        }
        server.start(wait = false)
        try {
            val origin = "http://127.0.0.1:${server.engine.resolvedConnectors().single().port}"
            HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 5_000 }; followRedirects = false }.use { http ->
                suspend fun get(path: String, token: String = fixture.admin.token) = http.get(origin + path) { bearerAuth(token) }
                assertEquals(HttpStatusCode.OK, get("/ready").status)
                for (path in listOf("/api/v1/members", "/api/v1/groups", "/api/v1/runs", "/api/v1/accounts", "/api/v1/capacity", "/api/v1/pools/budgets", "/api/v1/quotas", "/api/v1/forecasts", "/api/v1/usage/pending", "/api/v1/usage/reconciliations", "/api/v1/routes", "/api/v1/policy/models", "/api/v1/policy/pacing", "/api/v1/devices", "/api/v1/audit")) {
                    val response = get(path)
                    assertEquals(HttpStatusCode.OK, response.status, "Restored HTTP access failed: $path")
                    val body = response.bodyAsText()
                    assertTrue(fixture.secrets.none(body::contains), "Restored HTTP response leaked fixture credentials")
                }
                assertTrue(Json.parseToJsonElement(get("/api/v1/policy/pacing").bodyAsText()).jsonObject.getValue("observationOnly").jsonPrimitive.boolean,
                    "Restoration must retain observation mode instead of re-enabling enforcement")
                val forecast = Json.parseToJsonElement(get("/api/v1/forecasts").bodyAsText()).jsonObject
                assertEquals(fixture.accountId, forecast.getValue("account").jsonObject.getValue("id").jsonPrimitive.content)
                assertEquals("seven_day", forecast.getValue("windows").jsonArray.single().jsonObject.getValue("window").jsonPrimitive.content)
                val capacity = Json.parseToJsonElement(get("/api/v1/capacity", fixture.activeKey.accessToken).bodyAsText()).jsonObject
                assertEquals(200, capacity.getValue("chargedAndReservedUnits").jsonPrimitive.long)
                assertEquals(600, capacity.getValue("dailyUnits").jsonPrimitive.long)
                assertEquals(500, capacity.getValue("baseDailyUnits").jsonPrimitive.long)
                val overridesPath = "/api/v1/members/${fixture.admin.principal.member.id}/budget-overrides"
                val history = Json.parseToJsonElement(get(overridesPath).bodyAsText()).jsonObject
                val records = history.getValue("overrides").jsonArray.map { it.jsonObject }
                assertEquals(2, records.size)
                assertTrue(records.any { it.getValue("revocationReason") == JsonPrimitive("Earlier exception completed") })
                assertEquals(history.getValue("activeOverrideId"), capacity.getValue("budgetOverride").jsonObject.getValue("id"))
                assertEquals(HttpStatusCode.Forbidden, get(overridesPath, fixture.activeKey.accessToken).status)
                assertEquals(HttpStatusCode.Forbidden, get(overridesPath, fixture.memberToken).status)
                val pendingUsage = Json.parseToJsonElement(get("/api/v1/usage/pending").bodyAsText()).jsonObject.getValue("requests").jsonArray.single().jsonObject
                assertTrue(capacity.getValue("allowSharedOverflow").jsonPrimitive.boolean)
                assertEquals("restored-overflow", pendingUsage.getValue("sourcePoolId").jsonPrimitive.content)
                assertEquals("req_restore_pending", pendingUsage.getValue("providerRequestId").jsonPrimitive.content)
                val reconciledUsage = Json.parseToJsonElement(get("/api/v1/usage/reconciliations").bodyAsText()).jsonObject.getValue("reconciliations").jsonArray.single().jsonObject
                assertEquals("req_restore_reconciled", reconciledUsage.getValue("providerRequestId").jsonPrimitive.content)
                assertEquals("restored-overflow", reconciledUsage.getValue("sourcePoolId").jsonPrimitive.content)
                assertEquals(70, reconciledUsage.getValue("actualUnits").jsonPrimitive.long)
                assertEquals(HttpStatusCode.Forbidden, get("/api/v1/usage/pending", fixture.activeKey.accessToken).status)
                assertEquals(HttpStatusCode.Forbidden, get("/api/v1/usage/reconciliations", fixture.memberToken).status)
                val run = Json.parseToJsonElement(get("/api/v1/runs", fixture.activeKey.accessToken).bodyAsText()).jsonObject.getValue("runs").jsonArray.single().jsonObject
                assertEquals("ENDED", run.getValue("state").jsonPrimitive.content)
                assertEquals(200, run.getValue("chargedAndReservedUnits").jsonPrimitive.long)
                assertEquals(1, run.getValue("completed").jsonPrimitive.long)
                assertTrue(run.getValue("policy").jsonObject.getValue("paused").jsonPrimitive.boolean)
                val group = Json.parseToJsonElement(get("/api/v1/groups", fixture.memberToken).bodyAsText()).jsonArray.single().jsonObject
                assertEquals(listOf(group.getValue("id")), reconciledUsage.getValue("groupIds").jsonArray.toList())
                assertTrue(group.getValue("canManage").jsonPrimitive.boolean, "Scoped manager role must survive restore")
                assertEquals(200, group.getValue("chargedAndReservedUnits").jsonPrimitive.long)
                assertEquals(group.getValue("id"), capacity.getValue("groups").jsonArray.single().jsonObject.getValue("id"))
                val groupPath = "/api/v1/groups/${group.getValue("id").jsonPrimitive.content}"
                val roster = Json.parseToJsonElement(get(groupPath, fixture.memberToken).bodyAsText()).jsonObject.getValue("members").jsonArray
                val support = roster.single { it.jsonObject.getValue("username").jsonPrimitive.content == "support" }.jsonObject
                assertTrue(support.getValue("paused").jsonPrimitive.boolean, "Admission pause must survive restore")
                assertEquals(HttpStatusCode.OK, http.patch(origin + groupPath + "/members/${support.getValue("memberId").jsonPrimitive.content}/pause") {
                    bearerAuth(fixture.memberToken); contentType(ContentType.Application.Json); setBody("""{"paused":false}""")
                }.status, "Restored manager must retain scoped control")
                assertEquals(HttpStatusCode.Forbidden, get("/api/v1/groups", fixture.activeKey.accessToken).status)
                assertEquals(HttpStatusCode.Forbidden, get("/api/v1/members", fixture.activeKey.accessToken).status)
                assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/auth/me", fixture.revokedToken).status)
                assertEquals(HttpStatusCode.Unauthorized, get("/api/v1/auth/me", fixture.revokedKey).status)
            }
        } finally { server.stop(0, 5_000) }
    }

    /** Use the database vendor's dump/restore tools; application/test fixture writes use Exposed. */
    private fun transfer(container: MySQLContainer, backup: Path, diagnostic: Path, restore: Boolean) {
        val executable = if (restore) "mysql" else "mysqldump"
        val arguments = mutableListOf("docker", "exec", "-i", "-e", "MYSQL_PWD", container.containerId, executable, "--user=${container.username}")
        if (restore) arguments += "--binary-mode=1" else arguments += listOf("--single-transaction", "--no-tablespaces", "--set-gtid-purged=OFF", "--hex-blob", "--skip-comments", "--column-statistics=0", "--order-by-primary", "--skip-extended-insert")
        arguments += container.databaseName
        val builder = ProcessBuilder(arguments).redirectError(diagnostic.toFile())
        builder.environment()["MYSQL_PWD"] = container.password
        if (restore) builder.redirectInput(backup.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD) else builder.redirectOutput(backup.toFile())
        val process = builder.start()
        try {
            assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Owned database backup/restore command timed out")
            assertEquals(0, process.exitValue(), "Owned database backup/restore command failed; diagnostics withheld")
        } finally {
            if (process.isAlive) { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
        }
    }
    private fun databaseContainer() = MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withUsername("teamcode")
        .withPassword(secret()).withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g"))
    private fun privateFile(path: Path): Path = Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    private fun secret() = Base64.getUrlEncoder().withoutPadding().encodeToString(SecureRandom().generateSeed(32))
    private class Fixture(val admin: LoginSession, val memberToken: String, val revokedToken: String, val activeKey: CreatedServiceKey,
        val revokedKey: String, val accountId: String, val access: String, val refresh: String, val secrets: List<String>)
    companion object { private const val DAY = 86_400_000L }
}
