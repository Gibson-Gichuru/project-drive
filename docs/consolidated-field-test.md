# One combined field session

Owner decision, 15 November 2026: build and verify software between milestones;
collect field-dependent acceptance checks into one coordinated outing. Do not ask
for a separate drive for every feature. Keep unexecuted checks open, and retain
already valid evidence rather than repeating it without a reason.

## Readiness before scheduling

- Prepare the final candidate APK and record source, device/OS and map-data revision.
  Update in place while no journey is active; preserve existing journals/preferences.
- Two real Android phones must be available for the convoy and two-device battery
  checks. One phone plus emulators remains valid software evidence, not that gate.
- Both phones need working legitimate authentication for public convoy testing.
  The current public backend deliberately disables development identities and the
  app has no production sign-in yet. This is a software dependency to resolve before
  scheduling the combined convoy outing; do not publish development authentication
  or assume the OSM browser login signs into Outset.
- Use the public HTTPS map/API route over mobile data, without workstation ADB
  reverses. Verify both clients' map/search/route loading while parked. Full offline
  packs, traffic-aware ETAs, voice guidance and automatic rerouting are not claimed.
- Choose a familiar permitted route with a public destination and a safe stopping
  point. Include a known one-way/access or surface/elevated-road case only when it
  naturally fits the route. Record public names, not a private start or GPS trail.
- Mount devices consistently, note brightness and battery settings, and leave both
  unplugged. Choose a 40–50 minute recording window on both phones, inside the
  30–60 minute target. Start/end parked checks can be part of that window.

If these prerequisites are missing, continue software work and leave the outing
unscheduled. A repeat is needed only for a failed check or a material software/data
change that invalidates its evidence, not for every subsequent milestone.

## Run sheet

Only a passenger operates moving-phone controls. Perform network, permission,
sharing and lifecycle manipulations while parked. Drive normally; never provoke
hard braking, rapid acceleration or cornering to obtain candidate counts.

| Part | One-session actions | Evidence / acceptance |
| --- | --- | --- |
| Parked start | Check attribution, named-place/shared-link destination, actual current origin, fresh usable GPS, preview fit, pan/zoom and Recenter. Start guidance, separately consent to journal recording and convoy sharing. Check ongoing notification. | Correct real location and route; no fixed default origin; permission/notification state visible; both real participants shown with freshness/accuracy. |
| Ordinary journey | Follow the route. Observe instruction/marker progression and permitted road approach. Note any missed/incorrect instruction, restriction, road gap or UI failure. | M1 road/route observation and explicit arrival result at the destination. An estimate is not proof of legal access. |
| Background/display | Record at least ten minutes screen-off on one device and at least five minutes in another app; stagger checks on the second device. Check guidance/recording/sharing after return. | Physical duration evidence and continued collection, with correct stale states if fixes stop. A mere `interrupted=false` summary is not proof of screen-off survival. |
| Parked connectivity | Briefly remove network access on one participant, verify disconnected/stale UI (default stale after about 10 s, position TTL about 30 s), then restore access and verify fresh resync. Check a loaded route versus an uncached request. | Recovery without falsely live markers or silently resurrected consent; document cached-only limits. Do not confuse offline display with an offline map pack. |
| Parked privacy/permission | Revoke sharing on one phone; verify location disappears on the other. If practical, exercise permission denial/approximate mode on the second phone and restore its chosen setting before continuing. Use a separate disposable trip to test member removal/end without disrupting the main journal. | Share-off/removal/end deny subsequent locations. Denied/approximate fixes remain clearly labelled. Never weaken public authentication for this test. |
| Arrival and finish | Explicitly note whether arrival appeared. Finish journals and stop guidance/sharing; inspect notification Stop behavior while safely parked if still active. Confirm no active mode remains. | Both summaries saved with counts/quality and interruption state; controls stop collection. Existing owner records remain intact. |
| Private review | Read summaries promptly, before detailed events expire at 24 h (summaries expire at 7 days). Review durations/coverage, GPS/sensor counts, candidate/detail consistency, background/screen-off, charging, battery and network aggregates. | One private evidence bundle supports M1/M4/M5/M6 decisions. Keep raw database copies, identifiers, private timestamps and personal metrics out of Git. Publish only a redacted acceptance report. |

Candidate counts are uncalibrated GPS-derived estimates. Zero candidates during a
smooth drive can be correct; counts alone cannot establish sensitivity or false
positives. No raw speed/path history is saved for reconstruction. Existing 15 November
[Tigoni evidence](device-tests/2026-11-15-tigoni.md) already proves physical journal
capture and longitudinal candidate persistence; do not repeat a special event drive.

## Result form

| Item | Result to fill after the session |
| --- | --- |
| Candidate source / data release / devices | Pending |
| Public route names / ordinary conditions | Pending |
| Guidance progression / arrival / road-access observations | Pending |
| Two real participants / stale / reconnect / revocation | Pending |
| Background and screen-off on both phones | Pending |
| Two 30–60 minute recordings / battery and charging qualification | Pending |
| Crashes, failures or changes requiring a targeted repeat | Pending |
| Gate decisions and explicit remaining limits | Pending |

Server receipt-to-write latency is a separate automated measurement. Run it before
the outing; a GPS callback gap, phone-to-phone stopwatch or HTTP round trip does not
measure it. See [M6 desktop validation](m6-desktop-validation.md).
