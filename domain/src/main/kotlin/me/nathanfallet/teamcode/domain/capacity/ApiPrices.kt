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

/**
 * The team's dated price table. No price applies until an administrator enters one or loads [AnthropicListPrices]
 * (an explicit, audited action): list prices change, and a silent default would mislead.
 */
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

    /** The API-equivalent of [tokens] by token type (cache reads and writes apart), null when no row prices the model on that day. */
    fun costOf(provider: String, model: String, day: Long, tokens: TokenUsage): PricedCost? = priceFor(provider, model, day)?.let { price ->
        fun of(count: Long, perMillion: Long) = (count.toDouble() * perMillion / 1_000_000).roundToLong()
        PricedCost(of(tokens.input, price.inputMicros), of(tokens.cacheCreation, price.cacheWriteMicros), of(tokens.cacheRead, price.cacheReadMicros), of(tokens.output, price.outputMicros))
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
            // A free model would count credits with no cost and inflate the credit ratio: input and output need a price.
            if (price.inputMicros == 0L || price.outputMicros == 0L) refuse("Input and output prices must be above zero.")
        }
        if (prices.groupBy { Triple(it.provider, it.model, it.effectiveFromDay) }.any { it.value.size > 1 })
            refuse("Each provider, model and effective date has one row.")
        return prices
    }

    /** [current] plus the rows of [list] it lacks: a row already present for the same provider, model and day is kept as typed. */
    fun withMissing(current: List<ApiPrice>, list: List<ApiPrice>): List<ApiPrice> = current + list.filterNot { row -> current.any { it.sameKey(row) } }
}

/**
 * Anthropic's pay-as-you-go list prices, read on 2026-10-21 from https://platform.claude.com/docs/en/about-claude/pricing
 * (first-party API rates, in micro-dollars per million tokens). Never applied on its own: an administrator loads it with
 * `POST /api/v1/policy/api-prices/load-defaults` or the dashboard button, which adds these rows dated [CHECKED_ON_DAY].
 * Cache writes use the 5-minute rate (1.25 × input; the ledger does not split 5-minute and 1-hour writes). Claude Haiku 5.5
 * uses its rate for prompts up to 100,000 tokens. Retired models and limited-availability models (Mythos) are not listed.
 * A new list is a new [CHECKED_ON_DAY], so loading it adds rows instead of rewriting past days.
 */
object AnthropicListPrices {
    const val SOURCE = "https://platform.claude.com/docs/en/about-claude/pricing"
    /** 2026-10-21 as a UTC day number. */
    const val CHECKED_ON_DAY = 20_747L

    private fun row(model: String, input: Long, cacheWrite: Long, cacheRead: Long, output: Long) =
        ApiPrice("anthropic", model, CHECKED_ON_DAY, input, cacheWrite, cacheRead, output)

    val rows: List<ApiPrice> = listOf(
        row("claude-fable-5-1", 10_000_000, 12_500_000, 250_000, 50_000_000),
        row("claude-fable-5", 10_000_000, 12_500_000, 1_000_000, 50_000_000),
        row("claude-opus-5-5", 4_000_000, 5_000_000, 200_000, 20_000_000),
        row("claude-opus-5", 5_000_000, 6_250_000, 500_000, 25_000_000),
        row("claude-opus-4-8", 5_000_000, 6_250_000, 500_000, 25_000_000),
        row("claude-opus-4-7", 5_000_000, 6_250_000, 500_000, 25_000_000),
        row("claude-opus-4-6", 5_000_000, 6_250_000, 500_000, 25_000_000),
        row("claude-opus-4-5", 5_000_000, 6_250_000, 500_000, 25_000_000),
        row("claude-sonnet-5-5", 2_000_000, 2_500_000, 100_000, 10_000_000),
        row("claude-sonnet-5", 2_000_000, 2_500_000, 200_000, 10_000_000),
        row("claude-sonnet-4-6", 3_000_000, 3_750_000, 300_000, 15_000_000),
        row("claude-sonnet-4-5", 3_000_000, 3_750_000, 300_000, 15_000_000),
        row("claude-haiku-5-5", 100_000, 125_000, 10_000, 500_000),
        row("claude-haiku-4-5", 1_000_000, 1_250_000, 100_000, 5_000_000),
    )
}

