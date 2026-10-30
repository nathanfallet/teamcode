package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.cloud.*
import me.nathanfallet.teamcode.domain.team.ServiceScope
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.Passwords
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/**
 * `tcex_` credentials as [Tokens] rows limited to [EXECUTION_SCOPES]: the gateway authenticates them, revalidates them on
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
        // Lock order: session, then member, then the member's tokens, as member updates lock the member before its tokens.
        val eligible = Members.select(Members.id).where {
            (Members.id eq member) and (Members.teamId eq teamId) and (Members.disabled eq false) and (Members.mustChangePassword eq false)
        }.forUpdate().singleOrNull()
        if (eligible == null) return@query null
        val existing = CloudInferenceCredentials.select(CloudInferenceCredentials.tokenId, CloudInferenceCredentials.createdAt)
            .where { (CloudInferenceCredentials.sessionId eq sessionId) and (CloudInferenceCredentials.epoch eq epoch) }
            .orderBy(CloudInferenceCredentials.createdAt to SortOrder.DESC).toList()
        // Creation times are strictly increasing per execution, so "newest" never depends on a tie-break.
        val now = maxOf(nowMillis(), (existing.firstOrNull()?.get(CloudInferenceCredentials.createdAt) ?: 0L) + 1)
        val older = existing.drop((keep - 1).coerceAtLeast(0)).map { it[CloudInferenceCredentials.tokenId] }
        if (older.isNotEmpty()) Tokens.deleteWhere { Tokens.id inList older }
        val id = UUID.randomUUID().toString()
        val token = RunnerInferenceUseCase.TOKEN_PREFIX + Passwords.secret()
        val expiresAt = now + ttlMillis
        Tokens.insert {
            it[Tokens.id] = id; it[memberId] = member; it[tokenHash] = Passwords.digest(token)
            it[device] = "cloud session ${sessionId.take(8)} e$epoch"; it[deviceId] = null; it[createdAt] = now; it[Tokens.expiresAt] = expiresAt
            it[serviceScopes] = Json.encodeToString(EXECUTION_SCOPES.map { it.wire })
        }
        CloudInferenceCredentials.insert {
            it[tokenId] = id; it[CloudInferenceCredentials.sessionId] = sessionId; it[CloudInferenceCredentials.epoch] = epoch
            it[CloudInferenceCredentials.teamId] = teamId; it[createdAt] = now; it[CloudInferenceCredentials.expiresAt] = expiresAt
        }
        IssuedInferenceCredential(token, expiresAt, ttlMillis)
    }

    override suspend fun purge(limit: Int): Int = database.query {
        val now = nowMillis()
        // Expired ones first, oldest first, on the expiry index.
        val expired = CloudInferenceCredentials.select(CloudInferenceCredentials.tokenId).where { CloudInferenceCredentials.expiresAt lessEq now }
            .orderBy(CloudInferenceCredentials.expiresAt).limit(limit).map { it[CloudInferenceCredentials.tokenId] }
        // A safety net: any whose execution is no longer the live current one. The session store already revokes in
        // the transaction that ends an execution, so this finds nothing in normal operation. The table holds at most
        // two rows per running execution, so the join stays small.
        val orphaned = if (expired.size >= limit) emptyList() else (CloudInferenceCredentials innerJoin CloudSessions).join(CloudExecutions, JoinType.LEFT,
            additionalConstraint = { (CloudExecutions.sessionId eq CloudInferenceCredentials.sessionId) and (CloudExecutions.epoch eq CloudInferenceCredentials.epoch) })
            .select(CloudInferenceCredentials.tokenId).where {
                (CloudInferenceCredentials.epoch neq CloudSessions.currentEpoch) or CloudExecutions.state.isNull() or (CloudExecutions.state neq ExecutionState.RUNNING.wire)
            }.orderBy(CloudInferenceCredentials.createdAt).limit(limit - expired.size).map { it[CloudInferenceCredentials.tokenId] }
        val stale = expired + orphaned
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
/**
 * The revocation rule of every session write, in its transaction: credentials exist only while the session is in a
 * running state (issuance checks it under the session row lock), so leaving those states or heading for deletion
 * revokes all of them, and an execution that ends revokes its own. Writes that stay outside the running states cost
 * nothing.
 */
internal fun revokeInferenceCredentialsOnWrite(expected: CloudSession, next: CloudSession, endedEpoch: Int?) {
    val wasRunning = expected.state in RunnerInferenceUseCase.INFERENCE_STATES && expected.desiredState != DesiredState.TERMINATED
    val running = next.state in RunnerInferenceUseCase.INFERENCE_STATES && next.desiredState != DesiredState.TERMINATED
    when {
        wasRunning && !running -> revokeInferenceCredentials(expected.id)
        endedEpoch != null -> revokeInferenceCredentials(expected.id, endedEpoch)
    }
}
/** Revokes credentials of every epoch of [sessionId] older than [epoch]. */
internal fun revokeInferenceCredentialsBefore(sessionId: String, epoch: Int) {
    val ids = CloudInferenceCredentials.select(CloudInferenceCredentials.tokenId).where {
        (CloudInferenceCredentials.sessionId eq sessionId) and (CloudInferenceCredentials.epoch less epoch)
    }.map { it[CloudInferenceCredentials.tokenId] }
    if (ids.isNotEmpty()) Tokens.deleteWhere { Tokens.id inList ids }
}

/**
 * What a cloud execution may do as its owner: inference, the owner's MCP servers through the gateway (the runner
 * relays the agent's messages), the owner's effective skills and team hooks (the runner installs them), and artifacts
 * (the agent's `teamcode mcp artifacts`, through the runner's relay; never making public or deleting, which stay
 * interactive). Nothing else: no usage, no skill or hook publication, no cloud or interactive route.
 */
val EXECUTION_SCOPES: List<ServiceScope> = listOf(ServiceScope.INFERENCE, ServiceScope.MCP, ServiceScope.SKILLS_READ, ServiceScope.HOOKS_READ, ServiceScope.ARTIFACTS_WRITE, ServiceScope.WAITS_SESSION)
