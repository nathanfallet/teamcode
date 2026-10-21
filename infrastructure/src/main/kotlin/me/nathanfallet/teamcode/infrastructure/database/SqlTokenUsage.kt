package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.TokenUsage
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.jdbc.*

/**
 * Adds one settled request to its member's day row and to its account's hour row, inside the settlement transaction and
 * under the team lock that every settlement holds, so the update-or-insert cannot race another replica.
 */
internal fun JdbcTransaction.recordTokenUsage(teamId: String, memberId: String, accountId: String, dayNumber: Long, hourNumber: Long, runId: String?, usage: MeteredUsage) {
    val m = TokenUsageTable
    add(Counters(m, m.requests, m.unmeteredRequests, m.inputTokens, m.cacheCreationTokens, m.cacheReadTokens, m.outputTokens), usage,
        (m.teamId eq teamId) and (m.dayNumber eq dayNumber) and (m.memberId eq memberId) and (m.runKey eq runId.orEmpty()) and
            (m.provider eq usage.provider) and (m.model eq usage.model)) {
        it[m.teamId] = teamId; it[m.dayNumber] = dayNumber; it[m.memberId] = memberId; it[m.runKey] = runId.orEmpty(); it[m.provider] = usage.provider; it[m.model] = usage.model
    }
    val a = AccountTokenUsageTable
    add(Counters(a, a.requests, a.unmeteredRequests, a.inputTokens, a.cacheCreationTokens, a.cacheReadTokens, a.outputTokens), usage,
        (a.accountId eq accountId) and (a.hourNumber eq hourNumber) and (a.provider eq usage.provider) and (a.model eq usage.model)) {
        it[a.accountId] = accountId; it[a.hourNumber] = hourNumber; it[a.provider] = usage.provider; it[a.model] = usage.model
    }
}

private class Counters(val table: Table, val requests: Column<Long>, val unmetered: Column<Long>, val input: Column<Long>,
    val cacheCreation: Column<Long>, val cacheRead: Column<Long>, val output: Column<Long>)

private fun add(c: Counters, usage: MeteredUsage, key: Op<Boolean>, keyColumns: (UpdateBuilder<*>) -> Unit) {
    val tokens = usage.tokens
    val unmetered = if (tokens == null) 1L else 0L
    val updated = c.table.update({ key }) {
        it[c.requests] = c.requests + 1L
        it[c.unmetered] = c.unmetered + unmetered
        if (tokens != null) {
            it[c.input] = c.input + tokens.input; it[c.cacheCreation] = c.cacheCreation + tokens.cacheCreation
            it[c.cacheRead] = c.cacheRead + tokens.cacheRead; it[c.output] = c.output + tokens.output
        }
    }
    if (updated == 0) c.table.insert {
        keyColumns(it)
        it[c.requests] = 1; it[c.unmetered] = unmetered
        it[c.input] = tokens?.input ?: 0; it[c.cacheCreation] = tokens?.cacheCreation ?: 0
        it[c.cacheRead] = tokens?.cacheRead ?: 0; it[c.output] = tokens?.output ?: 0
    }
}

/** Keyset pages in primary-key order; a member reads their own rows, an interactive administrator or a `team:read` key any member's or the team's. */
internal fun JdbcTransaction.readTokenUsage(actor: Principal, query: TokenUsageQuery): TokenUsagePage {
    actor.requireUsageRead()
    requireCurrent(this, actor)
    TokenUsageLedger.validate(query)
    val reader = when {
        query.all -> { actor.requireTeamRead(); null }
        query.memberId != null && query.memberId != actor.member.id -> {
            actor.requireTeamRead()
            Members.select(Members.id).where { (Members.id eq query.memberId!!) and (Members.teamId eq actor.teamId) }.singleOrNull()
                ?: throw TeamFailure("not_found", 404, "Member not found.")
            query.memberId
        }
        else -> actor.member.id
    }
    val prices = ApiPriceTable(readApiPrices(actor.teamId))
    val t = TokenUsageTable
    val after = query.after?.let { c ->
        val model = (t.provider eq c.provider) and (t.model greater c.model)
        val provider = (t.provider greater c.provider) or model
        val run = (t.runKey greater c.runKey) or ((t.runKey eq c.runKey) and provider)
        val member = (t.memberId greater c.memberId) or ((t.memberId eq c.memberId) and run)
        (t.dayNumber greater c.dayNumber) or ((t.dayNumber eq c.dayNumber) and member)
    } ?: Op.TRUE
    val rows = (t innerJoin Members).select(t.columns + Members.username).where {
        (t.teamId eq actor.teamId) and (t.dayNumber greaterEq query.fromDay) and (t.dayNumber lessEq query.toDay) and
            (reader?.let { t.memberId eq it } ?: Op.TRUE) and after
    }.orderBy(t.dayNumber to SortOrder.ASC, t.memberId to SortOrder.ASC, t.runKey to SortOrder.ASC, t.provider to SortOrder.ASC, t.model to SortOrder.ASC)
        .limit(TokenUsageLedger.PAGE_SIZE + 1).map {
            TokenUsageRow(it[t.dayNumber], it[t.memberId], it[Members.username], it[t.runKey].ifEmpty { null }, it[t.provider], it[t.model],
                it[t.requests], it[t.unmeteredRequests], it[t.inputTokens], it[t.cacheCreationTokens], it[t.cacheReadTokens], it[t.outputTokens],
                prices.microsOf(it[t.provider], it[t.model], it[t.dayNumber],
                    TokenUsage(it[t.inputTokens], it[t.outputTokens], it[t.cacheReadTokens], it[t.cacheCreationTokens])))
        }
    val page = rows.take(TokenUsageLedger.PAGE_SIZE)
    val next = if (rows.size > TokenUsageLedger.PAGE_SIZE) page.last().let { TokenUsageCursor(it.dayNumber, it.memberId, it.runId.orEmpty(), it.provider, it.model).encode() } else null
    return TokenUsagePage(page, next)
}
