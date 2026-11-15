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
power/thermal benchmark. The GPS gap uses provider measurement timestamps and the
last-fix-to-end interval; it is not a network or server-latency measurement.
Charging disqualifies a discharge-only battery result.
These diagnostics stay local and are stripped before the existing telemetry upload
contract. Existing record schema, migrations, seven-day retention and event bounds
are unchanged. Old summaries do not acquire fabricated test data.

The application update uses `adb install -r`. Three owner summaries were privately
fingerprinted before installation; read-only post-installation comparison confirmed all three unchanged. Personal
record content and task-owned diagnostic database copies stay outside Git.
The later [Tigoni review](device-tests/2026-11-15-tigoni.md) confirms completed
physical-phone recording and saved acceleration/braking candidates. Only brief
background exposure and no screen-off duration were recorded; prolonged physical
background/screen-off operation remains an open field check.

## Verification and current limits

Debug app/test APKs, 40 JVM tests and Android lint passed. Initial compiler warnings
about qualifier annotation targets were resolved with explicit `@param:` targets.
The final emulator APK passed **23 distinct native checks**: 19 UI/camera/input,
permission, Room/sensor, convoy safety and public HTTPS checks; plus four background
checks for recording/rotation/notification Stop, guidance through Activity destruction
and replacement, notification denial, and process-termination recovery. The recording
test also passed a separate real emulator screen-off interval, with retained screen-off
seconds asserted. A host deliberately terminated an armed background session after
its checkpoint; recovery retained an interrupted record and left the service/recording
off. Task-created test records were removed by ID; original records were retained.
The arming invocation was intentionally terminated, not counted as a passed test.
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

Workflow finish removed only wrapper-owned build logs.

Recovery: revert/rebuild/install the same Android package. Keep the earlier
current-location and candidate-detection fixes. No database reset or backend
migration is required. Do not replace an APK during an actual active journey.

## Delivery addendum

Source: `a99ff775c0cd45f8db98a98c0267c36105d082e1`, pushed to GitHub.
The public-HTTPS APK was installed over the existing Samsung package with
`adb install -r` and launched successfully. St. Paul’s University, Limuru was sent
as the selected public destination; its private origin and route metrics are not
recorded here. The owner must choose My location and preview/inspect the route
while parked, and allow notifications at the first active journey start.
No real journey, physical screen-off recording, authenticated public convoy or
arrival result is claimed by this installation.

[CI](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43704989699) and
[deployment](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43705440408)
succeeded. The API is ready 1/1 at the source image tag; Flux is Ready=True at
`homelab-deploy@sha1:508f8b42f8e52e287a494ede9f65caf07ca1dbae`. A public map tile
returned HTTP 200. Existing 5 GiB database and 10 GiB map PVCs remain Bound. Routine
namespace reads used the existing deployer identity; Flux readiness used the existing
authorized operator context. No Kubernetes, Cloudflare, credential, RBAC or backend
contract changes were needed. Private task-created phone database copies were
removed after the unchanged-original-record comparison; the owner's originals remain.
