# Journal acceleration/braking defect — 15 November 2026

## Observed evidence

The owner reported a drive on 14 November with saved journal telemetry but no
acceleration or sudden-braking events. Read-only inspection of the connected
Samsung's local Room database confirmed GPS/speed, accelerometer and rotation
samples, with zero candidate events in each retained recording. Event details
were already empty in the inspected snapshots. No raw journey was retained by
the app, so the exact event-time intervals and manoeuvres cannot be replayed.
Zero counts alone cannot prove that a qualifying event occurred.

Personal recording values, identifiers and database copies are excluded from
this report and Git. Existing recordings were preserved during APK replacement;
all three records matched every saved database field before and after the update
and native tests. No production database migration was needed.

## Reproduced defects and correction

The classifier previously accepted event intervals only at 1–3 seconds and
required two consecutive threshold crossings. Deterministic tests show that
999 ms intervals suppress detection and that a one-second strong brake followed
by constant speed is missed. These are confirmed software defects; the retained
field data cannot establish which affected the owner's particular manoeuvres.

The processor now accepts 0.5–3 second intervals and confirms longitudinal
candidates after at least 0.75 seconds of consecutive threshold-qualified evidence.
Accumulated speed change must also exceed the sum of the reported endpoint speed
uncertainties. The stored magnitude is the window's mean rate. Poor-quality fixes,
coasting, direction changes and gaps reset evidence. A five-second cooldown per
type, 100-event detail limit and the existing 3/3.5 m/s² acceleration/braking
thresholds remain. Lateral events retain two-interval confirmation.

GPS speed drives candidate classification. Accelerometer/rotation streams provide
sensor context; vehicle-axis alignment and calibrated IMU fusion remain future
scope. The journal now labels rapid acceleration and hard braking explicitly and
explains the GPS quality dependency. This is a bounded prototype heuristic, not
validated driving analysis; ordinary acceleration may correctly produce no event.

## Local verification

Applied the previously selected Chris Banes Compose/testing and compact Gradle
workflow guidance at pinned revision
`b684174b626c58e00e102de06de99f8a8a25ba4d`.

- New jitter, brief-brake and short-window regressions failed against the old
  processor (three expected failures), establishing reproduction before the fix.
- All 38 JVM tests then passed. Added checks cover jitter, brief braking,
  short-window duration/uncertainty, coasting/quality/gaps, cooldowns and ordinary
  below-threshold speed changes; existing bounds/expiry/quality tests still pass.
- Debug APK, Android test APK and Android lint passed.
- The real Samsung passed all four `TelemetrySafetyTest` checks: isolated Room
  persistence of derived brief events, migration, recovery/expiry/binding/bounds,
  and native supported sensor start/stop. Fixed public test coordinates went only
  into a pure processor feeding a separate in-memory database. No Android mock
  provider, real GPS recording, automatic upload or production-record mutation
  was used for these checks.
- Updated APK installed using `adb install -r`; no uninstall/data-clear operation.
  Original saved data remained unchanged after testing.

Commands used the skill-managed wrapper around:

```sh
apps/android/gradlew -p apps/android --no-daemon \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb -s DEVICE shell am instrument -w \
  -e class org.projectdrive.app.TelemetrySafetyTest \
  org.projectdrive.app.test/androidx.test.runner.AndroidJUnitRunner
```

Wrapper workflow `80402b4e25275e4275ea0abc73259589` completed. Its questions:

- “Do the new callback-jitter and brief-braking regressions reproduce the missing
  candidate events?” Yes: the old implementation failed all three new regressions.
- “Do telemetry regression tests, the updated APK and device tests compile, and
  Android lint pass after the event detection fix?” Yes: 38 tests, both APKs and
  lint passed; the separate native execution passed four tests.

Finish removed only wrapper-owned logs. Build outputs and owner recordings were
retained. No backend contract, schema, paid service or library change is required.

## Remaining field gate

Existing summaries keep their original counts; missed historical events cannot
be reconstructed. Repeat an ordinary journey using the updated app, mounted
consistently and visible. Review counts, GPS coverage and interruptions afterward.
Do not deliberately provoke harsh manoeuvres. Event sensitivity and false positives
remain unverified on the road, and the M5 gate stays open. The reported journal
drive does not establish M1 navigation, M4 two-phone or M6 battery/latency results.
