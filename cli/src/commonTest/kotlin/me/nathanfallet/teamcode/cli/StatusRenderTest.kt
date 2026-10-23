package me.nathanfallet.teamcode.cli

import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.cli.status.*
import kotlin.test.*

class StatusRenderTest {
    private val now = 1_792_540_800_000L // 2026-10-21 00:00 UTC
    private val hour = 3_600_000L
    private val capacity = CapacityView("member-id", 41_666, 3, "dev", 14_300, 2, now + 24 * hour, weight = 5, poolTotalWeight = 12,
        poolDailyUnits = 100_000, queuedRequests = 1, groups = listOf(GroupAllowanceView("g", "Developers", 100_000, 4, false, 30_000, 2)), reservedUnits = 2_000)

    @Test fun accountsAreShownAndPrintedInTheOrderTheServerSent() {
        // The server sorts by next reset (unknown last); the CLI neither re-sorts the table nor the JSON, whatever the labels or resets say.
        val accounts = listOf(
            PoolAccountStatusView("Account 3", "anthropic", "READY", false, true, windows = listOf(AccountWindowView("five_hour", .1, now, now + hour))),
            PoolAccountStatusView("Account 1", "anthropic", "READY", false, true, windows = listOf(AccountWindowView("five_hour", .1, now, now + 2 * hour))),
            PoolAccountStatusView("Account 2", "anthropic", "READY", false, true, windows = listOf(AccountWindowView("five_hour", .1, now, now + 2 * hour))),
            PoolAccountStatusView("Account 4", "anthropic", "READY", false, true),
        )
        val report = StatusReport(GatewayStatus("pool", 0, 0, 0), null, null, accounts)
        val rows = renderStatus(report, now).lines().filter { it.startsWith("Account ") }
        assertEquals(listOf("Account 3", "Account 1", "Account 2", "Account 4"), rows.map { it.take(9) })
        assertEquals(listOf("Account 3", "Account 1", "Account 2", "Account 4"), Regex("\"label\":\"([^\"]+)\"").findAll(statusJson(report)).map { it.groupValues[1] }.toList())
    }

    @Test fun memberSeesOwnAllowanceAndNeutralAccounts() {
        val report = StatusReport(GatewayStatus("pool", 2, 10, 1), capacity, null, listOf(
            PoolAccountStatusView("Account 1", "anthropic", "READY", false, true, windows = listOf(
                AccountWindowView("five_hour", .42, now, now + 2 * hour + 10 * 60_000), AccountWindowView("seven_day", .8, now, now + 76 * hour),
                AccountWindowView("model:fable", 1.0, now, now + 76 * hour))),
            PoolAccountStatusView("Account 2", "anthropic", "READY", false, false, "cooling_down", now + 15 * 60_000),
            PoolAccountStatusView("Account 3", "anthropic", "RECONNECT_REQUIRED", false, false, "reconnect_required"),
        ))
        val text = renderStatus(report, now)
        assertEquals("""
            Mode: pool | Active: 2 | Waiting: 1 | Completed: 10

            Your quota today (resets 2026-10-22 00:00 UTC, in 1d 0h)
              Allocated   41.7K credits (pool share: weight 5 of 12 × 100.0K)
              Used        12.3K
              Reserved    2.0K (2 in flight, 1 waiting)
              Remaining   27.4K (65%)  ██████░░░░
              Concurrency 2 / 3
              Group       Developers: 30.0K / 100.0K credits, 2 / 4 active
              Pacing      none

            Pool accounts
            ACCOUNT    PROVIDER   STATE               5H                                WEEKLY                           OTHER       USABLE NOW
            Account 1  anthropic  READY               ████░░░░░░ 42% (reset in 2h 10m)  ████████░░ 80% (reset in 3d 4h)  fable 100%  yes
        """.trimIndent(), text.lines().take(14).joinToString("\n"))
        assertTrue(text.contains("no, cooling down until 2026-10-21 00:15 UTC, in 15m"), text)
        assertTrue(text.contains("no, reconnect required"), text)
    }

