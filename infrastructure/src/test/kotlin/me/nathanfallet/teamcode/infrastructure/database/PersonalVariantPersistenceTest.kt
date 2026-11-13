package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.variants.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

/** A member's own environment values and remote MCP servers against real MySQL. */
class PersonalVariantPersistenceTest {
    private suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)

    @Test fun aPersonalVariantIsAssignedToItsOwnerOnlyAndNeverReadableByOthers() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { db ->
                db.migrate()
                val team = SqlTeamService(db)
                val initial = team.bootstrap()!!
                team.changePassword(team.login("admin", initial, "fixture").principal, initial, "personal variant admin password")
                val admin = team.login("admin", "personal variant admin password", "fixture").principal
                suspend fun member(name: String): Principal {
                    val created = team.createMember(admin, name, Role.MEMBER)
                    team.changePassword(team.login(name, created.temporaryPassword, "fixture").principal, created.temporaryPassword, "$name personal password")
                    return team.login(name, "$name personal password", "fixture").principal
                }
                val vivien = member("vivien"); val chiheb = member("chiheb")
                val cipher = CredentialCipher(ByteArray(32) { 7 })
                val service = SqlVariantService(db, cipher)
                val resolve = ResolveEffectiveVariantsUseCase(service)

                // The owner creates an environment value and a remote MCP server; both are selected for them at once.
                val env = service.createPersonal(vivien, NewVariant(VariantKind.ENV, "OPENROUTER_API_KEY", "mine", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-vivien-value")))
                val mcp = service.createPersonal(vivien, NewVariant(VariantKind.MCP, "notes", "mine", VariantBinding.SHARED, "https://mcp.example.test/notes", sharedSecret = SecretValue("synthetic-vivien-token")))
                assertEquals(vivien.member.id, env.ownerId)
                val own = resolve(vivien.teamId, vivien.member.id)
                assertEquals(listOf("OPENROUTER_API_KEY"), own.environment.map { it.name })
                assertEquals(listOf("notes"), own.mcpServers.map { it.name })
                assertEquals(emptyList(), resolve(chiheb.teamId, chiheb.member.id).environment)
                assertEquals(emptyList(), resolve(chiheb.teamId, chiheb.member.id).mcpServers)
                assertEquals(2, service.mine(vivien).size); assertEquals(0, service.mine(chiheb).size)

                // Another member may use the same target and name for their own: names are unique per owner.
                service.createPersonal(chiheb, NewVariant(VariantKind.ENV, "OPENROUTER_API_KEY", "mine", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-chiheb-value")))
                assertEquals("synthetic-chiheb-value", resolve(chiheb.teamId, chiheb.member.id).environment.single().value.reveal())
                assertEquals("synthetic-vivien-value", resolve(vivien.teamId, vivien.member.id).environment.single().value.reveal())

                // Rules at creation.
                failure("slot_taken") { service.createPersonal(vivien, NewVariant(VariantKind.ENV, "OPENROUTER_API_KEY", "second", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-other-value"))) }
                failure("hosted_not_allowed") { service.createPersonal(vivien, NewVariant(VariantKind.MCP, "hosted", "mine", VariantBinding.SHARED, sharedSecret = StdioEnvironment.encode(mapOf("TOKEN" to "synthetic-token-1")), transport = McpTransport.STDIO, command = StdioCommand(StdioLauncher.NPM, "pkg@1.0.0"))) }
                failure("invalid_variant") { service.createPersonal(vivien, NewVariant(VariantKind.ENV, "OTHER_KEY", "mine", VariantBinding.PER_MEMBER)) }
                failure("invalid_variant") { service.createPersonal(vivien, NewVariant(VariantKind.ENV, "PATH", "mine", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-value"))) }
                failure("forbidden") { service.promote(vivien, env.id) }

                // The owner alone manages it.
                failure("not_found") { service.updatePersonal(chiheb, env.id, VariantUpdate(1, "renamed")) }
                failure("not_found") { service.deletePersonal(chiheb, env.id) }
                val rotated = service.updatePersonal(vivien, env.id, VariantUpdate(1, "mine", sharedSecret = SecretValue("synthetic-rotated-value")))
                assertEquals(2, rotated.version)
                assertEquals("synthetic-rotated-value", resolve(vivien.teamId, vivien.member.id).environment.single().value.reveal())
                failure("variant_conflict") { service.updatePersonal(vivien, env.id, VariantUpdate(1, "mine")) }

                // Administrators see the definition and the owner, never the secret, and cannot assign or edit it.
                assertTrue(service.list(admin).none { it.ownerId != null }, "the team list holds team variants only")
                val listed = service.personal(admin).single { it.id == mcp.id }
                assertEquals(vivien.member.id, listed.ownerId); assertEquals("https://mcp.example.test/notes", listed.endpoint); assertTrue(listed.hasSharedSecret)
                failure("variant_personal") { service.assign(admin, chiheb.member.id, mcp.id) }
                failure("variant_personal") { service.bulkAssign(admin, mcp.id) }
                failure("variant_personal") { service.update(admin, mcp.id, VariantUpdate(1, "x")) }

                // An administrator's exclusion stops a personal variant for that slot.
                service.exclude(admin, vivien.member.id, VariantTarget(VariantKind.ENV, "BLOCKED_KEY"))
                failure("variant_excluded") { service.createPersonal(vivien, NewVariant(VariantKind.ENV, "BLOCKED_KEY", "mine", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-value"))) }

                // Team variants are unaffected: they still count to the team limit only and keep their own names.
                val teamVariant = service.create(admin, NewVariant(VariantKind.ENV, "OPENROUTER_API_KEY", "mine", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-team-value")))
                assertNull(teamVariant.ownerId)

                // Promotion: a team variant; its owner keeps the selection, and a name clash with a team variant is refused.
                failure("variant_exists") { service.promote(admin, env.id) }
                val promoted = service.promote(admin, mcp.id)
                assertNull(promoted.ownerId)
                failure("variant_not_personal") { service.promote(admin, mcp.id) }
                assertEquals(listOf("notes"), resolve(vivien.teamId, vivien.member.id).mcpServers.map { it.name })
                assertEquals(0, service.mine(vivien).count { it.id == mcp.id })

                // Deletion removes the variant and its assignment.
                service.deletePersonal(vivien, env.id)
                assertEquals(emptyList(), resolve(vivien.teamId, vivien.member.id).environment)
                assertTrue(db.query { Variants.selectAll().where { Variants.id eq env.id }.empty() })

                // The limit counts a member's own variants only.
                for (index in 2..VariantService.PERSONAL_LIMIT.toInt()) service.createPersonal(chiheb, NewVariant(VariantKind.ENV, "CHIHEB_KEY_$index", "mine", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-value-$index")))
                failure("variant_limit") { service.createPersonal(chiheb, NewVariant(VariantKind.ENV, "CHIHEB_KEY_EXTRA", "mine", VariantBinding.SHARED, sharedSecret = SecretValue("synthetic-extra-value"))) }
            }
        }
    }
}
