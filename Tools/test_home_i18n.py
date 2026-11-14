#!/usr/bin/env python3
"""Focused zero-baseline localization guard for the phone Home/Today surfaces.

This intentionally does not use the repository-wide grandfathered baseline.  Once the
Home migration is complete, every finding in this source closure must stay at zero.
Run with::

    python3 Tools/test_home_i18n.py
"""

from __future__ import annotations

import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

import i18n_audit as audit


ROOT = Path(__file__).resolve().parents[1]

# The complete Android Today implementation plus the small Today-only helpers it calls.
# AppRoot is deliberately handled separately below: most of that file is the unrelated
# More/navigation UI, while only its Today tab and quick-action resources belong here.
ANDROID_HOME_FILES = {
    "android/app/src/main/java/com/noop/ui/TodayScreen.kt",
    "android/app/src/main/java/com/noop/ui/TodayDayNav.kt",
    "android/app/src/main/java/com/noop/ui/TodayLayoutPrefs.kt",
    "android/app/src/main/java/com/noop/ui/TodayMetricsLogic.kt",
    "android/app/src/main/java/com/noop/ui/TodayProvenance.kt",
    "android/app/src/main/java/com/noop/ui/TodayScoring.kt",
    "android/app/src/main/java/com/noop/ui/AutoWorkoutNudge.kt",
    "android/app/src/main/java/com/noop/ui/JournalReminder.kt",
    "android/app/src/main/java/com/noop/ui/CycleTrackerDialog.kt",
    "android/app/src/main/java/com/noop/ui/KeyMetricPrefs.kt",
    "android/app/src/main/java/com/noop/ui/DashboardCards.kt",
    "android/app/src/main/java/com/noop/analytics/ReadinessEngine.kt",
    "android/app/src/main/java/com/noop/analytics/StepsEstimateEngine.kt",
    "android/app/src/main/java/com/noop/analytics/RecoveryDrivers.kt",
}
ANDROID_SHELL_FILE = "android/app/src/main/java/com/noop/ui/AppRoot.kt"

# These helpers return display strings through variables, which scan_android cannot
# data-flow back from Text(variable).  Scan every literal in them conservatively.  The
# allowlist contains only persistence/source identifiers and date formats, never copy.
ANDROID_DISPLAY_HELPERS = {
    "android/app/src/main/java/com/noop/ui/TodayLayoutPrefs.kt",
    "android/app/src/main/java/com/noop/ui/TodayMetricsLogic.kt",
    "android/app/src/main/java/com/noop/ui/TodayProvenance.kt",
    "android/app/src/main/java/com/noop/ui/TodayScoring.kt",
}

# These producers feed strings into Home through fields/lambdas, so the general Android scanner cannot
# see the eventual Text(variable). Only stable wire ids, preference keys, units and format specs belong
# in the allowlist; every human phrase must become a semantic/resource contract.
ANDROID_INDIRECT_COPY_FILES = {
    "android/app/src/main/java/com/noop/analytics/ReadinessEngine.kt",
    "android/app/src/main/java/com/noop/analytics/StepsEstimateEngine.kt",
    "android/app/src/main/java/com/noop/ui/AutoWorkoutNudge.kt",
    "android/app/src/main/java/com/noop/ui/KeyMetricPrefs.kt",
    "android/app/src/main/java/com/noop/ui/DashboardCards.kt",
    "android/app/src/main/java/com/noop/analytics/RecoveryDrivers.kt",
}
ANDROID_INDIRECT_NON_UI_LITERALS = {
    # Engine keys, metric units and numeric format specs.
    "hrv", "rhr", "respRate", "acwr", "monotony", "ms", "bpm", "rpm", "%.1f",
    # Auto-workout source/wire values. Workout is the persisted generic sport tag, not rendered copy.
    "my-whoop", "Workout", "apple-health", "health-connect", "lifting", "autoDetect",
    # Stable KeyMetric raw values + preferences.
    "charge", "effort", "rest", "restingHr", "bloodOxygen", "respiratory", "steps",
    "weight", "calories", "today.keyMetrics", "today.keyMetricsDetailed",
    "today.keyMetricsWindowDays", ",",
    # Stable DashboardCard raw values + preference; units remain measurement metadata.
    "stress", "fitnessAge", "vo2max", "vitality", "skinTemp", "sleep", "hydration", "coupled",
    "stepsAverage30",
    "today.dashboardCards", "yrs", "kcal", "",
}

