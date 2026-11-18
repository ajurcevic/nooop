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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
// The cards the Trends tab draws for a period: This week, then Recovery, Sleep, Training and Body. Each section
// has a plain-English line, a headline average with an arrow against the period before, and charts that shade
// the owner's normal range. Every number arrives worked out (TrendsInsight.kt); this file only lays it out.
// Charts draw on a Canvas and place their labels with small Layouts, so all text keeps the app's typeface.

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

/** Height of the every-day Charge strip. */
private val StripHeight = 40.dp

/** Height of a small card's sparkline. */
private val SparkHeight = 44.dp

/** Height of the training-load chart's plot. */
private val LoadChartHeight = 96.dp

/** Narrowest a day's bar may get before neighbouring days share one bar. */
private val MinBarSlot = 3.dp

/** Widest bar on the This-week card. */
private val WeekBarWidth = 8.dp

/** Tallest bar on the This-week card. */
private val WeekBarHeight = 26.dp

/** The round week buttons on the This-week card. */
private val WeekButtonSize = 32.dp

/** One score on the This-week card: its week average, the arrow against the week before and its seven days. */
internal data class WeekScoreUi(
    val label: String,
    val value: String?,
    val valueColor: Color,
    val delta: Double?,
    val tone: HomeTone?,
    val formatDelta: (Double) -> String,
    /** Monday to Sunday on the stored 0-100 scale, null on a day without a value. */
    val daily: List<Double?>,
    /** A day's bar colour from its value. */
    val barColor: (Double) -> Color,
)

/** A section's headline: the metric, a quiet note on the right, its period average and the arrow. */
internal data class TrendHeadlineUi(
    val label: String,
    val detail: String?,
    val value: String?,
    val valueColor: Color,
    val delta: Double?,
    val tone: HomeTone?,
    val formatDelta: (Double) -> String,
    val deltaUnit: String? = null,
)

/** One of the small paired cards (HRV, resting heart rate, weight, skin temperature). */
internal data class TrendMiniUi(
    val title: String,
    val value: String?,
    /** The unit and "avg" after the number, such as "ms avg". */
    val unitAvg: String,
    val valueColor: Color,
    val delta: Double?,
    val tone: HomeTone?,
    val formatDelta: (Double) -> String,
    val deltaUnit: String?,
    /** The period's readings as epoch days. */
    val points: List<Pair<Long, Double>>,
    val band: Pair<Double, Double>?,
    val lineColor: Color,
    /** Longest gap, in days, the sparkline bridges before it breaks. */
    val maxGapDays: Int = TRENDS_ROLLING_DAYS,
)

/** Fitness and fatigue for the Training card, on the owner's Effort scale. */
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

/** The one caption under the range control: the period in brighter grey, then what every number means. */
@Composable
internal fun TrendsPeriodCaption(lead: String, body: String, modifier: Modifier = Modifier) {
    val text = uiString(R.string.metrics_two_sentences, lead, body)
    val start = text.indexOf(lead)
    val brighter = Palette.textSecondary
    val annotated = buildAnnotatedString {
        append(text)
        if (start >= 0) addStyle(SpanStyle(color = brighter), start, start + lead.length)
    }
    Text(
        annotated,
        style = NoopType.caption,
        color = Palette.textTertiary,
        modifier = modifier.fillMaxWidth().padding(horizontal = Metrics.space4),
    )
}

/** A Trends section: the category's icon and title, its plain line, then [content]. */
@Composable
internal fun TrendsSectionCard(
    category: MetricCategory,
    line: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space14)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                CategoryHeading(category)
                Text(line, style = NoopType.body, color = Palette.textSecondary)
            }
            content()
        }
    }
}

/** The hairline between the blocks of a section card. */
@Composable
internal fun TrendsBlockDivider() {
    HorizontalDivider(color = Palette.hairline)
}

/** A section's headline: label and note, then the big period average with "avg" and its arrow chip. */
@Composable
internal fun TrendHeadline(headline: TrendHeadlineUi) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                headline.label,
                style = NoopType.subhead,
                color = Palette.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            headline.detail?.let {
                Text(it, style = NoopType.caption, color = Palette.textTertiary, maxLines = 1)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space6)) {
                Text(
                    headline.value ?: TRENDS_EMPTY_VALUE,
                    style = NoopType.number(34f),
                    color = if (headline.value != null) headline.valueColor else Palette.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                if (headline.value != null) {
                    Text(
                        uiString(R.string.trends_avg_suffix),
                        style = NoopType.subhead,
                        color = Palette.textTertiary,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            headline.delta?.let { delta ->
                ComparisonChip(
                    delta = delta,
                    tone = headline.tone,
                    formatDelta = headline.formatDelta,
                    unit = headline.deltaUnit,
                )
            }
        }
    }
}

/** Period stats in even columns: a quiet label over each number. */
@Composable
internal fun TrendStatsRow(stats: List<Pair<String, String>>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        stats.forEach { (label, value) ->
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
                Text(
                    label,
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(value, style = NoopType.bodyNumber, color = Palette.textPrimary, maxLines = 1)
            }
        }
    }
}

