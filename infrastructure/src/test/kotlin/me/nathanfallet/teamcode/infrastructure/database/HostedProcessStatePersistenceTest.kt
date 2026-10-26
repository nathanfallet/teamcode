package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

/** `mcp_stdio_states` and the `mcp.*` team events against real MySQL with two replica pools. */
class HostedProcessStatePersistenceTest {
    @Test fun transitionsAreRecordedOnceWithTheirEventsAndFollowTheirVariant() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                a.query { assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(McpStdioStates, withLogs = false), "The reviewed migration matches the Exposed table") }
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                team.changePassword(team.login("admin", initial, "States").principal, initial, "states administrator password")
                val admin = team.login("admin", "states administrator password", "States").principal
                val alice = team.createMember(admin, "alice", Role.MEMBER).let { created ->
                    team.changePassword(team.login("alice", created.temporaryPassword, "States").principal, created.temporaryPassword, "alice replacement password")
                    created.member.id
                }
                val variants = SqlVariantService(a, CredentialCipher(ByteArray(32) { 44 }), { setOf(StdioLauncher.NPM) })
                val command = StdioCommand(StdioLauncher.NPM, "@acme/tracker-mcp@1.4.2", listOf("--token", "\${TRACKER_TOKEN}"))
                val shared = variants.create(admin, NewVariant(VariantKind.MCP, "tracker", "team", VariantBinding.SHARED, null, emptyMap(),
                    StdioEnvironment.encode(mapOf("TRACKER_TOKEN" to "synthetic-tracker-shared")), McpTransport.STDIO, command))
                val personal = variants.create(admin, NewVariant(VariantKind.MCP, "notes", "own", VariantBinding.PER_MEMBER, null, emptyMap(), null, McpTransport.STDIO,
                    command.copy(args = listOf("--token", "\${NOTES_TOKEN}"))))
                variants.assign(admin, alice, shared.id)
                variants.assign(admin, alice, personal.id, StdioEnvironment.encode(mapOf("NOTES_TOKEN" to "synthetic-notes-alice")))
                val assignments = variants.hostedMcpAssignments(100)!!.associateBy { it.variantId }
                val first = SqlHostedProcessStateStore(a); val second = SqlHostedProcessStateStore(b)
                val sharedAssignment = assignments.getValue(shared.id)
                val personalAssignment = assignments.getValue(personal.id)
                fun status(assignment: HostedMcpAssignment, state: String, failure: String? = null) = HostedProcessStatus(assignment.key, state, "npm", command.pkg, lastFailure = failure)
                suspend fun events() = a.query {
                    TeamEvents.selectAll().where { TeamEvents.teamId eq "default" }.orderBy(TeamEvents.seq to SortOrder.ASC)
                        .map { Triple(it[TeamEvents.type], it[TeamEvents.ownerId], Json.parseToJsonElement(it[TeamEvents.data]).jsonObject) }
                }

                // Both replicas compute the same failure from the same read: one row, one event.
                val failed = HostedProcessStateRules.change(personalAssignment, status(personalAssignment, "failed", "start_failed"), null, 1_000)!!
                val ready = HostedProcessStateRules.change(sharedAssignment, status(sharedAssignment, "ready"), null, 1_000)!!
                assertEquals(2, first.apply(listOf(failed, ready), emptyList()))
                assertEquals(0, second.apply(listOf(failed, ready), emptyList()), "A replica that read the same state applies nothing")
                assertEquals(listOf(Triple("mcp.stdio.failed", alice, "start_failed")), events().map { Triple(it.first, it.second, it.third["failure"]!!.jsonPrimitive.content) })
                assertEquals("notes", events().single().third["server"]!!.jsonPrimitive.content)
                assertEquals(alice, events().single().third["memberId"]!!.jsonPrimitive.content)

                // The member's list reads their own row and the team's shared one, never another team's.
                val mine = second.records("default", listOf(shared.id, personal.id), alice).associateBy { it.variantId }
                assertEquals("" to "ready", mine.getValue(shared.id).let { it.memberId to it.state })
                assertEquals(alice to "start_failed", mine.getValue(personal.id).let { it.memberId to it.failure })
                assertEquals(emptyList(), second.records("other-team", listOf(shared.id, personal.id), alice))
                assertEquals(2, second.records(setOf("default"), 10)!!.size)
                assertEquals(setOf("default"), second.teamsWithStates(10))
                assertNull(second.teamsWithStates(0), "More teams than the limit: no set")
                assertNull(second.records(setOf("default"), 1), "More rows than the limit: no list")

                // A stale previous row (the state moved on in between) is not overwritten.
                val stored = mine.getValue(personal.id)
                val recovered = HostedProcessStateRules.change(personalAssignment, status(personalAssignment, "ready"), stored, 2_000)!!
                assertEquals(HostedProcessEvent.RECOVERED, recovered.event)
                val stale = recovered.copy(previous = stored.copy(changedAt = 1))
                assertEquals(0, first.apply(listOf(stale), emptyList()))
                assertEquals(1, second.apply(listOf(recovered), emptyList()))
                val recoveredEvent = events().last()
                assertEquals("mcp.stdio.recovered", recoveredEvent.first)
                assertEquals("start_failed", recoveredEvent.third["failure"]!!.jsonPrimitive.content, "The recovery names what it recovered from")

                // A shared process's event belongs to nobody: only team readers see it.
                val sharedFailed = HostedProcessStateRules.change(sharedAssignment, status(sharedAssignment, "backoff", "crashed"), first.records(setOf("default"), 10)!!.single { it.memberId == "" }, 3_000)!!
                assertEquals(1, first.apply(listOf(sharedFailed), emptyList()))
                assertEquals("" to "tracker", events().last().let { it.second to it.third["server"]!!.jsonPrimitive.content })
                val feed = SqlTeamEventStore(a)
                val aliceAudience = TeamEventAudience(alice, sessions = true, runs = true, teamSessions = false, teamRuns = false)
                assertEquals(listOf("mcp.stdio.failed", "mcp.stdio.recovered"), feed.read("default", aliceAudience, 0, null, 100).events.map { it.type.wire }, "A member sees their own process's events")
                val teamAudience = aliceAudience.copy(memberId = admin.member.id, teamRuns = true)
                assertEquals(3, feed.read("default", teamAudience, 0, null, 100).events.size)

                // Unreachable: one event per team; a restart is journaled without a value.
                second.publishUnreachable(setOf("default"), HostedMcpSweep.REFUSED)
                assertEquals(Triple("mcp.unreachable", "", "refused"), events().last().let { Triple(it.first, it.second, it.third["reason"]!!.jsonPrimitive.content) })
                first.recordRestart(admin, personal.id, alice)
                assertEquals("${personal.id}/$alice", a.query { AuditEvents.selectAll().where { AuditEvents.action eq "mcp.stdio.restarted" }.single()[AuditEvents.subjectId] })

                // Removals delete only the row they read; deleting a variant drops its state.
                val rows = first.records(setOf("default"), 10)!!
                first.apply(emptyList(), listOf(rows.single { it.variantId == personal.id }.copy(changedAt = 1)))
                assertEquals(2, first.records(setOf("default"), 10)!!.size, "A removal of a row that changed meanwhile deletes nothing")
                first.apply(emptyList(), listOf(rows.single { it.variantId == personal.id }))
                assertEquals(listOf(shared.id), first.records(setOf("default"), 10)!!.map { it.variantId })
                variants.unassign(admin, alice, VariantTarget(VariantKind.MCP, "tracker"))
                variants.delete(admin, shared.id)
                assertEquals(emptyList(), first.records(setOf("default"), 10), "The foreign key removes a deleted variant's state")
                assertEquals(emptySet(), first.teamsWithStates(10))
            } }
        }
    }
}
