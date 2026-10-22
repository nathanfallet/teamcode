package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class MemberEnrollmentPersistenceTest {
    /** The access token names the provider account the synthetic verifier reports, so tests choose X, Y or Z. */
    private class Verifier : AccountVerifier {
        var displayName = "Claude Max"
        var gate: (suspend () -> Unit)? = null
        override suspend fun verify(teamId: String, credentials: ProviderCredentials): VerifiedProviderAccount {
            gate?.invoke()
            val account = credentials.accessToken.substringBefore('|')
            return VerifiedProviderAccount(AccountIdentity(teamId, "anthropic", account, "org-$account"), displayName, "$account@example.com")
        }
    }
    private var operation = 0
    private fun op() = "member-enrollment-${(++operation).toString().padStart(4, '0')}"
    private fun credentials(account: String, attempt: Int = 0) = ProviderCredentials("$account|access-$attempt", "refresh-$account-$attempt", Long.MAX_VALUE)

    @Test fun membersAddAndReconnectAccountsOnlyWhenAllowedAndNeverTakeOverAHealthyOne() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate()
                val team = SqlTeamService(a)
                suspend fun ready(username: String, temporary: String): Principal {
                    val first = team.login(username, temporary, "Member enrollment fixture")
                    team.changePassword(first.principal, temporary, "$username replacement password")
                    return team.login(username, "$username replacement password", "Member enrollment fixture").principal
                }
                val admin = ready("admin", team.bootstrap()!!)
                val ada = ready("ada", team.createMember(admin, "ada", Role.MEMBER).temporaryPassword)
                val grace = ready("grace", team.createMember(admin, "grace", Role.MEMBER).temporaryPassword)
                val cipher = CredentialCipher(ByteArray(32) { 17 })
                val verifier = Verifier()
                val accounts = AccountManagement(SqlAccountStore(a, cipher), verifier)
                val other = AccountManagement(SqlAccountStore(b, cipher), verifier)
                suspend fun add(actor: Principal, account: String, attempt: Int = 0, via: AccountManagement = accounts) =
                    via.enroll(actor, credentials(account, attempt), op(), origin = EnrollmentOrigin.SIGN_IN)
                suspend fun refused(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
                suspend fun rows(account: String) = a.query { Accounts.selectAll().where { Accounts.providerAccountId eq account }.count() }
                suspend fun events() = a.query {
                    AuditEvents.selectAll().where { (AuditEvents.teamId eq "default") and ((AuditEvents.action like "account.%") or (AuditEvents.action like "accounts.%")) }
                        .orderBy(AuditEvents.id).map { Triple(it[AuditEvents.actorId], it[AuditEvents.action], it[AuditEvents.subjectId]) }
                }
                suspend fun stored(account: String) = SqlAccountStore(a, cipher).find(AccountIdentity("default", "anthropic", account, "org-$account"))!!
                suspend fun secret(record: AccountRecord) = SqlAccountStore(a, cipher).credentials("default", record.id).accessToken

                // Default: administrators only, refused before any provider call, nothing written.
                assertEquals(AccountEnrollmentAccess(AccountEnrollmentPolicy.ADMINISTRATORS, false), accounts.enrollmentAccess(ada))
                assertTrue(accounts.enrollmentAccess(admin).allowed)
                var verified = 0
                verifier.gate = { verified++ }
                refused(AccountEnrollmentRules.ENROLLMENT_FORBIDDEN) { add(ada, "x") }
                assertEquals(0, verified, "A refused member never reaches the provider")
                assertEquals(0, rows("x"))
                verifier.gate = null
                refused("forbidden") { accounts.setEnrollmentPolicy(ada, AccountEnrollmentPolicy.MEMBERS) }

                // Every member may add accounts.
                assertEquals(AccountEnrollmentPolicy.MEMBERS, accounts.setEnrollmentPolicy(admin, AccountEnrollmentPolicy.MEMBERS))
                accounts.setEnrollmentPolicy(admin, AccountEnrollmentPolicy.MEMBERS)
                assertEquals(listOf(Triple(admin.member.id, "accounts.enrollment_policy_changed", "default:administrators->members")), events(), "Audited once, on change")
                assertTrue(accounts.enrollmentAccess(ada).allowed)
                val x = (add(ada, "x") as EnrollmentResult.Created).account
                assertEquals(AccountState.READY, x.state)
                assertEquals(ada.member.id, stored("x").enrolledBy)
                assertEquals(Triple(ada.member.id, "account.enrolled", x.id), events().last(), "The audit log names the member who added it")
                // The account gives its member no extra right: shared pool, default priority, administrator-only settings.
                assertEquals(null, stored("x").poolId); assertEquals(0, stored("x").priority)
                refused("forbidden") { accounts.configure(ada, x.id, "Mine", false, 100, "private") }
                refused("forbidden") { accounts.handover(ada, x.id) }
                refused("forbidden") { accounts.list(ada) }
                refused(AccountEnrollmentRules.IMPORT_FORBIDDEN) { accounts.enroll(ada, credentials("w"), op()) }
                refused("credential_scope") { accounts.enroll(ada.copy(serviceScopes = setOf(ServiceScope.INFERENCE)), credentials("w"), op(), origin = EnrollmentOrigin.SIGN_IN) }
                assertEquals(0, rows("w"))

                // Adding the same provider account again, even under another provider label, updates the one row.
                verifier.displayName = "Renamed by the provider"
                val again = (add(ada, "x", attempt = 1) as EnrollmentResult.Updated).account
                assertEquals(x.id, again.id); assertEquals(2, again.credentialVersion)
                assertEquals(1, rows("x"))
                assertEquals("Claude Max", stored("x").displayName, "The name stays the operator's")

                // Another member cannot replace the sign-in of a healthy account they did not add.
                refused(AccountEnrollmentRules.ACCOUNT_IN_USE) { add(grace, "x", attempt = 2) }
                assertEquals("x|access-1", secret(stored("x")))
                assertEquals(2, stored("x").credentialVersion)

                // Once it needs reconnecting, any member may reconnect it, keeping every setting.
                accounts.configure(admin, x.id, "Team Max", true, 7, "night")
                a.query { Accounts.update({ Accounts.id eq x.id }) { it[state] = AccountState.RECONNECT_REQUIRED.name } }
                // A different provider account never replaces it, whatever its name.
                val y = (add(grace, "y") as EnrollmentResult.Created).account
                assertNotEquals(x.id, y.id)
                assertEquals(AccountState.RECONNECT_REQUIRED, stored("x").state); assertEquals("x|access-1", secret(stored("x")))
                val reconnected = (add(grace, "x", attempt = 3) as EnrollmentResult.Updated).account
                assertEquals(x.id, reconnected.id)
                with(stored("x")) {
                    assertEquals(AccountState.READY, state); assertEquals(3, credentialVersion)
                    assertEquals("Team Max", displayName); assertTrue(disabled); assertEquals(7, priority); assertEquals("night", poolId)
                    assertEquals(ada.member.id, enrolledBy, "Reconnecting does not change who added it")
                }
                assertEquals("x|access-3", secret(stored("x")))
                assertEquals(Triple(grace.member.id, "account.reconnected", x.id), events().last(), "The audit log names who reconnected it")
                // Healthy again: it is protected again from everyone but its member and administrators.
                refused(AccountEnrollmentRules.ACCOUNT_IN_USE) { add(grace, "x", attempt = 4) }
                assertIs<EnrollmentResult.Updated>(add(ada, "x", attempt = 4))
                assertIs<EnrollmentResult.Updated>(add(admin, "x", attempt = 5))

                // Two simultaneous sign-ins to a new provider account by the same member: one row, Created then Updated.
                val both = CompletableDeferred<Unit>(); var arrived = 0
                verifier.gate = { if (++arrived == 2) both.complete(Unit); both.await() }
                val results = listOf(async { add(ada, "z", attempt = 1) }, async { add(ada, "z", attempt = 2, via = other) }).awaitAll()
                assertEquals(1, rows("z"))
                assertEquals(setOf("Created", "Updated"), results.map { it::class.simpleName }.toSet())
                // Two members at once: one row; the second finds a healthy account the first just added.
                val pair = CompletableDeferred<Unit>(); arrived = 0
                verifier.gate = { if (++arrived == 2) pair.complete(Unit); pair.await() }
                val race = listOf(async { runCatching { add(ada, "v") } }, async { runCatching { add(grace, "v", via = other) } }).awaitAll()
                verifier.gate = null
                assertEquals(1, rows("v"))
                assertEquals(1, race.count { it.getOrNull() is EnrollmentResult.Created })
                assertEquals(AccountEnrollmentRules.ACCOUNT_IN_USE, (race.single { it.isFailure }.exceptionOrNull() as TeamFailure).code)

                // Back to administrators only: members cannot even refresh their own sign-in.
                accounts.setEnrollmentPolicy(admin, AccountEnrollmentPolicy.ADMINISTRATORS)
                refused(AccountEnrollmentRules.ENROLLMENT_FORBIDDEN) { add(ada, "x", attempt = 6) }
                assertEquals(Triple(admin.member.id, "accounts.enrollment_policy_changed", "default:members->administrators"), events().last())
                assertFalse(events().toString().contains("access-"), "No credential reaches the audit log")
            } }
        }
    }
}
