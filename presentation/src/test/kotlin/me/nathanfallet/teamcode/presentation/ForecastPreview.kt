package me.nathanfallet.teamcode.presentation

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import me.nathanfallet.teamcode.domain.capacity.*

/** Generate a standalone preview through the real renderer using explicitly synthetic observations. */
fun main(args: Array<String>) {
    val target = Path.of(args.single()).toAbsolutePath()
    val now = Instant.parse("2026-10-19T12:00:00Z").toEpochMilli()
    val account = ForecastAccount("preview-account", "anthropic", "Team Max · synthetic preview", "READY", false, "engineering")
    fun series(window: String, values: List<Double>, reset: Long?, age: Long = 0): ForecastSeries {
        val samples = values.mapIndexed { index, used -> AccountQuota(account.id, window, used, now - age - 120_000 + index * 60_000, reset) }
        return BuildForecastSeriesUseCase()(samples.last(), samples, now)
    }
    val snapshot = ForecastSnapshot(now, listOf(account), account, listOf(
        series("five_hour", listOf(.44, .49, .53), now + 3_600_000),
        series("seven_day", listOf(.5, .5001, .5003), now + 12 * 3_600_000),
        series("model:unknown:seven_day", listOf(.24, .24, .24), null),
        series("model:stale:seven_day", listOf(.3, .31, .32), now + 3_600_000, 420_000)), false)
    Files.createDirectories(target.parent)
    Files.writeString(target, page("Quota forecasts · synthetic preview", "<p class='notice'>Synthetic preview only. No real account, provider call or production data is used.</p>" + forecastDashboard(snapshot)))
    println("Synthetic forecast preview: $target")
}
