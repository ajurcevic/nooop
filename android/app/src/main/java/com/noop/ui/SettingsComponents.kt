package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

// MARK: - Landing-list row

/**
 * One row of the Settings landing list: a title, an optional few-word summary of what the row opens, and
 * a trailing chevron. The whole row is the tap target. It is drawn like the More page's rows (same
 * padding and chevron) without the leading icon, so the list reads as words rather than a grid of glyphs.
 */
@Composable
internal fun SettingsNavRow(title: String, summary: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = NoopType.body, color = Palette.textPrimary)
            if (summary != null) {
                Text(summary, style = NoopType.footnote, color = Palette.textTertiary)
            }
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(Metrics.iconSmall),
        )
    }
}

/** The hairline between [SettingsNavRow]s in one card, inset to line up with the row text. */
@Composable
internal fun SettingsNavDivider() {
    HorizontalDivider(color = Palette.hairline, modifier = Modifier.padding(start = 16.dp))
}

// MARK: - Section card (ports SettingsView's private SettingsSection)

/**
 * A grouped settings card: an icon + title header, an optional short blurb, then content. The blurb is
 * for the one line a card's controls cannot say themselves; most cards have none. A faint brand-green
 * wash anchors the card to NOOP's neutral chrome (mirrors macOS).
 */
@Composable
internal fun SettingsCard(
    icon: ImageVector,
    title: String,
    blurb: String? = null,
    content: @Composable () -> Unit,
) {
    NoopCard(padding = 20.dp, tint = Palette.accent) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                Text(title, style = NoopType.title2, color = Palette.textPrimary)
            }
            if (blurb != null) {
                Text(blurb, style = NoopType.subhead, color = Palette.textSecondary)
            }
            content()
        }
    }
}

// MARK: - Labelled toggle row (title + detail + trailing Switch)

/**
 * A title + optional short detail on the left with a trailing [Switch], matching the in-section toggle
 * idiom the Strap/Health Connect sections already use. Used by the v5 Health & wellness group so every
 * opt-in reads consistently. The switch colours mirror the rest of Settings (gold track when on).
 */
@Composable
internal fun SettingsToggleRow(
    title: String,
    detail: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = NoopType.subhead, color = Palette.textPrimary)
            if (detail != null) {
                Text(detail, style = NoopType.footnote, color = Palette.textTertiary)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.surfaceBase,
                checkedTrackColor = Palette.accent,
                uncheckedThumbColor = Palette.textSecondary,
                uncheckedTrackColor = Palette.surfaceInset,
                uncheckedBorderColor = Palette.hairline,
            ),
        )
    }
}

// MARK: - Two-column form row (ports SettingsView's private FormRow)

/**
 * Label on the left, control on the right — the two-column form feel.
 *
 * The control is measured first against the full row width. When the label's longest word still fits
 * beside it, the two sit side by side with the control at the end. When it does not (a three-way pill
 * such as Clock's "System default / 12-hour / 24-hour" on a narrow phone), the label moves above the
 * control instead of being squeezed until it breaks mid-word or the pill clips its own labels.
 *
 * Keep sentence-length copy out of [control]: a control that wants the whole width always stacks. Put
 * that copy in a sibling `Text` BELOW the row instead (see the Step-calibration and Waist rows in
 * SettingsScreen); a SHORT right-aligned sub-value under the control (e.g. "Auto · 173 bpm") is fine.
 */
@Composable
internal fun SettingsFormRow(label: String, control: @Composable () -> Unit) {
    Layout(
        content = {
            Text(label, style = NoopType.body, color = Palette.textPrimary)
            Box { control() }
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 4.dp),
    ) { measurables, constraints ->
        val labelMeasurable = measurables[0]
        val controlMeasurable = measurables[1]
        val maxWidth = constraints.maxWidth
        val gap = 16.dp.roundToPx()
        val stackGap = 8.dp.roundToPx()

        val control = controlMeasurable.measure(Constraints(maxWidth = maxWidth))
        // The narrowest the label can go without breaking a word: its longest word.
        val labelMinWidth = labelMeasurable.minIntrinsicWidth(Constraints.Infinity)
        val sideBySide = labelMinWidth + gap + control.width <= maxWidth

        if (sideBySide) {
            val labelWidth = (maxWidth - control.width - gap).coerceAtLeast(0)
            val labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = labelWidth))
            val height = maxOf(labelPlaceable.height, control.height, constraints.minHeight)
            layout(maxWidth, height) {
                labelPlaceable.placeRelative(0, (height - labelPlaceable.height) / 2)
                control.placeRelative(maxWidth - control.width, (height - control.height) / 2)
            }
        } else {
            val labelPlaceable = labelMeasurable.measure(Constraints(maxWidth = maxWidth))
            val contentHeight = labelPlaceable.height + stackGap + control.height
            val height = maxOf(contentHeight, constraints.minHeight)
            layout(maxWidth, height) {
                labelPlaceable.placeRelative(0, 0)
                control.placeRelative(0, labelPlaceable.height + stackGap)
            }
        }
    }
}

// MARK: - Shared bits

@Composable
internal fun SettingsRowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(1.dp)
            .background(Palette.hairline),
    )
}

@Composable
internal fun SettingsNoteRow(icon: ImageVector, iconTint: Color, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        Text(text, style = NoopType.footnote, color = Palette.textSecondary)
    }
}
