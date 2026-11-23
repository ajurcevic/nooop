package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.analytics.SkinTempDisplay
import com.noop.analytics.TrainingLoadEngine
import com.noop.analytics.WeeklyDigestEngine
import com.noop.data.DailyMetric
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Trends (Zoop "Calm" redesign)
//
// The long view. One range control (1M / 3M / 6M / 1Y / All) and one caption that explains every number, then
// This week (Charge, Effort and Rest against the week before, browsable week by week) and four sections:
// Recovery, Sleep, Training and Body. A section's numbers are averages for the period with an arrow against
// the same span just before it, and its charts shade the owner's normal range. The maths lives in
// TrendsInsight.kt and the drawing in TrendsCalmCards.kt.
//
// Data: full history is loaded once via repo.daysMerged; until it arrives the reactive recentDays flow backs
// the screen, so it is never empty when data exists. Rest is the sleep_performance composite (the number the
// Today Rest score shows), and weight comes from Apple Health / Health Connect like the Metrics Weight tile.
//
// The Today-hosted trend cards (TrendHostCard, further down) keep their own chart card and trailing month.

@Composable
fun TrendsScreen(vm: AppViewModel) {
    // Reactive cache (oldest → newest) as the immediate backing.
    val reactiveDays by vm.recentDays.collectAsStateWithLifecycle()

    // Full history loaded once for the long ranges; falls back to the flow until it lands so the screen is
    // populated on first frame when any data exists.
    var fullHistory by remember { mutableStateOf<List<DailyMetric>?>(null) }
    LaunchedEffect(Unit) {
        // Merged: imported WHOOP days win; on-device computed days gap-fill the trends. Reads the registry's
        // ACTIVE strap id so daysMerged resolves the active-id ∪ canonical "my-whoop" union (SPINE / #814),
        // a re-added strap's data and the canonical import both surface; a single-WHOOP install is unchanged.
        fullHistory = vm.repo.daysMerged(vm.activeStrapId)
    }
    val days = fullHistory ?: reactiveDays

    val context = LocalContext.current
    // Display preferences, read on each composition like the rest of the app (returning from Settings
    // recomposes the screen). They only change how numbers read, never which numbers they are.
    val effortScale = UnitPrefs.effortScale(context)
    val unitSystem = UnitPrefs.system(context)
    val fahrenheit = UnitPrefs.temperature(context) == TemperatureUnit.FAHRENHEIT
    val preferSkinDeviation = UnitPrefs.skinTempPreferred(context) == SkinTempDisplay.Kind.DEVIATION

    // Day-cycle sky backdrop (#698) and sky-behind-cards (#434 family), read once like Today.
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(context) }
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(context) }

    var range by remember { mutableStateOf(TrendsRange.Quarter) }

    // #710: browse earlier weeks on the This-week card. 0 = the week containing today; each step back is one
    // Monday-to-Sunday week earlier, clamped so it never runs past the earliest day we hold.
    var weekOffset by remember { mutableStateOf(0) }
    val minWeekOffset = remember(days) { minWeekOffset(days) }
    LaunchedEffect(minWeekOffset) { weekOffset = weekOffset.coerceIn(minWeekOffset, 0) }

    // Rest = the sleep_performance COMPOSITE (0–100), the SAME metric the Today Rest score and the Sleep Rest
    // detail plot (#614 follow-up, #732). It is a resolved metric series, not a DailyMetric column.
    var sleepPerfByDay by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(days) {
        sleepPerfByDay = runCatching {
            vm.repo.resolvedSeries("sleep_performance", "my-whoop", "0000-00-00", "9999-99-99",
                strapDeviceId = vm.activeStrapId)
                .values.associate { it.first to it.second }
        }.getOrDefault(emptyMap())
    }

    // Every measured body weight by day, with the Metrics Weight tile's Health Connect rule.
    var weightByDay by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    val weightFromHealthConnect = ProfileStore.from(context).useHealthConnectWeight
    LaunchedEffect(days, weightFromHealthConnect) {
        weightByDay = runCatching {
            val apple = vm.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31")
            val healthConnect = vm.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31")
            weightHistoryKg(apple, healthConnect, healthConnectOnly = weightFromHealthConnect)
        }.getOrDefault(emptyMap())
    }

    // The period ends on the logical day (the one the day rows are keyed by), refreshed with the data.
    // HOISTED above the lazy scaffold: remember can't run inside the LazyListScope content lambda.
    val today = remember(days) { logicalDayNow() }
    val histories = remember(days) { MetricHistories(days) }
    val series = remember(histories, sleepPerfByDay, weightByDay) {
        TrendsSeries(
            charge = histories.charge,
            hrv = histories.hrv,
            restingHr = histories.restingHr,
            effort = histories.effort,
            rest = sleepPerfByDay,
            weight = weightByDay,
            skinDeviation = histories.skinDeviation,
            skinAbsolute = histories.skinAbsolute,
        )
    }
    val model = remember(series, range, today, preferSkinDeviation) {
        trendsPeriodModel(series, range.days, today, preferSkinDeviation)
    }
    // Training load models the full history, not the period: chronic load is a 42-day horizon by nature.
    // Days without an Effort are left out so the model runs through the latest day that has one.
    val training = remember(days) {
        TrainingLoadEngine.evaluate(
            days.filter { it.strain != null }.map { TrainingLoadEngine.DailyLoad(it.day, it.strain) },
        )
    }
    val monday = remember(today, weekOffset) {
        today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
    }
    val week = remember(series, monday) {
        listOf(weekSummary(series.charge, monday), weekSummary(series.effort, monday), weekSummary(series.rest, monday))
    }

    LazyScreenScaffold(
        title = stringResource(R.string.nav_trends),
        // LIQUID SKY BACKDROP (LiquidScreenSky.kt): the static time-of-day sky behind the header and top rows,
        // gated on the same day-cycle pref as Today; when off, the scaffold paints the flat canvas.
        topBackground = screenBackdropSlot(showDayCycleBackground, skyBehindCards),
        fullBleedBackground = screenBackdropFullBleed(showDayCycleBackground, skyBehindCards),
    ) {
        if (days.isEmpty()) {
            item { EmptyTrends() }
            return@LazyScreenScaffold
        }

        // --- Range control and the one caption that explains every number below. ---
        item {
            Column(
                modifier = Modifier.staggeredAppear(index = 0),
                verticalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                SegmentedPillControl(
                    items = TrendsRange.entries.toList(),
                    selection = range,
                    label = { it.label() },
                    onSelect = { range = it },
                    modifier = Modifier.fillMaxWidth(),
                    adaptsToAvailableWidth = true,
                )
                val periodDays = range.days
                if (periodDays != null) {
                    TrendsPeriodCaption(
                        lead = uiString(R.string.trends_caption_lead_days, periodDays),
                        body = uiString(R.string.trends_caption_period, periodDays),
                    )
                } else {
                    TrendsPeriodCaption(
                        lead = uiString(R.string.trends_caption_lead_all),
                        body = uiString(R.string.trends_caption_all),
                    )
                }
            }
        }

        // --- This week: Charge, Effort and Rest against the week before, with a bar per day. ---
        item {
            TrendsWeekCard(
                title = weekTitle(weekOffset),
                subtitle = uiString(R.string.trends_week_subtitle, trendsWeekRangeLabel(monday)),
                scores = weekScores(week, effortScale),
                canGoBack = weekOffset > minWeekOffset,
                canGoForward = weekOffset < 0,
                onStep = { step -> weekOffset = (weekOffset + step).coerceIn(minWeekOffset, 0) },
                modifier = Modifier.staggeredAppear(index = 1),
            )
        }

        // --- Recovery: the Charge line, its stats, HRV and resting heart rate, and every day's Charge. ---
        item {
            val charge = model.charge
            TrendsSectionCard(
                category = MetricCategory.RECOVERY,
                line = trendsRecoveryLine(model),
                modifier = Modifier.staggeredAppear(index = 2),
            ) {
                TrendHeadline(
                    TrendHeadlineUi(
                        label = uiString(R.string.trends_charge),
                        detail = null,
                        value = charge.average?.let(::trendsWhole),
                        valueColor = charge.average?.let { Palette.recoveryColor(it) } ?: Palette.textPrimary,
                        delta = charge.delta,
                        tone = comparisonTone(charge.average, charge.previousAverage, TrendRule.HIGHER_IS_BETTER),
                        formatDelta = ::trendsWhole,
                    ),
                )
                if (model.chargeLine.size >= 2) {
                    ChargeLineChart(model.chargeLine, model.axis, charge.band)
                }
                if (charge.average != null) {
                    TrendsBlockDivider()
                    TrendStatsRow(
                        listOf(
                            uiString(R.string.trends_avg) to trendsWhole(charge.average),
                            uiString(R.string.trends_peak) to (charge.peak?.let(::trendsWhole) ?: TRENDS_EMPTY_VALUE),
                            uiString(R.string.trends_low) to (charge.low?.let(::trendsWhole) ?: TRENDS_EMPTY_VALUE),
                            uiString(R.string.trends_days) to charge.points.size.toString(),
                        ),
                    )
                }
                if (model.hrv.average != null || model.restingHr.average != null) {
                    TrendsBlockDivider()
                    TrendMiniPair(
                        left = vitalMini(model.hrv, R.string.trends_hrv_full, "ms", Palette.metricPurple, TrendRule.HIGHER_IS_BETTER),
                        right = vitalMini(model.restingHr, R.string.trends_resting_hr_full, "bpm", Palette.metricRose, TrendRule.LOWER_IS_BETTER),
                        axis = model.axis,
                    )
                }
                if (charge.points.size >= 2) {
                    TrendsBlockDivider()
                    ChargeDayStrip(
                        points = charge.epochDays,
                        axis = model.axis,
                        lowPeak = if (charge.low != null && charge.peak != null) {
                            uiString(R.string.trends_low_peak, trendsWhole(charge.low), trendsWhole(charge.peak))
                        } else {
                            null
                        },
                    )
                }
            }
        }

        // --- Sleep: Rest's period average and its weekly (or monthly) bars. ---
        item {
            val rest = model.rest
            val period = barPeriodFor(model.days)
            TrendsSectionCard(
                category = MetricCategory.SLEEP,
                line = trendsSleepLine(model),
                modifier = Modifier.staggeredAppear(index = 3),
            ) {
                TrendHeadline(
                    TrendHeadlineUi(
                        label = uiString(R.string.trends_rest),
                        detail = uiString(if (period == BarPeriod.WEEK) R.string.trends_weekly else R.string.trends_monthly),
                        value = rest.average?.let(::trendsWhole),
                        valueColor = rest.average?.let { homeToneColor(restTone(it)) } ?: Palette.textPrimary,
                        delta = rest.delta,
                        tone = comparisonTone(rest.average, rest.previousAverage, TrendRule.HIGHER_IS_BETTER),
                        formatDelta = ::trendsWhole,
                    ),
                )
                RestPeriodBars(
                    bars = periodBars(rest.points, period),
                    band = rest.band,
                    barColor = { homeToneColor(restTone(it)) },
                    format = ::trendsWhole,
                )
            }
        }

        // --- Training: Effort's daily bars, then fitness against fatigue. ---
        item {
            val effort = model.effort
            val factor = UnitFormatter.effortValue(1.0, effortScale)
            val scaleMax = UnitFormatter.effortValue(100.0, effortScale)
            val load = trainingLoadUi(training, model.axis, effortScale)
            TrendsSectionCard(
                category = MetricCategory.TRAINING,
                line = trendsTrainingLine(model, training, load != null, effortScale),
                modifier = Modifier.staggeredAppear(index = 4),
            ) {
                TrendHeadline(
                    TrendHeadlineUi(
                        label = uiString(R.string.trends_effort),
                        detail = uiString(R.string.trends_daily_out_of, UnitFormatter.effortScaleMax(effortScale)),
                        value = effort.average?.let { effortText(it, effortScale) },
                        valueColor = effort.average?.let { Palette.strainColor(it) } ?: Palette.textPrimary,
                        delta = effort.delta,
                        tone = null,
                        formatDelta = { effortText(it, effortScale) },
                    ),
                )
                if (effort.points.isNotEmpty()) {
                    EffortDayBars(
                        points = effort.epochDays.map { (day, value) -> day to value * factor },
                        axis = model.axis,
                        scaleMax = scaleMax,
                        grid = if (effortScale == EffortScale.WHOOP) listOf(0.0, 7.0, 14.0, 21.0) else listOf(0.0, 25.0, 50.0, 75.0, 100.0),
                        band = effort.band?.let { (low, high) -> low * factor to high * factor },
                        format = ::trendsWhole,
                    )
                }
                if (load != null || !training.isAvailable) {
                    TrendsBlockDivider()
                    TrainingLoadBlock(
                        subtitle = trainingLoadSubtitle(training),
                        load = load,
                        note = if (training.isAvailable) {
                            null
                        } else {
                            uiString(R.string.trends_tl_needs, TrainingLoadEngine.standard.minimumDays, training.contiguousDays)
                        },
                        axis = model.axis,
                    )
                }
            }
        }

        // --- Body: weight and skin temperature. ---
        item {
            val weightFormat: (Double) -> String = { kg ->
                trendsDecimal(if (unitSystem == UnitSystem.IMPERIAL) UnitFormatter.kgToPounds(kg) else kg)
            }
            val weightUnit = UnitFormatter.massUnit(unitSystem)
            val skinKind = if (model.skinIsDeviation) SkinTempDisplay.Kind.DEVIATION else SkinTempDisplay.Kind.ABSOLUTE
            val skinFormat: (Double) -> String = { SkinTempDisplay.numberString(it, skinKind, fahrenheit) }
            val skinDeltaFormat: (Double) -> String = { trendsDecimal(if (fahrenheit) it * 9.0 / 5.0 else it) }
            val skinUnit = if (fahrenheit) "°F" else "°C"
            val weight = model.weight
            val skin = model.skin
            TrendsSectionCard(
                category = MetricCategory.BODY,
                line = trendsBodyLine(model, weightFormat, weightUnit, skinFormat, skinDeltaFormat, skinUnit),
                modifier = Modifier.staggeredAppear(index = 5),
            ) {
                if (weight.average != null || skin.average != null) {
                    TrendMiniPair(
                        left = weight.average?.let { average ->
                            TrendMiniUi(
                                title = uiString(R.string.today_metric_weight),
                                value = weightFormat(average),
                                unitAvg = uiString(R.string.trends_unit_avg, weightUnit),
                                valueColor = Palette.metricCyan,
                                delta = weight.delta,
                                tone = null,
                                formatDelta = weightFormat,
                                deltaUnit = null,
                                points = weight.epochDays,
                                band = weight.band,
                                lineColor = Palette.metricCyan,
                                maxGapDays = TRENDS_WEIGHT_MAX_GAP_DAYS,
                            )
                        },
                        right = skin.average?.let { average ->
                            TrendMiniUi(
                                title = uiString(R.string.today_card_skin_temp_subtitle),
                                value = skinFormat(average),
                                unitAvg = uiString(R.string.trends_unit_avg, skinUnit),
                                valueColor = Palette.metricCyan,
                                delta = skin.delta,
                                tone = comparisonTone(average, skin.previousAverage, TrendRule.STEADY_IS_BEST),
                                formatDelta = skinDeltaFormat,
                                deltaUnit = null,
                                points = skin.epochDays,
                                band = skin.band,
                                lineColor = Palette.metricCyan,
                            )
                        },
                        axis = model.axis,
                    )
                }
            }
        }
    }
}

