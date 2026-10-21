package me.nathanfallet.teamcode.domain

import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.TokenUsage
import me.nathanfallet.teamcode.domain.team.TeamFailure
import kotlin.test.*

class ApiPriceTableTest {
    private val day = 20_000L
    /** Micro-dollars per million tokens: 3_000_000 is $3 per million tokens. */
    private fun price(model: String, from: Long = day - 10, input: Long = 3_000_000, provider: String = "anthropic") =
        ApiPrice(provider, model, from, input, cacheWriteMicros = 3_750_000, cacheReadMicros = 300_000, outputMicros = 15_000_000)

    @Test fun anExactModelWinsOverTheLongestPrefixWhichWinsOverAShorterOne() {
        val table = ApiPriceTable(listOf(price("claude-", input = 1), price("claude-sonnet-4", input = 2), price("claude-sonnet-4-5", input = 3)))
        assertEquals(3, table.priceFor("anthropic", "claude-sonnet-4-5", day)!!.inputMicros, "An exact model")
        assertEquals(2, table.priceFor("anthropic", "claude-sonnet-4-1", day)!!.inputMicros, "The longest matching family prefix")
        assertEquals(1, table.priceFor("anthropic", "claude-haiku-4", day)!!.inputMicros)
        assertNull(table.priceFor("codex", "claude-sonnet-4-5", day), "Prices are per provider")
        assertNull(table.priceFor("anthropic", "gpt-5", day))
    }

    @Test fun aDayUsesTheMostRecentRowEffectiveOnOrBeforeIt() {
        val table = ApiPriceTable(listOf(price("claude-opus-4", from = day - 30, input = 15_000_000), price("claude-opus-4", from = day, input = 5_000_000),
            price("claude-opus-4", from = day + 1, input = 1)))
        assertEquals(15_000_000, table.priceFor("anthropic", "claude-opus-4-1", day - 1)!!.inputMicros, "Past days keep the price that applied to them")
        assertEquals(5_000_000, table.priceFor("anthropic", "claude-opus-4-1", day)!!.inputMicros)
        assertNull(table.priceFor("anthropic", "claude-opus-4-1", day - 31), "No row yet on that day")
        assertEquals(1, table.priceFor("anthropic", "claude-opus-4", day + 1)!!.inputMicros, "A row applies from its own day")
    }

    @Test fun theApiEquivalentPricesEachTokenTypeSeparately() {
        val table = ApiPriceTable(listOf(price("claude-sonnet-4")))
        // 1M input $3 + 2M cache writes $7.50 + 10M cache reads $3 + 0.1M output $1.50 = $15.
        assertEquals(15_000_000, table.microsOf("anthropic", "claude-sonnet-4-5", day,
            TokenUsage(input = 1_000_000, output = 100_000, cacheRead = 10_000_000, cacheCreation = 2_000_000)))
        assertNull(table.microsOf("anthropic", "unpriced-model", day, TokenUsage(input = 1)))
        assertEquals(0, table.microsOf("anthropic", "claude-sonnet-4", day, TokenUsage()))
    }

    @Test fun creditsCountEveryInputTokenOnceAndOutputFiveTimes() {
        assertEquals(1 + 3 + 4 + 2 * 5, TokenUsage(input = 1, output = 2, cacheRead = 3, cacheCreation = 4).credits)
    }

    @Test fun invalidTablesAreRefused() {
        fun refused(vararg rows: ApiPrice) = assertEquals("invalid_api_price", assertFailsWith<TeamFailure> { ApiPrices.validate(rows.toList()) }.code)
        refused(price("claude sonnet"))
        refused(price(""))
        refused(price("claude-opus-4").copy(outputMicros = -1))
        refused(price("claude-opus-4").copy(outputMicros = 0))
        refused(price("claude-opus-4", input = 0))
        refused(price("claude-opus-4").copy(effectiveFromDay = -1))
        refused(price("claude-opus-4"), price("claude-opus-4", input = 1))
        refused(price("claude-opus-4").copy(provider = "Anthropic!"))
        refused(*Array(ApiPrices.MAX_ROWS + 1) { price("model-$it") })
        assertEquals(2, ApiPrices.validate(listOf(price("claude-opus-4"), price("claude-opus-4", from = day))).size)
    }