# Confirmed runtime copy that reaches Today through variables, lambdas, model fields or accessibility
# aggregation. The normal Compose scanner cannot follow those producer paths, so pin every confirmed
# phrase until it has a resource/semantic contract. Wire ids and format strings are intentionally absent.
ANDROID_TODAY_RUNTIME_COPY = {
    "Today", "Yesterday", "day", "days",
    "Session running", "Session ended", "Start session",
    "Guarding — silence means you're on track.", "See the summary of your last session.",
    "Strap-guided effort session. It only buzzes when you drift off today's band.", "BETA",
    "Building your baseline", "Charge, Effort and Rest become personal after a few nights of wear.",
    "Live now. Your scores are building.", "Charge, Effort and Rest build over your next few nights of wear.",
    "Cards with no value yet show a dash.", "SOLID", "BUILDING", "CALIBRATING",
    "5-minute average | selected day", "5-minute average | since midnight",
    "24-hour heart rate", "Still", "Walking", "Running",
    "Good morning", "Good afternoon", "Good evening", "No Data", "Calibrating",
    "7-day trend", "14-day trend", "30-day trend", "1 week", "2 weeks", "1 month",
}
ANDROID_HELPER_NON_UI_LITERALS = {
    # TodayLayoutPrefs stable backup/persistence wire values.
    "hero", "liveSession", "synthesis", "keyMetrics", "workouts", "heartRate",
    "recoveryVitals", "yourCards", "menstrualCycle", "journal", "addedCards",
    "today.sectionOrder", "today.hiddenSections",
    # TodayScoring parse/default formats.
    "9999-12-31", "d MMM", "24h", "12h", "6h", "3h", "1h",
    "<1m", "${secs / 60}m", "${secs / 3600}h", "${secs / 86_400}d",
    # TodayProvenance source ids and metric dictionary keys.
    "-noop", "recovery", "strain", "sleep_performance", "oura-import", "oura-api",
    "fitbit-import", "garmin-import", "xiaomi-band",
}

# AppRoot's Home-owned shell strings are resource-backed: the visible Today bottom-tab
# label and the four actions opened from Today's + button.  Do not gate unrelated AppRoot
# findings (for example the More-page expanded/collapsed accessibility state).
ANDROID_HOME_SHELL_RESOURCES = {
    "nav_today", "action_live_hr", "action_start_workout", "action_log_journal",
    "action_breathe",
}

R_STRING = re.compile(r"\bR\.string\.([A-Za-z_][A-Za-z0-9_]*)")


def _format_findings(rows: list[tuple[str, int, str]]) -> str:
    return "\n".join(f"{path}:{line}: {literal!r}" for path, line, literal in rows)


def _all_kotlin_literals(path: Path) -> list[tuple[int, str]]:
    """All non-comment string literals, using the audit's template-aware lexer."""
    text = audit._mask_comments(path.read_text(encoding="utf-8"))
    result: list[tuple[int, str]] = []
    i = 0
    while i < len(text):
        if text[i] != '"':
            i += 1
            continue
        end = audit._skip_string_literal(text, i)
        result.append((text.count("\n", 0, i) + 1, text[i + 1:end - 1]))
        i = end
    return result


def _is_helper_copy(literal: str) -> bool:
    """Stricter than the general audit for display-producing helper files.

    Lower-case one-word values such as ``latest``/``night`` are normally
    indistinguishable from ids, but in this tiny closure the real ids are all
    enumerated in ANDROID_HELPER_NON_UI_LITERALS, so they must be gated too.
    """
    return bool(re.search(r"[A-Za-z]", literal)) and not audit.PURE_FORMAT_SPEC.fullmatch(literal)


def _android_resource_names(path: Path) -> set[str]:
    return set(R_STRING.findall(audit._mask_comments(path.read_text(encoding="utf-8"))))