/** Longest gap, in days, a weight sparkline bridges: weigh-ins are often days or weeks apart. */
private const val TRENDS_WEIGHT_MAX_GAP_DAYS = 45

// MARK: - Range control

/** The Trends range control: the last 30, 90, 180 or 365 days, or all history. */
private enum class TrendsRange(val days: Int?, private val shortLabel: String?) {
    Month(30, "1M"),
    Quarter(90, "3M"),
    Half(180, "6M"),
    Year(365, "1Y"),
    All(null, null);

    /** The pill's label; "All" is a word, so it is translated. */
    fun label(): String = shortLabel ?: uiString(R.string.trends_range_all)

    /** This range plus every longer one, shortest first: the order a hosted card widens through. */
    val widening: List<TrendsRange>
        get() = entries.dropWhile { it != this }
}

// MARK: - This week

/**
 * The most-negative weekOffset allowed: the number of whole Mon–Sun weeks between the earliest day we
 * hold and this week. Beyond it there's no data to show, so the back button disables. 0 when history
 * is empty or unparseable (so we stay on this week). `days` is oldest → newest. Mirrors iOS minWeekOffset.
 */
private fun minWeekOffset(days: List<DailyMetric>): Int {
    val earliest = days.firstOrNull()?.day ?: return 0
    val earliestMon = WeeklyDigestEngine.mondayOfWeek(earliest) ?: return 0
    val thisMon = WeeklyDigestEngine.mondayOfWeek(logicalDayKeyNow()) ?: return 0
    var off = 0
    var mon = thisMon
    // Walk weeks back until we pass the earliest week. Hard cap ~10 years so a bad date can't spin.
    while (mon > earliestMon && off > -520) {
        mon = WeeklyDigestEngine.addDays(mon, -7)
        off -= 1
    }
    return off
}

