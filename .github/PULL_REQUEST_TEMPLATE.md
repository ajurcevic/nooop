## What this PR does

<!-- A short description of the change and why it's needed. -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor / cleanup
- [ ] Documentation
- [ ] CI / tooling

## How it was tested

<!--
For anything on the BLE path, say what you tested on real hardware and which
strap (5.0 / MG or 4.0). For protocol or analytics changes, point to the test
that covers it. "Builds and unit tests pass" alone is not enough for BLE work.
-->

## Checklist

- [ ] Android unit tests pass if I touched `android/` (`./gradlew testFullDebugUnitTest`; after a branch switch add `--no-build-cache --rerun-tasks`, or Gradle may fail classes you never touched)
- [ ] No new build warnings introduced
- [ ] UI changes use only `Palette` / `Metrics` tokens — no hardcoded colors, fonts, or spacing
- [ ] No hardcoded hex frame bytes; protocol facts live in the schema / decoders
- [ ] Follows the conventions in [`AGENTS.md`](../AGENTS.md)
- [ ] I did not commit build output or any secrets/keystores

## Related issues

<!-- Refs #N -->
