# Current-location map startup

15 November 2026. The map previously started on a fixed Nairobi street view and
followed a position only during guidance or simulated playback. Normal browsing
now follows a fresh foreground location fix too.

## Behavior

- Existing foreground fine or coarse permission enables map location on startup,
  unless the user explicitly chose **Turn location off**. Only that display
  boolean is added to preferences; no location history is added. Existing saved
  camera state still preserves the viewed area through configuration changes.
- Without permission, the app offers **Use my location** and its disclosure before
  requesting Android permission. Denial leaves exploration and route planning
  available. No automatic permission dialog appears on launch.
- The map centers when a fresh fix arrives. A gesture leaves following paused;
  **Recenter** resumes it. Manual exploration survives configuration changes.
- Route previews retain their overview; guidance and labelled playback follow
  their respective positions. Automatic map location does not fill From or To.
  The planner's explicit Use my location action still selects a route origin.
- Approximate/uncertain fixes use a wider zoom. Without a fix the map retains its
  viewport and displays a waiting/unavailable state. A fresh session without
  location shows Kenya coverage rather than a fabricated user position.
- Idle map subscriptions stop in the background and resume in the foreground
  when enabled. The later [journey-service change](background-journeys.md) keeps
  explicitly started guidance, recording and sharing active with notification controls.
  This does not request background location permission.

The native provider adapter attempts GPS and network registrations independently,
so one provider's permission restriction does not prevent the other registering.
Existing freshness/quality gates remain. Location-subscription effects depend on
both map and recording state, avoiding an initial composition stopping startup GPS.
Approximate fixes can arrive more slowly; an old cached fix is not relabelled as
fresh to hide that delay. The waiting state remains until a usable provider fix
arrives. Current-location tiles still depend on the existing regional basemap.

## Verification

All **40 JVM tests** passed (zero failures/errors/skips), including idle-map
following, manual exploration/Recenter and uncertainty-aware zoom. Debug APK,
instrumentation APK and Android lint passed.

On the unlocked Samsung API 37 phone, the opt-in native current-location test
passed using actual device location, without mock providers or a journey. It
checked fresh-fix centering, real touchscreen pan, continued manual exploration
through location updates, Recenter, background clearing and foreground
reacquisition. Route endpoints stayed empty; guidance, playback, recording and
sharing stayed off. Coordinate comparisons remained in memory; no coordinates,
screenshots or raw location tracks were logged or published.

Emulator checks cover no-permission startup/disclosure dismissal, existing map
gestures and route overview, shared links/search, controlled UI shortcuts,
convoy/telemetry safety, current-location following, persistent explicit opt-out
and re-enabling from Recenter. GPS fixtures use a public-place emulator location,
never the physical phone. The public HTTPS maps/routing/private-auth boundary
test also passed with its explicit network opt-in. The first combined invocation
skipped that network check because its opt-in was absent; a separate invocation
then executed it successfully.

An additional coarse-only emulator run timed out waiting for a fresh fix; its
network provider was disabled. After reboot, fresh-fix centering passed that
stage, but a System UI ANR dialog intercepted the gesture check. These two
invocations failed; they are not counted as successful acceptance runs. A clean
read-only emulator with 4 GiB RAM then passed the complete test with coarse
permission only, including centering, pan, Recenter and foreground reacquisition
(1 test, 15.172 seconds). Fine permission was not granted for this repeat.
There were **21 distinct successful native emulator checks**, with this additional
coarse-only repeat and one actual-location check on the physical phone.
Uncertainty-aware zoom also has independent JVM coverage. Android documents that providers obfuscate fixes for
coarse permission: [LocationManager reference](https://developer.android.com/reference/android/location/LocationManager).

Native tests are opt-in and do not mutate production journal records. The owner
APK was replaced with `adb install -r`, preserving its package and data. This
stationary proof does not close the remaining navigation, convoy, battery or
telemetry-event field gates.

The final current-location layout was inspected with a public Nakuru emulator
fixture. Its capture is outside Git at `artifacts/outset/current-location.png`;
it is not an owner-location screenshot.

Example focused invocation after installing both debug APKs:

```sh
adb -s DEVICE shell am instrument -w -e currentLocationChecks true \
  -e class org.projectdrive.app.CurrentLocationTest#foregroundFixCentersMapAndRecenterResumesAfterExploration \
  org.projectdrive.app.test/androidx.test.runner.AndroidJUnitRunner
```

The device must be unlocked, location enabled, foreground permission granted and
map location not explicitly off. Do not grant permissions or inject locations on
an owner's phone merely to satisfy this test. Opt-out mutation is emulator-only.
The legacy loopback navigation script now rejects devices with location permission
and uses the new idle-card text; syntax passed, but that full loopback suite was
not rerun. Historical reports of GPS-off fresh startup describe older behavior;
this release resumes foreground map location when permission was already granted.

## Build workflow

Skill-managed Gradle workflow `07df1a0056378195a51c8f13ee632a62` questions/results:

- “Does foreground current-location startup, uncertainty-aware camera following
  and opt-out persistence build and pass unit tests and lint?” Yes: both APKs,
  40 JVM tests and lint passed.
- “Do lifecycle-safe location subscriptions and the native startup, exploration
  and opt-out checks compile and pass lint?” Yes: both APKs and lint passed;
  the separate native executions passed as described above.

Finish removed only wrapper-owned build logs; APKs and owner data were retained.

No new dependency, backend contract, database migration, public route or paid
service is introduced. Recovery is a source revert/rebuild/install of the same
Android package; preserve local data and the earlier telemetry correction.

## Delivery

Source commit: `7964d88d1a7103a8e8e1fcf6be46d6c9fa34f4a7`.
[GitHub CI](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43701183067)
passed. The existing [homelab release](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43701641521)
promotes the same source through its immutable image and GitOps branch.

Release succeeded. Flux reported Ready=True at
`homelab-deploy@sha1:c9d8e33cd0e73f916e4a4aaf39bd2e1951e1347d`; the API
rolled out at source tag `7964d88d1a7103a8e8e1fcf6be46d6c9fa34f4a7` with
1/1 ready replicas, a ready Service endpoint, and HTTP 200 for a public map tile.
The existing 5 GiB database and 10 GiB map PVCs remained Bound. Routine deployment
and storage reads used `homelab-deployer`; Flux and EndpointSlice reads used the
existing authorized operator context after the restricted identity lacked those
read capabilities. No RBAC, credentials, storage or Cloudflare changes were made.