    @Test fun overridesCapsPacingAndMissingPartsAreExplained() {
        val override = capacity.copy(budgetOverride = BudgetOverrideSummaryView("o", 60_000, now + 6 * hour), baseDailyUnits = 41_666, dailyUnits = 60_000)
        val paced = DirectiveView("d", "SLOW_DOWN", 1, 10_000, "Slow down", now, now + 3 * 60_000)
        val text = renderStatus(StatusReport(GatewayStatus("pool", 0, 0), override, paced, emptyList()), now)
        assertTrue(text.contains("temporary override until 2026-10-21 06:00 UTC, in 6h 0m, normal 41.7K"), text)
        assertTrue(text.contains("Pacing      slow_down: at most 1 concurrent, 10000 ms between requests"), text)
        val spaced = renderStatus(StatusReport(GatewayStatus("pool", 0, 0), override, paced.copy(maxConcurrent = 0), emptyList()), now)
        assertTrue(spaced.contains("Pacing      slow_down: no concurrency cap, 10000 ms between requests"), spaced)
        assertTrue(text.endsWith("Pool accounts: none in your pools."), text)
        val capped = renderStatus(StatusReport(GatewayStatus("pool", 0, 0), capacity.copy(hardCapUnits = 41_666, baseDailyUnits = 41_666)), now)
        assertTrue(capped.contains("(personal cap)"), capped)
        assertTrue(capped.endsWith("Pool accounts: unavailable from this server or credential."), capped)
        assertTrue(renderStatus(StatusReport(GatewayStatus("pool", 0, 0)), now).contains("Your quota: unavailable from this server or credential."))
    }

    @Test fun serverTextCannotInjectTerminalControlsAndJsonRoundTrips() {
        val report = StatusReport(GatewayStatus("pool", 0, 0), capacity, null, listOf(PoolAccountStatusView("Evil\u001b[2Jname", "anthropic", "READY", false, true)))
        assertFalse(renderStatus(report, now).contains('\u001b'))
        assertEquals(report, Serialization.json.decodeFromString<StatusReport>(Serialization.json.encodeToString(report)))
    }

    @Test fun barsFillInProportionAndNeverHideSmallUsage() {
        assertEquals("░░░░░░░░░░", bar(0.0)); assertEquals("█░░░░░░░░░", bar(.01)); assertEquals("█████░░░░░", bar(.5))
        assertEquals("██████████", bar(1.0)); assertEquals("██████████", bar(1.7)); assertEquals("░░░░░░░░░░", bar(-.2))
    }

    @Test fun colorIsOptInAndKeepsColumnsAligned() {
        val report = StatusReport(GatewayStatus("pool", 0, 0), capacity, null, listOf(
            PoolAccountStatusView("Account 1", "anthropic", "READY", false, false, "quota_exhausted", windows = listOf(AccountWindowView("seven_day", 1.0, now, now + hour))),
            PoolAccountStatusView("Account 2", "anthropic", "READY", false, true, windows = listOf(AccountWindowView("five_hour", .1, now, now + hour))),
        ))
        val plain = renderStatus(report, now)
        assertFalse(plain.contains('\u001b'))
        val colored = renderStatus(report, now, color = true)
        assertTrue(colored.contains("\u001b[31m██████████ 100%\u001b[0m"), colored)
        assertTrue(colored.contains("\u001b[32m█░░░░░░░░░ 10%\u001b[0m"), colored)
        // Without the color codes the colored table is the plain one.
        assertEquals(plain, colored.replace(Regex("\u001b\\[[0-9;]*m"), ""))
    }

    @Test fun durationsAreReadable() {
        assertEquals("0m", duration(-5)); assertEquals("1m", duration(1)); assertEquals("2h 10m", duration(130 * 60_000L)); assertEquals("3d 4h", duration(76 * hour))
    }
}
