# Map-first UI and camera controls

## Result

The map fills the display edge to edge. A compact bottom dock opens Navigation,
Convoy or Journal in a dismissible Material 3 bottom sheet. Route preview and
playback return to the map automatically; Back to map and Android Back dismiss
other tools. Recenter remains visible, as do active instructions and explicit
stop-sharing/finish-recording controls. Location stays opt-in.

The design uses forest green, warm paper surfaces, terracotta accents, rounded
cards and original line icons. The UI bundles Poppins Regular, Medium, SemiBold
and Bold locally for every Material typography role; it has no runtime font
service. Font origin, pinned revision and hashes are in [poppins-fonts.json](poppins-fonts.json).
The font's SIL Open Font License is distributed in the APK under
`assets/licenses/Poppins-OFL.txt`. Map glyphs remain the self-hosted Noto Sans set.
Day and night map styles use the same existing self-hosted OSM-derived sources.

## Applied skills

The owner replaced the earlier Material UI skill request with
[Chris Banes's skills](https://github.com/chrisbanes/skills), inspected at revision
`b684174b626c58e00e102de06de99f8a8a25ba4d`. Applied focused guidance:

- `compose-component-design`: root modifiers, caller-owned placement, and slots
  for the map and variable tool content.
- `compose-state-and-effects`: `DriveScreen` wires the models and renderer;
  plain `DriveContent` accepts immutable state, callbacks and content slots.
  It owns saved tool selection and sheet visibility. Renderer updates run in
  `SideEffect`; activity lifecycle continues to own MapView and foreground
  location/sensor cleanup.
- `compose-ui-testing-patterns`: platform integration for native map gestures,
  lifecycle recreation and callback wiring; pure unit tests for camera policy;
  captured images are visual inspections, not screenshot-baseline comparisons.
- `gradle-run`: all Gradle runs after adopting the skill use its compact-output
  wrapper. A missing Java launcher was repaired by linking the workstation's
  existing Android Studio JBR Java into the user's local bin directory. SDK
  configuration stays in ignored `local.properties`.

No new application library or paid service was added.

## Camera behavior

A native gesture enters exploration and suspends automatic following/fitting.
Repeated position or UI updates keep that manual viewport. Recenter restores
route overview or current navigation position. A deliberately new route or
starting guidance resumes centering. Activity recreation preserves exploration.
Losing GPS does not silently switch an active follow session into route fitting.

The opt-in integration check uses only the fixed public Nairobi sample, with
GPS off. It injects real touch-screen pan and two-pointer pinch events, checks
native camera movement, holds a manual viewport across updates, recreates the
activity, then checks overview Recenter and playback Recenter. It never starts a
real journey or records/shares a device location. Initial Espresso swipes did not
reach native gesture callbacks; touch-screen input injection resolved pan input.
The pinch fixture must exceed MapLibre's existing 0.6 dp/ms minimum scale speed;
the production gesture threshold is unchanged.

## Verification

The final UI APK and test APK build, Android lint, and all 26 unit tests pass.
The emulator passes all nine opt-in checks: native preview pan, pinch, preserved
viewport after recreation, overview Recenter, exploration during playback and
follow Recenter in one integration test; four convoy safety checks; three
telemetry safety checks; and the public HTTPS routing/tiles/fonts/auth boundary.

The connected Samsung passes the seven convoy/telemetry safety checks and the
separate opted-in public HTTPS check. Its native gesture integration timed out
waiting for map style readiness; a subsequent visual inspection shows the night
map and tools rendering with location/recording off. Hardware gesture acceptance
is therefore still open until a foreground, undisturbed rerun succeeds.
The M1 real journey, M4 multi-phone field/battery checks, and M5 physical drive
remain scheduled for 14 November 2026. This UI refresh does not close those gates.

## Reproduce

Use the skill's `gradle_run.py create`, then its `run --workflow ID --scope targeted
--question ... --` command around this nested Gradle invocation:

```sh
apps/android/gradlew -p apps/android --no-daemon \
  :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug
```

Install both APKs, unlock the test device, and run:

```sh
adb -s DEVICE shell am instrument -w \
  -e mapGestureChecks true -e publicEndpointChecks true \
  -e class org.projectdrive.app.MapInteractionTest,org.projectdrive.app.ConvoySafetyTest,org.projectdrive.app.TelemetrySafetyTest,org.projectdrive.app.PublicEndpointTest \
  org.projectdrive.app.test/androidx.test.runner.AndroidJUnitRunner
```

The public endpoint check requires internet and the existing HTTPS homelab route.
The map check requires an awake, unlocked, foreground activity. Default builds
keep development authentication disabled for the public backend.