class HomeLocalizationTest(unittest.TestCase):
    maxDiff = None

    def test_android_home_has_no_audit_findings(self) -> None:
        findings = [row for row in audit.scan_android() if row[0] in ANDROID_HOME_FILES]
        self.assertEqual([], findings, "Unlocalized Android Home UI:\n" + _format_findings(findings))

    def test_android_display_helpers_have_no_raw_ui_copy(self) -> None:
        findings: list[tuple[str, int, str]] = []
        for relative in sorted(ANDROID_DISPLAY_HELPERS):
            for line, literal in _all_kotlin_literals(ROOT / relative):
                if literal in ANDROID_HELPER_NON_UI_LITERALS:
                    continue
                if _is_helper_copy(literal):
                    findings.append((relative, line, literal))
        self.assertEqual([], findings, "Raw Android Home helper copy:\n" + _format_findings(findings))

    def test_android_indirect_home_producers_have_no_raw_ui_copy(self) -> None:
        findings: list[tuple[str, int, str]] = []
        for relative in sorted(ANDROID_INDIRECT_COPY_FILES):
            for line, literal in _all_kotlin_literals(ROOT / relative):
                if literal in ANDROID_INDIRECT_NON_UI_LITERALS:
                    continue
                if _is_helper_copy(literal):
                    findings.append((relative, line, literal))
        self.assertEqual([], findings, "Raw indirect Android Home copy:\n" + _format_findings(findings))

    def test_android_today_runtime_producer_copy_is_resource_backed(self) -> None:
        relative = "android/app/src/main/java/com/noop/ui/TodayScreen.kt"
        findings = [
            (relative, line, literal)
            for line, literal in _all_kotlin_literals(ROOT / relative)
            if literal in ANDROID_TODAY_RUNTIME_COPY
        ]
        self.assertEqual([], findings, "Raw Today runtime producer copy:\n" + _format_findings(findings))

    def test_android_score_section_label_is_resolved_at_ui_boundary(self) -> None:
        relative = "android/app/src/main/java/com/noop/ui/ScoringGuideScreen.kt"
        source = audit._mask_comments((ROOT / relative).read_text(encoding="utf-8"))
        self.assertNotRegex(source, r"val\s+label\s*:\s*String", "ScoreSection must expose a resource contract")

    def test_android_home_score_labels_and_explain_copy_are_resource_backed(self) -> None:
        today_relative = "android/app/src/main/java/com/noop/ui/TodayScreen.kt"
        today = audit._mask_comments((ROOT / today_relative).read_text(encoding="utf-8"))
        self.assertNotIn("domain.label", today, "Home score names must be resolved from Android resources")

        guide_relative = "android/app/src/main/java/com/noop/ui/ScoringGuideScreen.kt"
        findings = [
            (guide_relative, line, literal)
            for line, literal in _all_kotlin_literals(ROOT / guide_relative)
            if _is_helper_copy(literal)
            and literal not in {
                "${(sampleFraction * 100).roundToInt()}",
                "noop_scoring_guide_prefs",
                "scoringGuideCardSeen",
            }
        ]
        self.assertEqual([], findings, "Raw Android score-explainer copy:\n" + _format_findings(findings))

    def test_android_home_score_labels_and_empty_states_fit_localized_copy(self) -> None:
        source = (ROOT / "android/app/src/main/java/com/noop/ui/TodayScreen.kt").read_text(encoding="utf-8")
        self.assertIn("text = domainLabel.uppercase()", source)
        self.assertIn(".padding(horizontal = Metrics.space16)", source)
        self.assertIn("minScale = 0.7f", source)
        self.assertIn("private fun RingNoData(diameter: Dp)", source)
        self.assertIn("maxLines = 2", source)
        self.assertIn("overflow = TextOverflow.Clip", source)
        self.assertNotIn("Text(domainLabel.uppercase()", source)
        self.assertNotIn("private fun RingNoData()", source)

    def test_android_section_header_trailing_copy_does_not_squeeze_title(self) -> None:
        source = (ROOT / "android/app/src/main/java/com/noop/ui/Components.kt").read_text(encoding="utf-8")
        section_header = source.split("fun SectionHeader(", 1)[1].split("// MARK: - StrandTone", 1)[0]
        self.assertIn("if (overline != null || trailing != null)", section_header)
        self.assertIn("Text(title, style = NoopType.title2", section_header)
        self.assertLess(section_header.index("if (trailing != null)"), section_header.index("Text(title"))

    def test_android_today_source_counts_use_two_plural_resources(self) -> None:
        relative = "android/app/src/main/java/com/noop/ui/TodayScreen.kt"
        source = audit._mask_comments((ROOT / relative).read_text(encoding="utf-8"))
        self.assertIn("R.plurals.today_source_days", source)
        self.assertIn("R.plurals.today_source_workouts", source)
        self.assertNotIn("R.string.today_source_counts", source)

    def test_android_analytics_stays_free_of_ui_resources(self) -> None:
        source = (ROOT / "android/app/src/main/java/com/noop/analytics/ReadinessEngine.kt").read_text(encoding="utf-8")
        self.assertNotIn("com.noop.R", source)
        self.assertNotIn("androidx.annotation.StringRes", source)
        self.assertIn("enum class Copy", source)

    def test_android_english_home_contract_preserves_head_semantics(self) -> None:
        root = ET.parse(ROOT / "android/app/src/main/res/values/strings.xml").getroot()
        strings = {node.attrib["name"]: (node.text or "").replace("\\'", "'") for node in root.findall("string")}
        expected = {
            "today_card_hrv": "HRV", "today_card_hrv_subtitle": "Heart-rate variability",
            "today_card_resting_hr": "Resting HR", "today_card_resting_hr_subtitle": "Resting heart rate",
            "today_card_respiratory": "Respiratory", "today_card_respiratory_subtitle": "Breaths per minute",
            "today_card_steps": "Steps", "today_card_steps_subtitle": "Today",
            "today_card_stress": "Stress", "today_card_stress_subtitle": "Autonomic load",
            "today_card_fitness_age": "Fitness Age", "today_card_fitness_age_subtitle": "Updated weekly",
            "today_card_vo2max": "VO₂ Max", "today_card_vo2max_subtitle": "Estimated, updated weekly",
            "today_card_vitality": "Vitality", "today_card_vitality_subtitle": "Wellness score",
            "today_card_blood_oxygen": "Blood Oxygen", "today_card_blood_oxygen_subtitle": "Blood oxygen",
            "today_card_skin_temp": "Skin Temp", "today_card_skin_temp_subtitle": "Skin temperature",
            "today_card_sleep": "Sleep", "today_card_sleep_subtitle": "Last night",
            "today_card_calories": "Calories", "today_card_calories_subtitle": "Active energy",
            "today_card_hydration": "Hydration", "today_card_hydration_subtitle": "Today's fluid",
            "today_card_coupled": "Coupled view",
            "today_card_coupled_subtitle": "Recovery, strain and sleep in one glance",
            "today_readiness_wear_for_nights": "Wear the strap for a few nights and your readiness read will appear here.",
            "today_readiness_hrv_good": "above your baseline - well recovered",
            "today_readiness_normal_range": "in your normal range",
            "today_readiness_hrv_watch": "a touch below baseline",
            "today_readiness_hrv_bad": "suppressed - a sign of autonomic fatigue",
            "today_readiness_rhr_good": "at or below baseline",
            "today_readiness_rhr_watch": "running a little high",
            "today_readiness_rhr_bad": "elevated - overtraining or illness can do this",
            "today_readiness_resp_bad": "up vs baseline - sometimes an early sign of getting sick",
            "today_readiness_resp_watch": "slightly raised vs baseline",
            "today_readiness_monotony_watch": "low - similar strain every day raises strain/illness risk",
            "today_readiness_load_ramping_down": "ramping down (acute:chronic %1$s) - room to build",
            "today_readiness_load_sweet_spot": "in the sweet spot (acute:chronic %1$s)",
            "today_readiness_load_building_fast": "building fast (acute:chronic %1$s) - watch fatigue",
            "today_readiness_load_spiking": "spiking (acute:chronic %1$s) - higher injury risk",
            "today_readiness_more_nights": "A few more nights of data and your readiness read will sharpen.",
            "today_readiness_run_down_summary": "Several signals are down at once. Treat today as recovery - easy movement, real sleep tonight.",
            "today_readiness_strained_summary": "One of your signals is flagging. You can train, but keep it controlled and bank the recovery.",
            "today_readiness_primed_summary": "Your signals are aligned and your load is supported. A harder session is well backed today.",
            "today_readiness_balanced_summary": "Nothing's flagging. Train to feel - your body's holding steady.",
            "today_readiness_evidence_monotony": "monotony %1$s",
        }
        self.assertEqual({}, {key: (strings.get(key), value) for key, value in expected.items() if strings.get(key) != value})

    def test_android_today_chrome_and_click_labels_are_resource_backed(self) -> None:
        relative = "android/app/src/main/java/com/noop/ui/TodayScreen.kt"
        source = audit._mask_comments((ROOT / relative).read_text(encoding="utf-8"))
        patterns = {
            "raw SectionHeader/Overline": re.compile(r"\b(?:SectionHeader|Overline)\s*\(\s*\""),
            "raw onClickLabel": re.compile(r"\bonClickLabel\s*=\s*\""),
        }
        findings = []
        for kind, pattern in patterns.items():
            findings.extend(
                (relative, source.count("\n", 0, match.start()) + 1, kind)
                for match in pattern.finditer(source)
            )
        self.assertEqual([], findings, "Raw Today chrome/a11y copy:\n" + _format_findings(findings))

    def test_android_home_display_formatting_is_not_pinned_to_us_locale(self) -> None:
        display_files = ANDROID_INDIRECT_COPY_FILES | ANDROID_DISPLAY_HELPERS | {
            "android/app/src/main/java/com/noop/ui/TodayScreen.kt",
        }
        findings = []
        for relative in sorted(display_files):
            source = audit._mask_comments((ROOT / relative).read_text(encoding="utf-8"))
            findings.extend(
                (relative, source.count("\n", 0, match.start()) + 1, "Locale.US")
                for match in re.finditer(r"\b(?:java\.util\.)?Locale\.US\b", source)
            )
        self.assertEqual([], findings, "US-pinned Android Home display formatting:\n" + _format_findings(findings))

    def test_android_source_joiner_preserves_spaces_through_aapt(self) -> None:
        root = ET.parse(ROOT / "android/app/src/main/res/values/strings.xml").getroot()
        node = next(n for n in root.findall("string") if n.attrib.get("name") == "today_source_joiner")
        self.assertEqual('" + "', node.text, "Quote source_joiner so aapt preserves both spaces")

    def test_android_home_resources_cover_focus_locales(self) -> None:
        used = set(ANDROID_HOME_SHELL_RESOURCES)
        for relative in ANDROID_HOME_FILES:
            used.update(_android_resource_names(ROOT / relative))

        paths = {"en": ROOT / "android/app/src/main/res/values"}
        paths.update({
            lang: ROOT / f"android/app/src/main/res/{directory}"
            for lang, directory in audit.ANDROID_LOCALE_DIRS.items()
        })
        missing: list[str] = []
        for lang, directory in paths.items():
            # Android merges every values XML file, including feature-specific resources.
            # Inspect each locale independently so English fallback cannot hide missing copy.
            names = {
                node.attrib["name"]
                for path in sorted(directory.glob("*.xml"))
                for node in ET.parse(path).getroot()
                if node.tag in {"string", "plurals"}
            }
            missing.extend(f"{lang}: {name}" for name in sorted(used - names))
        self.assertEqual([], missing, "Missing Android Home resources:\n" + "\n".join(missing))

    def test_android_home_locale_context_values_are_natural_and_complete(self) -> None:
        expected = {
            "pl": {
                "l10n_today_screen_no_cardio_load_yet_effort_builds_e952006c":
                    "Nie ma jeszcze obciążenia kardio. Wysiłek zwiększa się, gdy tętno osiągnie strefę wysiłku (około 50% rezerwy tętna). Spokojny dzień szczerze odczytuje się w pobliżu zera.",
            },
            "zh": {
                "l10n_today_screen_edit_key_metrics_f95e61a4": "编辑关键指标",
                "l10n_today_screen_no_new_nights_from_your_strap_for_stale_days_8863bcfe":
                    "你的手环已有 %1$d 天没有记录到新的夜间数据。请检查它是否已连接并正在保存数据。",
            },
            "pt-rPT": {"l10n_today_screen_synthesis_876bc749": "SÍNTESE"},
            "es": {"l10n_today_screen_unitformatter_effortdisplay_strain_effortscale_effort_53dbd951": "%1$s Esfuerzo"},
        }
        mismatches = {}
        for directory, locale_values in expected.items():
            root = ET.parse(ROOT / f"android/app/src/main/res/values-{directory}/strings.xml").getroot()
            strings = {node.attrib["name"]: node.text or "" for node in root.findall("string")}
            for key, value in locale_values.items():
                if strings.get(key) != value:
                    mismatches[f"{directory}:{key}"] = (strings.get(key), value)
        self.assertEqual({}, mismatches)

if __name__ == "__main__":
    unittest.main(verbosity=2)
