package me.nathanfallet.teamcode.api.models

import kotlinx.serialization.Serializable

/** Administrative account labels without provider identities or credentials. */
@Serializable
data class ForecastAccountView(val id: String, val provider: String, val displayName: String, val state: String, val disabled: Boolean, val poolId: String?, val email: String? = null)

/** One real measurement; absent measurements are not represented by zeroes. */
@Serializable
data class ForecastSampleView(val utilization: Double, val observedAtMillis: Long, val resetsAtMillis: Long?)

/** Segments never connect across a reset, a stale gap or a decrease in utilization. */
@Serializable
data class ForecastWindowView(val window: String, val utilization: Double, val observedAtMillis: Long, val resetsAtMillis: Long?, val state: String, val pointsPerHour: Double?, val reachesThresholdAtMillis: Long?, val segments: List<List<ForecastSampleView>>, val learningSamples: Int, val learningSpanMillis: Long)

/** Bounded snapshot for one selected subscription; truncated data is explicitly flagged. */
@Serializable
data class ForecastSnapshotView(val generatedAtMillis: Long, val accounts: List<ForecastAccountView>, val account: ForecastAccountView?, val windows: List<ForecastWindowView>, val truncated: Boolean, val pressureThreshold: Double)
