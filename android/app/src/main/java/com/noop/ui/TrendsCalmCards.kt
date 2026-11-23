package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Trends cards (Zoop "Calm" redesign)
//
// The cards the Trends tab draws for a period: one summary of Charge, Effort and Rest, each with a chip against
// the span before, then a card per chart under a plain title (Charge, Rest, Effort, Training load and Body). The
// charts shade the owner's normal range. Every number arrives worked out (TrendsInsight.kt); this file only lays
// it out. Charts draw on a Canvas and place their labels with small Layouts, so all text keeps the app's typeface.

/** Shown in place of an average the period does not have. Matches the Home and Metrics tabs. */
internal const val TRENDS_EMPTY_VALUE = "—"

/** Width kept at a chart's right edge for its value labels. */
private val AxisGutter = 30.dp

/** Room above and below a plot, so a label or dot on its edge is not cut off. */
private val PlotInset = 8.dp

/** Height of the Charge line chart's plot. */
private val LineChartHeight = 132.dp

/** Height of the Rest and Effort bar charts' plots. */
private val BarChartHeight = 96.dp

/** Height of a small card's sparkline. */
private val SparkHeight = 44.dp

/** Height of the training-load chart's plot. */
private val LoadChartHeight = 96.dp

/** Narrowest a day's bar may get before neighbouring days share one bar. */
private val MinBarSlot = 3.dp

/** One score on the summary card: its period average and the chip against the same span just before it. */
internal data class TrendSummaryScoreUi(
    val label: String,
    val value: String?,
    val valueColor: Color,
    /** The period average less the previous one; null for all history or a previous span with too few days. */
    val delta: Double?,
    val tone: HomeTone?,
    val formatDelta: (Double) -> String,
)

/** One of the small paired cards on the Body card: the latest reading over a sparkline of the period. */
internal data class TrendMiniUi(
    val title: String,
    /** The latest reading, formatted. */
    val value: String,
    /** The unit after the number, such as "kg". */
    val unit: String,
    val valueColor: Color,
    /** A reading as text, for the sparkline's spoken summary. */
    val format: (Double) -> String,
    /** The period's readings as epoch days. */
    val points: List<Pair<Long, Double>>,
    val band: Pair<Double, Double>?,
    val lineColor: Color,
    /** Longest gap, in days, the sparkline bridges before it breaks. */
    val maxGapDays: Int = TRENDS_ROLLING_DAYS,
)

/** Fitness and fatigue for the Training load card, on the owner's Effort scale. */
internal data class TrainingLoadUi(
    val fitness: List<Pair<Long, Double>>,
    val fatigue: List<Pair<Long, Double>>,
    val form: TrainingForm,
    /** "Form +1.2 · fresh". */
    val formText: String,
    val format: (Double) -> String,
)

/** A value label in a chart's right-hand gutter: its height on the plot (0 top, 1 bottom), text and colour. */
private data class EdgeLabel(val fraction: Float, val text: String, val color: Color, val bold: Boolean = false)

// MARK: - Page furniture

/**
 * What the Trends numbers mean, opened from the info button at the end of the range control: the period and
 * what the averages, chips and bands stand for, then how the Charge line, the Rest bars (a week each up to six
 * months, a month each beyond) and the Body readings are drawn. A null [days] is all history, which has no
 * earlier span to compare with.
 */
@Composable
internal fun TrendsMethodDialog(days: Int?, onDismiss: () -> Unit) {
    val lead = if (days != null) uiString(R.string.trends_caption_lead_days, days) else uiString(R.string.trends_caption_lead_all)
    val body = if (days != null) uiString(R.string.trends_caption_period, days) else uiString(R.string.trends_caption_all)
    val charts = uiString(
        if (barPeriodFor(days) == BarPeriod.WEEK) R.string.trends_method_charts_weekly else R.string.trends_method_charts_monthly,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Text(uiString(R.string.metrics_two_sentences, lead, body), style = NoopType.subhead, color = Palette.textSecondary)
                Text(charts, style = NoopType.subhead, color = Palette.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(uiString(R.string.l10n_components_close_bbfa773e), color = Palette.accent)
            }
        },
    )
}

/** A Trends card: its plain title, with [trailing] at the end of the title row when given, then [content]. */
@Composable
internal fun TrendsSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                if (trailing != null) {
                    Spacer(Modifier.width(Metrics.space8))
                    trailing()
                }
            }
            content()
        }
    }
}