// MARK: - This week

/**
 * The This-week card: Charge, Effort and Rest for one Monday-to-Sunday week, each with its average, the arrow
 * against the week before and a bar per day. The round buttons step to earlier weeks and back.
 */
@Composable
internal fun TrendsWeekCard(
    title: String,
    subtitle: String,
    scores: List<WeekScoreUi>,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    NoopCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = NoopType.headline, color = Palette.textPrimary, maxLines = 1)
                    Text(
                        subtitle,
                        style = NoopType.caption,
                        color = Palette.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                WeekStepButton(Icons.Rounded.ChevronLeft, uiString(R.string.trends_previous_week), canGoBack) { onStep(-1) }
                WeekStepButton(Icons.Rounded.ChevronRight, uiString(R.string.trends_next_week), canGoForward) { onStep(1) }
            }
            if (scores.all { it.value == null }) {
                Text(
                    uiString(R.string.trends_no_readings_this_week),
                    style = NoopType.body,
                    color = Palette.textTertiary,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
                ) {
                    scores.forEach { score ->
                        WeekScoreColumn(score, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/** A round week button with the full touch target around it; dimmed and inert at either end of the history. */
@Composable
private fun WeekStepButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Box(
            modifier = Modifier
                .alpha(if (enabled) 1f else Palette.disabledOpacity)
                .size(WeekButtonSize)
                .clip(CircleShape)
                .background(Palette.surfaceInset),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = Palette.textSecondary, modifier = Modifier.size(Metrics.barIcon))
        }
    }
}

/** One score's column: label, week average in its colour, the arrow chip and the seven daily bars. */
@Composable
private fun WeekScoreColumn(score: WeekScoreUi, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(score.label, style = NoopType.subhead, color = Palette.textSecondary, maxLines = 1)
        Text(
            score.value ?: TRENDS_EMPTY_VALUE,
            style = NoopType.number(26f),
            color = if (score.value != null) score.valueColor else Palette.textTertiary,
            maxLines = 1,
        )
        score.delta?.let { ComparisonChip(delta = it, tone = score.tone, formatDelta = score.formatDelta) }
        Spacer(Modifier.weight(1f))
        WeekBars(score.daily, score.barColor)
    }
}

