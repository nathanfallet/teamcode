package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.AuditEvents
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** Bounded indexed keyset reads. IDs are stable even when another replica appends events. */
class SqlAuditService(private val database: Database) : AuditService {
    override suspend fun list(actor: Principal, beforeId: Long?): AuditPage {
        actor.requireAdmin()
        AuditService.validateCursor(beforeId)
        return database.query {
            requireCurrent(this, actor)
            val rows = AuditEvents.selectAll().where {
                (AuditEvents.teamId eq actor.teamId) and (beforeId?.let { AuditEvents.id less it } ?: Op.TRUE)
            }.orderBy(AuditEvents.id to SortOrder.DESC).limit(AuditService.PAGE_SIZE + 1).map {
                AuditEvent(it[AuditEvents.id], it[AuditEvents.actorId], it[AuditEvents.action], it[AuditEvents.subjectId], it[AuditEvents.occurredAt], it[AuditEvents.effectiveMemberId], it[AuditEvents.credentialId])
            }
            val events = rows.take(AuditService.PAGE_SIZE)
            AuditPage(events, if (rows.size > AuditService.PAGE_SIZE) events.last().id else null)
        }
    }
}
