package com.noop.ui

import com.noop.analytics.RestScorer
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins ONE nightly sleep need across the Sleep tab: the hours-vs-needed tile, its detail sheet and the debt
 * ledger's "Per-night need" all read [RestScorer.personalNeedMinutes]. They used to read two estimators (a
 * 7.5 h-floored mean and the upper-quartile personal need), so the same night could name two needs.
 */
class SleepNeedUnificationTest {

    private val pinnedToday = "2026-06-11"

    private fun day(d: String, asleep: Double?) = DailyMetric(
        deviceId = "my-whoop", day = d, totalSleepMin = asleep,
        deepMin = 80.0, remMin = 90.0, lightMin = 200.0, efficiency = 90.0,
    )

    /** Ten nights, enough to clear the 7-night minimum, with long nights pulling the upper quartile above 8 h. */
    private val tenNights = listOf(
        420.0, 450.0, 500.0, 530.0, 540.0, 430.0, 560.0, 470.0, 520.0, 510.0,
    ).mapIndexed { i, m -> day("2026-06-%02d".format(i + 1), m) }

    @Test
    fun personalNeedMinutesWrapsTheHourEstimator() {
        val minutes = tenNights.map { it.totalSleepMin }
        val hours = RestScorer.personalizedNeedHours(minutes.mapNotNull { it?.div(60.0) }, null)
        assertEquals(hours * 60.0, RestScorer.personalNeedMinutes(minutes), 1e-9)
    }

    @Test
    fun nullAndZeroNightsCountAsNoData() {
        // Fewer than 7 real nights once null/zero are dropped → the 8 h population target.
        val sparse = listOf(null, 0.0, 300.0, 310.0, null, 320.0)
        assertEquals(480.0, RestScorer.personalNeedMinutes(sparse), 1e-9)
    }

    @Test
    fun personalNeedNeverDropsBelowTheAdultTarget() {
        // A chronic six-hour sleeper must not learn a six-hour need (that would erase their debt).
        val short = (1..10).map { 360.0 }
        assertEquals(480.0, RestScorer.personalNeedMinutes(short), 1e-9)
    }

    @Test
    fun hoursVsNeededTileAndDebtLedgerShareOneNeed() {
        val need = RestScorer.personalNeedMinutes(tenNights.map { it.totalSleepMin })
        val m = buildSleepModel(tenNights, session = null, todayKey = pinnedToday)!!
        val latestAsleep = tenNights.last().totalSleepMin!!
        assertEquals(latestAsleep / need * 100.0, m.hoursVsNeeded.latest!!, 1e-9)
        assertEquals(need, m.sleepDebtLedger.needMin, 1e-9)
    }

    @Test
    fun detailSheetMeasuresAgainstTheSameNeed() {
        val need = RestScorer.personalNeedMinutes(tenNights.map { it.totalSleepMin })
        val points = buildSleepMetricPoints(tenNights, "hours_vs_needed")
        val latestAsleep = tenNights.last().totalSleepMin!!
        assertEquals(minOf(100.0, latestAsleep / need * 100.0), points.last().second, 1e-9)
    }
}