/** "This week", "Last week" or "3 weeks ago". */
private fun weekTitle(weekOffset: Int): String = when (weekOffset) {
    0 -> uiString(R.string.trends_this_week)
    -1 -> uiString(R.string.trends_last_week)
    else -> uiPlural(R.plurals.trends_weeks_ago, -weekOffset, -weekOffset)
}

/** Charge, Effort and Rest for the This-week card, from the week's three summaries in that order. */
private fun weekScores(week: List<WeekSummary>, effortScale: EffortScale): List<WeekScoreUi> {
    val (charge, effort, rest) = week
    return listOf(
        WeekScoreUi(
            label = uiString(R.string.trends_charge),
            value = charge.average?.let(::trendsWhole),
            valueColor = charge.average?.let { Palette.recoveryColor(it) } ?: Palette.textPrimary,
            delta = charge.delta,
            tone = comparisonTone(charge.average, charge.previousAverage, TrendRule.HIGHER_IS_BETTER),
            formatDelta = ::trendsWhole,
            daily = charge.daily,
            barColor = { Palette.recoveryColor(it) },
        ),
        WeekScoreUi(
            label = uiString(R.string.trends_effort),
            value = effort.average?.let { effortText(it, effortScale) },
            valueColor = effort.average?.let { Palette.strainColor(it) } ?: Palette.textPrimary,
            delta = effort.delta,
            tone = null,
            formatDelta = { effortText(it, effortScale) },
            daily = effort.daily,
            barColor = { Palette.strainColor(it) },
        ),
        WeekScoreUi(
            label = uiString(R.string.trends_rest),
            value = rest.average?.let(::trendsWhole),
            valueColor = rest.average?.let { homeToneColor(restTone(it)) } ?: Palette.textPrimary,
            delta = rest.delta,
            tone = comparisonTone(rest.average, rest.previousAverage, TrendRule.HIGHER_IS_BETTER),
            formatDelta = ::trendsWhole,
            daily = rest.daily,
            barColor = { homeToneColor(restTone(it)) },
        ),
    )
}