/** The quiet line a card shows in place of a chart its period has too few readings for. */
@Composable
internal fun TrendsNoChart() {
    Text(uiString(R.string.trends_not_enough_data), style = NoopType.caption, color = Palette.textTertiary)
}

// MARK: - Summary

/**
 * The summary card: Charge, Effort and Rest side by side, each with its period average in its colour and the
 * chip against the same span just before it. All history has no earlier span, so it shows no chips.
 */
@Composable
internal fun TrendsSummaryCard(scores: List<TrendSummaryScoreUi>, modifier: Modifier = Modifier) {
    NoopCard(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            scores.forEach { score ->
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                    Text(score.label, style = NoopType.subhead, color = Palette.textSecondary, maxLines = 1)
                    Text(
                        score.value ?: TRENDS_EMPTY_VALUE,
                        style = NoopType.number(26f),
                        color = if (score.value != null) score.valueColor else Palette.textTertiary,
                        maxLines = 1,
                    )
                    score.delta?.let { ComparisonChip(delta = it, tone = score.tone, formatDelta = score.formatDelta) }
                }
            }
        }
    }
}

// MARK: - Charge

/**
 * The Charge card: each day's 7-day average across the period as a line, or the not-enough-data note while the
 * period has too few days for one. A tap or drag picks a day, and its date and value show at the end of the title
 * row, so picking a day never moves the chart.
 */
@Composable
internal fun TrendsChargeCard(
    line: List<Pair<Long, Double>>,
    axis: TrendAxis,
    band: Pair<Double, Double>?,
    modifier: Modifier = Modifier,
) {
    var selected by remember(line) { mutableIntStateOf(-1) }
    val picked = line.getOrNull(selected)
    TrendsSectionCard(
        title = uiString(R.string.trends_charge),
        modifier = modifier,
        trailing = if (picked != null) {
            { PickedDayPill(uiString(R.string.trends_point_label, pointDate(picked.first), picked.second.roundToInt().toString())) }
        } else {
            null
        },
    ) {
        if (line.size >= 2) {
            ChargeLineChart(line, axis, band, selected = selected, onSelect = { selected = it })
        } else {
            TrendsNoChart()
        }
    }
}

/**
 * The Charge line: each day's 7-day average across the period in the recovery colours, over the shaded normal
 * band and round gridlines labelled on the right. A tap or drag hands the day under the finger to [onSelect],
 * and the day at [selected] (an index into [line], -1 for none) gets a marker line and a ringed dot.
 */
