package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Service keys share the owner's ledger and token revocation path; only their hashes are stored. */
class SqlServiceKeyService(private val database: Database) : ServiceKeyService {
    override suspend fun list(actor: Principal, memberId: String): List<ServiceKey> = database.query {
        authorize(this, actor); requireMember(actor, memberId)
        Tokens.select(Tokens.id, Tokens.memberId, Tokens.device, Tokens.serviceScopes, Tokens.createdAt, Tokens.expiresAt)
            .where { (Tokens.memberId eq memberId) and serviceKey() }
            .orderBy(Tokens.createdAt to SortOrder.DESC, Tokens.id to SortOrder.ASC).limit(ServiceKeyService.LIMIT.toInt())
            .map(::metadata)
    }
    override suspend fun create(actor: Principal, memberId: String, name: String, scopes: Set<ServiceScope>, validDays: Int?): CreatedServiceKey {
        ServiceKeyService.validate(name, scopes, validDays)
        return database.query {
            lockTeam(actor.teamId); authorize(this, actor)
            val role = requireMember(actor, memberId, lock = true)
            for (scope in listOf(ServiceScope.SKILLS_PUBLISH, ServiceScope.HOOKS_PUBLISH, ServiceScope.ADMIN_CLOUD, ServiceScope.TEAM_READ)) if (scope in scopes && role != Role.ADMIN.name)
                throw TeamFailure("invalid_service_key", 400, "Only an administrator's key can receive the ${scope.wire} scope.")
            ServiceKeyService.requireNoExpiryAllowed(validDays, role == Role.ADMIN.name)
            if (Tokens.select(Tokens.id).where { (Tokens.memberId eq memberId) and serviceKey() }.count() >= ServiceKeyService.LIMIT)
                throw TeamFailure("service_key_limit", 409, "Remove an unused service key before creating another.")
            val now = nowMillis()
            val metadata = ServiceKey(UUID.randomUUID().toString(), memberId, name, scopes.toSet(), now, if (validDays == null) ServiceKeyService.NO_EXPIRY else now + validDays * 86_400_000L)
            val token = "tcsk_" + Passwords.secret()
            Tokens.insert {
                it[id] = metadata.id; it[Tokens.memberId] = memberId; it[tokenHash] = Passwords.digest(token)
                it[device] = name; it[deviceId] = null; it[createdAt] = now; it[expiresAt] = metadata.expiresAt
                it[serviceScopes] = Json.encodeToString(scopes.map { scope -> scope.wire }.sorted())
            }
            // A key without expiry is its own action, so it can be told apart (and found) in the audit journal.
            audit(actor.teamId, actor.member.id, if (metadata.neverExpires) "service_key.created_no_expiry" else "service_key.created", metadata.id)
            CreatedServiceKey(metadata, token)
        }
    }
    override suspend fun revoke(actor: Principal, id: String): String = database.query {
        lockTeam(actor.teamId); authorize(this, actor)
        val row = (Tokens innerJoin Members).select(Tokens.memberId).where {
            (Tokens.id eq id) and serviceKey() and (Members.teamId eq actor.teamId) and
                (if (actor.member.role.isAdministrator) Op.TRUE else Tokens.memberId eq actor.member.id)
        }.singleOrNull() ?: throw missing()
        val member = row[Tokens.memberId]
        requireMember(actor, member, lock = true)
        Tokens.deleteWhere { Tokens.id eq id }
        audit(actor.teamId, actor.member.id, "service_key.revoked", id)
        member
    }
    private fun authorize(transaction: JdbcTransaction, actor: Principal) { actor.requireInteractive(); requireCurrent(transaction, actor) }
    /** Returns the eligible member's current role. */
    private fun requireMember(actor: Principal, id: String, lock: Boolean = false): String {
        if (actor.member.id != id) actor.requireAdmin()
        val query = Members.select(Members.id, Members.role).where {
            (Members.id eq id) and (Members.teamId eq actor.teamId) and (Members.disabled eq false) and (Members.mustChangePassword eq false)
        }
        if (lock) query.forUpdate()
        return query.singleOrNull()?.get(Members.role) ?: throw missing()
    }
    private fun metadata(row: ResultRow) = ServiceKey(row[Tokens.id], row[Tokens.memberId], row[Tokens.device], requireNotNull(readServiceScopes(row[Tokens.serviceScopes])), requireNotNull(row[Tokens.createdAt]), row[Tokens.expiresAt])
    private fun missing() = TeamFailure("not_found", 404, "Service key or eligible member not found.")
    /** Scoped tokens a member created; cloud execution credentials are scoped too but live and die with their execution. */
    private fun serviceKey(): Op<Boolean> = Tokens.serviceScopes.isNotNull() and
        (Tokens.id notInSubQuery CloudInferenceCredentials.select(CloudInferenceCredentials.tokenId))
}

/** Malformed persisted permissions must fail closed rather than become an interactive session. */
internal fun readServiceScopes(value: String?): Set<ServiceScope>? {
    if (value == null) return null
    try {
        val names = Json.decodeFromString<List<String>>(value)
        require(names.size in 1..ServiceScope.entries.size)
        return names.map { name -> requireNotNull(ServiceScope.entries.find { it.wire == name }) }.toSet()
    } catch (_: IllegalArgumentException) {
        throw TeamFailure("authentication_required", 401, "The service key is invalid. Sign in again.")
    }
}
