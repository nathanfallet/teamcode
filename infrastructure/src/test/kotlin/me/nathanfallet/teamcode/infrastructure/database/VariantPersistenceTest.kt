package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** Roadmap acceptance for environment/MCP variants against real MySQL and two independent replica pools. */
class VariantPersistenceTest {
    private val env = VariantKind.ENV
    private val mcp = VariantKind.MCP

    @Test fun assignmentsResolvePerMemberWithExclusionsAndImmediateChangesAcrossReplicas() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                a.query { assertEquals(emptyList(), MigrationUtils.statementsRequiredForDatabaseMigration(Variants, VariantAssignments, VariantExclusions, withLogs = false), "Migration matches the Exposed tables") }
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                team.changePassword(team.login("admin", initial, "Variants").principal, initial, "variant administrator password")
                val admin = team.login("admin", "variant administrator password", "Variants").principal
                val cipher = CredentialCipher(ByteArray(32) { 41 })
                val first = SqlVariantService(a, cipher); val second = SqlVariantService(b, cipher)
                val resolveA = ResolveEffectiveVariantsUseCase(first); val resolveB = ResolveEffectiveVariantsUseCase(second)
                val pending = team.createMember(admin, "dave", Role.MEMBER)
                val early = first.create(admin, NewVariant(env, "EARLY_TOKEN", "early", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-early")))
                first.assign(admin, pending.member.id, early.id)
                assertEquals(EffectiveVariants.none(pending.member.id), resolveA("default", pending.member.id), "A member with a temporary password receives nothing")
                suspend fun member(name: String): String {
                    val created = team.createMember(admin, name, Role.MEMBER)
                    team.changePassword(team.login(name, created.temporaryPassword, "Variants").principal, created.temporaryPassword, "$name replacement password")
                    return created.member.id
                }
                val dave = pending.member.id.also { team.changePassword(team.login("dave", pending.temporaryPassword, "Variants").principal, pending.temporaryPassword, "dave replacement password") }
                assertEquals("synthetic-early", resolveA("default", dave).environment.single().value.reveal())
                team.updateMember(admin, dave, Role.MEMBER, true)
                second.delete(admin, early.id)
                val alice = member("alice"); val bob = member("bob"); val carol = member("carol")
                suspend fun envOf(member: String) = resolveB("default", member).environment.associate { it.name to it.value.reveal() }
                suspend fun mcpOf(member: String) = resolveA("default", member).mcpServers.associateBy { it.name }

                // Two members receive two different variants of the same variable and of the same MCP server.
                val orgA = first.create(admin, NewVariant(env, "GITHUB_TOKEN", "org-a", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-github-a")))
                val orgB = first.create(admin, NewVariant(env, "GITHUB_TOKEN", "org-b", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-github-b")))
                val teamMcp = first.create(admin, NewVariant(mcp, "tracker", "team", VariantBinding.SHARED, "https://mcp.example.test/team", mapOf("workspace" to "core"), SecretValue("synthetic-team-credential")))
                val personalMcp = second.create(admin, NewVariant(mcp, "tracker", "personal", VariantBinding.PER_MEMBER, "https://mcp.example.test/personal"))
                failure("variant_exists") { second.create(admin, NewVariant(env, "GITHUB_TOKEN", "org-a", VariantBinding.SHARED, sharedSecret = SecretValue("other"))) }
                first.assign(admin, alice, orgA.id); second.assign(admin, bob, orgB.id)
                first.assign(admin, alice, teamMcp.id)
                failure("invalid_variant") { first.assign(admin, bob, personalMcp.id) }
                failure("invalid_variant") { first.assign(admin, bob, teamMcp.id, SecretValue("not for a shared variant")) }
                second.assign(admin, bob, personalMcp.id, SecretValue("synthetic-bob-delegated"))
                assertEquals(mapOf("GITHUB_TOKEN" to "synthetic-github-a"), envOf(alice))
                assertEquals(mapOf("GITHUB_TOKEN" to "synthetic-github-b"), envOf(bob))
                assertEquals("https://mcp.example.test/team" to "synthetic-team-credential", mcpOf(alice).getValue("tracker").let { it.endpoint to it.credential?.reveal() })
                assertEquals(mapOf("workspace" to "core"), mcpOf(alice).getValue("tracker").config)
                assertEquals("https://mcp.example.test/personal" to "synthetic-bob-delegated", mcpOf(bob).getValue("tracker").let { it.endpoint to it.credential?.reveal() })

                // A third member without an assignment receives nothing, even though shared variants exist.
                assertEquals(EffectiveVariants.none(carol), resolveA("default", carol))

                // Bulk assignment skips excluded members and keeps direct selections.
                val npmShared = first.create(admin, NewVariant(env, "NPM_TOKEN", "shared", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-npm-shared")))
                val npmOwn = first.create(admin, NewVariant(env, "NPM_TOKEN", "own", VariantBinding.PER_MEMBER))
                second.assign(admin, bob, npmOwn.id, SecretValue("synthetic-npm-bob"))
                first.exclude(admin, carol, VariantTarget(env, "NPM_TOKEN"))
                failure("invalid_variant") { first.bulkAssign(admin, npmOwn.id) }
                assertEquals(BulkAssignment(assigned = 2, keptDirect = 1, excluded = 1), second.bulkAssign(admin, npmShared.id))
                failure("invalid_variant") { first.create(admin, NewVariant(env, "LD_PRELOAD", "x", VariantBinding.SHARED, sharedSecret = SecretValue("x"))) }
                assertEquals("synthetic-npm-shared", envOf(alice)["NPM_TOKEN"])
                assertEquals("synthetic-npm-shared", envOf(admin.member.id)["NPM_TOKEN"])
                assertEquals("synthetic-npm-bob", envOf(bob)["NPM_TOKEN"])
                assertEquals(EffectiveVariants.none(carol), resolveA("default", carol))
                failure("variant_excluded") { second.assign(admin, carol, npmShared.id) }
                second.bulkAssign(admin, npmShared.id)
                assertEquals(EffectiveVariants.none(carol), resolveA("default", carol), "An exclusion survives a repeated bulk assignment")
                // Excluding an assigned member removes access at once; removing the exclusion does not grant anything back.
                first.exclude(admin, alice, VariantTarget(env, "NPM_TOKEN"))
                assertNull(envOf(alice)["NPM_TOKEN"])
                second.include(admin, alice, VariantTarget(env, "NPM_TOKEN"))
                assertNull(envOf(alice)["NPM_TOKEN"])

                // Changes on one replica are visible on the next resolution through the other.
                val rotated = first.update(admin, orgA.id, VariantUpdate(orgA.version, "org-a", sharedSecret = SecretValue("synthetic-github-rotated")))
                assertEquals(2, rotated.version)
                assertEquals("synthetic-github-rotated", envOf(alice)["GITHUB_TOKEN"])
                assertEquals(2, resolveB("default", alice).environment.single { it.name == "GITHUB_TOKEN" }.version)
                failure("variant_conflict") { second.update(admin, orgA.id, VariantUpdate(orgA.version, "org-a")) }
                assertEquals("Org-A", second.update(admin, orgA.id, VariantUpdate(rotated.version, "Org-A")).name, "A case-only rename does not conflict with itself")
                failure("variant_exists") { second.update(admin, orgA.id, VariantUpdate(rotated.version + 1, "ORG-B")) }
                val beforeRotation = resolveA("default", bob).mcpServers.single().assignedAt
                second.assign(admin, bob, personalMcp.id, SecretValue("synthetic-bob-delegated"))
                assertTrue(resolveA("default", bob).mcpServers.single().assignedAt >= beforeRotation)
                val cleared = first.update(admin, teamMcp.id, VariantUpdate(teamMcp.version, "team", mapOf("workspace" to "core"), clearSharedSecret = true))
                assertFalse(cleared.hasSharedSecret)
                assertEquals("https://mcp.example.test/team", cleared.endpoint)
                failure("invalid_variant") { first.update(admin, orgB.id, VariantUpdate(orgB.version, "org-b", clearSharedSecret = true)) }
                assertEquals("synthetic-github-rotated", envOf(alice)["GITHUB_TOKEN"], "A metadata update without a secret keeps the stored value")
                second.assign(admin, alice, orgB.id)
                assertEquals("synthetic-github-b", envOf(alice)["GITHUB_TOKEN"])
                first.unassign(admin, alice, VariantTarget(env, "GITHUB_TOKEN"))
                assertNull(envOf(alice)["GITHUB_TOKEN"])
                team.updateMember(admin, bob, Role.MEMBER, true)
                assertEquals(EffectiveVariants.none(bob), resolveA("default", bob), "A disabled member receives nothing")
                team.updateMember(admin, bob, Role.MEMBER, false)
                assertEquals("synthetic-bob-delegated", mcpOf(bob).getValue("tracker").credential?.reveal())
                second.delete(admin, personalMcp.id)
                assertNull(mcpOf(bob)["tracker"])
                assertEquals(EffectiveVariants.none(alice), resolveA("other-team", alice), "Resolution is team-scoped")

                // Administrator views carry metadata only.
                val views = first.assignments(admin)
                assertEquals(listOf("admin", "alice", "bob", "carol", "dave"), views.map { it.username })
                assertEquals(listOf(VariantTarget(env, "NPM_TOKEN")), views.single { it.memberId == carol }.exclusions)
                assertEquals(AssignmentSource.BULK, views.single { it.memberId == admin.member.id }.assignments.single().source)
                assertTrue(views.single { it.memberId == bob }.assignments.single { it.target.name == "NPM_TOKEN" }.hasMemberSecret)
                assertEquals(views.filter { it.memberId == bob }, second.assignments(admin, bob))
                failure("not_found") { second.assignments(admin, "00000000-0000-0000-0000-000000000000") }
                val metadata = (views.toString() + first.list(admin).toString())
                for (secret in listOf("synthetic-github", "synthetic-team-credential", "synthetic-npm", "synthetic-bob")) assertFalse(metadata.contains(secret), secret)

                // Values are encrypted at rest, bound to their row, and absent from the audit journal.
                a.query {
                    val stored = Variants.selectAll().map { it[Variants.sharedSecret].orEmpty() } + VariantAssignments.selectAll().map { it[VariantAssignments.memberSecret].orEmpty() }
                    assertTrue(stored.none { it.contains("synthetic") })
                    val audit = AuditEvents.selectAll().where { AuditEvents.action like "variant.%" }.map { listOf(it[AuditEvents.action], it[AuditEvents.subjectId]) }
                    assertTrue(audit.map { it[0] }.containsAll(listOf("variant.created", "variant.updated", "variant.deleted", "variant.assigned", "variant.unassigned", "variant.bulk_assigned", "variant.excluded", "variant.included")))
                    assertTrue(audit.flatten().none { it.contains("synthetic") || it.contains("example.test") })
                    assertTrue(audit.any { it == listOf("variant.assigned", admin.member.id) }, "Bulk assignment journals each affected member")
                    val envelope = Variants.select(Variants.sharedSecret).where { Variants.id eq npmShared.id }.single()[Variants.sharedSecret]
                    Variants.update({ Variants.id eq orgB.id }) { it[sharedSecret] = envelope }
                }
                // One unreadable envelope withholds only its own slot.
                val degraded = resolveA("default", bob)
                assertEquals(listOf(VariantTarget(env, "GITHUB_TOKEN")), degraded.unavailable)
                assertEquals(mapOf("NPM_TOKEN" to "synthetic-npm-bob"), degraded.environment.associate { it.name to it.value.reveal() })

                // Only current interactive administrators manage variants.
                failure("forbidden") { first.list(admin.copy(member = admin.member.copy(role = Role.MEMBER))) }
                val key = SqlServiceKeyService(a).create(admin, admin.member.id, "Variant reader", setOf(ServiceScope.USAGE_READ), 1)
                failure("credential_scope") { first.list(team.authenticate(key.accessToken)!!) }
                failure("authentication_required") { first.list(admin.copy(teamId = "other-team")) }
                a.query { Members.update({ Members.id eq admin.member.id }) { it[role] = "MEMBER"; it[isOwner] = false } }
                failure("authentication_required") { second.assign(admin, carol, orgA.id) }
            } }
        }
    }
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
}