/** An API-equivalent split by token type, in micro-dollars. */
data class PricedCost(val inputMicros: Long = 0, val cacheWriteMicros: Long = 0, val cacheReadMicros: Long = 0, val outputMicros: Long = 0) {
    val totalMicros: Long get() = inputMicros + cacheWriteMicros + cacheReadMicros + outputMicros
    operator fun plus(other: PricedCost) = PricedCost(inputMicros + other.inputMicros, cacheWriteMicros + other.cacheWriteMicros,
        cacheReadMicros + other.cacheReadMicros, outputMicros + other.outputMicros)
}

/** Every token of [this] usage, cached or not: what "tokens" means when a capacity is converted from dollars. */
val TokenUsage.allTokens: Long get() = input + cacheRead + cacheCreation + output

/** One model's tokens over the look-back and, when every day of it is priced, their API-equivalent by token type. */
data class ModelUsage(val provider: String, val model: String, val tokens: TokenUsage, val cost: PricedCost?)

/**
 * The team's model mix over the last seven days of the ledger. [tokensPerMicro] converts a capacity in API-equivalent
 * into tokens at that mix (every token type, cache reads included, over priced models only); null without priced traffic.
 * A capacity in tokens therefore moves with the mix: the same window holds about 20 times more cache reads than
 * uncached input tokens, which is why the capacity is learned in API-equivalent and only shown in tokens.
 */
data class TokenMix(val models: List<ModelUsage>, val tokensPerMicro: Double?) {
    companion object {
        fun of(prices: ApiPriceTable, usage: List<PricedTokens>): TokenMix {
            val models = usage.groupBy { it.provider to it.model }.map { (key, rows) ->
                var tokens = TokenUsage()
                var cost: PricedCost? = PricedCost()
                for (row in rows) {
                    tokens = TokenUsage(tokens.input + row.tokens.input, tokens.output + row.tokens.output,
                        tokens.cacheRead + row.tokens.cacheRead, tokens.cacheCreation + row.tokens.cacheCreation)
                    val priced = prices.costOf(row.provider, row.model, row.day, row.tokens)
                    cost = if (priced == null) { if (row.tokens.allTokens > 0) null else cost } else cost?.plus(priced)
                }
                ModelUsage(key.first, key.second, tokens, cost)
            }.sortedWith(compareByDescending<ModelUsage> { it.cost?.totalMicros ?: -1L }.thenBy { it.provider }.thenBy { it.model })
            val priced = models.filter { it.cost != null }
            val micros = priced.sumOf { it.cost!!.totalMicros }
            return TokenMix(models, if (micros > 0) priced.sumOf { it.tokens.allTokens }.toDouble() / micros else null)
        }
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
    /** Adds [price], replacing the row with the same provider, model and day; implementations do it under the team lock. */
    suspend fun put(actor: Principal, price: ApiPrice): List<ApiPrice> = replace(actor, list(actor).filterNot { it.sameKey(price) } + price)
    /** Removes the row of [provider], [model] and [effectiveFromDay], if any; implementations do it under the team lock. */
    suspend fun remove(actor: Principal, provider: String, model: String, effectiveFromDay: Long): List<ApiPrice> =
        replace(actor, list(actor).filterNot { it.provider == provider && it.model == model && it.effectiveFromDay == effectiveFromDay })
    /**
     * Adds the rows of [AnthropicListPrices] the table lacks (administrators only, audited); rows already present for the
     * same model and day are kept, so loading twice changes nothing. Implementations do it under the team lock.
     */
    suspend fun loadDefaults(actor: Principal): List<ApiPrice> = replace(actor, ApiPrices.withMissing(list(actor), AnthropicListPrices.rows))
}

fun ApiPrice.sameKey(other: ApiPrice) = provider == other.provider && model == other.model && effectiveFromDay == other.effectiveFromDay
