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

The final APK/test APK build, Android lint, and all 32 JVM unit tests pass.
The API 37 emulator passes 14 stationary native tests: three camera, three input,
four convoy safety, three telemetry safety, and one public HTTPS boundary check.
The connected Samsung qualifies all 13 applicable tests across focused runs;
the isolated synthetic-fix model test is emulator-only. No Android mock provider
or real journey was used.

The phone's final three camera tests passed after the owner unlocked it. Earlier
style-readiness failures occurred while Android reported dozing and a locked
keyguard; they are retained here as failed attempts, resolved by that foreground
rerun. Pan, pinch, overview/follow Recenter, preserved exploration, rapid recreation
and saving before style readiness are now verified on both devices.

Manual emulator inspection covers day/night, landscape, 130% text, and an actual
public HTTPS name-search flow from Nairobi to Nairobi West (3.4 km route).
Rapid night-mode/font changes retain the regional viewport. Captures live outside
Git in the workstation's `artifacts/ui-ux-refresh` directory and use public places
with GPS off. The phone was returned to a clean map launch with empty endpoints.
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
  -e class org.projectdrive.app.MapInteractionTest,org.projectdrive.app.NavigationInputTest,org.projectdrive.app.ConvoySafetyTest,org.projectdrive.app.TelemetrySafetyTest,org.projectdrive.app.PublicEndpointTest \
  org.projectdrive.app.test/androidx.test.runner.AndroidJUnitRunner
```

The public endpoint check requires internet and the existing HTTPS homelab route.
The map check requires an awake, unlocked, foreground activity. Default builds
keep development authentication disabled for the public backend.

The skill wrapper completed workflow `d2095f1f72b331d4c49904c2c131dbed`.
Final verification questions and answers:

- “Do redirect safety tests and the final Android app build, unit tests, device
  suite, and lint pass?” Build/unit/lint passed; native execution is recorded above.
- “Does rapid-configuration camera restoration compile and keep Android unit
  tests and lint passing?” Yes.
- “Does the deterministic pending-style viewport regression fixture compile and
  pass lint?” Yes; its subsequent native execution passed on both devices.

Only wrapper-owned diagnostic logs were subject to the wrapper's finish cleanup;
project build outputs, screenshots and map data were retained.

GitHub source commits are `097321f` (map UI), `9c6d89b` (place search/links)
and `0523796` (rapid camera restoration). Final runtime-source
[CI run](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43498994802)
and [image/GitOps promotion](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43500129272)
both succeeded. Search/data mounts reuse the existing public Cloudflare path
rules and homelab storage; no new public route or schema migration was required.
The API rollout completed on image
`ghcr.io/gibson-gichuru/project-drive:0523796123b65758ecff76bbd8a2d98684f7c2c3`.
Flux reports Ready at
`homelab-deploy@sha1:cac478eea5e472606cb029d65c248db8dcf00f2d`; all four
Project Drive pods are Ready with zero restarts at final inspection. Temporary
data-import resources, local port forwarding and the disposable emulator were
removed/stopped; persistent map/database volumes were retained.

## Rapid configuration changes

Visual inspection at 130% font scale found that changing night mode and font size
quickly could save the SDK's temporary world camera before the replacement map's
style was ready. The renderer now restores its previous camera explicitly after
style loading and copies that previous camera into the next saved-state bundle
when initialization is still pending. It keeps the existing saved viewport and
exploration policy; route endpoints remain empty on a fresh launch.

Two focused native regressions cover rapid activity recreation without a route
and saving an unattached/pending map before style readiness. The latter supplies
a fixed public viewport and proves it survives before an asynchronous callback
can finish. These tests supplement the native route pan/pinch/Recenter test.
