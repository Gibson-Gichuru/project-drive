# Explicit background journeys

15 November 2026. Owner-authorized expansion before the Tigoni field test.
Navigation guidance, local journal recording and explicitly enabled convoy sharing
now use a process-owned session and a native Android location foreground service.
The Activity/ViewModel adapters render and dispatch actions; they no longer own
active collection. `DriveContent` remains the plain state/callback/slot UI boundary.

Start a journey mode from the visible app. Android notification access is requested
in context; the current implementation requires visible journey notifications.
The ongoing **Outset journey active** notification opens the app and provides
**Stop journey**, ending guidance, recording and sharing together. Individual app
Stop/Finish actions still stop their respective modes. Sessions continue through
Home, another app, screen-off and Activity replacement/rotation. Idle map browsing
does not start this service and stops location when backgrounded.

The service is unexported, non-sticky, and has no boot receiver. No
ACCESS_BACKGROUND_LOCATION permission, paid SDK or battery-optimization bypass is
added. FOREGROUND_SERVICE / FOREGROUND_SERVICE_LOCATION and an explicit location
service type support a session started while the app is visible. Notification
permission/channel denial blocks hidden startup; disabling the channel during a
session stops it. A bounded partial wake lock supports GPS/sensor/checkpoint work
with the display off; it is released when the service stops. Journey sessions have
a four-hour limit. Force-stop/system termination can interrupt a session: the last
five-second local checkpoint is recovered as interrupted, with recording/sharing/
guidance off. There is no promise of survival through arbitrary process termination.

Android references: [location service types](https://developer.android.com/develop/background-work/services/fgs/service-types),
[while-in-use start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start),
[notification permission](https://developer.android.com/develop/ui/views/notifications/notification-permission).

## Field evidence and retention

Existing summary and derived-event data remain. Each new local summary adds a
`field_test` object with version/API/model, battery endpoints, sampled charging,
background and screen-off seconds, battery temperature/thermal/power-save context,
network transport categories, sensor availability, maximum GPS callback gap and
speed-quality sample count. It contains no coordinates, SSIDs, device identifiers
or raw sensor arrays. Sampling is approximately one second, not a laboratory
power/thermal benchmark. Charging disqualifies a discharge-only battery result.
These diagnostics stay local and are stripped before the existing telemetry upload
contract. Existing record schema, migrations, seven-day retention and event bounds
are unchanged. Old summaries do not acquire fabricated test data.

The application update uses `adb install -r`. Three owner summaries were privately
fingerprinted before installation for a read-only preservation check. Personal
record content and task-owned diagnostic database copies stay outside Git.
The [Tigoni plan](device-tests/2026-11-15-tigoni.md) is prepared, not a field result.

## Verification and current limits

Debug app/test APKs, 40 JVM tests and Android lint passed. Initial compiler warnings
about qualifier annotation targets were resolved with explicit `@param:` targets.
A native emulator test passed background recording, rotation, normal save, notification
Stop and removal of its own stationary test record. Final native screen-off,
guidance, denial and process-termination checks are recorded in the delivery addendum.
No test uses injected coordinates on the physical phone. Software participants
and fixed public positions are emulator-only. Historical M4/M5 foreground-only
lifecycle reports describe the previous behavior and are superseded by this design.

The old opt-in convoy and telemetry harness expectations were updated for service
continuation. Their authenticated local end-to-end reruns are separate from the
solo background checks; do not infer an authenticated public convoy result.
Public convoy/sign-in remains unavailable until legitimate production identity is
implemented. Two-real-phone convoy, physical navigation/arrival, telemetry event
sensitivity, and full M6 latency/battery/operations gates remain open.

## Managed Gradle evidence

Workflow `e1504e12fc6569099d5b4d241acc5f37` used the `gradle_run.py` wrapper around
`:app:assembleDebug`, `:app:testDebugUnitTest` (initial/final complete runs),
`:app:assembleDebugAndroidTest` and `:app:lintDebug`.

- “Do service-owned journey sessions, visible background controls and local field
  diagnostics build and pass existing unit tests and lint?” Yes; initial annotation
  target warnings were inspected and resolved.
- “Do cancellation-safe service sessions and native background, notification-stop
  and process-recovery checks compile and pass lint?” Yes.
- “Do screen-off and guidance lifecycle checks, updated consent and aggregate
  battery diagnostics compile and pass lint?” Yes.
- “Do final notification-channel guards, bounded background sessions and screen-off
  test fixtures pass builds, unit tests and lint?” Yes; 40 JVM tests and lint passed.

Recovery: revert/rebuild/install the same Android package. Keep the earlier
current-location and candidate-detection fixes. No database reset or backend
migration is required. Do not replace an APK during an actual active journey.
