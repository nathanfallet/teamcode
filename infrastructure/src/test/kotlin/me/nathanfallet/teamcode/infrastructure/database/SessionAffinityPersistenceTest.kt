package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class SessionAffinityPersistenceTest {
    private fun hint(conversation: Char = 'b', model: String = "claude-opus-test", route: Char = 'c') =
        RequestAffinity("a".repeat(64), conversation.toString().repeat(64), model, route.toString().repeat(64), WireProtocol.MESSAGES)

    @Test fun independentReplicasPreserveAffinityWithoutBypassingEligibilityOrOwnership() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                val pending = team.login("admin", initial, "affinity test")
                team.changePassword(pending.principal, initial, "affinity test replacement password")
                val actor = team.login("admin", "affinity test replacement password", "affinity test").principal
                val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 23 }))
                suspend fun account(name: String, provider: String = "anthropic", pool: String? = null): AccountRecord {
                    val verified = VerifiedProviderAccount(AccountIdentity("default", provider, name, "test-org"), name)
                    val record = (store.upsert(AccountMutationActor.System, verified, ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "enroll-affinity-$name") as EnrollmentResult.Created).account
                    store.configure(AccountMutationActor.System, "default", record.id, name, false, 0, pool)
                    return store.handover(AccountMutationActor.System, "default", record.id, accessOnly = true)
                }
                val accounts = listOf(account("first"), account("second"))
                val admissionA = SqlAdmissionStore(a)
                val admissionB = SqlAdmissionStore(b)
                admissionA.configure(actor, MemberPolicy(actor.member.id, 0, 4))
                suspend fun place(store: SqlAdmissionStore, affinity: RequestAffinity, model: String = affinity.requestedModel, provider: String = "anthropic"): Reservation =
                    store.reserve(actor, 100, model, provider, affinity = affinity).also { store.settle(it, 0) }

                val parent = hint()
                val first = place(admissionA, parent).account
                val other = accounts.single { it.id != first.id }
                assertEquals(RouteTarget("anthropic", parent.requestedModel), admissionB.preferredTarget(actor, parent))
                // A fresh subagent sharing the session goes elsewhere; later parent turns retain their cache.
                assertEquals(other.id, place(admissionB, hint('d')).account.id)
                assertEquals(first.id, place(SqlAdmissionStore(b), parent).account.id)

                val now = a.query { nowMillis() }
                admissionA.observe(first.id, listOf(AccountQuota(first.id, "model:sonnet", 1.0, now, now + 3_600_000)))
                assertEquals(other.id, place(admissionB, hint(model = "claude-sonnet-test")).account.id)
                assertEquals(first.id, place(admissionA, parent).account.id, "A Sonnet diversion must not move the Opus pin")

                // Exclusions and cooldowns still rotate a pinned request; no affinity bypasses admission.
                val excluded = admissionB.reserve(actor, 100, parent.requestedModel, excludedAccountIds = setOf(first.id), affinity = parent)
                assertEquals(other.id, excluded.account.id); admissionB.settle(excluded, 0)
                admissionA.coolDown(other.id, now + 60_000)
                assertEquals(first.id, place(admissionA, parent).account.id)
                a.query { Accounts.update({ Accounts.id eq other.id }) { it[cooldownUntil] = 0 } }

                store.configure(AccountMutationActor.System, "default", other.id, other.displayName, false, 5)
                assertEquals(first.id, place(admissionB, parent).account.id, "A higher-priority account never preempts a pinned conversation")
                assertEquals(other.id, place(admissionA, hint('9')).account.id, "Priority still places new conversations")
                store.configure(AccountMutationActor.System, "default", first.id, first.displayName, true, 0)
                assertEquals(other.id, place(admissionB, parent).account.id, "Disabled accounts cannot retain a pin")
                store.configure(AccountMutationActor.System, "default", first.id, first.displayName, false, 0)
                assertEquals(other.id, place(admissionA, parent).account.id, "The pin moved with the conversation and stays put")
                store.configure(AccountMutationActor.System, "default", other.id, other.displayName, false, 0)
                assertEquals(other.id, place(admissionA, parent).account.id)

                // Only a relevant window at 95% moves a pin: 94% keeps it, 95% of the five-hour or weekly window does not.
                val observedAt = a.query { nowMillis() }
                admissionA.observe(other.id, listOf(AccountQuota(other.id, "five_hour", .94, observedAt, observedAt + 3_600_000)))
                assertEquals(other.id, place(admissionB, parent).account.id)
                admissionA.observe(other.id, listOf(AccountQuota(other.id, "five_hour", .95, observedAt + 1, observedAt + 3_600_000)))
                assertEquals(first.id, place(admissionB, parent).account.id, "A five-hour window at 95% moves the pin")
                admissionA.observe(first.id, listOf(AccountQuota(first.id, "seven_day", .96, observedAt + 1, observedAt + 86_400_000)))
                admissionA.observe(other.id, listOf(AccountQuota(other.id, "five_hour", .2, observedAt + 2, observedAt + 3_600_000)))
                assertEquals(other.id, place(admissionA, parent).account.id, "A weekly window at 95% moves the pin")
                admissionA.observe(first.id, listOf(AccountQuota(first.id, "seven_day", .1, observedAt + 2, observedAt + 86_400_000)))
                assertEquals(other.id, place(admissionA, parent).account.id, "Recovered capacity elsewhere does not pull the conversation back")

                // Two independent connection pools see the first placement before admitting the next, and concurrent
                // turns of one conversation never spill to another account: accounts have no parallelism limit.
                val concurrent = coroutineScope { (1..3).map { index -> async(Dispatchers.IO) {
                    (if (index % 2 == 0) admissionA else admissionB).reserve(actor, 100, parent.requestedModel, affinity = hint('e'))
                } }.awaitAll() }
                assertEquals(1, concurrent.map { it.account.id }.distinct().size)
                val more = admissionB.reserve(actor, 100, parent.requestedModel, affinity = hint('e'))
                assertEquals(concurrent.first().account.id, more.account.id, "A busy pinned account keeps its conversation")
                (concurrent + more).forEach { admissionA.settle(it, 0) }

                account("codex", "codex")
                place(admissionB, parent, "gpt-6-astra", "codex")
                assertEquals(RouteTarget("codex", "gpt-6-astra"), admissionA.preferredTarget(actor, parent))
                assertNull(admissionA.preferredTarget(actor, hint(route = 'f')), "Changed routing rules must not inherit an old target")

                val created = team.createMember(actor, "colleague", Role.MEMBER)
                val colleaguePending = team.login("colleague", created.temporaryPassword, "test")
                team.changePassword(colleaguePending.principal, created.temporaryPassword, "colleague new password")
                val colleague = team.login("colleague", "colleague new password", "test").principal
                assertNull(admissionB.preferredTarget(colleague, parent), "A client-supplied session cannot read another member's pin")
                val privateAccount = account("protected", pool = "protected")
                admissionA.configure(actor, MemberPolicy(actor.member.id, 0, 4, "protected"))
                assertEquals(privateAccount.id, place(admissionB, parent).account.id, "Moving a member into a protected pool invalidates old account eligibility")
                admissionA.configure(actor, MemberPolicy(actor.member.id, 0, 4))

                a.query { ConversationAffinities.update({ ConversationAffinities.teamId eq "default" }) { it[expiresAt] = 0; it[lastSeenAt] = 0 } }
                assertNull(admissionA.preferredTarget(actor, parent))
                admissionB.maintain()
                assertEquals(0, a.query { ConversationAffinities.selectAll().count().toInt() })
                place(admissionA, parent)
                // A client minting fresh identifiers cannot grow persistent hints without bound.
                // Fill this member's real default limit, then verify admission continues unpinned.
                a.query {
                    val c = this
                    val current = c.nowMillis()
                    ConversationAffinities.batchInsert(1 until SqlSessionAffinity.MAX_MEMBER_HINTS, shouldReturnGeneratedValues = false) { index ->
                        this[ConversationAffinities.teamId] = "default"; this[ConversationAffinities.memberId] = actor.member.id
                        this[ConversationAffinities.sessionKey] = parent.sessionKey; this[ConversationAffinities.conversationKey] = index.toString(16).padStart(64, '0')
                        this[ConversationAffinities.requestedModel] = parent.requestedModel; this[ConversationAffinities.routeKey] = parent.routeKey
                        this[ConversationAffinities.provider] = "anthropic"; this[ConversationAffinities.resolvedModel] = parent.requestedModel
                        this[ConversationAffinities.accountId] = first.id; this[ConversationAffinities.lastSeenAt] = current; this[ConversationAffinities.expiresAt] = current + 3_600_000
                    }
                }
                place(admissionB, hint('d'))
                assertNull(admissionA.preferredTarget(actor, hint('d')))
                place(admissionB, parent)
                assertNotNull(admissionA.preferredTarget(actor, parent), "Existing affinity still renews at the storage limit")
                assertEquals(SqlSessionAffinity.MAX_MEMBER_HINTS, a.query {
                    ConversationAffinities.selectAll().where { ConversationAffinities.memberId eq actor.member.id }.count().toInt()
                })
                team.logout(actor)
                assertEquals("authentication_required", assertFailsWith<TeamFailure> { admissionB.preferredTarget(actor, parent) }.code)
                assertEquals("authentication_required", assertFailsWith<TeamFailure> { place(admissionB, parent) }.code)
            } }
        }
    }
}
