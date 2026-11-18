# Building NOOP for Android

NOOP is an offline companion app for WHOOP straps, focused in this repository on the **WHOOP 5.0 / MG**.
It pairs directly with the strap over Bluetooth Low Energy, stores everything on-device in SQLite (Room),
imports WHOOP CSV exports and Health Connect data, and computes recovery / strain / HRV / sleep locally.
There is no NOOP cloud or account. Network features and the default-on release check are documented in
the [privacy inventory](PRIVACY_SECURITY.md#11-network-paths-canonical-inventory).

> **Not affiliated with WHOOP, and not a medical device.** "WHOOP" is used only to identify the
> hardware this software interoperates with. NOOP contains no WHOOP code, firmware, or assets. All
> outputs (HR, HRV, recovery, strain, sleep, SpO₂, temperature) are approximations and are **not**
> clinically validated. See [`DISCLAIMER.md`](../DISCLAIMER.md) and [`ATTRIBUTION.md`](../ATTRIBUTION.md).

---

## Prerequisites

| Tool | Version |
|---|---|
| JDK | 17 |
| Android SDK | `platform-tools`, `platforms;android-35`, `build-tools;34.0.0` |
| Gradle | 8.7, provisioned by `android/gradlew` (do not install a system Gradle) |
| Android Studio | optional, current stable |

Point Gradle at the SDK with `android/local.properties` (`sdk.dir=/path/to/sdk`, git-ignored) or
`$ANDROID_HOME`. On a non-x86-64 Linux host (for example arm64), install `qemu-user-static
binfmt-support`, because Google ships `aapt2` and `d8` as x86-64 binaries only.

## Build and install

```bash
cd android

# The real app ("full" flavor), debug build:
./gradlew assembleFullDebug

# Install onto a phone with USB debugging enabled:
./gradlew installFullDebug
```

The debug APK lands at `android/app/build/outputs/apk/full/debug/app-full-debug.apk`. The `demo` flavor
(`./gradlew assembleDemoDebug`) preloads synthetic data so every screen can be explored without a strap.

An emulator cannot reach a physical strap, so BLE work needs a real phone and a real WHOOP.

## Release signing

Release signing credentials live in `android/keystore.properties` (git-ignored, never committed) with
the keys `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. When that file is absent, release
builds fall back to the debug key, so `./gradlew assembleFullRelease` always produces an installable APK.
If `android/fork-debug.keystore` exists, debug builds are signed with it, so successive debug APKs can be
installed over each other.

## Pairing a WHOOP 5.0 / MG

1. A WHOOP strap bonds to one device at a time. While it is connected to NOOP it will not stream to the
   official WHOOP app, and the other way round. This is reversible: pair it in the other app to get it
   back.
2. In NOOP, open the **Devices** screen, start the Add Device wizard and pick **WHOOP 5.0 / MG**.
3. Grant the Bluetooth permissions when asked (on Android 11 and older, location too).

WHOOP 4.0 is still supported and can be picked in the same wizard.

## Testing

```bash
cd android && ./gradlew testFullDebugUnitTest      # JVM unit tests, no phone or strap needed
cd Tools && python3 -m unittest -v $(ls test_*.py | sed 's/\.py$//')
```

After switching branches, add `--no-build-cache --rerun-tasks` to the Gradle command, or Gradle may
serve generated Room/KSP sources from the previous branch.

---

## Credits

NOOP builds on prior community reverse-engineering and interoperability work:

- **`ryanbr/noop`** — the upstream multi-platform project this fork is cut down from.
- **`johnmiddleton12/my-whoop`** — WHOOP 4.0 BLE protocol.
- **`b-nnett/goose`** — WHOOP 5.0 / MG BLE protocol (service family `fd4b0001-…`, CRC16-Modbus
  header) that the WHOOP-5 decode path is ported from.

See [`ATTRIBUTION.md`](../ATTRIBUTION.md) for full detail.
