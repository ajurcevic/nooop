# AGENTS.md — working on NOOP for Android

Guidance for anyone (human or AI agent) changing this repository. This is the high-signal map.
[`docs/ANDROID.md`](docs/ANDROID.md) covers the app in depth, [`docs/BUILD.md`](docs/BUILD.md) covers
signing and pairing, and [`docs/SCOPE.md`](docs/SCOPE.md) names the WHOOP-app features that stay out of
scope. [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) still carries the full BLE safety contract; its
Swift, macOS and design-system sections describe the upstream project and do not apply here.

## What this repository is (and the hard scope limits)

An **Android-only fork** of NOOP, focused on the **WHOOP 5.0 / MG** strap. NOOP is an
**offline-by-default, on-device** companion app: it pairs over Bluetooth, stores everything in on-device
SQLite (Room), and computes recovery / strain / HRV / sleep locally. There is **no NOOP-operated server,
no account, no cloud dependency, no telemetry**. Upstream issue #1314 permits one narrow exception: a
default-off Experimental client may export data one way to an HTTP(S) endpoint the user owns and
configures. It must remain outside strap sync, never read data back, and ship no receiver or hosted
service in this repository.

What was removed from upstream, on purpose:
- The macOS, iOS and watch apps, the Swift packages under `Packages/`, and the Xcode project.
- Support for other wearables: Oura, Polar, Garmin, Amazfit / Mi Band, generic BLE heart-rate straps and
  FTMS gym machines. The Add Device wizard offers WHOOP 5.0 / MG and WHOOP 4.0 only, and
  `SourceCoordinator` refuses a non-WHOOP registry row.
- Apple-only tooling, the Swift parity governance and its workflows.

What was kept on purpose:
- **WHOOP 4.0** code. It still works, but **WHOOP 5.0 / MG is the default** wherever a model has to be
  assumed (fresh install, reconnect fallback, wizard default).
- The **Room schema and migrations**, unchanged. A few stored-data helpers that WHOOP or analytics code
  still reads keep their old names (`OuraRespScale`, `OuraRedrainCollapse`, `StandardHrMapping`,
  `StandardHeartRate`, `RrSourceChannel`), so existing databases and `.noopbak` backups keep loading.
- File importers for other brands' exports (for example `WearableExportImporter`,
  `XiaomiBandImporter`). They read files, not devices.

A change is out of scope if it:
- adds a server, account, cloud dependency, or sends data off-device without the explicit user export
  boundary in [`docs/SCOPE.md`](docs/SCOPE.md);
- adds analytics/telemetry/crash-reporting that phones home;
- adds WHOOP firmware, decompiled app code, logos/assets, or any DRM circumvention. NOOP is
  **clean-room interoperability** with hardware the user owns.

Licensing: contributions are under the repo's [PolyForm Noncommercial 1.0.0](LICENSE) license.

## Architecture at a glance

Everything lives in the standalone Gradle project under `android/` (Kotlin, Jetpack Compose, Room;
flavors `Full` = real app and `Demo`).

| Package | What lives here |
|---|---|
| `com.noop.protocol` | BLE frame parse, CRC, command/event/packet decode. Pure Kotlin. |
| `com.noop.ble` | `WhoopBleClient`, the connection service, `SourceCoordinator`, bonding, reconnect, history offload and live collection. |
| `com.noop.data` | Room database, DAOs, `WhoopRepository`, device registry, backups. |
| `com.noop.analytics` | HRV / recovery / strain / sleep / stress math. Database-free. |
| `com.noop.ingest` | WHOOP CSV, Health Connect and other file importers. |
| `com.noop.ui` | Compose screens, `AppViewModel`, the Add Device wizard. |
| `com.noop.widget` | Glance home-screen widgets. |

The more "wire-level" (bytes) or "math-level" a change is, the more it must be covered by a JVM unit test
that runs with no phone and no strap.

## Build, test & CI

### Prerequisites
- **JDK 17** (`sourceCompatibility`/`jvmTarget` are 17 in `android/app/build.gradle.kts`). Gradle **8.7**
  is provisioned by `android/gradlew`; don't install a system Gradle.
- **Android SDK**: `platform-tools`, `platforms;android-35`, `build-tools;34.0.0`. Point Gradle at it via
  `android/local.properties` (`sdk.dir=…`, gitignored) or `$ANDROID_HOME`.
- **Python 3.12** for the tooling under `Tools/`.
- **Non-x86-64 Linux (e.g. arm64):** Google ships `aapt2` / `d8` as x86-64 only, so install
  `qemu-user-static binfmt-support`.

### Fast local loops
```bash
cd android && ./gradlew testFullDebugUnitTest              # add --tests "com.noop.…" to filter
cd android && ./gradlew assembleFullDebug                  # build the real app
# After a branch switch, ALWAYS add --no-build-cache --rerun-tasks. Gradle can otherwise serve
# generated sources (Room/KSP) from the previous branch and fail classes you never touched.
cd Tools && python3 -m unittest -v $(ls test_*.py | sed 's/\.py$//')
python3 Tools/i18n_audit.py --ci origin/main               # translation gate
python3 Tools/doc_comment_lint.py                          # detached doc comments
```