// MARK: - Section lines and models

/** HRV or resting heart rate as a small card: its average in the metric's hue, the arrow, and its sparkline. */
private fun vitalMini(stats: TrendPeriodStats, title: Int, unit: String, color: Color, rule: TrendRule): TrendMiniUi? {
    val average = stats.average ?: return null
    return TrendMiniUi(
        title = uiString(title),
        value = trendsWhole(average),
        unitAvg = uiString(R.string.trends_unit_avg, unit),
        valueColor = color,
        delta = stats.delta,
        tone = comparisonTone(average, stats.previousAverage, rule),
        formatDelta = ::trendsWhole,
        deltaUnit = unit,
        points = stats.epochDays,
        band = stats.band,
        lineColor = color,
    )
}

/** The Recovery line: Charge's move on the period before, crediting HRV only when it moved the same way. */
private fun trendsRecoveryLine(model: TrendsPeriodModel): String {
    val charge = model.charge
    val sentence = recoveryTrendSentence(
        charge = trendDirection(charge.delta, charge.previousAverage),
        hrv = trendDirection(model.hrv.delta, model.hrv.previousAverage),
        hasValues = charge.average != null,
    )
    return sentenceText(sentence, charge.delta?.let { trendsWhole(abs(it)) }, charge.average?.let(::trendsWhole), model.days)
}

/** The Sleep line: Rest's move on the period before. */
private fun trendsSleepLine(model: TrendsPeriodModel): String {
    val rest = model.rest
    val sentence = sleepTrendSentence(trendDirection(rest.delta, rest.previousAverage), rest.average != null)
    return sentenceText(sentence, rest.delta?.let { trendsWhole(abs(it)) }, rest.average?.let(::trendsWhole), model.days)
}

