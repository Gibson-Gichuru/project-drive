# Outset — map and journey card

Date: 15 November 2026. The owner supplied a visual reference and requested a
short road-trip/adventure name. **Outset** is the working application name,
suggesting the start of a journey.

## Design

The map fills the display. Pale neutral roads/buildings, soft green parks and
blue water sit beneath charcoal circular controls and a rounded journey card.
The previous brand banner and permanent three-tab dock are removed. All Material
UI typography remains the locally bundled Poppins family. Tools use the same
charcoal surfaces during day and night; the basemap follows system night mode.

Top-left opens Navigation; top-right opens tools. Recenter and Convoy float above
the card. The card provides planning/route details, Journal and more tools; its
content adapts to route preview, real guidance, explicitly labelled simulation,
recording and sharing. A compact landscape card leaves room for the map, with
tools still reachable from the top controls. Detail sheets retain Navigation,
Convoy and Journal tabs, scrolling, keyboard handling and Back to map.

Active guidance has a direct Stop action. Finish recording and Stop sharing now
remain visible over the map. The design does not fabricate profile identity,
online status, current location, a route, GPS heading or a location-history map.
The original vehicle pin appears only for an available position; simulation has
its own purple pin and label. The journal retains its derived-summary semantics.
OpenStreetMap/OpenMapTiles credit and native attribution remain accessible.

Pan/pinch exploration and Recenter policy are unchanged. Route-fit padding is
adjusted for the new card. Camera restoration through configuration changes is
preserved. Place-name search, incoming/shared links and empty fresh endpoints
remain supported. Camera and sheet behavior continue through the plain
`DriveContent` state/callback/slot boundary, following the previously selected
Chris Banes Compose skills.

The launcher label, original adaptive road icon, visible sheet heading and consent
copy now use Outset. Package `org.projectdrive.app`, database, API/repository names
and public hostname retain their stable identifiers so an update preserves local
journeys and existing deployment links. No new mapping SDK, paid service, runtime
font download or external image is introduced. The reference's Google branding,
car photograph and map data were not copied.

## Verification

All 38 JVM tests passed, along with the debug APK, device test APK and Android
lint. The API 37 emulator passed 18 native tests: three controlled-content UI,
three camera, three navigation input, four convoy safety, four telemetry safety,
and one public HTTPS boundary check. Controlled UI tests render `DriveContent`
with fixture slots/callbacks in the existing test host, without application
models, network, GPS or owner recordings. They prove tool selection/dismissal,
Recenter callbacks, incoming planner requests and visible recording/share stops.

Manual inspection covers day/night, landscape and 130% text, plus an actual named
Nairobi → Nairobi West planner flow and labelled simulated vehicle marker. Large
text initially let the card cover an endpoint; route padding now accounts for
font growth. The first padding build caught a Kotlin mixed numeric-type error;
explicit float constants corrected it and the subsequent build/lint passed.
The camera test additionally checks the projected destination against actual
journey-card text bounds. All three final camera tests passed at 130% text,
including endpoint clearance, pan/pinch/Recenter and recreation restoration.

The Android APK was replaced on the Samsung with `adb install -r`, using the same
package/database. Read-only comparison confirmed all three owner recordings
unchanged across the rebrand. Task-created private diagnostic database copies
were removed afterward. Public-place emulator captures are kept outside Git in
`artifacts/outset/`; the owner's reference and private phone images are not
published. The original full loopback navigation script was updated for accessible
icon descriptions, named result selection and the new route-card label; syntax
passed, but its separate loopback/offline suite was not rerun.

The deferred real-world navigation, convoy, battery and telemetry event-sensitivity
gates stay separate from visual and stationary testing.

## Build workflow evidence

The compact Gradle wrapper completed workflow
`88c145bbf4f06a462a4b2785833dbe2d`. Nested tasks were
`:app:assembleDebug`, `:app:testDebugUnitTest` (initial complete run),
`:app:assembleDebugAndroidTest` and `:app:lintDebug`. Later focused builds covered
new launcher/test resources and the font-padding regression. Questions/results:

- “Does the Outset map-card redesign build, preserve all unit tests, and pass
  Android lint?” Yes: both APKs, 38 JVM tests and lint passed.
- “Do the Outset launcher resources and controlled map-card interaction tests
  compile and pass lint?” Yes; their native execution subsequently passed.
- “Do the final Outset branding, map card and visible-control test changes build
  and pass lint?” Yes; all 18 native tests subsequently passed.
- “Does font-aware route padding build with the Outset app and device tests and
  pass lint?” Initial compile failed on mixed Int/Float arithmetic at renderer
  line 169. The source was inspected and constants corrected to Float.
- “Does corrected font-aware padding and the route-endpoint visibility regression
  build and pass lint?” Yes; the three focused camera tests then passed at 130%.

Finish removed only wrapper-owned logs; APKs, captures and owner data were retained.

## Delivery and recovery

The source change is `57a78f8c8f3f01f08e4eff4223f354e2edabf83e`.
[GitHub CI](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43698440404)
verifies the source before the established image/GitOps promotion runs. There are
no backend contract, authentication, Kubernetes resource or database migration
changes in this UI release. Existing storage and Cloudflare routes are reused.
To recover the previous UI, revert this source commit and rebuild/install the
same Android package, keeping telemetry correction `2f5b9fa`. Local data does not
need a migration or reset. Normal server release recovery follows the existing
tested GitOps workflow.

[Deployment](https://github.com/Gibson-Gichuru/project-drive/actions/runs/43698903089)
succeeded. The API rollout completed and Flux reported Ready=True at
`homelab-deploy@sha1:c8736b1a94dc60d6de0ada495410a7c0683a61dc`.
The subsequent [current-location correction](current-location.md) changes startup
centering while preserving the design and free exploration behavior described here.