@Composable
internal fun ChargeLineChart(
    line: List<Pair<Long, Double>>,
    axis: TrendAxis,
    band: Pair<Double, Double>?,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (line.isEmpty()) return
    val days = remember(line) { line.map { it.first } }
    val runs = remember(days) { contiguousRuns(days, TRENDS_ROLLING_DAYS) }
    val floor = remember(line, band) { chargeAxisFloor(minOf(line.minOf { it.second }, band?.first ?: 100.0)) }
    val grid = remember(floor) { gridValues(floor, 100.0, 20.0) }
    val picked = line.getOrNull(selected)
    val stops = Palette.recoveryStops.toTypedArray()
    val gridColor = Palette.hairline
    val bandColor = Palette.textSecondary.copy(alpha = 0.07f)
    val ring = Palette.surfaceRaised
    val marker = Palette.textPrimary.copy(alpha = 0.3f)
    val labelColor = Palette.textTertiary
    val endColor = Palette.recoveryColor(line.last().second)
    val fillColor = Palette.recoveryColor(line.map { it.second }.average())
    val pickedColor = picked?.let { Palette.recoveryColor(it.second) } ?: endColor
    fun share(value: Double): Float = (1.0 - (value - floor) / (100.0 - floor)).toFloat()
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Box(modifier = Modifier.fillMaxWidth().height(LineChartHeight + PlotInset * 2)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = PlotInset, bottom = PlotInset, end = AxisGutter)
                    .trendSummary(line.map { it.second }) { it.roundToInt().toString() }
                    .pickAlongX(line) { at ->
                        onSelect(nearestIndex(days, axis.startDay + at.toDouble() * (axis.endDay - axis.startDay)))
                    },
            ) {
                val width = size.width
                val height = size.height
                fun x(day: Long): Float = axis.fraction(day) * width
                fun y(value: Double): Float = share(value) * height
                grid.forEach { value ->
                    drawLine(gridColor, Offset(0f, y(value)), Offset(width, y(value)), strokeWidth = 1.dp.toPx())
                }
                if (band != null) {
                    drawRoundRect(
                        bandColor,
                        topLeft = Offset(0f, y(band.second)),
                        size = Size(width, (y(band.first) - y(band.second)).coerceAtLeast(1f)),
                        cornerRadius = CornerRadius(6.dp.toPx()),
                    )
                }
                val stroke = Brush.verticalGradient(*stops, startY = y(0.0), endY = y(100.0))
                runs.forEach { run ->
                    val points = run.map { Offset(x(line[it].first), y(line[it].second)) }
                    if (points.size == 1) {
                        drawCircle(stroke, radius = 2.dp.toPx(), center = points.single())
                    } else {
                        val area = smoothPath(points).apply {
                            lineTo(points.last().x, height)
                            lineTo(points.first().x, height)
                            close()
                        }
                        drawPath(area, Brush.verticalGradient(listOf(fillColor.copy(alpha = 0.14f), Color.Transparent)))
                        drawPath(
                            smoothPath(points),
                            stroke,
                            style = Stroke(width = 2.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
                if (picked != null) {
                    val pickedX = x(picked.first)
                    drawLine(marker, Offset(pickedX, 0f), Offset(pickedX, height), strokeWidth = 1.dp.toPx())
                    drawDot(pickedColor, ring, Offset(pickedX, y(picked.second)))
                }
                val end = line.last()
                val endPoint = Offset(x(end.first), y(end.second))
                drawCircle(endColor.copy(alpha = 0.3f), radius = 9.dp.toPx(), center = endPoint)
                drawDot(endColor, ring, endPoint)
            }
            EdgeLabels(
                labels = grid.map { EdgeLabel(share(it), it.roundToInt().toString(), labelColor) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(AxisGutter)
                    .fillMaxHeight()
                    .padding(vertical = PlotInset),
            )
        }
        AxisTicks(axis.ticks, Modifier.padding(end = AxisGutter))
    }
}

// MARK: - Body

/** Two small cards side by side, bottom-aligned so their sparklines line up whatever their titles wrap to. */
@Composable
internal fun TrendMiniPair(left: TrendMiniUi?, right: TrendMiniUi?, axis: TrendAxis) {
    Row(
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        if (left != null) TrendMini(left, axis, Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
        if (right != null) TrendMini(right, axis, Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
    }
}

/** A small card: title, the latest reading with its unit, then a sparkline over the period. */
@Composable
private fun TrendMini(mini: TrendMiniUi, axis: TrendAxis, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(mini.title, style = NoopType.subhead, color = Palette.textSecondary, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Text(
                mini.value,
                style = NoopType.number(22f),
                color = mini.valueColor,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                mini.unit,
                style = NoopType.caption,
                color = Palette.textTertiary,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
        }
        Spacer(Modifier.weight(1f))
        if (mini.points.isNotEmpty()) {
            TrendSparkline(mini.points, axis, mini.band, mini.lineColor, mini.maxGapDays, mini.format)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(axis.startLabel, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(axis.endLabel, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            }
        }
    }
}

/** A small line over the period with the normal band behind it and a ringed dot on the latest reading. */
@Composable
private fun TrendSparkline(
    points: List<Pair<Long, Double>>,
    axis: TrendAxis,
    band: Pair<Double, Double>?,
    color: Color,
    maxGapDays: Int,
    format: (Double) -> String,
) {
    val runs = remember(points, maxGapDays) { contiguousRuns(points.map { it.first }, maxGapDays) }
    val values = points.map { it.second } + listOfNotNull(band?.first, band?.second)
    val low = values.min()
    val high = values.max()
    val span = (high - low).takeIf { it > 1e-9 } ?: maxOf(abs(high), 1.0)
    val yMin = low - span * 0.2
    val yMax = high + span * 0.2
    val ring = Palette.surfaceRaised
    Canvas(modifier = Modifier.fillMaxWidth().height(SparkHeight).trendSummary(points.map { it.second }, format)) {
        val width = size.width
        val height = size.height
        fun x(day: Long): Float = axis.fraction(day) * width
        fun y(value: Double): Float = (height - (value - yMin) / (yMax - yMin) * height).toFloat()
        if (band != null) {
            drawRoundRect(
                color.copy(alpha = 0.10f),
                topLeft = Offset(0f, y(band.second)),
                size = Size(width, (y(band.first) - y(band.second)).coerceAtLeast(1f)),
                cornerRadius = CornerRadius(5.dp.toPx()),
            )
        }
        runs.forEach { run ->
            val offsets = run.map { Offset(x(points[it].first), y(points[it].second)) }
            if (offsets.size == 1) {
                drawCircle(color, radius = 1.5.dp.toPx(), center = offsets.single())
            } else {
                drawPath(
                    smoothPath(offsets),
                    color,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        val end = points.last()
        drawDot(color, ring, Offset(x(end.first), y(end.second)), radius = 4.dp)
    }
}

// MARK: - Rest

/** The Rest bars: one per week or month on the 0-100 scale, the latest bright and labelled with its value. */
@Composable
internal fun RestPeriodBars(
    bars: List<PeriodBar>,
    band: Pair<Double, Double>?,
    barColor: (Double) -> Color,
    format: (Double) -> String,
) {
    if (bars.isEmpty()) return
    val axis = remember(bars) { trendAxis(bars.first().start, bars.last().end) }
    val gridColor = Palette.hairline
    val bandColor = Palette.textSecondary.copy(alpha = 0.07f)
    val labelColor = Palette.textTertiary
    val last = bars.last()
    val span = (axis.endDay - axis.startDay + 1).toFloat()
    val lastCentre = ((last.start.toEpochDay() + last.end.toEpochDay() + 1) / 2.0 - axis.startDay).toFloat() / span
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Box(modifier = Modifier.fillMaxWidth().height(BarChartHeight + PlotInset * 2)) {
            val plot = Modifier.fillMaxSize().padding(top = PlotInset, bottom = PlotInset, end = AxisGutter)
            Canvas(modifier = plot.trendSummary(bars.map { it.average }, format)) {
                val width = size.width
                val height = size.height
                val dayWidth = width / span
                fun y(value: Double): Float = height - (value / 100.0).toFloat().coerceIn(0f, 1f) * height
                listOf(0.0, 50.0, 100.0).forEach { value ->
                    drawLine(gridColor, Offset(0f, y(value)), Offset(width, y(value)), strokeWidth = 1.dp.toPx())
                }
                if (band != null) {
                    drawRect(
                        bandColor,
                        topLeft = Offset(0f, y(band.second)),
                        size = Size(width, (y(band.first) - y(band.second)).coerceAtLeast(1f)),
                    )
                }
                bars.forEachIndexed { index, bar ->
                    val left = (bar.start.toEpochDay() - axis.startDay) * dayWidth
                    val right = (bar.end.toEpochDay() - axis.startDay + 1) * dayWidth
                    val barWidth = ((right - left) * 0.6f).coerceAtLeast(1f)
                    drawTopRoundedBar(
                        barColor(bar.average).copy(alpha = if (index == bars.lastIndex) 1f else 0.5f),
                        left = left + (right - left - barWidth) / 2f,
                        top = minOf(y(bar.average), height - 2.dp.toPx()),
                        width = barWidth,
                        bottom = height,
                        radius = 4.dp.toPx(),
                    )
                }
            }
            EdgeLabels(
                labels = listOf(50.0, 100.0).map { EdgeLabel(1f - (it / 100.0).toFloat(), format(it), labelColor) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(AxisGutter)
                    .fillMaxHeight()
                    .padding(vertical = PlotInset),
            )
            PointLabel(
                text = format(last.average),
                x = lastCentre,
                y = 1f - (last.average / 100.0).toFloat().coerceIn(0f, 1f),
                modifier = plot,
            )
        }
        AxisTicks(axis.ticks, Modifier.padding(end = AxisGutter))
    }
}

// MARK: - Effort and training load

/**
 * Daily Effort bars on the owner's scale, gridlines labelled on the right, the normal band behind them and the
 * latest week bright. [points] and [band] are already on that scale, which tops out at [scaleMax].
 */
@Composable
internal fun EffortDayBars(
    points: List<Pair<Long, Double>>,
    axis: TrendAxis,
    scaleMax: Double,
    grid: List<Double>,
    band: Pair<Double, Double>?,
    format: (Double) -> String,
) {
    val labelColor = Palette.textTertiary
    val color = Palette.effortColor
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Box(modifier = Modifier.fillMaxWidth().height(BarChartHeight + PlotInset * 2)) {
            DayBars(
                points,
                axis,
                maxValue = scaleMax,
                height = BarChartHeight,
                barColor = { color },
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(end = AxisGutter)
                    .trendSummary(points.map { it.second }, format),
                band = band,
                grid = grid,
                dimBefore = axis.endDay - (TRENDS_ROLLING_DAYS - 1),
            )
            EdgeLabels(
                labels = grid.map { EdgeLabel(1f - (it / scaleMax).toFloat(), format(it), labelColor) },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(AxisGutter)
                    .fillMaxHeight()
                    .padding(vertical = PlotInset),
            )
        }
        AxisTicks(axis.ticks, Modifier.padding(end = AxisGutter))
    }
}

/**
 * The Training load card: the form chip at the end of its title, fitness and fatigue named in a legend, then the
 * two lines with their latest values on the right. [note] comes last, such as how far the model has come; with
 * no [load] it is all the card shows, such as how many days the model needs.
 */
@Composable
internal fun TrainingLoadCard(load: TrainingLoadUi?, note: String?, axis: TrendAxis, modifier: Modifier = Modifier) {
    TrendsSectionCard(
        title = uiString(R.string.trends_training_load),
        modifier = modifier,
        trailing = if (load != null) {
            { FormChip(load.formText, formColor(load.form)) }
        } else {
            null
        },
    ) {
        if (load != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                LegendLine(Palette.metricPurple, uiString(R.string.trends_fitness))
                LegendLine(Palette.effortColor, uiString(R.string.trends_fatigue))
            }
            LoadChart(load, axis)
        }
        note?.let { Text(it, style = NoopType.caption, color = Palette.textTertiary) }
    }
}

/** The form chip's colour: fresh reads positive, balanced stays grey and carrying load reads amber. */
private fun formColor(form: TrainingForm): Color = when (form) {
    TrainingForm.FRESH -> Palette.statusPositive
    TrainingForm.BALANCED -> Palette.textSecondary
    TrainingForm.LOADED -> Palette.statusWarning
}

/** A small pill of text tinted in [color]. */
@Composable
private fun FormChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Metrics.space10, vertical = Metrics.space4),
    ) {
        Text(text, style = NoopType.captionNumber, color = color, maxLines = 1)
    }
}

/** A legend entry: a short line in the series colour and its name. */
@Composable
private fun LegendLine(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Box(
            modifier = Modifier
                .size(width = Metrics.legendLineWidth, height = Metrics.legendLineHeight)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
        Text(label, style = NoopType.caption, color = Palette.textSecondary, maxLines = 1)
    }
}

/** Fitness and fatigue on one shared scale, so the gap between the lines is the form. */
@Composable
private fun LoadChart(load: TrainingLoadUi, axis: TrendAxis) {
    val values = load.fitness.map { it.second } + load.fatigue.map { it.second }
    if (values.isEmpty()) return
    val low = values.min()
    val high = values.max()
    val pad = ((high - low) * 0.15).takeIf { it > 1e-9 } ?: maxOf(abs(high) * 0.1, 1.0)
    val yMin = low - pad
    val yMax = high + pad
    fun share(value: Double): Float = (1.0 - (value - yMin) / (yMax - yMin)).toFloat()
    val fitnessColor = Palette.metricPurple
    val fatigueColor = Palette.effortColor
    val gridColor = Palette.hairline
    val ring = Palette.surfaceRaised
    val fitnessLabel = Palette.textPrimary
    val fatigueLabel = Palette.textSecondary
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Box(modifier = Modifier.fillMaxWidth().height(LoadChartHeight + PlotInset * 2)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = PlotInset, bottom = PlotInset, end = AxisGutter)
                    .trendSummary(load.fitness.map { it.second }) { it.roundToInt().toString() },
            ) {
                val width = size.width
                val height = size.height
                for (line in 0..4) {
                    val gridY = height * line / 4f
                    drawLine(gridColor, Offset(0f, gridY), Offset(width, gridY), strokeWidth = 1.dp.toPx())
                }
                listOf(load.fatigue to fatigueColor, load.fitness to fitnessColor).forEach { (series, color) ->
                    val offsets = series.map { (day, value) -> Offset(axis.fraction(day) * width, share(value) * height) }
                    if (offsets.size >= 2) {
                        drawPath(
                            smoothPath(offsets),
                            color,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                    offsets.lastOrNull()?.let { drawDot(color, ring, it, radius = 4.dp) }
                }
            }
            EdgeLabels(
                labels = listOfNotNull(
                    load.fitness.lastOrNull()?.let { EdgeLabel(share(it.second), load.format(it.second), fitnessLabel, bold = true) },
                    load.fatigue.lastOrNull()?.let { EdgeLabel(share(it.second), load.format(it.second), fatigueLabel, bold = true) },
                ),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(AxisGutter)
                    .fillMaxHeight()
                    .padding(vertical = PlotInset),
            )
        }
        AxisTicks(axis.ticks, Modifier.padding(end = AxisGutter))
    }
}

// MARK: - Chart parts

/**
 * Daily bars along [axis] from zero to [maxValue]: one per day while they fit, and one per run of days (the
 * run's mean) when they would get thinner than [MinBarSlot]. [band] shades the normal range, [grid] draws
 * faint lines, and bars that end before [dimBefore] draw softer so the latest days stand out.
 */
@Composable
private fun DayBars(
    points: List<Pair<Long, Double>>,
    axis: TrendAxis,
    maxValue: Double,
    height: Dp,
    barColor: (Double) -> Color,
    modifier: Modifier = Modifier,
    band: Pair<Double, Double>? = null,
    grid: List<Double> = emptyList(),
    dimBefore: Long? = null,
) {
    val gridColor = Palette.hairline
    val bandColor = Palette.textSecondary.copy(alpha = 0.09f)
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        val width = size.width
        val plotHeight = size.height
        val span = axis.endDay - axis.startDay + 1
        val dayWidth = width / span
        val perBar = daysPerBar(span, (width / MinBarSlot.toPx()).toInt())
        val minHeight = 2.dp.toPx()
        fun y(value: Double): Float = plotHeight - (value / maxValue).toFloat().coerceIn(0f, 1f) * plotHeight
        grid.forEach { value ->
            drawLine(gridColor, Offset(0f, y(value)), Offset(width, y(value)), strokeWidth = 1.dp.toPx())
        }
        if (band != null) {
            drawRect(
                bandColor,
                topLeft = Offset(0f, y(band.second)),
                size = Size(width, (y(band.first) - y(band.second)).coerceAtLeast(1f)),
            )
        }
        dayBuckets(points, axis.startDay, perBar).forEach { bucket ->
            val left = (bucket.firstDay - axis.startDay) * dayWidth
            val right = (minOf(bucket.lastDay, axis.endDay) - axis.startDay + 1) * dayWidth
            val barWidth = ((right - left) * 0.66f).coerceAtLeast(1f)
            val top = minOf(y(bucket.average), plotHeight - minHeight)
            val dim = dimBefore != null && bucket.lastDay < dimBefore
            drawRoundRect(
                barColor(bucket.average).copy(alpha = if (dim) 0.55f else 1f),
                topLeft = Offset(left + (right - left - barWidth) / 2f, top),
                size = Size(barWidth, plotHeight - top),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** A ringed dot: [color] inside a thin ring of the card colour, so it lifts off the line under it. */
private fun DrawScope.drawDot(color: Color, ring: Color, center: Offset, radius: Dp = 4.5.dp) {
    drawCircle(ring, radius = radius.toPx() + 2.dp.toPx(), center = center)
    drawCircle(color, radius = radius.toPx(), center = center)
}

/** A bar with rounded top corners and a flat base. */
private fun DrawScope.drawTopRoundedBar(color: Color, left: Float, top: Float, width: Float, bottom: Float, radius: Float) {
    val corner = CornerRadius(minOf(radius, width / 2f))
    val path = Path().apply {
        addRoundRect(
            RoundRect(
                left = left,
                top = top,
                right = left + width,
                bottom = bottom,
                topLeftCornerRadius = corner,
                topRightCornerRadius = corner,
                bottomRightCornerRadius = CornerRadius.Zero,
                bottomLeftCornerRadius = CornerRadius.Zero,
            ),
        )
    }
    drawPath(path, color)
}

/**
 * Labels in a chart's right-hand gutter, right-aligned and centred on their heights. A label that would overlap
 * the one above it moves down just enough to clear it, so two end values close together both stay readable.
 */
@Composable
private fun EdgeLabels(labels: List<EdgeLabel>, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = {
            labels.forEach { label ->
                Text(
                    label.text,
                    style = if (label.bold) NoopType.number(11f, FontWeight.Bold) else NoopType.footnote,
                    color = label.color,
                    maxLines = 1,
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val tops = IntArray(placeables.size)
        var clear = Int.MIN_VALUE
        labels.indices.sortedBy { labels[it].fraction }.forEach { index ->
            val placeable = placeables[index]
            val centred = (labels[index].fraction * height - placeable.height / 2f).roundToInt()
            tops[index] = maxOf(centred, clear)
            clear = tops[index] + placeable.height
        }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable -> placeable.place(width - placeable.width, tops[index]) }
        }
    }
}

/** Date labels under a plot, centred on their days; a label that would touch the one before it is left out. */
@Composable
private fun AxisTicks(ticks: List<AxisTick>, modifier: Modifier = Modifier) {
    if (ticks.isEmpty()) return
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            ticks.forEach { Text(it.label, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1) }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val gap = Metrics.space6.roundToPx()
        val lefts = IntArray(placeables.size)
        val shown = BooleanArray(placeables.size)
        var clear = Int.MIN_VALUE
        ticks.forEachIndexed { index, tick ->
            val placeable = placeables[index]
            val left = (tick.fraction * width - placeable.width / 2f).roundToInt()
                .coerceIn(0, (width - placeable.width).coerceAtLeast(0))
            if (clear == Int.MIN_VALUE || left >= clear + gap) {
                lefts[index] = left
                shown[index] = true
                clear = left + placeable.width
            }
        }
        val height = placeables.maxOf { it.height }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable -> if (shown[index]) placeable.place(lefts[index], 0) }
        }
    }
}

/**
 * A picked day's date and value on a quiet pill. Its padding keeps it shorter than a card title, so it can appear
 * at the end of the title row without making the row taller.
 */
@Composable
private fun PickedDayPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.surfaceInset)
            .padding(horizontal = Metrics.space10, vertical = Metrics.space2),
    ) {
        Text(text, style = NoopType.caption, color = Palette.textPrimary, maxLines = 1)
    }
}

