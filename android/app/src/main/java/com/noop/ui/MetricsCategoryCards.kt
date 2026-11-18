package com.noop.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Metrics category cards (Zoop "Calm" redesign)
//
// The Metrics tab groups every number into four cards, Recovery, Sleep, Training and Body, each with one
// plain-English line and a two-column grid of tiles. A tile shows the number in its data colour and a small
// arrow chip against the owner's 30-day baseline; a tap opens it in place to the full card width with the
// normal range, a 30-day chart and three small stats. The numbers and their baselines come in already
// resolved (MetricTileUi); the maths behind them is MetricsInsight.kt.

/** Shown in place of a number the day does not have. Matches the Home tab's empty value. */
private const val METRICS_EMPTY_VALUE = "—"

/** Corner radius of a tile inside a category card. */
private val TileRadius = 18.dp

/** Inner padding of a tile. */
private val TilePadding = 14.dp

/** Height of the 30-day chart inside an opened tile. */
private val ChartHeight = 88.dp

/** One tile on a Metrics category card, resolved to display text and colours. */
internal data class MetricTileUi(
    /** Stable identity for the opened-tile state: the Key Metric's raw key. */
    val id: String,
    val label: String,
    /** The formatted number, or null when the day has none (the tile then shows a dash). */
    val value: String?,
    val unit: String,
    val valueColor: Color,
    /** The metric's own hue for its chart line and range band. */
    val lineColor: Color,
    /** The number on the comparison scale (Effort stored 0-100, temperatures in Celsius). Null hides the arrow. */
    val raw: Double?,
    val insight: MetricInsight,
    val rule: TrendRule,
    /** Share of the baseline inside which the number reads as close to it. */
    val tolerance: Double = HOME_TREND_TOLERANCE,
    /** Formats a number on the comparison scale, without its unit, for the baseline, range and stats. */
    val format: (Double) -> String,
    /** Formats the size of a difference (always positive) for the arrow chip. */
    val formatDelta: (Double) -> String = format,
    /** An extra line under the comparison, such as where an estimate came from. */
    val caption: String? = null,
    /** Compare with "normal" (zero) rather than a baseline: a skin temperature deviation is already relative. */
    val referenceIsNormal: Boolean = false,
    /** A full-width tile with a bar to this top of scale, on the comparison scale (Effort's 100). */
    val barMax: Double? = null,
    /** The bar's top of scale as shown ("21" or "100"). */
    val barMaxLabel: String? = null,
    /** Quiet words after the label on a full-width tile, such as "today so far". */
    val labelSuffix: String? = null,
    /** The opened chart's first date, the window's start ("18 Oct"). */
    val axisStart: String? = null,
    /** The opened chart's last date: "Today", or the day the number was measured. */
    val axisEnd: String? = null,
    /** Opens the metric's own trend screen, or does what an empty tile needs (calibration). */
    val onOpen: (() -> Unit)? = null,
    /** Days (yyyy-MM-dd) whose value is Zoop's estimate; the opened chart marks them with a hollow dot. */
    val estimatedDays: Set<String> = emptySet(),
) {
    /** What the arrow compares with: the 30-day baseline, or zero for a deviation. */
    val reference: Double? get() = if (referenceIsNormal) 0.0 else insight.baseline

    /** The arrow's colour band, null for a neutral metric or a missing side. */
    val tone: HomeTone? get() = comparisonTone(raw, reference, rule)

    /** Which side of its reference the number sits on, null without both sides. */
    val side: BaselineSide? get() {
        val value = raw ?: return null
        val ref = reference ?: return null
        return baselineSide(value, ref, rule, tolerance)
    }

    /** Only a tile with a number has anything to open in place. */
    val canExpand: Boolean get() = raw != null
}

/** A summary-card chip: a label and its arrow against the baseline, such as "HRV ↑ 3 ms". */
internal data class MetricDriver(
    val label: String,
    val delta: Double,
    val tone: HomeTone?,
    val formatDelta: (Double) -> String,
)

/** The category card's title, shared with the Trends section cards. */
internal fun categoryTitle(category: MetricCategory): String = when (category) {
    MetricCategory.RECOVERY -> uiString(R.string.l10n_today_screen_recovery_ea924f72)
    MetricCategory.SLEEP -> uiString(R.string.l10n_today_screen_sleep_3cac34e6)
    MetricCategory.TRAINING -> uiString(R.string.metrics_category_training)
    MetricCategory.BODY -> uiString(R.string.metrics_category_body)
}

