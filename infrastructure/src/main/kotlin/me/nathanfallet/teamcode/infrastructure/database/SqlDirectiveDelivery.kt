package me.nathanfallet.teamcode.infrastructure.database

import java.security.MessageDigest
import java.util.UUID
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** A short delivery lease avoids concurrent duplicates without claiming the agent obeyed advice. */
internal class SqlDirectiveDelivery(private val database: Database) {
    suspend fun preview(actor: Principal): AgentDirective? = database.query {
        actor.requireInteractive(); actor.requireReady(); requireCurrent(this, actor)
        read(actor, "", nowMillis())
    }
    suspend fun current(actor: Principal, sessionId: String?): AgentDirective? {
        actor.requireScope(ServiceScope.GUIDANCE)
        val scope = sessionId?.let(::sessionHash).orEmpty()
        return database.query {
            requireCurrent(this, actor)
            if (readPacingSettings(actor.teamId).observationOnly) null else read(actor, scope, nowMillis())
        }
    }
    suspend fun claim(actor: Principal, sessionId: String, stage: String): DirectiveDelivery? {
        actor.requireScope(ServiceScope.GUIDANCE); validateStage(stage)
        val scope = sessionHash(sessionId)
        return database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            if (readPacingSettings(actor.teamId).observationOnly) return@query null
            val now = nowMillis()
            val directive = read(actor, scope, now) ?: return@query null
            val previous = DirectiveDeliveries.selectAll().where {
                (DirectiveDeliveries.memberId eq actor.member.id) and (DirectiveDeliveries.sessionKey eq scope) and (DirectiveDeliveries.stage eq stage)
            }.singleOrNull()
            if (previous?.get(DirectiveDeliveries.directiveId) == directive.id &&
                (previous[DirectiveDeliveries.emittedAt] != null || previous[DirectiveDeliveries.claimedUntil] > now)) return@query null
            if (previous == null && (DirectiveDeliveries.selectAll().count() >= 20_000 ||
                DirectiveDeliveries.selectAll().where { DirectiveDeliveries.memberId eq actor.member.id }.count() >= 2_000)) return@query null
            val id = UUID.randomUUID().toString()
            DirectiveDeliveries.upsert {
                it[memberId] = actor.member.id; it[sessionKey] = scope; it[DirectiveDeliveries.stage] = stage; it[directiveId] = directive.id
                it[claimId] = id; it[tokenId] = actor.tokenId; it[claimedUntil] = now + 15_000; it[emittedAt] = null; it[updatedAt] = now
            }
            DirectiveDelivery(directive, id)
        }
    }
    suspend fun acknowledge(actor: Principal, id: String, stage: String, sessionId: String?, claimId: String?) {
        actor.requireScope(ServiceScope.GUIDANCE); validateStage(stage)
        val scope = sessionId?.let(::sessionHash).orEmpty()
        if ((sessionId == null) != (claimId == null)) throw TeamFailure("invalid_claim", 400, "Session receipts require their delivery claim.")
        database.query {
            lockTeam(actor.teamId); requireCurrent(this, actor)
            val now = nowMillis()
            val table = if (scope.isEmpty()) Directives else SessionDirectives
            if (table.select(table.id).where { (table.memberId eq actor.member.id) and (table.scopeKey eq scope) and (table.id eq id) and (table.expiresAt greater now) }.empty()) throw TeamFailure("directive_expired", 409, "This directive has expired or been replaced.")
            if (sessionId != null) {
                val key = (DirectiveDeliveries.memberId eq actor.member.id) and (DirectiveDeliveries.sessionKey eq scope) and (DirectiveDeliveries.stage eq stage)
                val claim = DirectiveDeliveries.select(DirectiveDeliveries.claimedUntil).where {
                    key and (DirectiveDeliveries.directiveId eq id) and (DirectiveDeliveries.claimId eq claimId!!) and (DirectiveDeliveries.tokenId eq actor.tokenId)
                }.singleOrNull()?.get(DirectiveDeliveries.claimedUntil)
                if (claim == null || claim <= now) throw TeamFailure("claim_expired", 409, "This delivery claim expired or was replaced.")
                DirectiveDeliveries.update({ key }) { it[emittedAt] = now; it[updatedAt] = now }
            }
            DirectiveReceipts.upsert { it[directiveId] = id; it[tokenId] = actor.tokenId; it[DirectiveReceipts.stage] = stage; it[receivedAt] = now }
        }
    }
    private fun read(actor: Principal, scope: String, now: Long): AgentDirective? {
        if (scope.isNotEmpty() && ConversationAffinities.select(ConversationAffinities.memberId).where {
            (ConversationAffinities.teamId eq actor.teamId) and (ConversationAffinities.memberId eq actor.member.id) and (ConversationAffinities.sessionKey eq scope) and
                (ConversationAffinities.lastSeenAt greater now - SqlSessionAffinity.ACTIVE_TTL) and (ConversationAffinities.expiresAt greater now)
        }.limit(1).empty()) return null
        val table = if (scope.isEmpty()) Directives else SessionDirectives
        return table.selectAll().where { (table.memberId eq actor.member.id) and (table.scopeKey eq scope) and (table.expiresAt greater now) }
            .singleOrNull()?.let { directiveRecord(it, table) }
    }
    private fun validateStage(stage: String) {
        if (stage !in setOf("RECEIVED_BY_CLI", "EMITTED_TO_HOOK")) throw TeamFailure("invalid_stage", 400, "Unknown delivery stage.")
    }
    private fun sessionHash(value: String): String {
        if (!Regex("[A-Za-z0-9._-]{1,128}").matches(value)) throw TeamFailure("invalid_session", 400, "Provide a valid client session identifier.")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
