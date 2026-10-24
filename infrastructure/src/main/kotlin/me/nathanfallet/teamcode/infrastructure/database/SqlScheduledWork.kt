package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Database-clock leases coordinate workers without a pod identity or an in-memory cursor. */
class SqlScheduledWork(private val database: Database) {
    suspend fun runDue(name: String, intervalMillis: Long = 60_000, timeoutMillis: Long = 45_000, block: suspend () -> Unit): Boolean {
        require(name.length in 1..100 && intervalMillis > 0 && timeoutMillis in 1..300_000)
        val owner = UUID.randomUUID().toString()
        val claimed = database.query {
            ScheduledWork.upsert(onUpdate = { statement -> statement[ScheduledWork.name] = ScheduledWork.name }) { it[ScheduledWork.name] = name }
            ScheduledWork.select(ScheduledWork.name).where { ScheduledWork.name eq name }.forUpdate().single()
            val now = nowMillis()
            ScheduledWork.update({ (ScheduledWork.name eq name) and (ScheduledWork.availableAt lessEq now) }) {
                it[ScheduledWork.owner] = owner; it[availableAt] = now + timeoutMillis + 15_000; it[lastStartedAt] = now
            } == 1
        }
        if (!claimed) return false
        try { withTimeout(timeoutMillis) { block() } }
        finally {
            withContext(NonCancellable) { database.query {
                val available = nowMillis() + intervalMillis
                ScheduledWork.update({ (ScheduledWork.name eq name) and (ScheduledWork.owner eq owner) }) { it[ScheduledWork.owner] = null; it[availableAt] = available }
            } }
        }
        return true
    }
    suspend fun dueAccounts(): List<String> = database.query {
        val now = nowMillis()
        val workName = CustomFunction<String>("CONCAT", TextColumnType(), stringLiteral("quota/"), Accounts.id)
        Accounts.join(ScheduledWork, JoinType.LEFT, additionalConstraint = { ScheduledWork.name eq workName })
            .select(Accounts.id).where {
                (Accounts.disabled eq false) and (Accounts.state inList listOf("READY", "ACCESS_ONLY")) and
                    (ScheduledWork.availableAt.isNull() or (ScheduledWork.availableAt lessEq now))
            }.orderBy(Coalesce(ScheduledWork.lastStartedAt, longLiteral(0)) to SortOrder.ASC, Accounts.id to SortOrder.ASC).limit(8).map { it[Accounts.id] }
    }
}