/** The small rounded icon beside a category title. */
internal fun categoryIcon(category: MetricCategory): ImageVector = when (category) {
    MetricCategory.RECOVERY -> Icons.Rounded.Bolt
    MetricCategory.SLEEP -> Icons.Rounded.Bedtime
    MetricCategory.TRAINING -> Icons.AutoMirrored.Rounded.DirectionsRun
    MetricCategory.BODY -> Icons.Rounded.Accessibility
}

/**
 * The four category cards for the enabled metrics, in the fixed Recovery, Sleep, Training, Body order. A
 * card with no enabled metric is left out. [tiles] holds every metric, enabled or not, so each card's line
 * can read the facts it is about even when the owner hid one of the tiles.
 */
@Composable
internal fun MetricCategoryCards(
    tiles: Map<KeyMetric, MetricTileUi>,
    enabled: List<KeyMetric>,
    isToday: Boolean,
    expandedId: String?,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        MetricCategory.entries.forEach { category ->
            val shown = enabled.filter { metricCategory(it) == category }.mapNotNull { tiles[it] }
            if (shown.isEmpty()) return@forEach
            MetricCategoryCard(
                category = category,
                line = categoryLine(category, tiles, isToday),
                tiles = shown,
                expandedId = expandedId,
                onToggle = onToggle,
            )
        }
    }
}

/** The one plain-English line under a category title, built from the same tiles the card draws. */
private fun categoryLine(category: MetricCategory, tiles: Map<KeyMetric, MetricTileUi>, isToday: Boolean): String? =
    when (category) {
        MetricCategory.RECOVERY -> {
            val charge = recoveryChargeLine(tiles[KeyMetric.CHARGE]?.raw)?.let { uiString(it) }
            val vitals = recoveryVitalsLine(tiles[KeyMetric.HRV]?.tone, tiles[KeyMetric.RESTING_HR]?.tone)
                ?.let { uiString(it) }
            when {
                charge != null && vitals != null -> uiString(R.string.metrics_two_sentences, charge, vitals)
                charge != null -> charge
                vitals != null -> vitals
                else -> uiString(R.string.metrics_recovery_empty)
            }
        }
        MetricCategory.SLEEP -> {
            val rest = sleepRestLine(tiles[KeyMetric.REST]?.raw)?.let { uiString(it) }
            val asleep = tiles[KeyMetric.ASLEEP]
            val asleepValue = asleep?.raw
            val duration = if (asleep != null && asleepValue != null) {
                val res = sleepDurationLine(asleep.tone)
                val reference = asleep.reference
                if (reference != null && asleep.tone != null && asleep.tone != HomeTone.FAIR) {
                    uiString(res, asleep.format(asleepValue), asleep.formatDelta(abs(asleepValue - reference)))
                } else {
                    uiString(res, asleep.format(asleepValue))
                }
            } else {
                null
            }
            when {
                rest != null && duration != null -> uiString(R.string.metrics_two_sentences, rest, duration)
                rest != null -> rest
                duration != null -> duration
                else -> uiString(R.string.metrics_sleep_empty)
            }
        }
        MetricCategory.TRAINING -> {
            val effort = tiles[KeyMetric.EFFORT]
            uiString(trainingLine(effort?.raw, effort?.insight?.baseline, isToday))
        }
        MetricCategory.BODY -> {
            val skin = tiles[KeyMetric.SKIN_TEMP]?.side
            // raw, not value: a weight taken from the profile shows a number but was never measured.
            val any = listOf(KeyMetric.WEIGHT, KeyMetric.SKIN_TEMP, KeyMetric.BLOOD_OXYGEN)
                .any { tiles[it]?.raw != null }
            uiString(bodyLine(skin, any))
        }
    }

