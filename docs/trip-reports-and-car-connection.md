# Journey reports and car connection

Owner-requested UI/data expansion, 15 November 2026. The Samsung drive-report
reference informs the visual hierarchy: rounded charcoal cards, large journey
metrics, colored signal rows and a teal speed chart. Outset retains its bundled
Poppins and original components. No Samsung/Google artwork, map branding or report
data is copied.

The owner explicitly chose **summaries and speed chart only**, without local GPS
route history. Old trips cannot acquire missing speed history or a reconstructed
path. Weather, addresses, phone-use and low-speed-distance metrics are not invented.

## Report UI

Journal entries are compact cards with time, estimated distance, signal counts and
**View trip report**. The report opens in a full-screen dismissible dialog, with a
Back to journal control that stays visible above the scrollable content. It shows recording start/end,
duration/distance, acceleration/braking/lateral candidate counts, estimated mean
and maximum speed, and expandable recording quality including available local
battery/background/screen-off evidence. Interrupted records clearly identify the
last checkpoint. Recording time is not represented as navigation departure/arrival.

`TripReportContent` receives immutable report data and an onBack callback; it does
not access ViewModels, Room, networking or location. Journal selection and report
scroll/quality expansion belong to presentation state. Recorder/store/upload
remain outside the content boundary. Large text and narrow widths retain scrolling.

## Bounded local speed history

New explicitly consented recordings aggregate qualified speed samples into
ten-second buckets, storing sample count, sample mean and peak per bucket. The
same fresh-position/speed-accuracy policy used by the summary gates this input.
At most 1,440 buckets cover the four-hour limit. Missing buckets stay missing;
chart paths break across them rather than connecting a gap to an invented zero.
Mean and peak curves are estimates, not an instantaneous raw speed trace.

Buckets live under versioned `local_report` in the existing summary JSON, with no
Room schema migration. Five-second checkpoints and final saves retain them; process
recovery keeps an interrupted chart with capture off. Local charts expire with
summaries after seven days, are bounded by the existing 20-recording cap and can
be deleted with the summary. Event-detail retention remains 24 hours or until
upload acknowledgement. No coordinates, GPS path or raw sensor arrays are added.

Both `local_report` and existing `field_test` diagnostics are removed by the upload
summary helper before building the unchanged server payload. Upload acknowledgement
clears local event details but retains the local chart until summary expiry/delete.
The recording disclosure explains the local chart and retention. Legacy summaries
retain their stored fields and show an explicit speed-history-unavailable state;
the original mean/max speed remains available when it was recorded.

## Android Auto connection detection