/**
 * The Training line: where fitness is heading and how fresh the owner is while training load is on screen,
 * else Effort's move on the period before.
 */
private fun trendsTrainingLine(
    model: TrendsPeriodModel,
    training: TrainingLoadEngine.Result,
    loadShown: Boolean,
    effortScale: EffortScale,
): String {
    val latest = training.points.lastOrNull()
    if (loadShown && latest != null) {
        val sentences = trainingLoadSentences(fitnessDirection(training.points), trainingForm(latest)).map { uiString(it) }
        return if (sentences.size == 2) uiString(R.string.metrics_two_sentences, sentences[0], sentences[1]) else sentences.first()
    }
    val effort = model.effort
    val sentence = effortTrendSentence(
        trendDirection(effort.delta, effort.previousAverage, tolerance = EFFORT_TYPICAL_TOLERANCE),
        effort.average != null,
    )
    return sentenceText(
        sentence,
        effort.delta?.let { effortText(abs(it), effortScale) },
        effort.average?.let { effortText(it, effortScale) },
        model.days,
    )
}

/** The Body line: a sentence for weight and one for skin temperature, each only when the period has readings. */
private fun trendsBodyLine(
    model: TrendsPeriodModel,
    weightFormat: (Double) -> String,
    weightUnit: String,
    skinFormat: (Double) -> String,
    skinDeltaFormat: (Double) -> String,
    skinUnit: String,
): String {
    val weight = model.weight
    val weightSentence = weightTrendSentence(
        trendDirection(weight.delta, weight.previousAverage, steadyBand = TRENDS_WEIGHT_STEADY_KG),
        weight.average != null,
    )?.let {
        sentenceText(
            it,
            weight.delta?.let { delta -> withUnit(weightFormat(abs(delta)), weightUnit) },
            weight.average?.let { average -> withUnit(weightFormat(average), weightUnit) },
            model.days,
        )
    }
    val skin = model.skin
    val skinSentence = skinTrendSentence(
        trendDirection(skin.delta, skin.previousAverage, steadyBand = TRENDS_SKIN_STEADY_C),
        skin.average != null,
    )?.let {
        sentenceText(
            it,
            skin.delta?.let { delta -> withUnit(skinDeltaFormat(abs(delta)), skinUnit) },
            skin.average?.let { average -> withUnit(skinFormat(average), skinUnit) },
            model.days,
        )
    }
    return when {
        weightSentence != null && skinSentence != null ->
            uiString(R.string.metrics_two_sentences, weightSentence, skinSentence)
        weightSentence != null -> weightSentence
        skinSentence != null -> skinSentence
        else -> uiString(R.string.trends_body_empty)
    }
}

/** A section sentence with its arguments filled in: the change and the period's length, or the average. */
private fun sentenceText(sentence: TrendSentence, change: String?, level: String?, days: Int?): String =
    when (sentence.args) {
        SentenceArgs.CHANGE -> uiString(sentence.res, change.orEmpty(), days ?: 0)
        SentenceArgs.LEVEL -> uiString(sentence.res, level.orEmpty())
        SentenceArgs.NONE -> uiString(sentence.res)
    }

/** "0.6 kg": a number and its unit, joined the way the reader's language does. */
private fun withUnit(number: String, unit: String): String = uiString(R.string.trends_value_unit, number, unit)

/**
 * Fitness and fatigue inside the period, on the owner's Effort scale, with the form chip's text. Null while the
 * model is unavailable, or when its latest day falls before the period (a strap left in a drawer for months).
 */
private fun trainingLoadUi(training: TrainingLoadEngine.Result, axis: TrendAxis, effortScale: EffortScale): TrainingLoadUi? {
    if (!training.isAvailable) return null
    val latest = training.points.lastOrNull() ?: return null
    val factor = UnitFormatter.effortValue(1.0, effortScale)
    val fitness = epochPoints(training.points.map { it.day to it.chronicLoad * factor }).filter { it.first >= axis.startDay }
    val fatigue = epochPoints(training.points.map { it.day to it.acuteLoad * factor }).filter { it.first >= axis.startDay }
    if (fitness.isEmpty()) return null
    val form = trainingForm(latest)
    val balance = String.format(Locale.getDefault(), "%+.1f", latest.balance * factor)
    return TrainingLoadUi(
        fitness = fitness,
        fatigue = fatigue,
        form = form,
        formText = uiString(R.string.trends_form_chip, balance, uiString(trainingFormWord(form))),
        format = ::trendsDecimal,
    )
}

/** What the training-load block compares, or how far along the model is while it builds. */
private fun trainingLoadSubtitle(training: TrainingLoadEngine.Result): String = when (training.state) {
    TrainingLoadEngine.State.ESTABLISHED -> uiString(R.string.trends_tl_established)
    TrainingLoadEngine.State.BUILDING ->
        uiString(R.string.trends_tl_building, training.contiguousDays, TrainingLoadEngine.standard.establishedDays)
    TrainingLoadEngine.State.UNAVAILABLE -> uiString(R.string.trends_tl_unavailable)
}

/** Effort on the owner's scale: one decimal out of 21, a whole number out of 100. */
private fun effortText(stored: Double, scale: EffortScale): String {
    val shown = UnitFormatter.effortValue(stored, scale)
    return if (scale == EffortScale.WHOOP) trendsDecimal(shown) else trendsWhole(shown)
}

