package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.mcp.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Gateway admission, revocation, limits and journal against real MySQL with two independent replica pools. */
class McpAccessPersistenceTest {
    @Test fun admissionRevalidatesAssignmentsPoliciesAndSharedLimitsAcrossReplicas() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                a.query { assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(McpToolPolicies, McpCalls, withLogs = false), "Migration matches the Exposed tables") }
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                team.changePassword(team.login("admin", initial, "MCP").principal, initial, "mcp administrator password")
                val admin = team.login("admin", "mcp administrator password", "MCP").principal
                val created = team.createMember(admin, "alice", Role.MEMBER)
                team.changePassword(team.login("alice", created.temporaryPassword, "MCP").principal, created.temporaryPassword, "alice replacement password")
                val alice = team.login("alice", "alice replacement password", "MCP").principal
                val cipher = CredentialCipher(ByteArray(32) { 43 })
                val variants = SqlVariantService(b, cipher)
                val first = SqlMcpAccessStore(a); val second = SqlMcpAccessStore(b)
                val tracker = variants.create(admin, NewVariant(VariantKind.MCP, "tracker", "team", VariantBinding.SHARED, "https://tracker.example.test/mcp", sharedSecret = SecretValue("synthetic-credential")))
                val env = variants.create(admin, NewVariant(VariantKind.ENV, "TOKEN", "env", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-env")))
                val limits = McpLimits(memberConcurrentPerServer = 2, serverConcurrent = 3, memberCallsPerMinute = 4, serverCallsPerMinute = 5)
                suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)

                failure("mcp_tool_denied") { first.admit(alice, "tracker", tracker.id, McpOperation.CALL, "search", limits) }
                variants.assign(admin, alice.member.id, tracker.id)
                val lease = first.admit(alice, "tracker", tracker.id, McpOperation.CALL, "search", limits)
                first.finish(lease, McpOutcome.OK, 12, 34)
                first.finish(lease, McpOutcome.TIMEOUT, 99, 99) // Idempotent: an already settled row keeps its outcome.

                // A tool policy written on one replica applies to the next admission on the other.
                failure("invalid_tool_policy") { second.setToolPolicy(admin, env.id, McpToolPolicy(McpToolMode.ALLOWLIST, setOf("x"))) }
                failure("forbidden") { second.setToolPolicy(alice, tracker.id, McpToolPolicy.ALL) }
                assertEquals(McpToolPolicy.ALL, second.toolPolicy(admin, tracker.id))
                second.setToolPolicy(admin, tracker.id, McpToolPolicy(McpToolMode.ALLOWLIST, setOf("search")))
                assertEquals(mapOf(tracker.id to McpToolPolicy(McpToolMode.ALLOWLIST, setOf("search"))), first.policies("default", listOf(tracker.id)))
                failure("mcp_tool_denied") { first.admit(alice, "tracker", tracker.id, McpOperation.CALL, "delete", limits) }
                first.admit(alice, "tracker", tracker.id, McpOperation.LIST, null, limits).also { first.finish(it, McpOutcome.OK, 1, 1) }

                // Concurrency and rate are shared: two held slots on replica A block a third on replica B.
                val held = listOf(first.admit(alice, "tracker", tracker.id, McpOperation.CALL, "search", limits), second.admit(alice, "tracker", tracker.id, McpOperation.CALL, "search", limits))
                failure("mcp_member_concurrency") { second.admit(alice, "tracker", tracker.id, McpOperation.CALL, "search", limits) }
                held.forEach { first.finish(it, McpOutcome.OK, 1, 1) }
                failure("mcp_member_rate") { second.admit(alice, "tracker", tracker.id, McpOperation.CALL, "search", limits) }
                val roomy = limits.copy(memberCallsPerMinute = 10, serverCallsPerMinute = 10)
                first.finish(first.admit(alice, "tracker", tracker.id, McpOperation.CALL, "search", roomy), McpOutcome.OK, 1, 1)
                variants.assign(admin, admin.member.id, tracker.id)
                // The administrator has no recent call of their own, but the team's five calls fill the server's rate.
                failure("mcp_server_rate") { second.admit(admin, "tracker", tracker.id, McpOperation.CALL, "search", limits.copy(memberCallsPerMinute = 5, serverCallsPerMinute = 5)) }

                // Revocation, exclusion and a disabled member are refused at the next admission.
                variants.unassign(admin, alice.member.id, VariantTarget(VariantKind.MCP, "tracker"))
                failure("mcp_tool_denied") { first.admit(alice, "tracker", tracker.id, McpOperation.LIST, null, limits.copy(memberCallsPerMinute = 50, serverCallsPerMinute = 50)) }
                variants.exclude(admin, admin.member.id, VariantTarget(VariantKind.MCP, "tracker"))
                failure("mcp_tool_denied") { first.admit(admin, "tracker", tracker.id, McpOperation.LIST, null, limits.copy(memberCallsPerMinute = 50, serverCallsPerMinute = 50)) }
                team.updateMember(admin, alice.member.id, Role.MEMBER, true)
                failure("authentication_required") { first.admit(alice, "tracker", tracker.id, McpOperation.LIST, null, limits) }
                team.updateMember(admin, alice.member.id, Role.MEMBER, false)
                val restored = team.login("alice", "alice replacement password", "MCP").principal
                failure("credential_scope") { first.admit(restored.copy(serviceScopes = setOf(ServiceScope.INFERENCE)), "tracker", tracker.id, McpOperation.LIST, null, limits) }

                // Refusals are journaled within the member's rate and dropped past it, so storage stays bounded.
                repeat(6) { first.denied(restored, "", "probe", limits) }
                val usage = second.usage(admin, 1)
                assertEquals(4L, usage.single { it.memberId == alice.member.id && it.server == "" }.denied)
                val aliceTracker = usage.single { it.memberId == alice.member.id && it.server == "tracker" }
                assertEquals(listOf(4L, 1L, 0L, 0L, 38L), listOf(aliceTracker.calls, aliceTracker.lists, aliceTracker.errors, aliceTracker.denied, aliceTracker.responseBytes))
                assertEquals(listOf(alice.member.id), second.usage(restored, 1).map { it.memberId }.distinct(), "Members only see their own usage")
                failure("invalid_window") { second.usage(admin, 31) }
                failure("forbidden") { second.calls(restored, null) }
                val journal = second.calls(admin, null)
                assertEquals(9, journal.calls.size)
                assertNull(journal.nextBeforeId)
                assertTrue(journal.calls.zipWithNext().all { (x, y) -> x.id > y.id })
                assertEquals(McpOutcome.OK, journal.calls.single { it.id == lease.id }.outcome)
                a.query {
                    val stored = McpCalls.selectAll().flatMap { row -> McpCalls.columns.map { row[it]?.toString().orEmpty() } }
                    assertTrue(stored.none { it.contains("synthetic") || it.contains("example.test") }, "The journal stores metadata only")
                    assertTrue(AuditEvents.selectAll().any { it[AuditEvents.action] == "mcp.tool_policy_updated" && it[AuditEvents.subjectId] == tracker.id })
                    McpCalls.update({ McpCalls.id eq lease.id }) { it[startedAt] = 1 }
                }
                first.maintain()
                assertTrue(second.calls(admin, null).calls.none { it.id == lease.id }, "Retention removes old rows")
                variants.delete(admin, tracker.id)
                assertTrue(first.policies("default", listOf(tracker.id)).isEmpty(), "Deleting a variant deletes its policy")
            } }
        }
    }
}
