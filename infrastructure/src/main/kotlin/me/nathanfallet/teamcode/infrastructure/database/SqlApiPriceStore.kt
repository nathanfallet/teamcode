package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.ApiPricesTable
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*

/** The team's price table: read with `usage:read`, replaced whole by an interactive administrator under the team lock, audited. */
class SqlApiPriceStore(private val database: Database) : ApiPriceStore {
    override suspend fun list(actor: Principal): List<ApiPrice> = database.query {
        actor.requireUsageRead(); requireCurrent(this, actor)
        readApiPrices(actor.teamId)
    }

    override suspend fun replace(actor: Principal, prices: List<ApiPrice>): List<ApiPrice> = database.query {
        actor.requireAdmin()
        val next = ApiPrices.validate(prices).sortedWith(ORDER)
        lockTeam(actor.teamId); requireCurrent(this, actor)
        val previous = readApiPrices(actor.teamId)
        if (previous == next) return@query next
        ApiPricesTable.deleteWhere { teamId eq actor.teamId }
        ApiPricesTable.batchInsert(next) { price ->
            this[ApiPricesTable.teamId] = actor.teamId; this[ApiPricesTable.provider] = price.provider; this[ApiPricesTable.model] = price.model
            this[ApiPricesTable.effectiveFromDay] = price.effectiveFromDay; this[ApiPricesTable.inputMicros] = price.inputMicros
            this[ApiPricesTable.cacheWriteMicros] = price.cacheWriteMicros; this[ApiPricesTable.cacheReadMicros] = price.cacheReadMicros
            this[ApiPricesTable.outputMicros] = price.outputMicros
        }
        audit(actor.teamId, actor.member.id, "capacity.api_price_changed", "${actor.teamId}:${previous.size}->${next.size}")
        next
    }

    private companion object {
        val ORDER = compareBy<ApiPrice>({ it.provider }, { it.model }, { it.effectiveFromDay })
    }
}

/** At most [ApiPrices.MAX_ROWS] rows per team; call inside the caller's transaction. */
internal fun readApiPrices(teamId: String): List<ApiPrice> = ApiPricesTable.selectAll().where { ApiPricesTable.teamId eq teamId }
    .orderBy(ApiPricesTable.provider to SortOrder.ASC, ApiPricesTable.model to SortOrder.ASC, ApiPricesTable.effectiveFromDay to SortOrder.ASC)
    .limit(ApiPrices.MAX_ROWS).map {
        ApiPrice(it[ApiPricesTable.provider], it[ApiPricesTable.model], it[ApiPricesTable.effectiveFromDay], it[ApiPricesTable.inputMicros],
            it[ApiPricesTable.cacheWriteMicros], it[ApiPricesTable.cacheReadMicros], it[ApiPricesTable.outputMicros])
    }