/** A whole number, rounded: Charge, Rest, HRV, resting heart rate. */
private fun trendsWhole(value: Double): String = value.roundToInt().toString()

/** One decimal in the reader's locale: weight, temperatures, Effort out of 21. */
private fun trendsDecimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)

// MARK: - Resolved metric (the Today-hosted trend cards)

/** A metric's window for a hosted card: its plotted values and the day-string of each point. */
private data class ResolvedMetric(
    val values: List<Double>,
    val dates: List<String>,
)

/**
 * Walk the widening order once: take the smallest range ≥ selected whose window holds
 * ≥1 non-null point for [value]; if none do, fall back to all history.
 */
private fun resolveMetric(
    days: List<DailyMetric>,
    selected: TrendsRange,
    value: (DailyMetric) -> Double?,
): ResolvedMetric {
    for (r in selected.widening) {
        val pts = windowPoints(days, r, value)
        if (pts.isNotEmpty()) return ResolvedMetric(values = pts.map { it.second }, dates = pts.map { it.first })
    }
    val pts = windowPoints(days, TrendsRange.All, value)
    return ResolvedMetric(values = pts.map { it.second }, dates = pts.map { it.first })
}

/**
 * Non-null metric points (day, value) within [range]'s trailing window (oldest → newest). `days` is the
 * full oldest-first history. A null `range.days` (all history) returns every non-null point. The day
 * string is carried alongside each value so the chart can draw a real date X-axis.
 */
private fun windowPoints(
    days: List<DailyMetric>,
    range: TrendsRange,
    value: (DailyMetric) -> Double?,
): List<Pair<String, Double>> {
    if (days.isEmpty()) return emptyList()
    val sliced = when (val n = range.days) {
        null -> days
        // Trailing N CALENDAR days ending today, anchored to the phone's date, NOT the last N rows
        // (which on a stale import made months-old data fill the short windows, looking current, #23).
        else -> {
            val cutoff = LocalDate.now().minusDays((n - 1).toLong()).toString()
            days.filter { it.day >= cutoff }
        }
    }
    return sliced.mapNotNull { d -> value(d)?.let { d.day to it } }
}

// MARK: - ChartCard, the hosted trend card
//
// A NoopCard holding a header (overline-styled title + trailing read-out), a fixed-height LineChart, and a
// divided footer of labelled stats. The Today-hosted trend cards draw it.

@Composable
private fun ChartCard(
    title: String,
    subtitle: String?,
    trailing: String?,
    color: Color,
    values: List<Double>,
    footer: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    dates: List<String> = emptyList(),
    formatY: (Double) -> String = { "${it.roundToInt()}" },
    // Bevel: a domain card wash, a bright end-cap "now" colour, and an optional window-change TrendChip.
    tint: Color? = null,
    tipColor: Color = color,
    change: Double? = null,
    higherIsBetter: Boolean? = null,
    changeFmt: (Double) -> String = { "${it.roundToInt()}" },
) {
    NoopCard(modifier = modifier, padding = Metrics.cardPadding, tint = tint) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header.
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Overline(title)
                    if (subtitle != null) {
                        Text(subtitle, style = NoopType.footnote, color = Palette.textTertiary)
                    }
                }
                if (trailing != null) {
                    // Neutral 15pt readout (matches iOS TrendsView), not the 22sp tinted figure.
                    Text(trailing, style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
            }

            // Chart (fixed height) or sparse placeholder. The chart is flanked by a max/avg/min
            // Y-axis column on the left and a first/mid/last date X-axis row underneath, so the
            // line reads against real numbers and dates instead of a bare unlabelled curve.
            if (values.size >= 2) {
                ChartWithAxes(
                    values = values,
                    dates = dates,
                    color = color,
                    tipColor = tipColor,
                    formatY = formatY,
                )
            } else {
                SparsePlaceholder()
            }

            // Footer stats + a window-change chip aligned to the trailing edge.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { ChartFooter(footer) }
                ChangeChip(change, higherIsBetter, changeFmt)
            }
        }
    }
}

/** A TrendChip for a window's period change , green/rose by whether the move is good for THIS metric. */
@Composable
private fun ChangeChip(change: Double?, higherIsBetter: Boolean?, fmt: (Double) -> String) {
    if (change == null || kotlin.math.abs(change) <= 0.0001) return
    val sign = if (change >= 0) "+" else "−"
    val deltaText = uiString(R.string.l10n_trends_screen_sign_fmt_kotlin_math_abs_change_9ad2f71e, sign, fmt(kotlin.math.abs(change)))
    val color = when (higherIsBetter) {
        null -> Palette.textTertiary
        else -> if ((change > 0) == higherIsBetter) Palette.statusPositive else Palette.metricRose
    }
    // Parity with iOS #967 (fix(trends): label change indicators): a "Trend" overline above the delta chip
    // so it reads as a labeled statistic beside the ChartFooter columns instead of an unlabeled pill at the
    // card edge, announced as ONE statistic ("Trend: +5") so TalkBack reads it as one value, not two.
    val trendLabel = stringResource(R.string.trends_trend)
    // Routed through a format resource so the label + locale-correct separator (e.g. French thin space,
    // Chinese full-width colon) are localized — a bare "$label: $delta" template is both un-localizable
    // and flagged by the i18n regression gate.
    val trendA11y = stringResource(R.string.trends_trend_a11y, deltaText)
    Column(
        modifier = Modifier.clearAndSetSemantics { contentDescription = trendA11y },
        horizontalAlignment = Alignment.Start,
    ) {
        Overline(trendLabel, color = Palette.textTertiary)
        TrendChip(text = deltaText, color = color)
    }
}

