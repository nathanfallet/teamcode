package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

class SqlAccountStore(private val database: Database, private val cipher: CredentialCipher) : AccountStore, AccountEmailStore {
    private val merge = MergeAccountUseCase()
    private fun identityFilter(identity: AccountIdentity) = (Accounts.teamId eq identity.teamId) and (Accounts.provider eq identity.provider) and
        (Accounts.providerAccountId eq identity.accountId) and (Accounts.organizationId eq identity.organizationId)
    override suspend fun find(identity: AccountIdentity): AccountRecord? = database.query {
        Accounts.selectAll().where { identityFilter(identity) }.singleOrNull()?.let(::accountRecord)
    }
    override suspend fun upsert(author: AccountMutationActor, verified: VerifiedProviderAccount, credentials: ProviderCredentials, expectedVersion: Long?, operationId: String, origin: EnrollmentOrigin): EnrollmentResult = database.query {
        val identity = verified.identity
        val actorId = authorizeMutation(this, author, identity.teamId)
        val payload = Json.encodeToString(CredentialPayload(credentials.accessToken, credentials.refreshToken, credentials.expiresAtMillis))
        val hash = Passwords.digest(listOf(identity.provider, identity.accountId, identity.organizationId, payload).joinToString("\u0000"))
        val previous = EnrollmentOperations.selectAll().where { (EnrollmentOperations.teamId eq identity.teamId) and (EnrollmentOperations.operationId eq operationId) }.singleOrNull()
        if (previous != null) {
            if (previous[EnrollmentOperations.requestHash] != hash) return@query EnrollmentResult.Conflict
            val account = Accounts.selectAll().where { (Accounts.id eq previous[EnrollmentOperations.accountId]) and (Accounts.teamId eq identity.teamId) }.single().let(::accountRecord)
            return@query if (previous[EnrollmentOperations.created]) EnrollmentResult.Created(account) else EnrollmentResult.Updated(account)
        }
        val row = Accounts.selectAll().where { identityFilter(identity) }.forUpdate().singleOrNull()
        val existing = row?.let(::accountRecord)
        if (row != null && row[Accounts.refreshPending] && existing!!.state != AccountState.RECONNECT_REQUIRED && row[Accounts.refreshStartedAt] + 60_000 > nowMillis()) return@query EnrollmentResult.Conflict
        if (existing?.credentialVersion != expectedVersion) return@query EnrollmentResult.Conflict
        val account = existing?.let { merge.merge(it, verified, expectedVersion!!, origin) } ?: AccountRecord(UUID.randomUUID().toString(), identity, verified.displayName.take(120), 1, false, null, 0, merge.initialState(origin), verified.email)
        val envelope = cipher.encrypt(payload.toByteArray(), "${identity.teamId}:${account.id}")
        if (existing == null) Accounts.insert {
            it[id] = account.id; it[teamId] = identity.teamId; it[provider] = identity.provider; it[providerAccountId] = identity.accountId
            it[organizationId] = identity.organizationId; it[displayName] = account.displayName; it[email] = account.email; it[credentialVersion] = account.credentialVersion
            it[credentialEnvelope] = envelope; it[state] = account.state.name
        } else Accounts.update({ Accounts.id eq account.id }) { it[credentialVersion] = account.credentialVersion; it[email] = account.email; it[state] = account.state.name; it[credentialEnvelope] = envelope; it[refreshPending] = false }
        EnrollmentOperations.insert { it[teamId] = identity.teamId; it[EnrollmentOperations.operationId] = operationId; it[requestHash] = hash; it[accountId] = account.id; it[created] = existing == null }
        audit(identity.teamId, actorId, if (existing == null) "account.enrolled" else "account.credentials_updated", account.id)
        if (existing == null) EnrollmentResult.Created(account) else EnrollmentResult.Updated(account)
    }
    /** Fills a missing display address only; an address already known is never replaced and credentials are untouched. */
    override suspend fun recordEmail(identity: AccountIdentity, accountId: String, email: String): Boolean = database.query {
        Accounts.update({ (Accounts.id eq accountId) and identityFilter(identity) and Accounts.email.isNull() }) { it[Accounts.email] = email } == 1
    }
    override suspend fun list(teamId: String): List<AccountRecord> = database.query { Accounts.selectAll().where { Accounts.teamId eq teamId }.orderBy(Accounts.displayName).limit(1000).map(::accountRecord) }
    override suspend fun configure(author: AccountMutationActor, teamId: String, id: String, displayName: String, disabled: Boolean, priority: Int, poolId: String?): AccountRecord = database.query {
        val actorId = authorizeMutation(this, author, teamId)
        Accounts.update({ (Accounts.teamId eq teamId) and (Accounts.id eq id) }) {
            it[Accounts.displayName] = displayName; it[Accounts.disabled] = disabled; it[Accounts.priority] = priority; it[Accounts.poolId] = poolId
        }
        val account = Accounts.selectAll().where { (Accounts.teamId eq teamId) and (Accounts.id eq id) }.singleOrNull()?.let(::accountRecord) ?: throw missing()
        audit(teamId, actorId, "account.configured", id)
        account
    }
    override suspend fun handover(author: AccountMutationActor, teamId: String, id: String, accessOnly: Boolean, requirePending: Boolean): AccountRecord = database.query {
        val actorId = authorizeMutation(this, author, teamId)
        val row = Accounts.selectAll().where { (Accounts.teamId eq teamId) and (Accounts.id eq id) }.forUpdate().singleOrNull() ?: throw missing()
        if (requirePending && row[Accounts.state] != AccountState.PENDING_HANDOVER.name) throw TeamFailure("handover_not_pending", 409, "This account no longer waits for a handover. Reload the dashboard.")
        if (row[Accounts.refreshPending]) throw TeamFailure("provider_reconnect", 409, "Import verified current credentials before changing refresh ownership.")
        val state = if (accessOnly) AccountState.ACCESS_ONLY else AccountState.READY
        Accounts.update({ (Accounts.teamId eq teamId) and (Accounts.id eq id) }) { it[Accounts.state] = state.name }
        if (row[Accounts.state] != state.name) audit(teamId, actorId, if (accessOnly) "account.access_only" else "account.refresh_handover", id)
        accountRecord(row).copy(state = state)
    }
    override suspend fun credentials(teamId: String, id: String): ProviderCredentials = database.query {
        val encrypted = Accounts.select(Accounts.credentialEnvelope).where { (Accounts.teamId eq teamId) and (Accounts.id eq id) }.singleOrNull()?.get(Accounts.credentialEnvelope) ?: throw missing()
        val value = Json.decodeFromString<CredentialPayload>(cipher.decrypt(encrypted, "$teamId:$id").decodeToString())
        ProviderCredentials(value.accessToken, value.refreshToken, value.expiresAtMillis)
    }
    private fun authorizeMutation(transaction: JdbcTransaction, author: AccountMutationActor, teamId: String): String {
        if (author is AccountMutationActor.User) {
            author.principal.requireAdmin()
            if (author.principal.teamId != teamId) throw TeamFailure("forbidden", 403, "An account write cannot cross team boundaries.")
        }
        transaction.lockTeam(teamId)
        return when (author) {
            is AccountMutationActor.User -> {
                requireCurrent(transaction, author.principal)
                author.principal.member.id
            }
            AccountMutationActor.System -> "system"
        }
    }
    private fun missing() = TeamFailure("not_found", 404, "Account not found.")
}
@Serializable
internal class CredentialPayload(val accessToken: String, val refreshToken: String, val expiresAtMillis: Long)
internal fun accountRecord(row: ResultRow) = AccountRecord(row[Accounts.id], AccountIdentity(row[Accounts.teamId], row[Accounts.provider], row[Accounts.providerAccountId], row[Accounts.organizationId]), row[Accounts.displayName], row[Accounts.credentialVersion], row[Accounts.disabled], row[Accounts.poolId], row[Accounts.priority], AccountState.valueOf(row[Accounts.state]), row[Accounts.email])