/** One category card: icon and title, the plain line, then the tiles on a two-column grid. */
@Composable
private fun MetricCategoryCard(
    category: MetricCategory,
    line: String?,
    tiles: List<MetricTileUi>,
    expandedId: String?,
    onToggle: (String) -> Unit,
) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            CategoryHeading(category)
            if (line != null) {
                Text(line, style = NoopType.body, color = Palette.textSecondary)
            }
            val rows = tileRows(tiles) { it.id == expandedId || it.barMax != null }
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = Metrics.space4).animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(Metrics.space10),
            ) {
                rows.forEach { row ->
                    val only = row.singleOrNull()
                    when {
                        only != null && only.id == expandedId ->
                            ExpandedMetricTile(only, onToggle = { onToggle(only.id) })
                        only != null && only.barMax != null ->
                            WideBarTile(only, onToggle = { tileTap(only, onToggle) })
                        else -> Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                        ) {
                            row.forEach { tile ->
                                CompactMetricTile(
                                    tile,
                                    onToggle = { tileTap(tile, onToggle) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** A category's mint icon in its soft circle beside the title, shared by the Metrics and Trends cards. */
@Composable
internal fun CategoryHeading(category: MetricCategory) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Palette.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                categoryIcon(category),
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(Metrics.iconSmall),
            )
        }
        Text(categoryTitle(category), style = NoopType.headline, color = Palette.textPrimary)
    }
}

/** A tap on a closed tile: open it in place when it has a number, else do what the empty tile offers. */
private fun tileTap(tile: MetricTileUi, onToggle: (String) -> Unit) {
    if (tile.canExpand) onToggle(tile.id) else tile.onOpen?.invoke()
}

/** The surface every tile shares: the inset fill, and the mint ring of the opened state. */
@Composable
private fun Modifier.tileSurface(opened: Boolean): Modifier {
    val shape = RoundedCornerShape(TileRadius)
    val base = this.clip(shape).background(Palette.surfaceInset)
    return if (opened) {
        base.background(Palette.accent.copy(alpha = 0.06f)).border(1.dp, Palette.accent.copy(alpha = 0.35f), shape)
    } else {
        base
    }
}

/** A closed tile: label and chevron, the number, then its arrow chip against the baseline. */
@Composable
private fun CompactMetricTile(tile: MetricTileUi, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val tappable = tile.canExpand || tile.onOpen != null
    Column(
        modifier = modifier
            .tileSurface(opened = false)
            .then(
                if (tappable) {
                    Modifier.clickable(onClickLabel = uiString(R.string.metrics_action_open_tile), onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .padding(TilePadding),
        verticalArrangement = Arrangement.spacedBy(Metrics.space6),
    ) {
        TileHeader(tile.label, opened = false, showChevron = tile.canExpand)
        ValueLine(tile, size = 26f)
        Spacer(Modifier.weight(1f))
        ComparisonLine(tile)
        tile.caption?.let {
            Text(it, style = NoopType.caption, color = Palette.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The tile's label with the open/close chevron on the right. */
@Composable
private fun TileHeader(label: String, opened: Boolean, showChevron: Boolean = true, suffix: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = NoopType.subhead, color = Palette.textSecondary, maxLines = 1)
            if (suffix != null) {
                Text(
                    " · ",
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                    maxLines = 1,
                )
                Text(
                    suffix,
                    style = NoopType.subhead,
                    color = Palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showChevron) {
            Icon(
                if (opened) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(Metrics.iconSmall),
            )
        }
    }
}

/** The big number in its data colour with its unit beside it, or a dash when the day has none. */
@Composable
private fun ValueLine(tile: MetricTileUi, size: Float) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Text(
            tile.value ?: METRICS_EMPTY_VALUE,
            style = NoopType.number(size),
            color = if (tile.value != null) tile.valueColor else Palette.textTertiary,
            maxLines = 1,
        )
        if (tile.value != null && tile.unit.isNotEmpty()) {
            Text(
                tile.unit,
                style = NoopType.subhead,
                color = Palette.textTertiary,
                maxLines = 1,
                modifier = Modifier.padding(bottom = (size / 9f).dp),
            )
        }
    }
}

/** The arrow chip and "vs 61", "Building your baseline" before there is one, or nothing without a number. */
@Composable
private fun ComparisonLine(tile: MetricTileUi) {
    val value = tile.raw ?: return
    val reference = tile.reference
    if (reference == null) {
        Text(
            uiString(R.string.today_update_building_baseline_title),
            style = NoopType.caption,
            color = Palette.textTertiary,
            maxLines = 2,
        )
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        ComparisonChip(delta = value - reference, tone = tile.tone, formatDelta = tile.formatDelta)
        Text(
            if (tile.referenceIsNormal) uiString(R.string.metrics_vs_normal)
            else uiString(R.string.metrics_vs_baseline, tile.format(reference)),
            style = NoopType.caption,
            color = Palette.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A small pill tinted in its comparison colour with an up or down arrow and the size of the difference, and
 * the [unit] after it where there is room for one (an opened tile). A difference that rounds to nothing shows
 * no arrow. Neutral metrics use the secondary grey.
 */
@Composable
internal fun ComparisonChip(delta: Double, tone: HomeTone?, formatDelta: (Double) -> String, unit: String? = null) {
    val color = tone?.let { homeToneColor(it) } ?: Palette.textSecondary
    val shown = formatDelta(abs(delta))
    val arrow = when {
        shown == formatDelta(0.0) -> null
        delta > 0 -> Icons.Rounded.ArrowUpward
        else -> Icons.Rounded.ArrowDownward
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = Metrics.space8, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (arrow != null) {
            Icon(arrow, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
        }
        Text(shown, style = NoopType.captionNumber, color = color, maxLines = 1)
        if (!unit.isNullOrEmpty() && !unit.startsWith("/")) {
            Text(unit, style = NoopType.caption, color = color, maxLines = 1)
        }
    }
}

/**
 * The full-width Effort tile: the number out of its scale, the arrow chip, and a bar from zero with a tick at
 * the baseline, labelled 0, "Baseline 12.4" and the top of the scale.
 */
@Composable
private fun WideBarTile(tile: MetricTileUi, onToggle: () -> Unit) {
    val max = tile.barMax ?: return
    val value = tile.raw
    val reference = tile.reference
    val tappable = tile.canExpand || tile.onOpen != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tileSurface(opened = false)
            .then(
                if (tappable) {
                    Modifier.clickable(onClickLabel = uiString(R.string.metrics_action_open_tile), onClick = onToggle)
                } else {
                    Modifier
                },
            )
            .padding(TilePadding),
        verticalArrangement = Arrangement.spacedBy(Metrics.space10),
    ) {
        TileHeader(tile.label, opened = false, showChevron = tile.canExpand, suffix = tile.labelSuffix)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) { ValueLine(tile, size = 30f) }
            if (value != null && reference != null) {
                ComparisonChip(delta = value - reference, tone = tile.tone, formatDelta = tile.formatDelta)
                Spacer(Modifier.width(Metrics.space6))
                Text(
                    uiString(R.string.metrics_vs_baseline, tile.format(reference)),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                    maxLines = 1,
                )
            } else if (value != null) {
                Text(
                    uiString(R.string.today_update_building_baseline_title),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                    maxLines = 1,
                )
            }
        }
        if (value != null) {
            val fill = (value / max).toFloat().coerceIn(0f, 1f)
            val tick = reference?.let { (it / max).toFloat().coerceIn(0f, 1f) }
            val track = Palette.hairlineStrong
            val deep = Palette.effortDeep
            val bright = tile.lineColor
            val tickColor = Palette.textSecondary
            Canvas(modifier = Modifier.fillMaxWidth().height(18.dp)) {
                val barHeight = 8.dp.toPx()
                val top = (size.height - barHeight) / 2f
                val radius = CornerRadius(barHeight / 2f, barHeight / 2f)
                drawRoundRect(track, topLeft = Offset(0f, top), size = Size(size.width, barHeight), cornerRadius = radius)
                if (fill > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(listOf(deep, bright), startX = 0f, endX = size.width * fill),
                        topLeft = Offset(0f, top),
                        size = Size(size.width * fill, barHeight),
                        cornerRadius = radius,
                    )
                }
                if (tick != null) {
                    val x = size.width * tick
                    drawLine(
                        tickColor,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }
            }
            ScaleLabels(
                start = tile.format(0.0),
                middle = reference?.let { uiString(R.string.metrics_baseline_value, tile.format(it)) },
                middleFraction = tick ?: 0.5f,
                end = tile.barMaxLabel ?: tile.format(max),
            )
        }
        tile.caption?.let {
            Text(it, style = NoopType.caption, color = Palette.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Labels under a bar: [start] at the left edge, [end] at the right, and [middle] centred on its tick. An end
 * label that the middle one would overlap is left out rather than drawn on top of it.
 */
@Composable
private fun ScaleLabels(start: String, middle: String?, middleFraction: Float, end: String) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Text(start, style = NoopType.captionNumber, color = Palette.textTertiary, maxLines = 1)
            Text(middle.orEmpty(), style = NoopType.captionNumber, color = Palette.textSecondary, maxLines = 1)
            Text(end, style = NoopType.captionNumber, color = Palette.textTertiary, maxLines = 1)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val startP = placeables[0]
        val middleP = placeables[1]
        val endP = placeables[2]
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val middleX = (middleFraction * width - middleP.width / 2f).roundToInt()
            .coerceIn(0, (width - middleP.width).coerceAtLeast(0))
        val height = maxOf(startP.height, middleP.height, endP.height)
        layout(width, height) {
            val hasMiddle = middle != null
            if (!hasMiddle || middleX >= startP.width + gap) startP.place(0, 0)
            if (hasMiddle) middleP.place(middleX, 0)
            if (!hasMiddle || middleX + middleP.width + gap <= width - endP.width) endP.place(width - endP.width, 0)
        }
    }
}

/**
 * An opened tile, the full card width: the number and its chip with a plain sentence, the normal-range bar,
 * the 30-day chart with the normal band and the dashed baseline, three small stats and a way to the full trend.
 */
@Composable
private fun ExpandedMetricTile(tile: MetricTileUi, onToggle: () -> Unit) {
    val value = tile.raw
    val reference = tile.reference
    val insight = tile.insight
    val low = insight.rangeLow
    val high = insight.rangeHigh
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tileSurface(opened = true)
            .clickable(onClickLabel = uiString(R.string.today_action_collapse), onClick = onToggle)
            .padding(TilePadding),
        verticalArrangement = Arrangement.spacedBy(Metrics.space14),
    ) {
        TileHeader(tile.label, opened = true, suffix = tile.labelSuffix)
        Row(verticalAlignment = Alignment.Top) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                ValueLine(tile, size = 40f)
                if (value != null && reference != null) {
                    ComparisonChip(
                        delta = value - reference,
                        tone = tile.tone,
                        formatDelta = tile.formatDelta,
                        unit = tile.unit,
                    )
                }
            }
            Spacer(Modifier.width(Metrics.space16))
            val sentence = expandedSentence(tile)
            if (sentence != null) {
                Text(
                    sentence,
                    style = NoopType.body,
                    color = Palette.textSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (value != null && low != null && high != null) {
            RangeBar(
                value = value,
                low = low,
                high = high,
                reference = reference,
                bandColor = tile.lineColor,
                dotColor = tile.valueColor,
                format = tile.format,
            )
        }
        if (insight.points.size >= 2) {
            // The point the owner tapped or dragged to, read out in the axis row; cleared when the data changes.
            var picked by remember(tile.id, insight.points) { mutableStateOf<Int?>(null) }
            BaselineChart(
                points = insight.points,
                low = low,
                high = high,
                reference = reference,
                lineColor = tile.lineColor,
                dotColor = tile.valueColor,
                picked = picked,
                onPick = { picked = it },
                estimated = estimatedPointIndices(insight.windowStart, insight.points, tile.estimatedDays),
            )
            ChartAxis(
                start = tile.axisStart,
                end = tile.axisEnd,
                middle = picked?.let { index -> insight.points.getOrNull(index)?.let { index to it } }?.let { (index, point) ->
                    val label = pickedPointLabel(insight.windowStart, point, tile.format, tile.unit)
                    if (index in estimatedPointIndices(insight.windowStart, insight.points, tile.estimatedDays)) {
                        uiString(R.string.metrics_point_estimated, label)
                    } else {
                        label
                    }
                },
            )
        }
        StatsRow(tile)
        tile.onOpen?.let { open ->
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = open)
                    .padding(horizontal = Metrics.space8, vertical = Metrics.space4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space2),
            ) {
                Text(uiString(R.string.metrics_open_trend), style = NoopType.subhead, color = Palette.accent)
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = Palette.accent,
                    modifier = Modifier.size(Metrics.iconSmall),
                )
            }
        }
    }
}

/** "Above your baseline. Inside your normal range." from the opened tile's own numbers. */
private fun expandedSentence(tile: MetricTileUi): String? {
    val value = tile.raw ?: return null
    val side = tile.side ?: return null
    val first = uiString(baselineSentence(side))
    val low = tile.insight.rangeLow
    val high = tile.insight.rangeHigh
    if (low == null || high == null) return first
    return uiString(R.string.metrics_two_sentences, first, uiString(rangeSentence(rangePosition(value, low, high))))
}

/**
 * The normal-range bar: a light track, the owner's 30-day normal range as a band, the baseline as a tick and
 * the day's number as a dot, with the range's ends labelled underneath.
 */
@Composable
private fun RangeBar(
    value: Double,
    low: Double,
    high: Double,
    reference: Double?,
    bandColor: Color,
    dotColor: Color,
    format: (Double) -> String,
) {
    val lowest = minOf(low, value, reference ?: low)
    val highest = maxOf(high, value, reference ?: high)
    val span = (highest - lowest).takeIf { it > 1e-9 } ?: maxOf(abs(highest), 1.0)
    val axisMin = lowest - span * 0.25
    val axisMax = highest + span * 0.25
    fun fraction(v: Double): Float = ((v - axisMin) / (axisMax - axisMin)).toFloat().coerceIn(0f, 1f)
    val track = Palette.hairlineStrong
    val tickColor = Palette.textSecondary
    val ring = Palette.surfaceInset
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(20.dp)) {
            val barHeight = 8.dp.toPx()
            val top = (size.height - barHeight) / 2f
            val cy = size.height / 2f
            val radius = CornerRadius(barHeight / 2f, barHeight / 2f)
            drawRoundRect(track, topLeft = Offset(0f, top), size = Size(size.width, barHeight), cornerRadius = radius)
            val bandStart = size.width * fraction(low)
            val bandEnd = size.width * fraction(high)
            drawRoundRect(
                bandColor.copy(alpha = 0.35f),
                topLeft = Offset(bandStart, top),
                size = Size((bandEnd - bandStart).coerceAtLeast(barHeight), barHeight),
                cornerRadius = radius,
            )
            if (reference != null) {
                val x = size.width * fraction(reference)
                drawLine(
                    tickColor,
                    start = Offset(x, cy - 7.dp.toPx()),
                    end = Offset(x, cy + 7.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            val dot = Offset(size.width * fraction(value), cy)
            drawCircle(ring, radius = 8.dp.toPx(), center = dot)
            drawCircle(dotColor, radius = 6.dp.toPx(), center = dot)
        }
        RangeLabels(
            lowFraction = fraction(low),
            highFraction = fraction(high),
            lowText = format(low),
            highText = format(high),
        )
    }
}

/**
 * "Normal range" at the left and the range's two ends under their positions on the bar. The ends move apart
 * when they would touch, and the title is dropped when the low end sits where it would go.
 */
@Composable
private fun RangeLabels(lowFraction: Float, highFraction: Float, lowText: String, highText: String) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Text(uiString(R.string.metrics_normal_range), style = NoopType.caption, color = Palette.textTertiary, maxLines = 1)
            Text(lowText, style = NoopType.captionNumber, color = Palette.textSecondary, maxLines = 1)
            Text(highText, style = NoopType.captionNumber, color = Palette.textSecondary, maxLines = 1)
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val titleP = placeables[0]
        val lowP = placeables[1]
        val highP = placeables[2]
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val lowMax = (width - lowP.width).coerceAtLeast(0)
        val highMax = (width - highP.width).coerceAtLeast(0)
        var lowX = (lowFraction * width - lowP.width / 2f).roundToInt().coerceIn(0, lowMax)
        var highX = (highFraction * width - highP.width / 2f).roundToInt().coerceIn(0, highMax)
        if (lowX + lowP.width + gap > highX) {
            val middle = (lowX + lowP.width + highX) / 2
            lowX = (middle - gap / 2 - lowP.width).coerceIn(0, lowMax)
            highX = (lowX + lowP.width + gap).coerceIn(0, highMax)
        }
        val height = maxOf(titleP.height, lowP.height, highP.height)
        layout(width, height) {
            if (titleP.width + gap <= lowX) titleP.place(0, 0)
            lowP.place(lowX, 0)
            highP.place(highX, 0)
        }
    }
}

/**
 * The 30-day line: the normal band shaded behind it, the baseline dashed, a soft fill fading down from the
 * line and a ringed dot on the day itself. Points sit at their calendar position, so a missed day is a gap
 * in time, not a squeezed one.
 */
@Composable
private fun BaselineChart(
    points: List<Pair<Int, Double>>,
    low: Double?,
    high: Double?,
    reference: Double?,
    lineColor: Color,
    dotColor: Color,
    picked: Int? = null,
    onPick: (Int) -> Unit = {},
    estimated: Set<Int> = emptySet(),
) {
    val values = points.map { it.second } + listOfNotNull(low, high, reference)
    val minValue = values.min()
    val maxValue = values.max()
    val span = (maxValue - minValue).takeIf { it > 1e-9 } ?: maxOf(abs(maxValue), 1.0)
    val yMin = minValue - span * 0.15
    val yMax = maxValue + span * 0.15
    val dash = Palette.textTertiary
    val ring = Palette.surfaceInset
    val lastDay = points.last().first.coerceAtLeast(1)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(ChartHeight)
            // Tap or drag along the line to read a day. These consume the touch, so a tap on the chart does not
            // also collapse the tile around it.
            .pointerInput(points) {
                detectTapGestures { onPick(nearestPointIndex(points, lastDay, size.width.toFloat(), it.x)) }
            }
            .pointerInput(points) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onPick(nearestPointIndex(points, lastDay, size.width.toFloat(), change.position.x))
                }
            },
    ) {
        val width = size.width
        val height = size.height
        fun x(day: Int): Float = day.toFloat() / lastDay * width
        fun y(v: Double): Float = (height - (v - yMin) / (yMax - yMin) * height).toFloat()
        if (low != null && high != null) {
            drawRect(
                lineColor.copy(alpha = 0.10f),
                topLeft = Offset(0f, y(high)),
                size = Size(width, (y(low) - y(high)).coerceAtLeast(1f)),
            )
        }
        if (reference != null) {
            val dashLength = 4.dp.toPx()
            drawLine(
                dash,
                start = Offset(0f, y(reference)),
                end = Offset(width, y(reference)),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(dashLength, dashLength)),
            )
        }
        val offsets = points.map { Offset(x(it.first), y(it.second)) }
        val area = smoothPath(offsets).apply {
            lineTo(offsets.last().x, height)
            lineTo(offsets.first().x, height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.16f), Color.Transparent)))
        drawPath(
            smoothPath(offsets),
            lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        // An estimated day is a hollow dot on the line, so a guessed point never reads as solidly as a measured one.
        estimated.forEach { index ->
            offsets.getOrNull(index)?.let { at ->
                drawCircle(ring, radius = 3.dp.toPx(), center = at)
                drawCircle(lineColor, radius = 3.dp.toPx(), center = at, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        val end = offsets.last()
        drawCircle(ring, radius = 5.dp.toPx(), center = end)
        drawCircle(dotColor, radius = 3.5.dp.toPx(), center = end)
        offsets.getOrNull(picked ?: -1)?.let { at ->
            drawLine(dash, start = Offset(at.x, 0f), end = Offset(at.x, height), strokeWidth = 1.dp.toPx())
            drawCircle(ring, radius = 5.dp.toPx(), center = at)
            drawCircle(lineColor, radius = 3.5.dp.toPx(), center = at)
        }
    }
}

/**
 * The index of the point nearest [x] on a chart [width] wide whose x axis runs from day 0 to [lastDay]. Pure,
 * so the pick is testable without a touch.
 */
internal fun nearestPointIndex(points: List<Pair<Int, Double>>, lastDay: Int, width: Float, x: Float): Int {
    if (points.isEmpty() || width <= 0f) return 0
    val day = x / width * lastDay.coerceAtLeast(1)
    return points.indices.minByOrNull { abs(points[it].first - day) } ?: 0
}

/** The indices of [points] whose calendar day (counted from [windowStart]) is in [estimatedDays]. Pure. */
internal fun estimatedPointIndices(
    windowStart: LocalDate?,
    points: List<Pair<Int, Double>>,
    estimatedDays: Set<String>,
): Set<Int> {
    if (windowStart == null || estimatedDays.isEmpty()) return emptySet()
    return points.indices.filterTo(HashSet()) { windowStart.plusDays(points[it].first.toLong()).toString() in estimatedDays }
}

/** "12 Nov · 54 ms": the picked point's date and value, or the value alone when the window has no start date. */
internal fun pickedPointLabel(
    windowStart: LocalDate?,
    point: Pair<Int, Double>,
    format: (Double) -> String,
    unit: String,
): String {
    val value = listOf(format(point.second), unit).filter { it.isNotBlank() }.joinToString(" ")
    val date = windowStart?.plusDays(point.first.toLong())?.let { shortDate(it, 0) } ?: return value
    return "$date · $value"
}

/** A smooth curve through [points], easing horizontally between each pair so it never overshoots them. */
internal fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        val midX = (a.x + b.x) / 2f
        cubicTo(midX, a.y, midX, b.y, b.x, b.y)
    }
}

/**
 * The dates under the chart: the window's first day, "Last 30 days" (or the picked point's reading, [middle])
 * and the number's own day.
 */
@Composable
private fun ChartAxis(start: String?, end: String?, middle: String? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(start.orEmpty(), style = NoopType.caption, color = Palette.textTertiary, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(
            middle ?: uiString(R.string.metrics_last_30_days),
            style = NoopType.caption,
            color = if (middle != null) Palette.textPrimary else Palette.textTertiary,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(end.orEmpty(), style = NoopType.caption, color = Palette.textTertiary, maxLines = 1)
    }
}

/** Baseline, normal range and 7-day average in three small columns. A deviation's baseline is zero by definition. */
@Composable
private fun StatsRow(tile: MetricTileUi) {
    val insight = tile.insight
    val low = insight.rangeLow
    val high = insight.rangeHigh
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        if (!tile.referenceIsNormal) {
            StatColumn(
                label = uiString(R.string.l10n_insights_screen_baseline_e6ab7982),
                value = insight.baseline?.let(tile.format),
                unit = tile.unit,
                modifier = Modifier.weight(1f),
            )
        }
        StatColumn(
            label = uiString(R.string.metrics_range),
            value = if (low != null && high != null) uiString(R.string.metrics_range_value, tile.format(low), tile.format(high)) else null,
            unit = tile.unit,
            modifier = Modifier.weight(1f),
        )
        StatColumn(
            label = uiString(R.string.metrics_average_7_days),
            value = insight.average7?.let(tile.format),
            unit = tile.unit,
            modifier = Modifier.weight(1f),
        )
    }
}

/** One small stat: a quiet label over the number and its unit, or a dash. */
@Composable
private fun StatColumn(label: String, value: String?, unit: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
        Text(label, style = NoopType.caption, color = Palette.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            Text(
                value ?: METRICS_EMPTY_VALUE,
                style = NoopType.bodyNumber,
                color = if (value != null) Palette.textPrimary else Palette.textTertiary,
                maxLines = 1,
            )
            if (value != null && unit.isNotEmpty() && !unit.startsWith("/")) {
                Text(unit, style = NoopType.caption, color = Palette.textTertiary, maxLines = 1)
            }
        }
    }
}