    @Test fun theCreditRatioComesFromPricedUsageOnly() {
        val table = ApiPriceTable(listOf(price("claude-sonnet-4")))
        val priced = PricedTokens("anthropic", "claude-sonnet-4", day, TokenUsage(input = 1_000_000))
        val unpriced = PricedTokens("anthropic", "mystery", day, TokenUsage(input = 9_000_000))
        // 1M credits for $3 (3M micro-dollars): one third of a credit per micro-dollar.
        val ratio = CreditRatio.of(table, listOf(priced, unpriced))
        assertEquals(1.0 / 3, ratio.creditsPerMicro!!, 1e-12)
        assertEquals(listOf("anthropic/mystery"), ratio.unpricedModels)
        assertNull(CreditRatio.of(table, listOf(unpriced)).creditsPerMicro, "No priced traffic: no ratio")
        assertNull(CreditRatio.of(table, emptyList()).creditsPerMicro)
    }

    @Test fun theAnthropicListIsValidDatedAndPricesEachCurrentModel() {
        val rows = ApiPrices.validate(AnthropicListPrices.rows)
        assertTrue(rows.all { it.provider == "anthropic" && it.effectiveFromDay == AnthropicListPrices.CHECKED_ON_DAY })
        assertEquals(20_747, AnthropicListPrices.CHECKED_ON_DAY, "2026-10-21, the day the list was read")
        val table = ApiPriceTable(rows)
        fun prices(model: String) = table.priceFor("anthropic", model, AnthropicListPrices.CHECKED_ON_DAY)?.let {
            listOf(it.inputMicros, it.cacheWriteMicros, it.cacheReadMicros, it.outputMicros)
        }
        assertEquals(listOf(4_000_000L, 5_000_000L, 200_000L, 20_000_000L), prices("claude-opus-5-5"))
        assertEquals(listOf(5_000_000L, 6_250_000L, 500_000L, 25_000_000L), prices("claude-opus-5"), "The longer claude-opus-5-5 row does not price Opus 5")
        assertEquals(listOf(5_000_000L, 6_250_000L, 500_000L, 25_000_000L), prices("claude-opus-4-5-20251101"), "A dated identifier uses its family row")
        assertEquals(listOf(10_000_000L, 12_500_000L, 250_000L, 50_000_000L), prices("claude-fable-5-1"))
        assertEquals(listOf(10_000_000L, 12_500_000L, 1_000_000L, 50_000_000L), prices("claude-fable-5"))
        assertEquals(listOf(2_000_000L, 2_500_000L, 100_000L, 10_000_000L), prices("claude-sonnet-5-5"))
        assertEquals(listOf(2_000_000L, 2_500_000L, 200_000L, 10_000_000L), prices("claude-sonnet-5"))
        assertEquals(listOf(3_000_000L, 3_750_000L, 300_000L, 15_000_000L), prices("claude-sonnet-4-6"))
        assertEquals(listOf(100_000L, 125_000L, 10_000L, 500_000L), prices("claude-haiku-5-5"))
        assertEquals(listOf(1_000_000L, 1_250_000L, 100_000L, 5_000_000L), prices("claude-haiku-4-5"))
        assertNull(prices("claude-opus-4-1"), "Retired models are not loaded")
        assertNull(table.priceFor("anthropic", "claude-opus-5-5", AnthropicListPrices.CHECKED_ON_DAY - 1), "Nothing before the day the list was read")
    }

    @Test fun loadingTheListAddsOnlyMissingRowsAndIsIdempotent() {
        val typed = price("claude-opus-5-5", from = AnthropicListPrices.CHECKED_ON_DAY, input = 1)
        val other = price("claude-sonnet-4")
        val loaded = ApiPrices.withMissing(listOf(typed, other), AnthropicListPrices.rows)
        assertEquals(typed, loaded.single { it.model == "claude-opus-5-5" }, "A row the administrator typed for the same model and day is kept")
        assertTrue(other in loaded)
        assertEquals(AnthropicListPrices.rows.size + 1, loaded.size)
        assertEquals(loaded, ApiPrices.withMissing(loaded, AnthropicListPrices.rows), "Loading twice changes nothing")
    }
}