### What each CI job covers
| Workflow | Covers | Default state |
|---|---|---|
| `android.yml` | `assembleFullDebug` + `testFullDebugUnitTest` | **active**, path-filtered to `android/**` |
| `tools-python.yml` | The `Tools/test_*.py` suites, with a discovery floor | **active**, NO path filter: several suites assert on Android source (`test_home_i18n.py` reads `TodayScreen.kt` and the string resources), so no path list describes their inputs. |
| `source-hygiene.yml` | Doc comments that bind to nothing (`Tools/doc_comment_lint.py`) | **active** |
| `i18n-coverage.yml` | Diff-scoped translation gate (`Tools/i18n_audit.py --ci`) | **active** |
| `prune-stale-branches.yml` | Deletes branches whose PR merged or closed unmerged | weekly + dispatch |
| `fork-testing-build.yml` / `fork-release.yml` | Staging / release APK builds | on dispatch |

**BLE behavior cannot be CI-tested.** Anything on the connection / offload / live-HR path
(`com.noop.ble`) must be **validated on a real strap**; compile success proves
nothing about connection behavior. Say what you tested on hardware.

## Hard rules before you touch these areas

- **BLE (read the BLE safety contract in [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) first):** never
  add **destructive** commands — firmware/DFU, ship-mode, power-cycle, force-trim, fuel-gauge reset, or
  anything else that can brick, wipe, or permanently alter the device. The ban is on *destructive*, not
  on writing; reversibility is the test. A new non-trivial command is issue-first: justify why it is
  reversible, confirmation-gate it, never send it automatically, and document its payload and its
  on-device verification before any code. CRC-gate every inbound frame; keep the connection path stable;
  no hardcoded hex frame bytes in app code — protocol facts live in the decoders/schema.
- **`didBond` is load-bearing well beyond the handshake.** The bond watchdog, the never-bonded detector
  and the bond-refusal give-up all treat "connected but never bonded" as a fault to correct. A change that
  legitimately leaves a strap unbonded silently re-arms all of them (#1635). Not-bonding is only evidence
  of a fault when we were actually trying to bond.
- **A diagnostic may only assert what it can attribute.** Prefer silence, or name the gap, over a log
  line that claims more than it observed. Do not let a path go quiet either: gate per-connect readouts
  behind the Test Centre domain, but leave rare-event evidence (a state transition, a mismatch) always on.
- **Two readouts of one fact must not be able to disagree.** Do not show a fact twice; when it must
  appear twice, resolve both from one gated funnel and one clock.
- **A gate must be able to fail on the change that caused it.** Never gate a CI poll on
  `failures == 0`: an approval-parked roster has no failures and is not green. Require
  `non-success == 0` plus a stable total plus a roster floor.
- **Device / strap model resolution:** map a registry `model` label to a family through the one
  canonical resolver (`DeviceFamily.forRegistryModel`), never a scattered string compare — the wizard
  stores `"4.0"`, other paths `"WHOOP 4.0"`. Reads must thread the registry's **active** strap id, not a
  raw BLE address.
- **`doc_comment_lint` reports at the wrong line on purpose.** The baseline is a per-file count, so one
  new detached doc comment makes failures print against other, pre-existing sites. Look at what you just
  inserted; insert new declarations above a neighbour's doc block, not between it and its declaration.
- **Design system is law:** UI uses only the `Palette` / `Metrics` tokens. No hardcoded colors, fonts,
  or spacing.
- **Migrations:** add a versioned migration + a test; never mutate an existing migration. Column order in
  a Room `CREATE TABLE` must match the entity field order. Watch for data-loss traps (window-wide deletes,
  backfill rewrites).
- **The `.noopbak` backup format** (`BackupSettingsCodec` in `android/…/data/BackupSettings.kt`) must
  stay readable by upstream NOOP: keep its canonical keys and JSON kinds. Only Int/Double/String cross
  the wire. Dedup keys that cross that boundary must use a platform-neutral hash (e.g. FNV-1a over UTF-16
  code units), never Kotlin `hashCode`.
- **Deriving a physiological signal from raw sensor data:** validate that the method tracks a varying
  input, not one matched night. Until it does, land it as instrumentation or behind a default-off
  Experimental toggle; never make it the default or feed it into recovery or illness gates.

## PR & commit conventions

- **One concern per PR.** Keep a protocol change, a schema migration, and a UI change separate.
- **Use English for all repository-facing text.** App translations stay in their target languages.
- **Reference issues neutrally** with `Refs #N`; avoid GitHub auto-closing keywords.
- **Show your verification.** BLE → what you tested on hardware. Analytics → the method + a test.
  UI → design tokens only.
- **Versioning (SemVer):** bump `versionName` in `android/app/build.gradle.kts`; `versionCode` increments
  independently. The parts are counters, not decimals (`2.0.10` follows `2.0.9`).
- **Voice:** docs/comments are neutral, third-person, project-voice. Keep upstream credits intact.

When in doubt, prefer the smallest change that's correct and covered by a test that runs without a strap.
