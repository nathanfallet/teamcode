package me.nathanfallet.teamcode.domain.capacity

import me.nathanfallet.teamcode.domain.routing.TokenUsage
import me.nathanfallet.teamcode.domain.team.Principal
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.math.roundToLong

/**
 * One row of a team's API price table (ADR 0002): what the provider's pay-as-you-go API would charge for [model] from
 * [effectiveFromDay] (a UTC day number), in **micro-dollars per million tokens** for each token type. `3_000_000` is
 * $3 per million tokens, so a token at that price is worth 3 micro-dollars. [model] is an exact model identifier or a
 * lowercase family prefix (`claude-sonnet-4`); a new price is a new row, so past days keep the price that applied to them.
 */
data class ApiPrice(
    val provider: String, val model: String, val effectiveFromDay: Long,
    val inputMicros: Long, val cacheWriteMicros: Long, val cacheReadMicros: Long, val outputMicros: Long,
)

/** The team's dated price table. TeamCode ships no built-in prices: list prices change, and a stale default would mislead. */
class ApiPriceTable(private val prices: List<ApiPrice>) {
    val isEmpty: Boolean get() = prices.isEmpty()

    /**
     * The row that prices [model] on [day]: among the rows of [provider] effective on or before [day] whose model is
     * [model] or a prefix of it, an exact model wins over the longest prefix, and then the most recent row wins.
     */
    fun priceFor(provider: String, model: String, day: Long): ApiPrice? = model.lowercase().let { served ->
        prices.filter { it.provider == provider && it.effectiveFromDay <= day && served.startsWith(it.model) }
            .maxWithOrNull(compareBy<ApiPrice>({ it.model == served }, { it.model.length }, { it.effectiveFromDay }))
    }

    /** The API-equivalent of [tokens] in micro-dollars, null when no row prices the model on that day. */
    fun microsOf(provider: String, model: String, day: Long, tokens: TokenUsage): Long? = priceFor(provider, model, day)?.let { price ->
        (tokens.input.toDouble() * price.inputMicros + tokens.cacheCreation.toDouble() * price.cacheWriteMicros +
            tokens.cacheRead.toDouble() * price.cacheReadMicros + tokens.output.toDouble() * price.outputMicros).div(1_000_000).roundToLong()
    }
}

/** Team credits for these tokens, as admission settles them: every input token once (cached or not) and output five times. */
val TokenUsage.credits: Long get() = input + cacheRead + cacheCreation + output * 5

object ApiPrices {
    const val MAX_ROWS = 200
    /** $1,000,000 per million tokens: far above any list price, low enough that a day of tokens cannot overflow. */
    const val MAX_PRICE_MICROS = 1_000_000_000_000L
    private val PROVIDER = Regex("[a-z0-9_-]{1,32}")
    private val MODEL = Regex("[a-z0-9_.:/-]{1,128}")

    fun validate(prices: List<ApiPrice>): List<ApiPrice> {
        fun refuse(message: String): Nothing = throw TeamFailure("invalid_api_price", 400, message)
        if (prices.size > MAX_ROWS) refuse("At most $MAX_ROWS price rows.")
        for (price in prices) {
            if (!PROVIDER.matches(price.provider)) refuse("Use a provider identifier such as anthropic or codex.")
            if (!MODEL.matches(price.model)) refuse("Use a lowercase model identifier or family prefix of letters, digits, '.', '-', '_', ':' or '/'.")
            if (price.effectiveFromDay !in 0..2_932_896) refuse("Use an effective date written YYYY-MM-DD.")
            if (listOf(price.inputMicros, price.cacheWriteMicros, price.cacheReadMicros, price.outputMicros).any { it !in 0..MAX_PRICE_MICROS })
                refuse("Prices are micro-dollars per million tokens, from 0 to $MAX_PRICE_MICROS.")
        }
        if (prices.groupBy { Triple(it.provider, it.model, it.effectiveFromDay) }.any { it.value.size > 1 })
            refuse("Each provider, model and effective date has one row.")
        return prices
    }
}

/** Tokens of one provider, model and UTC day, as the raw ledger stores them. */
data class PricedTokens(val provider: String, val model: String, val day: Long, val tokens: TokenUsage)

/**
 * Credits charged per micro-dollar of API-equivalent over the same tokens, from the priced models only ([unpricedModels]
 * lists the others as `provider/model`). Null without priced traffic. Admission still counts credits, so this converts
 * a capacity estimated in API-equivalent into the pool's unit.
 */
data class CreditRatio(val creditsPerMicro: Double?, val unpricedModels: List<String>) {
    companion object {
        fun of(prices: ApiPriceTable, usage: List<PricedTokens>): CreditRatio {
            var credits = 0L
            var micros = 0L
            val unpriced = sortedSetOf<String>()
            for (row in usage) {
                val cost = prices.microsOf(row.provider, row.model, row.day, row.tokens)
                if (cost == null) { if (row.tokens.credits > 0) unpriced += "${row.provider}/${row.model}"; continue }
                credits += row.tokens.credits; micros += cost
            }
            return CreditRatio(if (micros > 0 && credits > 0) credits.toDouble() / micros else null, unpriced.toList())
        }
    }
}

/** The team's API price table, edited by interactive administrators and read with `usage:read`; every change is audited. */
interface ApiPriceStore {
    suspend fun list(actor: Principal): List<ApiPrice>
    /** Replaces the whole table after [ApiPrices.validate]. */
    suspend fun replace(actor: Principal, prices: List<ApiPrice>): List<ApiPrice>
}
