package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Metadata only: bodies stay with their cancellable HTTP request, never in the database. */
internal class SqlAdmissionQueue(private val database: Database) {
    suspend fun enqueue(actor: Principal, input: AdmissionInput): AdmissionTicket = database.query {
        actor.requireScope(ServiceScope.INFERENCE); lockTeam(actor.teamId); requireCurrent(this, actor)
        require(input.units in 1..100_000_000 && input.provider.length in 1..32)
        val now = nowMillis()
        input.runId?.let { requireRunAdmission(actor, it, input.units, now, input.workload, checkConcurrency = false) }
        AdmissionWaiters.deleteWhere { (teamId eq actor.teamId) and (expiresAt lessEq now) }
        val policy = readMemberPolicy(this, actor.member.id)
        val memberCount = AdmissionWaiters.selectAll().where { AdmissionWaiters.memberId eq actor.member.id }.count()
        val teamCount = AdmissionWaiters.selectAll().where { AdmissionWaiters.teamId eq actor.teamId }.count()
        if (memberCount >= 8 || teamCount >= 128) throw TeamFailure("queue_full", 503, "The TeamCode admission queue is full. Retry shortly.", retryAfterSeconds = 15)
        val id = UUID.randomUUID().toString()
        val turnPool = accountPlacement(actor, policy, input, now).turnPool
        AdmissionWaiters.insert {
            it[AdmissionWaiters.id] = id; it[teamId] = actor.teamId; it[memberId] = actor.member.id
            it[poolId] = policy.poolId.orEmpty(); it[provider] = input.provider; it[workload] = input.workload.name
            it[createdAt] = now; it[readyAt] = now; it[expiresAt] = now + AwaitAdmissionUseCase.LEASE_MILLIS
            it[runId] = input.runId; it[turnPoolId] = turnPool
        }
        AdmissionTicket(id)
    }
    suspend fun renew(actor: Principal, ticket: AdmissionTicket, blocked: Boolean, input: AdmissionInput? = null) = database.query {
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val now = nowMillis()
        val turnPool = input?.let { accountPlacement(actor, readMemberPolicy(this, actor.member.id), it, now).turnPool }
        val changed = AdmissionWaiters.update({
            (AdmissionWaiters.id eq ticket.id) and (AdmissionWaiters.teamId eq actor.teamId) and
                (AdmissionWaiters.memberId eq actor.member.id) and (AdmissionWaiters.expiresAt greater now)
        }) {
            it[readyAt] = now + if (blocked) AwaitAdmissionUseCase.LEASE_MILLIS else 0
            it[expiresAt] = now + AwaitAdmissionUseCase.LEASE_MILLIS
            if (turnPool != null) it[turnPoolId] = turnPool
        }
        if (changed != 1) throw TeamFailure("queue_expired", 503, "The pending admission lease expired. Retry the request.")
    }
    suspend fun release(actor: Principal, ticket: AdmissionTicket) {
        try { database.query {
            lockTeam(actor.teamId)
            AdmissionWaiters.deleteWhere { (id eq ticket.id) and (teamId eq actor.teamId) and (memberId eq actor.member.id) }
        } } catch (_: java.sql.SQLException) {
            System.err.println("TeamCode admission queue cleanup pending; the bounded lease will expire.")
        }
    }
}

/** Runs inside the same team-locked transaction as reservation creation. */
internal fun JdbcTransaction.consumeQueueTurn(actor: Principal, ticket: AdmissionTicket, policy: MemberPolicy, provider: String, now: Long, runId: String? = null, sourcePool: String = policy.poolId.orEmpty()) {
    val waiter = AdmissionWaiters.selectAll().where {
        (AdmissionWaiters.id eq ticket.id) and (AdmissionWaiters.teamId eq actor.teamId) and (AdmissionWaiters.memberId eq actor.member.id) and (AdmissionWaiters.expiresAt greater now)
    }.singleOrNull() ?: throw TeamFailure("queue_expired", 503, "The pending admission lease expired. Retry the request.")
    if (waiter[AdmissionWaiters.poolId] != policy.poolId.orEmpty() || waiter[AdmissionWaiters.provider] != provider || waiter[AdmissionWaiters.runId] != runId) {
        throw TeamFailure("queue_context_changed", 409, "Your assigned capacity changed while waiting. Submit the request again.", retryable = true)
    }
    val turnScope = (AdmissionTurns.teamId eq actor.teamId) and (AdmissionTurns.poolId eq sourcePool) and (AdmissionTurns.provider eq provider)
    val waiters = AdmissionWaiters.selectAll().where {
        (AdmissionWaiters.teamId eq actor.teamId) and (AdmissionWaiters.id neq ticket.id) and
            ((AdmissionWaiters.turnPoolId eq sourcePool) or (AdmissionWaiters.turnPoolId.isNull() and (AdmissionWaiters.poolId eq sourcePool))) and
            (AdmissionWaiters.provider eq provider) and (AdmissionWaiters.expiresAt greater now) and (AdmissionWaiters.readyAt lessEq now)
    }.limit(128).toList() + waiter
    val members = waiters.map { it[AdmissionWaiters.memberId] }.distinct()
    val turns = AdmissionTurns.selectAll().where { turnScope and (AdmissionTurns.memberId inList members) }
        .associate { it[AdmissionTurns.memberId] to it[AdmissionTurns.turn] }
    val candidates = waiters.map { QueueCandidate(it[AdmissionWaiters.id], it[AdmissionWaiters.memberId], it[AdmissionWaiters.createdAt], Workload.valueOf(it[AdmissionWaiters.workload]), turns[it[AdmissionWaiters.memberId]] ?: 0) }
    if (SelectQueueTurnUseCase().select(candidates, now) != ticket.id) throw TeamFailure("queue_turn", 429, "Another queued member has the next admission turn.")
    val maximum = AdmissionTurns.turn.max()
    val nextTurn = (AdmissionTurns.select(maximum).where { turnScope }.single()[maximum] ?: 0L) + 1
    AdmissionTurns.upsert {
        it[teamId] = actor.teamId; it[poolId] = sourcePool; it[AdmissionTurns.provider] = provider
        it[memberId] = actor.member.id; it[turn] = nextTurn
    }
    AdmissionWaiters.deleteWhere { id eq ticket.id }
}
