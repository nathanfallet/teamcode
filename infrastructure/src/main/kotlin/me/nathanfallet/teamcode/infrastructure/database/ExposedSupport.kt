package me.nathanfallet.teamcode.infrastructure.database

import java.math.BigDecimal
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Read the database clock so leases and windows agree across hosts. */
internal fun JdbcTransaction.nowMillis(): Long {
    val timestamp = CustomFunction<String>("CURRENT_TIMESTAMP", TextColumnType(), intLiteral(3))
    val epoch = CustomFunction<BigDecimal>("UNIX_TIMESTAMP", DecimalColumnType(20, 3), timestamp)
    return (Table.Dual.select(epoch).single()[epoch] * BigDecimal(1000)).toLong()
}
internal fun JdbcTransaction.lockTeam(team: String) {
    check(Teams.select(Teams.id).where { Teams.id eq team }.forUpdate().singleOrNull() != null)
}
internal fun JdbcTransaction.audit(team: String, actor: String, action: String, subject: String, effectiveMember: String? = null, credential: String? = null, related: String? = null, details: String? = null) {
    val now = nowMillis()
    AuditEvents.insert {
        it[teamId] = team
        it[actorId] = actor
        it[AuditEvents.action] = action
        it[subjectId] = subject
        it[occurredAt] = now
        // Only cloud actions and skill pins name these columns, so other writes stay compatible with the previous schema.
        if (effectiveMember != null) it[effectiveMemberId] = effectiveMember
        if (credential != null) it[credentialId] = credential
        if (related != null) it[relatedMemberId] = related
        if (details != null) it[AuditEvents.details] = details
    }
}