/** One value label centred over a point of a plot, just above it; [x] and [y] are shares of the plot's size. */
@Composable
private fun PointLabel(text: String, x: Float, y: Float, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier,
        content = {
            Text(text, style = NoopType.number(11f, FontWeight.Bold), color = Palette.textPrimary, maxLines = 1)
        },
    ) { measurables, constraints ->
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val left = (x * width - placeable.width / 2f).roundToInt()
            .coerceIn(0, (width - placeable.width).coerceAtLeast(0))
        val top = (y * height).roundToInt() - placeable.height - Metrics.space2.roundToPx()
        layout(width, height) { placeable.place(left, top) }
    }
}

/**
 * Tap or drag across a chart to pick the point under the finger. [onPick] gets the x position as a share of the
 * chart's width; [key] restarts the gesture handlers when the data changes.
 */
private fun Modifier.pickAlongX(key: Any?, onPick: (Float) -> Unit): Modifier =
    pointerInput(key) {
        detectTapGestures(onTap = { offset ->
            if (size.width > 0) onPick((offset.x / size.width).coerceIn(0f, 1f))
        })
    }.pointerInput(key) {
        detectHorizontalDragGestures(
            onDragStart = { start ->
                if (size.width > 0) onPick((start.x / size.width).coerceIn(0f, 1f))
            },
            onHorizontalDrag = { change, _ ->
                if (size.width > 0) onPick((change.position.x / size.width).coerceIn(0f, 1f))
                change.consume()
            },
        )
    }

/** Latest, lowest and highest of [values], or null with none to describe. Pure, so the summary is testable. */
internal fun trendSeriesSummary(values: List<Double>): Triple<Double, Double, Double>? {
    val clean = values.filter { it.isFinite() }
    if (clean.isEmpty()) return null
    return Triple(clean.last(), clean.min(), clean.max())
}

/**
 * Gives a Trends chart ONE spoken summary for TalkBack (latest, lowest, highest), in place of the nothing a
 * bare Canvas says. One node per chart, never one per bar or point, the same rule Charts.kt keeps.
 */
private fun Modifier.trendSummary(values: List<Double>, format: (Double) -> String): Modifier {
    val text = trendSeriesSummary(values)?.let { (latest, low, high) ->
        uiString(R.string.trends_chart_summary, format(latest), format(low), format(high))
    } ?: uiString(R.string.trends_chart_no_data)
    return clearAndSetSemantics { contentDescription = text }
}

/** "Tue 3 Nov" for a picked day, in the reader's language. */
private fun pointDate(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
