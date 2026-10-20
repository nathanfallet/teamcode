package me.nathanfallet.teamcode.presentation

import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import me.nathanfallet.teamcode.domain.capacity.*
import org.xml.sax.InputSource
import kotlin.test.*

class ForecastChartTest {
    private val now = 2_000_000L
    private fun q(age: Long, used: Double, reset: Long? = now + 3_600_000) = AccountQuota("account", "five_hour", used, now - age, reset)
    private fun series(samples: List<AccountQuota>) = BuildForecastSeriesUseCase()(samples.last(), samples, now)

    @Test fun validSvgPreservesDiscontinuitiesAndNeverProjectsUnknownOrStaleData() {
        val data = series(listOf(q(900_000, .1), q(840_000, .2), q(480_000, .3), q(420_000, .1), q(360_000, .2, null), q(300_000, .3, null)))
        val svg = forecastChart(data, now, .9, "quota-test")
        val factory = DocumentBuilderFactory.newInstance().apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        val xml = factory.newDocumentBuilder().parse(InputSource(StringReader(svg)))
        assertEquals("svg", xml.documentElement.nodeName)
        assertEquals("img", xml.documentElement.getAttribute("role"))
        assertEquals(4, xml.getElementsByTagName("polyline").length)
        assertEquals(6, xml.getElementsByTagName("circle").length)
        assertFalse(svg.contains("quota-projection"))
        val stale = series(listOf(q(520_000, .1), q(460_000, .2), q(400_000, .3)))
        assertFalse(forecastChart(stale, now, .9, "stale").contains("quota-projection"))
    }

    @Test fun projectionStopsAtResetAndSvgCoordinatesIgnoreHostNumberLocale() {
        val reset = now + 1_800_000
        val data = series(listOf(q(120_000, .1, reset), q(60_000, .10001, reset), q(0, .10002, reset)))
        assertEquals(QuotaForecast.State.RESET_FIRST, data.forecast.state)
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            val svg = forecastChart(data, now, .9, "reset")
            assertTrue(svg.contains("class=\"quota-projection\""))
            assertTrue(svg.contains("x2=\"477.0\""), "A reset halfway through the future hour clips the projection")
            assertFalse(svg.contains("NaN")); assertFalse(svg.contains("Infinity"))
        } finally { Locale.setDefault(previous) }
    }

    @Test fun absentAccountsAndMeasurementsRemainExplicitlyUnknown() {
        assertTrue(forecastDashboard(ForecastSnapshot(now, emptyList(), null, emptyList(), false)).contains("No subscription accounts"))
        val account = ForecastAccount("account", "anthropic", "Fixture", "READY", false, null)
        val html = forecastDashboard(ForecastSnapshot(now, listOf(account), account, emptyList(), false))
        assertTrue(html.contains("No provider windows have been observed"))
        assertFalse(html.contains("% observed")); assertFalse(html.contains("<svg"))
    }
}