/** Seven bars, Monday to Sunday, from the 0-100 scale; a day without a value keeps a short grey stub. */
@Composable
private fun WeekBars(daily: List<Double?>, barColor: (Double) -> Color) {
    val stub = Palette.hairlineStrong
    Canvas(modifier = Modifier.fillMaxWidth().height(WeekBarHeight)) {
        val gap = Metrics.space4.toPx()
        val slots = daily.size.coerceAtLeast(1)
        val barWidth = minOf(WeekBarWidth.toPx(), (size.width - gap * (slots - 1)) / slots).coerceAtLeast(1f)
        val minHeight = 3.dp.toPx()
        daily.forEachIndexed { index, value ->
            val height = if (value == null) {
                minHeight
            } else {
                maxOf((value / 100.0).toFloat().coerceIn(0f, 1f) * size.height, minHeight)
            }
            drawRoundRect(
                if (value == null) stub else barColor(value),
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

// MARK: - Recovery

/**
 * The Charge line: each day's 7-day average across the period in the recovery colours, over the shaded normal
 * band and round gridlines labelled on the right. A tap or drag picks a day and labels it above the plot.
 */
@Composable
internal fun ChargeLineChart(
    line: List<Pair<Long, Double>>,
    axis: TrendAxis,
    band: Pair<Double, Double>?,
    modifier: Modifier = Modifier,
) {
    if (line.isEmpty()) return
    val days = remember(line) { line.map { it.first } }
    val runs = remember(days) { contiguousRuns(days, TRENDS_ROLLING_DAYS) }
    val floor = remember(line, band) { chargeAxisFloor(minOf(line.minOf { it.second }, band?.first ?: 100.0)) }
    val grid = remember(floor) { gridValues(floor, 100.0, 20.0) }
    var selected by remember(line) { mutableIntStateOf(-1) }
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
        ChartHeader(
            caption = uiString(R.string.trends_seven_day_average),
            pill = picked?.let { (day, value) ->
                uiString(R.string.trends_point_label, pointDate(day), value.roundToInt().toString())
            },
            pillAt = picked?.let { axis.fraction(it.first) } ?: 0f,
        )
        Box(modifier = Modifier.fillMaxWidth().height(LineChartHeight + PlotInset * 2)) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = PlotInset, bottom = PlotInset, end = AxisGutter)
                    .pickAlongX(line) { at ->
                        selected = nearestIndex(days, axis.startDay + at.toDouble() * (axis.endDay - axis.startDay))
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

/** Every day's Charge as a bar in its recovery colour, under its title and the period's low and peak. */
@Composable
internal fun ChargeDayStrip(points: List<Pair<Long, Double>>, axis: TrendAxis, lowPeak: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                uiString(R.string.trends_every_day_charge),
                style = NoopType.subhead,
                color = Palette.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            lowPeak?.let { Text(it, style = NoopType.caption, color = Palette.textTertiary, maxLines = 1) }
        }
        DayBars(points, axis, maxValue = 100.0, height = StripHeight, barColor = { Palette.recoveryColor(it) })
        AxisTicks(axis.ticks)
    }
}

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

/** A small card: title, period average with its unit, the arrow chip, then a sparkline over the period. */
@Composable
private fun TrendMini(mini: TrendMiniUi, axis: TrendAxis, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(mini.title, style = NoopType.subhead, color = Palette.textSecondary, maxLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            Text(
                mini.value ?: TRENDS_EMPTY_VALUE,
                style = NoopType.number(22f),
                color = if (mini.value != null) mini.valueColor else Palette.textTertiary,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            if (mini.value != null) {
                Text(
                    mini.unitAvg,
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        mini.delta?.let {
            ComparisonChip(delta = it, tone = mini.tone, formatDelta = mini.formatDelta, unit = mini.deltaUnit)
        }
        Spacer(Modifier.weight(1f))
        if (mini.points.isNotEmpty()) {
            TrendSparkline(mini.points, axis, mini.band, mini.lineColor, mini.maxGapDays)
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
) {
    val runs = remember(points, maxGapDays) { contiguousRuns(points.map { it.first }, maxGapDays) }
    val values = points.map { it.second } + listOfNotNull(band?.first, band?.second)
    val low = values.min()
    val high = values.max()
    val span = (high - low).takeIf { it > 1e-9 } ?: maxOf(abs(high), 1.0)
    val yMin = low - span * 0.2
    val yMax = high + span * 0.2
    val ring = Palette.surfaceRaised
    Canvas(modifier = Modifier.fillMaxWidth().height(SparkHeight)) {
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

// MARK: - Sleep

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
            Canvas(modifier = plot) {
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

// MARK: - Training

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
                modifier = Modifier.align(Alignment.Center).padding(end = AxisGutter),
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
 * The training-load block: title, what it compares and the form chip, then fitness and fatigue as two lines
 * with their latest values on the right. With no [load] it shows [note] instead, such as how many days it needs.
 */
@Composable
internal fun TrainingLoadBlock(subtitle: String, load: TrainingLoadUi?, note: String?, axis: TrendAxis) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(uiString(R.string.trends_training_load), style = NoopType.subhead, color = Palette.textSecondary)
                Text(subtitle, style = NoopType.caption, color = Palette.textTertiary)
            }
            if (load != null) {
                Spacer(Modifier.width(Metrics.space8))
                FormChip(load.formText, formColor(load.form))
            }
        }
        if (load == null) {
            note?.let { Text(it, style = NoopType.caption, color = Palette.textTertiary) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                LegendLine(Palette.metricPurple, uiString(R.string.trends_fitness))
                LegendLine(Palette.effortColor, uiString(R.string.trends_fatigue))
            }
            LoadChart(load, axis)
        }
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
                    .padding(top = PlotInset, bottom = PlotInset, end = AxisGutter),
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
 * The row above a line chart: [caption] on the left and, while a day is picked, its [pill] centred over it at
 * [pillAt] (a share of the plot's width). The caption steps aside when the pill would cover it. The row keeps
 * its height either way, so picking a day never moves the chart.
 */
@Composable
private fun ChartHeader(caption: String, pill: String?, pillAt: Float) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Text(caption, style = NoopType.caption, color = Palette.textTertiary, maxLines = 1)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.surfaceInset)
                    .padding(horizontal = Metrics.space10, vertical = Metrics.space4),
            ) {
                Text(pill.orEmpty(), style = NoopType.caption, color = Palette.textPrimary, maxLines = 1)
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val captionPlaceable = measurables[0].measure(loose)
        val pillPlaceable = measurables[1].measure(loose)
        val width = constraints.maxWidth
        val plotWidth = (width - AxisGutter.roundToPx()).coerceAtLeast(0)
        val height = maxOf(captionPlaceable.height, pillPlaceable.height)
        val pillLeft = (pillAt * plotWidth - pillPlaceable.width / 2f).roundToInt()
            .coerceIn(0, (width - pillPlaceable.width).coerceAtLeast(0))
        val gap = Metrics.space8.roundToPx()
        layout(width, height) {
            val captionTop = (height - captionPlaceable.height) / 2
            if (pill == null) {
                captionPlaceable.place(0, captionTop)
            } else {
                if (captionPlaceable.width + gap <= pillLeft) captionPlaceable.place(0, captionTop)
                pillPlaceable.place(pillLeft, (height - pillPlaceable.height) / 2)
            }
        }
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

/** "Tue 3 Nov" for a picked day, in the reader's language. */
private fun pointDate(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