/** The one line that explains every arrow on the tab, shown once above the category cards. */
@Composable
internal fun MetricsArrowCaption(modifier: Modifier = Modifier) {
    Text(
        uiString(R.string.metrics_arrow_caption),
        style = NoopType.footnote,
        color = Palette.textTertiary,
        modifier = modifier.fillMaxWidth().padding(horizontal = Metrics.space4),
    )
}

/** The quiet "Customize" text button at the end of the cards, which opens the metric chooser. */
@Composable
internal fun MetricsCustomizeButton(onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = Metrics.space16, vertical = Metrics.space10),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        ) {
            Icon(
                Icons.Rounded.Tune,
                contentDescription = null,
                tint = Palette.textSecondary,
                modifier = Modifier.size(Metrics.iconSmall),
            )
            Text(uiString(R.string.today_customize_action), style = NoopType.subhead, color = Palette.textSecondary)
        }
    }
}

/** The summary card's chips: each a label and its arrow against the baseline, wrapping onto a second line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MetricDriverChips(drivers: List<MetricDriver>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
        verticalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        drivers.forEach { driver ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Palette.surfaceInset)
                    .padding(start = Metrics.space12, end = Metrics.space4, top = Metrics.space4, bottom = Metrics.space4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
            ) {
                Text(driver.label, style = NoopType.subhead, color = Palette.textPrimary, maxLines = 1)
                ComparisonChip(delta = driver.delta, tone = driver.tone, formatDelta = driver.formatDelta)
            }
        }
    }
}

/** The day [daysBack] days before [day] as "19 Oct", for chart labels. */
internal fun shortDate(day: LocalDate, daysBack: Int): String =
    day.minusDays(daysBack.toLong()).format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
