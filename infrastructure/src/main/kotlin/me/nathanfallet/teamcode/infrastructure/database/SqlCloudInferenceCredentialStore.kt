package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.ServiceScope
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * `tcex_` credentials as [Tokens] rows limited to inference: the gateway authenticates them, revalidates them on
 * every request and applies the member's model policy, quotas and routing like any other member token. Only the
 * SHA-256 of the token is stored.
 */
class SqlCloudInferenceCredentialStore(private val database: Database) : CloudInferenceCredentialStore {
    override suspend fun issue(teamId: String, sessionId: String, epoch: Int, ttlMillis: Long, keep: Int): IssuedInferenceCredential? = database.query {
        // The session row lock serializes issuance with every session write that ends the execution (they update
        // the same row before revoking), so a credential is never left behind by a concurrent pause or transfer.
        val session = CloudSessions.select(CloudSessions.currentEpoch, CloudSessions.state, CloudSessions.desiredState, CloudSessions.ownerId)
            .where { (CloudSessions.id eq sessionId) and (CloudSessions.teamId eq teamId) }.forUpdate().singleOrNull() ?: return@query null
        val state = CloudSessionState.parse(session[CloudSessions.state])
        if (session[CloudSessions.currentEpoch] != epoch || state !in RunnerInferenceUseCase.INFERENCE_STATES ||
            session[CloudSessions.desiredState] == DesiredState.TERMINATED.wire) return@query null
        val execution = CloudExecutions.select(CloudExecutions.state, CloudExecutions.ownerId)
            .where { (CloudExecutions.sessionId eq sessionId) and (CloudExecutions.epoch eq epoch) }.singleOrNull() ?: return@query null
        if (execution[CloudExecutions.state] != ExecutionState.RUNNING.wire) return@query null
        // The member the execution runs for, fixed when it started; never the owner of a later epoch.
        val member = execution[CloudExecutions.ownerId] ?: session[CloudSessions.ownerId]
        val eligible = Members.select(Members.id).where {
            (Members.id eq member) and (Members.teamId eq teamId) and (Members.disabled eq false) and (Members.mustChangePassword eq false)
        }.singleOrNull()
        if (eligible == null) return@query null
        val now = nowMillis()
        val older = CloudInferenceCredentials.select(CloudInferenceCredentials.tokenId)
            .where { (CloudInferenceCredentials.sessionId eq sessionId) and (CloudInferenceCredentials.epoch eq epoch) }
            .orderBy(CloudInferenceCredentials.createdAt to SortOrder.DESC, CloudInferenceCredentials.tokenId to SortOrder.DESC)
            .map { it[CloudInferenceCredentials.tokenId] }.drop((keep - 1).coerceAtLeast(0))
        if (older.isNotEmpty()) Tokens.deleteWhere { Tokens.id inList older }
        val id = UUID.randomUUID().toString()
        val token = RunnerInferenceUseCase.TOKEN_PREFIX + Passwords.secret()
        val expiresAt = now + ttlMillis
        Tokens.insert {
            it[Tokens.id] = id; it[memberId] = member; it[tokenHash] = Passwords.digest(token)
            it[device] = "cloud session ${sessionId.take(8)} e$epoch"; it[deviceId] = null; it[createdAt] = now; it[Tokens.expiresAt] = expiresAt
            it[serviceScopes] = "[\"${ServiceScope.INFERENCE.wire}\"]"
        }
        CloudInferenceCredentials.insert {
            it[tokenId] = id; it[CloudInferenceCredentials.sessionId] = sessionId; it[CloudInferenceCredentials.epoch] = epoch
            it[CloudInferenceCredentials.teamId] = teamId; it[createdAt] = now; it[CloudInferenceCredentials.expiresAt] = expiresAt
        }
        IssuedInferenceCredential(token, expiresAt)
    }

    override suspend fun purge(limit: Int): Int = database.query {
        val now = nowMillis()
        // Expired ones, and any whose execution is no longer the live current one (a safety net: the session store
        // already revokes in the transaction that ends an execution).
        val stale = (CloudInferenceCredentials innerJoin CloudSessions).join(CloudExecutions, JoinType.LEFT,
            additionalConstraint = { (CloudExecutions.sessionId eq CloudInferenceCredentials.sessionId) and (CloudExecutions.epoch eq CloudInferenceCredentials.epoch) })
            .select(CloudInferenceCredentials.tokenId).where {
                (CloudInferenceCredentials.expiresAt lessEq now) or (CloudInferenceCredentials.epoch neq CloudSessions.currentEpoch) or
                    CloudExecutions.state.isNull() or (CloudExecutions.state neq ExecutionState.RUNNING.wire)
            }.limit(limit).map { it[CloudInferenceCredentials.tokenId] }
        if (stale.isEmpty()) 0 else Tokens.deleteWhere { Tokens.id inList stale }
    }
}

/**
 * Revokes inference credentials of [sessionId] in the caller's transaction: those of [epoch] only, or every one
 * of the session when [epoch] is null. Deleting the token row is the revocation; the gateway rechecks the token on
 * every request, so a stream in flight stops at its next check.
 */
internal fun revokeInferenceCredentials(sessionId: String, epoch: Int? = null) {
    val ids = CloudInferenceCredentials.select(CloudInferenceCredentials.tokenId).where {
        (CloudInferenceCredentials.sessionId eq sessionId) and (epoch?.let { CloudInferenceCredentials.epoch eq it } ?: Op.TRUE)
    }.map { it[CloudInferenceCredentials.tokenId] }
    if (ids.isNotEmpty()) Tokens.deleteWhere { Tokens.id inList ids }
}
/** Revokes credentials of every epoch of [sessionId] older than [epoch]. */
internal fun revokeInferenceCredentialsBefore(sessionId: String, epoch: Int) {
    val ids = CloudInferenceCredentials.select(CloudInferenceCredentials.tokenId).where {
        (CloudInferenceCredentials.sessionId eq sessionId) and (CloudInferenceCredentials.epoch less epoch)
    }.map { it[CloudInferenceCredentials.tokenId] }
    if (ids.isNotEmpty()) Tokens.deleteWhere { Tokens.id inList ids }
}