Uses the open-source Apache-2.0 AndroidX Car App `app:1.7.0` artifact and its
[`CarConnection` API](https://developer.android.com/training/cars/apps/library/connection-api).
The [stable release listing](https://developer.android.com/jetpack/androidx/releases/car-app)
and published 1.7.0 source/manifest were checked. Guava's Android runtime variant
is constrained to [33.6.0-android](https://github.com/google/guava/releases/tag/v33.6.0)
instead of inheriting Car App's legacy 31.1 dependency. Compose tests use the
existing BOM's UI-test artifact and its current v2 test rule.

`CarConnectionMonitor` maps provider results to checking, not detected, Android
Auto projection, native Android Automotive or unavailable. It observes while an
Activity is visible or an explicitly started foreground journey service is active;
idle background removes its observer. An unavailable source is not polled on every
GPS/state update. The exported system/library update notification prompts a provider
query; arbitrary broadcast extras do not determine connection or consent.

Projection/native connection appears as a compact map badge; Journal shows current
connection status. No vehicle identifiers, names, Bluetooth scans or extra location
permissions are requested, and no connection history is persisted/uploaded. A
connection does not start recording, location sharing or guidance, grant permissions,
or relaunch the app from a stopped process. Manual foreground journey controls remain
the only start path. AndroidX reports absent/unavailable provider data as not
connected in some cases, so the UI says **not detected** rather than proving that
no head unit exists nearby.

This adds phone-side detection, not a projected dashboard/navigation interface.
No `CarAppService`, head-unit templates or Android Auto launcher registration is
declared. The Android Auto host is optional and not bundled; solo navigation and
local reports work without it. Real wired/wireless projection and disconnect checks
join [the consolidated field session](consolidated-field-test.md), with no separate
road test requested for this feature.

## Verification and delivery

Native fixtures use synthetic report data and isolated in-memory Room storage, not
owner GPS or journal rows. Check monitor transition/cleanup/provider failure and
native provider absence; report legacy/interrupted/missing-speed/large-text cases;
chart persistence, expiry and upload exclusion; existing navigation/journal safety
and explicit background journeys. A simulated connection state is not a real
Android Auto head-unit pass.

Executed on the final candidate:

- Debug app/test APKs, **43 JVM tests** (zero failures/errors/skips) and Android
  lint passed with strict dependency verification and the committed locks. All
  21 newly recorded artifact checksums independently matched official Google
  Maven/Maven Central content, including published variant URLs for Compose
  AARs. Existing verification checksums were unchanged.
- **23 distinct successful native emulator checks:** car connection 3, report
  storage/privacy 2, report UI 4, map/tool UI 4, telemetry safety 4, real native map
  gesture/recreation 3, and background recording/guidance/notification denial 3.
  Initial map checks were skipped without their opt-in argument; the explicit
  `mapGestureChecks=true` rerun passed all three and supplies that evidence.
  Report fixture refinements were rerun; repeated methods are counted once.
- The actual Samsung passed **five isolated native checks**: car connection
  transition/cleanup/failure/native query 3 and local report persistence/privacy 2.
  Fake transitions use a local LiveData source, not broadcasts to Android Auto or
  a spoofed system provider. Room checks use a separate in-memory database.
- Native report screenshots were independently copied and visually inspected:
  `/home/x3h3l0/projects/navi/artifacts/outset/report-overview.png`,
  `report-speed.png` and `report-large-text.png` in that same directory. These
  contain only controlled synthetic UI data; no owner positions/records. Fixed
  header/back access, chart gaps and 130% text were checked. Screenshot files on
  the emulator were removed after copying.
- Final APK updated in place and cold-launched on the phone. The installed base
  APK SHA-256 matched the local build. All five owner recordings matched every
  saved field before and after the update/native checks; none were active. Task-
  owned private database snapshots/fingerprints were deleted; originals remain.

Real wired/wireless Android Auto projection and disconnect recovery remain in the
combined field checklist. Native provider queries do not establish head-unit
compatibility. New speed history needs a future ordinary recording; no old drive
was recomputed and no new physical drive was requested for software verification.

### Managed Gradle record

Workflow `775809bccbf37d4bada79a2539e2570a` used the selected Chris Banes
`gradle_run.py` wrapper. Runs invoked the project `apps/android/gradlew -p
apps/android --no-daemon` with the tasks/questions below; finish removed only
wrapper-owned logs, retaining APKs, reports and synthetic screenshot artifacts.

1. “Do the report UI, bounded local speed history and Android Auto connection
   monitor build and pass unit tests and lint with locked dependencies?” Initial
   run stopped at verification metadata for new dependencies; no test pass claimed.
2. “Do the report, chart privacy and car-connection checks compile and pass after
   recording checksums for the new AndroidX dependencies?” This run was interrupted
   with recorded SIGTERM 15 before completion; no test pass claimed. The wrapper
   recorded cleanup and no worker/child remained before further work.
3. “Do the report and car-connection APKs compile with the reviewed dependency
   versions and generated verification metadata?” Both APKs passed with
   `--write-locks --write-verification-metadata sha256`; a Compose test-rule
   deprecation was resolved using its documented v2 API. The earlier Android
   assets DSL deprecation was also resolved.
4. “Do the final report and Android Auto code pass strict dependency verification,
   APK builds, all unit tests and lint?” Yes: both APKs, 43 JVM tests and lint passed
   with normal strict verification and no warnings.
5. “Does the native report screenshot fixture compile after explicitly scrolling
   the whole speed chart into view?” `:app:assembleDebugAndroidTest` passed;
   separate native execution then passed all four report checks.
6. “Do the fixed-header trip report and final fixtures pass APK builds, unit tests
   and lint?” Yes: both APKs, all 43 JVM tests and lint passed without warnings.
   The final native report rerun passed all four checks, including visible Back
   access after chart/quality scrolling.

The full build runs used `:app:assembleDebug`, `:app:assembleDebugAndroidTest`,
`:app:testDebugUnitTest` and `:app:lintDebug`; the APK-only run omitted tests/lint.
No raw Gradle logs, personal phone data or debug database were committed.

### Delivery — 15 November 2026

Source `f35717c916e6a62f6ee651c0756250a44eaae2b1` passed [CI](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43716115129) and [image/GitOps publication](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43716782639). The live API uses that exact image; Flux reports Ready at `homelab-deploy@sha1:a07551728aeedc806c20a746e95613b06cd71d1d`. API, router and tile Deployments each have one ready replica. Database and map PVCs remain Bound at 5 GiB and 10 GiB. The five public Kenya route checks, vector tile and Latin glyph check passed after promotion.

The owner scheduled the combined physical session for **25 November 2026**. The all-day calendar reminder was saved; the two-real-phone, prolonged background, battery and actual head-unit checks remain open.