/**
 * A [LineChart] with a max/avg/min Y-axis label column and a first/mid/last date X-axis row.
 * Date strings (ISO yyyy-MM-dd) are reformatted to "d MMM"; an unparseable string falls back to
 * its raw value so a non-ISO key never blanks a label.
 */
@Composable
private fun ChartWithAxes(
    values: List<Double>,
    dates: List<String>,
    color: Color,
    formatY: (Double) -> String,
    tipColor: Color = color,
) {
    val maxV = values.max()
    val avgV = values.average()
    val minV = values.min()
    // Trend chart style (line vs bar). Read here at the single chart choke point; SharedPreferences isn't
    // reactive, but returning from Settings recomposes the host screen, which re-reads it.
    val chartStyle = UnitPrefs.trendChartStyle(LocalContext.current)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(
            modifier = Modifier.height(Metrics.chartHeight),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatY(maxV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            Text(formatY(avgV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            Text(formatY(minV), style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // The shared LineChart with a glowing "now" end-cap drawn on top. The cap reproduces LineChart's
            // own point geometry (same strokePx/topPad/bottomPad) so the dot lands on the line's final sample.
            Box(modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight)) {
                if (chartStyle == TrendChartStyle.BAR) {
                    // Bar mode: value-ramp bars from the baseline. selectionEnabled is OFF so BarChart
                    // mean-bins a dense window down to the pixel width.
                    BarChart(
                        values = values,
                        modifier = Modifier.fillMaxSize(),
                        color = color,
                        selectionEnabled = false,
                    )
                } else {
                    LineChart(
                        values = values,
                        modifier = Modifier.fillMaxSize(),
                        color = color,
                        fill = true,
                        selectionEnabled = true,
                        // #463: the pinpoint label goes through the SAME formatter as the axis column,
                        // so a tapped Effort day can't print the stored 0-100 value beside a 0-21 axis.
                        formatValue = formatY,
                        selectionLabels = dates.map(::prettyAxisDate),
                    )
                    GlowEndCap(values = values, tipColor = tipColor)
                }
            }
            val axisLabels = trendAxisLabels(dates)
            if (axisLabels.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    axisLabels.forEach { label ->
                        Text(
                            prettyAxisDate(label.day),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                            modifier = Modifier.weight(1f),
                            textAlign = when (label.anchor) {
                                TrendAxisAnchor.START -> TextAlign.Start
                                TrendAxisAnchor.CENTER -> TextAlign.Center
                                TrendAxisAnchor.END -> TextAlign.End
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

internal enum class TrendAxisAnchor { START, CENTER, END }

internal data class TrendAxisLabel(val day: String, val anchor: TrendAxisAnchor)

/** Selects date labels and pins them to the corresponding start, middle, and end of the plot. */
internal fun trendAxisLabels(dates: List<String>): List<TrendAxisLabel> = when {
    dates.size < 2 -> emptyList()
    dates.size == 2 -> listOf(
        TrendAxisLabel(dates.first(), TrendAxisAnchor.START),
        TrendAxisLabel(dates.last(), TrendAxisAnchor.END),
    )
    else -> listOf(
        TrendAxisLabel(dates.first(), TrendAxisAnchor.START),
        TrendAxisLabel(dates[dates.lastIndex / 2], TrendAxisAnchor.CENTER),
        TrendAxisLabel(dates.last(), TrendAxisAnchor.END),
    )
}

/** ISO "yyyy-MM-dd" → "d MMM"; falls back to the raw string (or "" when null) if it doesn't parse. */
private fun prettyAxisDate(day: String?): String =
    day?.let {
        runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) }
            .getOrDefault(it)
    }.orEmpty()

/**
 * One Trends metric trend, rendered as a Today host card (#today-hosted-cards).
 *
 * A home-screen card has nowhere to put a range selector and no obvious place to persist a per-card
 * choice, so it takes a fixed trailing month and widens for a wearer whose history is shorter than that.
 * The Trends tab remains where you change the window.
 *
 * Costs nothing to host. [resolveMetric] is a pure walk over the `days` list Today already holds, so
 * unlike the sleep model or the stress curve there is no read behind this and nothing to gate.
 */
@Composable
internal fun TrendHostCard(card: HostedCard, days: List<DailyMetric>, effortScale: EffortScale) {
    val range = TrendsRange.Month
    when (card) {
        HostedCard.TREND_HRV -> MetricTrendCard(
            title = stringResource(R.string.trends_hrv_full), unit = "ms",
            color = Palette.metricPurple,
            higherIsBetter = true,
            resolved = remember(days) { resolveMetric(days, range) { it.avgHrv } },
            fmt = { "${it.roundToInt()}" },
        )
        HostedCard.TREND_RESTING_HR -> MetricTrendCard(
            title = stringResource(R.string.trends_resting_hr_full), unit = "bpm",
            color = Palette.metricRose,
            higherIsBetter = false,
            resolved = remember(days) { resolveMetric(days, range) { it.restingHr?.toDouble() } },
            fmt = { "${it.roundToInt()}" },
        )
        HostedCard.TREND_EFFORT -> MetricTrendCard(
            // Plotted values stay on the stored 0-100 scale (line shape unchanged); only the displayed
            // numbers and unit follow the Effort-scale toggle, converted inside `fmt` (#268).
            title = stringResource(R.string.trends_effort),
            unit = "/ ${UnitFormatter.effortScaleMax(effortScale)}",
            color = Palette.effortColor,
            tint = Palette.effortColor,
            tipColor = Palette.effortBright,
            higherIsBetter = null,
            resolved = remember(days) { resolveMetric(days, range) { it.strain } },
            fmt = { UnitFormatter.effortDisplay(it, effortScale) },
        )
        else -> Unit
    }
}

/** A labelled metric-trend card built from a [ResolvedMetric] with mean / min / max. */
@Composable
private fun MetricTrendCard(
    title: String,
    unit: String,
    color: Color,
    resolved: ResolvedMetric,
    fmt: (Double) -> String,
    tint: Color? = null,
    tipColor: Color = color,
    higherIsBetter: Boolean? = null,
) {
    val avg = resolved.values.averageOrNull()
    ChartCard(
        title = title,
        subtitle = null,
        trailing = avg?.let { fmt(it) },
        color = color,
        tint = tint,
        tipColor = tipColor,
        values = resolved.values,
        dates = resolved.dates,
        formatY = fmt,
        change = periodChange(resolved.values),
        higherIsBetter = higherIsBetter,
        changeFmt = fmt,
        footer = listOf(
            // Plain "Mean" to match the bare Min/Max columns; the unit moves into the value
            // (e.g. "58 ms") so uppercasing can't render a shouty "MEAN MS".
            stringResource(R.string.trends_mean) to (avg?.let { "${fmt(it)} $unit" } ?: EM_DASH),
            stringResource(R.string.trends_min) to (resolved.values.minOrNull()?.let { fmt(it) } ?: EM_DASH),
            stringResource(R.string.trends_max) to (resolved.values.maxOrNull()?.let { fmt(it) } ?: EM_DASH),
        ),
    )
}

/**
 * The window's trend as a signed mean-of-recent-half minus mean-of-earlier-half , drives the card's
 * TrendChip so a glance reads the direction, like Today's deltas. null for a window too short to split.
 */
private fun periodChange(values: List<Double>): Double? {
    if (values.size < 4) return null
    val mid = values.size / 2
    val earlier = values.take(mid)
    val recent = values.drop(mid)
    if (earlier.isEmpty() || recent.isEmpty()) return null
    return recent.average() - earlier.average()
}

/** Evenly-spaced labelled stats under a chart, separated by a hairline rule. */
@Composable
private fun ChartFooter(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
        HorizontalDivider(color = Palette.hairline)
        Row(modifier = Modifier.fillMaxWidth()) {
            items.forEach { (label, value) ->
                Column(modifier = Modifier.weight(1f)) {
                    Overline(label, color = Palette.textTertiary)
                    Text(value, style = NoopType.bodyNumber, color = Palette.textPrimary)
                }
            }
        }
    }
}

// MARK: - Shared bits

/**
 * A glowing dot pinned to a LineChart's latest sample , the Bevel "now" end-cap (a soft halo + bright
 * core + white centre), matching Today's OverviewHRChart. Drawn as a sibling overlay so the shared
 * LineChart stays untouched; it reproduces that chart's point geometry exactly (strokePx 2.5, top/
 * bottom pad strokePx+4, finite-value min/max) so the cap sits on the curve's final point.
 */
@Composable
private fun GlowEndCap(values: List<Double>, tipColor: Color) {
    val clean = remember(values) { values.filter { it.isFinite() } }
    if (clean.size < 2) return
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokePx = 2.5f
        val topPad = strokePx + 4f
        val bottomPad = strokePx + 4f
        val minV = clean.min()
        val maxV = clean.max()
        val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
        val usableH = (size.height - topPad - bottomPad).coerceAtLeast(1f)
        val x = size.width  // the latest point sits at the right edge
        val norm = ((clean.last() - minV) / span).toFloat().coerceIn(0f, 1f)
        val y = topPad + (1f - norm) * usableH
        val center = Offset(x, y)
        drawCircle(color = tipColor.copy(alpha = 0.30f), radius = 9f, center = center)
        drawCircle(color = tipColor.copy(alpha = 0.65f), radius = 5.5f, center = center)
        drawCircle(color = Palette.tipCore, radius = 2.4f, center = center)
    }
}

/** Inset well shown when a window has too few points to plot, mirroring sparsePlaceholder. */
@Composable
private fun SparsePlaceholder(height: Dp = Metrics.chartHeight) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(Metrics.cornerSm))
            .background(Palette.surfaceInset),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(R.string.trends_not_enough_data),
            style = NoopType.subhead,
            color = Palette.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyTrends() {
    DataPendingNote(
        title = stringResource(R.string.trends_empty_title),
        body = stringResource(R.string.trends_empty_body),
    )
}

// MARK: - Small numeric helpers

private const val EM_DASH = ","

private fun List<Double>.averageOrNull(): Double? =
    if (isEmpty()) null else sum() / size
